const LOOPBACK_HOSTS = new Set(["127.0.0.1", "localhost", "[::1]"]);

function withoutTrailingSlash(value: string): string {
  return value.replace(/\/+$/, "");
}

/**
 * Resolve the public PWA API target without silently shipping a production
 * bundle that calls an insecure localhost endpoint.
 */
export function resolveApiBase(configured: string | undefined, isDevelopment: boolean): string {
  const value = configured?.trim() ?? "";
  if (!value) return isDevelopment ? "http://127.0.0.1:8080" : "";

  if (value.startsWith("/")) {
    if (value.startsWith("//") || value.includes("\\") || /[?#]/.test(value)) return "";
    return withoutTrailingSlash(value);
  }

  let url: URL;
  try { url = new URL(value); }
  catch { return ""; }

  if (url.username || url.password || url.search || url.hash) return "";
  if (url.protocol === "https:") return `${url.origin}${withoutTrailingSlash(url.pathname)}`;
  if (isDevelopment && url.protocol === "http:" && LOOPBACK_HOSTS.has(url.hostname)) {
    return `${url.origin}${withoutTrailingSlash(url.pathname)}`;
  }
  return "";
}
