import { flushPromises, mount } from "@vue/test-utils";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const playerMocks = vi.hoisted(() => ({
  feed: vi.fn(),
  destroy: vi.fn(),
}));

vi.mock("pcm-player", () => ({
  default: class MockPcmPlayer {
    feed = playerMocks.feed;
    destroy = playerMocks.destroy;
  },
}));

vi.mock("@/utils/websocket", () => ({
  resolveWebSocketUrl: () => "ws://voice.test",
}));

import InterviewVoicePlayer from "@/components/InterviewVoicePlayer.vue";

class FakeWebSocket {
  static OPEN = 1;
  static CLOSED = 3;
  static instances: FakeWebSocket[] = [];

  readyState = FakeWebSocket.OPEN;
  private listeners = new Map<string, Array<(event: any) => void>>();

  constructor() {
    FakeWebSocket.instances.push(this);
  }

  addEventListener(type: string, listener: (event: any) => void) {
    const listeners = this.listeners.get(type) || [];
    listeners.push(listener);
    this.listeners.set(type, listeners);
  }

  send() {}

  close() {
    this.readyState = FakeWebSocket.CLOSED;
  }

  emit(type: string, event: any = {}) {
    for (const listener of this.listeners.get(type) || []) {
      listener(event);
    }
  }
}

describe("interview voice player", () => {
  beforeEach(() => {
    FakeWebSocket.instances = [];
    vi.stubGlobal("WebSocket", FakeWebSocket);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("drops an oversized paused playback backlog instead of retaining every chunk", async () => {
    const wrapper = mount(InterviewVoicePlayer, {
      props: { playbackEnabled: false },
    });
    const socket = FakeWebSocket.instances[0];

    for (let index = 0; index < 257; index += 1) {
      socket.emit("message", { data: "AAAA" });
    }
    socket.emit("message", {
      data: JSON.stringify({ normalMessage: true, content: "audio:end" }),
    });

    await wrapper.setProps({ playbackEnabled: true });
    await flushPromises();

    expect(playerMocks.feed).not.toHaveBeenCalled();
    expect(wrapper.emitted("broadcastingEnd")).toHaveLength(1);
    wrapper.unmount();
  });
});
