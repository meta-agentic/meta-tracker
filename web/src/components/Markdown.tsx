import ReactMarkdown, { defaultUrlTransform, type Components } from "react-markdown";
import remarkGfm from "remark-gfm";

const SAFE_SCHEME = /^(https?:|mailto:)/i;

/**
 * Keeps relative and fragment URLs, and absolute URLs only for http(s) and mailto.
 * Anything else (javascript:, data:, vbscript:, ...) becomes an empty URL.
 */
function safeUrl(url: string): string {
  const cleaned = defaultUrlTransform(url);
  if (cleaned === "") return "";
  // Strip control characters and whitespace that browsers ignore inside a scheme.
  // eslint-disable-next-line no-control-regex
  const probe = cleaned.replace(/[\u0000- ]/g, "");
  if (SAFE_SCHEME.test(probe)) return cleaned;
  return /^[a-z][a-z0-9+.-]*:/i.test(probe) ? "" : cleaned;
}

/** Table cells arrive with an inline text-align style; turn it into an align attribute the stylesheet targets. */
function cell(Tag: "th" | "td"): Components["th"] {
  return function Cell({ node, style, children, ...rest }) {
    void node;
    const align = style?.textAlign;
    return (
      <Tag {...rest} align={align === "left" || align === "center" || align === "right" ? align : undefined}>
        {children}
      </Tag>
    );
  };
}

const components: Components = {
  th: cell("th"),
  td: cell("td"),
  a({ node, href, children, ...rest }) {
    void node;
    if (!href) return <span>{children}</span>;
    if (href.startsWith("#")) return <a {...rest} href={href}>{children}</a>;
    return (
      <a {...rest} href={href} target="_blank" rel="noopener noreferrer">
        {children}
      </a>
    );
  },
  // The CSP allows images from this origin only. A remote image would be a violation and a
  // tracking request, so an image is shown as a plain link to its URL instead.
  img({ node, src, alt }) {
    void node;
    const href = typeof src === "string" ? src : "";
    if (!href) return <span>{alt}</span>;
    return (
      <a href={href} target="_blank" rel="noopener noreferrer">
        {alt || href}
      </a>
    );
  },
};

/**
 * Renders CommonMark plus GitHub tables. Raw HTML in the source is shown as text, never parsed.
 * Output carries no inline style attributes, so it works under a strict CSP.
 */
export function Markdown({ source }: { source: string }) {
  return (
    <div className="vec-markdown">
      <ReactMarkdown remarkPlugins={[remarkGfm]} urlTransform={safeUrl} components={components}>
        {source}
      </ReactMarkdown>
    </div>
  );
}
