export function resolveWebSocketUrl(
  configuredUrl: string | undefined,
  fallbackPath: string
): string {
  const value = configuredUrl?.trim() || fallbackPath;
  if (/^wss?:\/\//i.test(value)) {
    return value;
  }

  const path = value.startsWith("/") ? value : `/${value}`;
  const protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
  return `${protocol}//${window.location.host}${path}`;
}
