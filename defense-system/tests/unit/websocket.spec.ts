import { afterEach, describe, expect, it, vi } from "vitest";
import { resolveWebSocketUrl } from "@/utils/websocket";

describe("resolveWebSocketUrl", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("keeps an explicitly configured websocket URL", () => {
    expect(
      resolveWebSocketUrl(
        "  wss://socket.example.test/voice  ",
        "/ws/fallback"
      )
    ).toBe("wss://socket.example.test/voice");
  });

  it("resolves a relative configured path against an HTTPS page", () => {
    stubLocation("https:", "interview.example.test");

    expect(resolveWebSocketUrl("voice/stream", "/ws/fallback")).toBe(
      "wss://interview.example.test/voice/stream"
    );
  });

  it("uses the fallback path and ws protocol on an HTTP page", () => {
    stubLocation("http:", "127.0.0.1:3002");

    expect(resolveWebSocketUrl("   ", "/ws/defense-assessment")).toBe(
      "ws://127.0.0.1:3002/ws/defense-assessment"
    );
  });

  function stubLocation(protocol: string, host: string) {
    vi.stubGlobal("window", {
      location: { protocol, host },
    });
  }
});
