import { describe, expect, it } from "vitest";
import { render } from "@testing-library/react";
import { Markdown } from "./Markdown";
import vaultBody from "../test/fixtures/vault-item-body.md?raw";

function html(source: string): HTMLElement {
  return render(<Markdown source={source} />).container;
}

describe("Markdown", () => {
  it("renders headings, emphasis, quotes and lists", () => {
    const c = html("# One\n\n## Two\n\n*em* and **strong**\n\n> quoted\n\n- a\n- b\n\n1. x\n2. y");
    expect(c.querySelector("h1")?.textContent).toBe("One");
    expect(c.querySelector("h2")?.textContent).toBe("Two");
    expect(c.querySelector("em")?.textContent).toBe("em");
    expect(c.querySelector("strong")?.textContent).toBe("strong");
    expect(c.querySelector("blockquote")?.textContent).toContain("quoted");
    expect(c.querySelectorAll("ul > li")).toHaveLength(2);
    expect(c.querySelectorAll("ol > li")).toHaveLength(2);
  });

  it("renders inline and block code", () => {
    const c = html("use `x` here\n\n```ts\nconst a = 1;\n```");
    expect(c.querySelector("p code")?.textContent).toBe("x");
    expect(c.querySelector("pre code")?.textContent).toContain("const a = 1;");
  });

  it("renders GitHub tables without inline styles", () => {
    const c = html("| a | b | c |\n|:--|:-:|--:|\n| 1 | 2 | 3 |");
    expect(c.querySelectorAll("th")).toHaveLength(3);
    expect(c.querySelectorAll("td")).toHaveLength(3);
    expect(c.querySelector("[style]")).toBeNull();
  });

  it("renders links in a new tab with noopener noreferrer", () => {
    const a = html("[site](https://example.com) and <https://example.org> and [m](mailto:a@b.co)").querySelectorAll("a");
    expect(a).toHaveLength(3);
    for (const link of a) {
      expect(link.getAttribute("target")).toBe("_blank");
      expect(link.getAttribute("rel")).toBe("noopener noreferrer");
    }
  });

  it("keeps fragment links in the same tab", () => {
    const a = html("[top](#top)").querySelector("a");
    expect(a?.getAttribute("href")).toBe("#top");
    expect(a?.hasAttribute("target")).toBe(false);
  });

  it("renders an image as a plain link, never an img element", () => {
    const c = html("![logo](https://example.com/p.png) ![bad](javascript:alert(1))");
    expect(c.querySelector("img")).toBeNull();
    const a = c.querySelector("a");
    expect(a?.getAttribute("href")).toBe("https://example.com/p.png");
    expect(a?.textContent).toBe("logo");
    expect(c.querySelectorAll("a")).toHaveLength(1);
  });

  it("shows raw HTML as text and never creates elements from it", () => {
    const c = html('<script>alert(1)</script>\n\n<img src=x onerror="alert(1)">\n\n<b style="color:red">hi</b>');
    expect(c.querySelector("script, img, b, [style], [onerror]")).toBeNull();
    expect(c.textContent).toContain("<script>");
  });

  it("refuses javascript: and other non-http(s)/mailto schemes", () => {
    const c = html("[a](javascript:alert(1)) [b](data:text/html,x) [c](vbscript:x) [d](JaVaScRiPt:alert(1)) [e](ftp://h/f) [ok](/rel) [h](#frag)");
    expect([...c.querySelectorAll("a")].map((l) => l.getAttribute("href"))).toEqual(["/rel", "#frag"]);
    expect(c.textContent).toContain("a");
  });

  it("renders a real vault item body (snapshot)", () => {
    expect(html(vaultBody).innerHTML).toMatchSnapshot();
  });
});
