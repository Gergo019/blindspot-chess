// @vitest-environment node
import indexHtml from "../index.html?raw";
import nginxConf from "../nginx/default.conf.template?raw";
import securityHeaders from "../nginx/security-headers.conf?raw";

/**
 * The security headers nginx sends (nginx/security-headers.conf). These checks fail the build when
 * the inline script in index.html changes without its CSP hash, when a directive goes missing, or
 * when an nginx location sets its own headers without the include (and so silently drops the rest).
 */

function header(name: string): string {
  const match = new RegExp(`add_header ${name} "([^"]+)" always;`).exec(securityHeaders);
  if (!match?.[1]) throw new Error(`${name} is not set in security-headers.conf`);
  return match[1];
}

function directives(csp: string): Map<string, string[]> {
  return new Map(
    csp
      .split(";")
      .map((part) => part.trim().split(/\s+/))
      .filter((tokens): tokens is [string, ...string[]] => tokens[0] !== undefined && tokens[0] !== "")
      .map(([name, ...values]) => [name, values]),
  );
}

async function sha256Base64(text: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return btoa(String.fromCharCode(...new Uint8Array(digest)));
}

describe("Content-Security-Policy", () => {
  const csp = directives(header("Content-Security-Policy"));

  it("allows every inline script of index.html by its hash, and nothing else inline", async () => {
    const inline = [...indexHtml.matchAll(/<script(?![^>]*\bsrc=)[^>]*>([\s\S]*?)<\/script>/g)].map(
      (m) => m[1] ?? "",
    );
    expect(inline.length).toBeGreaterThan(0);
    const scriptSrc = csp.get("script-src") ?? [];
    for (const script of inline) {
      expect(scriptSrc).toContain(`'sha256-${await sha256Base64(script)}'`);
    }
    expect(scriptSrc).not.toContain("'unsafe-inline'");
    expect(scriptSrc).not.toContain("'unsafe-eval'");
  });

  it("restricts everything to the same origin and forbids framing, plugins and base changes", () => {
    expect(csp.get("default-src")).toEqual(["'self'"]);
    expect(csp.get("connect-src")).toEqual(["'self'"]);
    expect(csp.get("frame-ancestors")).toEqual(["'none'"]);
    expect(csp.get("object-src")).toEqual(["'none'"]);
    expect(csp.get("base-uri")).toEqual(["'self'"]);
    expect(csp.get("style-src")).not.toContain("'unsafe-inline'");
  });
});

describe("other security headers", () => {
  it.each([
    ["X-Content-Type-Options", "nosniff"],
    ["X-Frame-Options", "DENY"],
    ["Referrer-Policy", "same-origin"],
  ])("%s is %s", (name, value) => {
    expect(header(name)).toBe(value);
  });
});

describe("nginx config", () => {
  const include = "include /etc/nginx/snippets/security-headers.conf;";

  it("includes the security headers at server level", () => {
    const serverLevel = nginxConf.slice(0, nginxConf.indexOf("location"));
    expect(serverLevel).toContain(include);
  });

  it("includes them again in every location that sets its own headers (nginx does not inherit them)", () => {
    const locations = [...nginxConf.matchAll(/location\s+([^{]+)\{([^}]*)\}/g)];
    expect(locations.length).toBeGreaterThan(0);
    for (const [, path, body] of locations) {
      if (body?.includes("add_header")) expect(body, `location ${String(path).trim()}`).toContain(include);
    }
  });

  it("hides the nginx version", () => {
    expect(nginxConf).toContain("server_tokens off;");
  });
});
