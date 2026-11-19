// SPDX-License-Identifier: Apache-2.0
package io.vectis.server.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.ws.rs.core.MediaType;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The wire form of an item write, read into the values a command takes. Only the shape is checked
 * here, and a request that does not have it is malformed (400): a body that is not a JSON object
 * sent as JSON, a member of the wrong JSON type or one the write does not take, an {@code If-Match}
 * that is not one version. Whether the values keep the rules is the command layer's to say.
 */
final class ItemRequests {

    /** {@code "<version>"}: the item's ETag, a strong one, as its reads answer it. */
    private static final Pattern IF_MATCH = Pattern.compile("\"([0-9]{1,18})\"");

    private ItemRequests() {
    }

    /**
     * The JSON object the body holds, with no member but {@code allowed}. JSON only: a body the
     * browser could send across origins without asking first, such as {@code text/plain}, is
     * refused.
     */
    static ObjectNode object(ObjectMapper json, String contentType, String body, Set<String> allowed) {
        if (!isJson(contentType)) {
            throw ProblemException.badRequest("the body is a JSON object, sent as application/json; got "
                    + (contentType == null ? "no Content-Type" : "Content-Type " + contentType));
        }
        JsonNode node;
        try {
            node = body == null || body.isBlank() ? null : json.readTree(body);
        } catch (JsonProcessingException e) {
            node = null;
        }
        if (!(node instanceof ObjectNode object)) {
            throw ProblemException.badRequest("the body is a JSON object");
        }
        List<String> unknown = new ArrayList<>();
        object.fieldNames().forEachRemaining(name -> {
            if (!allowed.contains(name)) {
                unknown.add(name);
            }
        });
        if (!unknown.isEmpty()) {
            throw ProblemException.badRequest("this write takes " + String.join(", ", allowed.stream().sorted().toList())
                    + "; got " + String.join(", ", unknown));
        }
        return object;
    }

    private static boolean isJson(String contentType) {
        if (contentType == null) {
            return false;
        }
        MediaType type;
        try {
            type = MediaType.valueOf(contentType);
        } catch (IllegalArgumentException e) {
            return false;
        }
        return type.getType().equalsIgnoreCase("application")
                && (type.getSubtype().equalsIgnoreCase("json") || type.getSubtype().toLowerCase(Locale.ROOT).endsWith("+json"));
    }

    /** The text member {@code name}, or null if the body leaves it out. */
    static String text(ObjectNode body, String name) {
        JsonNode value = body.get(name);
        if (value == null) {
            return null;
        }
        if (!value.isTextual()) {
            throw ProblemException.badRequest(name + " is text");
        }
        return value.textValue();
    }

    /** The object member {@code name} as plain Java values, or null if the body leaves it out. */
    static Map<String, Object> object(ObjectMapper json, ObjectNode body, String name) {
        JsonNode value = body.get(name);
        if (value == null) {
            return null;
        }
        if (!value.isObject()) {
            throw ProblemException.badRequest(name + " is an object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> fields = json.convertValue(value, Map.class);
        return fields;
    }

    /** The version {@code If-Match} names, or null if the request sends none. */
    static Long version(String ifMatch) {
        if (ifMatch == null) {
            return null;
        }
        Matcher m = IF_MATCH.matcher(ifMatch.strip());
        if (!m.matches()) {
            throw ProblemException.badRequest("If-Match is the item's version in quotes, as its ETag gives it, such as"
                    + " \"3\"; got '" + ifMatch + "'");
        }
        return Long.parseLong(m.group(1));
    }
}
