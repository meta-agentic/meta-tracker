package io.vectis.server.auth;

import static io.vectis.server.auth.OidcProviderContainer.CLIENT_ID;
import static io.vectis.server.auth.OidcProviderContainer.CLIENT_SECRET;
import static io.vectis.server.auth.OidcProviderContainer.PASSWORD;
import static io.vectis.server.auth.OidcProviderContainer.USERNAME;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.SignatureException;
import java.security.spec.RSAPublicKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * VEC-79 AC 1: a token obtained from the containerised provider through the authorization
 * code flow with PKCE (S256), driven over plain HTTP the way a browser would, verifies
 * against the provider's published JWKS. Skipped, not failed, where Docker is unavailable.
 */
@Testcontainers(disabledWithoutDocker = true)
class OidcProviderPkceTest {

    private static final String REDIRECT_URI = "http://localhost/callback";
    private static final Pattern LOGIN_ACTION = Pattern.compile("action=\"([^\"]*login-actions/authenticate[^\"]*)\"");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Decoder B64 = Base64.getUrlDecoder();

    private static OidcProviderContainer provider;
    private static JsonNode discovery;

    @BeforeAll
    static void startProvider() throws Exception {
        long started = System.nanoTime();
        provider = new OidcProviderContainer();
        provider.start();
        System.out.printf("OIDC provider ready in %d ms%n", (System.nanoTime() - started) / 1_000_000);
        discovery = JSON.readTree(send(HttpClient.newHttpClient(),
            HttpRequest.newBuilder(URI.create(provider.issuer() + "/.well-known/openid-configuration")).build()));
    }

    @AfterAll
    static void stopProvider() {
        if (provider != null) {
            provider.stop();
        }
    }

    @Test
    void codeFlowWithPkceYieldsTokensThatVerifyAgainstTheJwks() throws Exception {
        assertEquals(provider.issuer(), discovery.get("issuer").asText());
        String verifier = randomToken();

        // PKCE is enforced, not merely accepted: a code redeemed with another verifier is refused.
        HttpResponse<String> refused = redeem(authorize(challenge(verifier)), randomToken());
        assertEquals(400, refused.statusCode(), refused.body());

        HttpResponse<String> granted = redeem(authorize(challenge(verifier)), verifier);
        assertEquals(200, granted.statusCode(), granted.body());
        JsonNode tokens = JSON.readTree(granted.body());
        JsonNode jwks = JSON.readTree(send(HttpClient.newHttpClient(),
            HttpRequest.newBuilder(URI.create(discovery.get("jwks_uri").asText())).build()));

        JsonNode id = verified(tokens.get("id_token").asText(), jwks);
        JsonNode aud = id.get("aud");
        assertEquals(provider.issuer(), id.get("iss").asText());
        assertEquals(CLIENT_ID, aud.isArray() ? aud.get(0).asText() : aud.asText());
        assertEquals(USERNAME, id.get("preferred_username").asText());

        String access = tokens.get("access_token").asText();
        JsonNode claims = verified(access, jwks);
        assertEquals(provider.issuer(), claims.get("iss").asText());
        assertTrue(claims.get("exp").asLong() > Instant.now().getEpochSecond());

        // The check has teeth: the access token's claims under the ID token's signature fail.
        String[] a = access.split("\\.");
        String forged = a[0] + "." + a[1] + "." + tokens.get("id_token").asText().split("\\.")[2];
        assertThrows(SignatureException.class, () -> verified(forged, jwks));
    }

    /** Signs in as the bootstrap user and returns the authorization code from the redirect. */
    private static String authorize(String challenge) throws Exception {
        HttpClient browser = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        String state = randomToken();
        URI start = URI.create(discovery.get("authorization_endpoint").asText() + "?" + form(Map.of(
            "response_type", "code", "client_id", CLIENT_ID, "redirect_uri", REDIRECT_URI, "scope", "openid",
            "state", state, "code_challenge", challenge, "code_challenge_method", "S256")));
        HttpResponse<String> page = browser.send(HttpRequest.newBuilder(start).build(), HttpResponse.BodyHandlers.ofString());
        Matcher action = LOGIN_ACTION.matcher(page.body());
        assertTrue(action.find(), "the provider served no login form");
        // By hand, not a CookieManager: the provider marks its cookies Secure, and this is plain HTTP.
        String cookies = page.headers().allValues("Set-Cookie").stream()
            .map(c -> c.split(";", 2)[0]).collect(Collectors.joining("; "));

        HttpResponse<String> login = browser.send(HttpRequest.newBuilder(URI.create(action.group(1).replace("&amp;", "&")))
            .header("Content-Type", "application/x-www-form-urlencoded").header("Cookie", cookies)
            .POST(HttpRequest.BodyPublishers.ofString(form(Map.of("username", USERNAME, "password", PASSWORD))))
            .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(302, login.statusCode(), login.body());
        URI callback = URI.create(login.headers().firstValue("Location").orElseThrow());
        assertTrue(callback.toString().startsWith(REDIRECT_URI), callback.toString());
        Map<String, String> query = new LinkedHashMap<>();
        for (String pair : callback.getRawQuery().split("&")) {
            String[] kv = pair.split("=", 2);
            query.put(kv[0], URLDecoder.decode(kv[1], UTF_8));
        }
        assertEquals(state, query.get("state"));
        return query.get("code");
    }

    private static HttpResponse<String> redeem(String code, String verifier) throws Exception {
        String basic = Base64.getEncoder().encodeToString((CLIENT_ID + ":" + CLIENT_SECRET).getBytes(UTF_8));
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(discovery.get("token_endpoint").asText()))
            .header("Authorization", "Basic " + basic)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form(Map.of("grant_type", "authorization_code", "code", code,
                "redirect_uri", REDIRECT_URI, "code_verifier", verifier))))
            .build(), HttpResponse.BodyHandlers.ofString());
    }

    /** RS256 verification against the JWKS key named by the token's {@code kid}; returns the claims. */
    private static JsonNode verified(String jwt, JsonNode jwks) throws Exception {
        String[] part = jwt.split("\\.");
        JsonNode header = JSON.readTree(B64.decode(part[0]));
        assertEquals("RS256", header.get("alg").asText());
        JsonNode jwk = null;
        for (JsonNode key : jwks.get("keys")) {
            if (key.get("kid").asText().equals(header.get("kid").asText())) {
                jwk = key;
            }
        }
        assertTrue(jwk != null, "no JWKS key for kid " + header.get("kid"));
        Signature rs256 = Signature.getInstance("SHA256withRSA");
        rs256.initVerify(KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(
            new BigInteger(1, B64.decode(jwk.get("n").asText())), new BigInteger(1, B64.decode(jwk.get("e").asText())))));
        rs256.update((part[0] + "." + part[1]).getBytes(US_ASCII));
        if (!rs256.verify(B64.decode(part[2]))) {
            throw new SignatureException("signature does not verify against the provider's JWKS");
        }
        return JSON.readTree(B64.decode(part[1]));
    }

    private static String challenge(String verifier) throws Exception {
        byte[] sha256 = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(sha256);
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String form(Map<String, String> fields) {
        return fields.entrySet().stream()
            .map(e -> URLEncoder.encode(e.getKey(), UTF_8) + "=" + URLEncoder.encode(e.getValue(), UTF_8))
            .collect(Collectors.joining("&"));
    }

    private static String send(HttpClient client, HttpRequest request) throws Exception {
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), request.uri() + ": " + response.body());
        return response.body();
    }
}
