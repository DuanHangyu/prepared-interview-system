import { flushPromises, mount, type VueWrapper } from "@vue/test-utils";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import QuestionPage from "@/views/studentDashboard/questions/index2.vue";

const testState = vi.hoisted(() => ({
  route: { query: { id: "2" } },
  getAssessmentDetail: vi.fn(),
  initAudio: vi.fn(),
  getUserMedia: vi.fn(),
}));

vi.mock("vue-router", () => ({
  useRoute: () => testState.route,
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
}));

vi.mock("@/api/assessment", () => ({
  getAssessmentDetail: testState.getAssessmentDetail,
}));

vi.mock("@/composables/useSpeechRecognition.js", async () => {
  const { ref } = await import("vue");
  return {
    useSpeechRecognition: () => ({
      initAudio: testState.initAudio,
      startAudio: vi.fn().mockResolvedValue(true),
      stopAudio: vi.fn(),
      finishAudioTurn: vi.fn().mockResolvedValue(true),
      closeAudio: vi.fn(),
      volumeLevel: ref(0),
      audioReady: ref(false),
      audioError: ref(""),
    }),
  };
});

class ControlledWebSocket {
  static OPEN = 1;
  static CLOSED = 3;
  static instances: ControlledWebSocket[] = [];

  readyState = 0;
  send = vi.fn();
  onopen: (() => void) | null = null;
  onclose: (() => void) | null = null;
  onerror: (() => void) | null = null;
  onmessage: ((event: { data: string }) => void) | null = null;

  constructor() {
    Object.defineProperty(this, "__v_skip", { value: true });
    ControlledWebSocket.instances.push(this);
  }

  open() {
    this.readyState = ControlledWebSocket.OPEN;
    this.onopen?.();
  }

  disconnect() {
    this.readyState = ControlledWebSocket.CLOSED;
    this.onclose?.();
  }

  receive(payload: unknown) {
    this.onmessage?.({ data: JSON.stringify(payload) });
  }

  close() {
    this.readyState = ControlledWebSocket.CLOSED;
  }
}

describe("question page manual reconnect", () => {
  let wrapper: VueWrapper | undefined;

  beforeEach(() => {
    vi.useFakeTimers();
    ControlledWebSocket.instances = [];
    testState.initAudio.mockReset().mockResolvedValue(true);
    testState.getAssessmentDetail.mockReset().mockResolvedValue({
      data: { id: 2, answerTime: 30, questionCount: 1 },
    });
    testState.getUserMedia.mockReset().mockResolvedValue({
      getTracks: () => [{ stop: vi.fn() }],
    });
    vi.stubGlobal("WebSocket", ControlledWebSocket);
    vi.stubGlobal("navigator", {
      mediaDevices: {
        getUserMedia: testState.getUserMedia,
      },
    });
  });

  afterEach(async () => {
    wrapper?.unmount();
    wrapper = undefined;
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it("starts a fresh connection after automatic retries are exhausted", async () => {
    wrapper = mount(QuestionPage, {
      global: {
        stubs: {
          DefenseHeader: true,
          WaveComponent: true,
          LoadingOverlay: true,
          Progress: true,
          InterviewVoicePlayer: {
            emits: ["ready"],
            template: "<div />",
            mounted() {
              this.$emit("ready");
            },
          },
        },
      },
    });
    await flushPromises();

    expect(testState.getUserMedia).toHaveBeenCalledWith({
      video: {
        width: { ideal: 320, max: 640 },
        height: { ideal: 240, max: 480 },
        frameRate: { ideal: 8, max: 12 },
        facingMode: { ideal: "user" },
      },
    });

    ControlledWebSocket.instances[0].open();
    await flushPromises();

    for (let attempt = 0; attempt < 5; attempt += 1) {
      ControlledWebSocket.instances.at(-1)!.disconnect();
      await vi.advanceTimersByTimeAsync(3000);
      await flushPromises();
    }

    expect(wrapper.text()).toContain("面试连接已断开");
    const connectionsBeforeManualRetry = ControlledWebSocket.instances.length;
    const retry = wrapper
      .findAll("button")
      .find((button) => button.text().includes("重试"));
    expect(retry).toBeDefined();

    await retry!.trigger("click");
    await flushPromises();

    expect(ControlledWebSocket.instances).toHaveLength(
      connectionsBeforeManualRetry + 1
    );
    expect(wrapper.text()).not.toContain("面试连接已断开");
  });

  it("starts answering when voice playback reports playing but never reports completion", async () => {
    wrapper = mount(QuestionPage, {
      global: {
        stubs: {
          DefenseHeader: true,
          WaveComponent: true,
          LoadingOverlay: true,
          Progress: true,
          InterviewVoicePlayer: {
            name: "InterviewVoicePlayer",
            props: ["playbackEnabled"],
            emits: ["ready", "playing", "broadcastingEnd"],
            template: "<div />",
            mounted() {
              this.$emit("ready");
            },
          },
        },
      },
    });
    await flushPromises();
    const socket = ControlledWebSocket.instances[0];
    socket.open();
    await flushPromises();

    socket.receive({
      normalMessage: true,
      content: JSON.stringify({
        title: "请补充说明实现细节",
        follow: false,
        index: 1,
        total: 1,
      }),
    });
    await flushPromises();
    wrapper.findComponent({ name: "InterviewVoicePlayer" }).vm.$emit("playing", true);

    await vi.advanceTimersByTimeAsync(30_000);
    await flushPromises();

    expect(socket.send.mock.calls.map(([payload]) => JSON.parse(payload))).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ action: "start-answer", message: "2" }),
      ])
    );
  });

  it("renders a cached follow-up when the page reloads without the original question", async () => {
    wrapper = mount(QuestionPage, {
      global: {
        stubs: {
          DefenseHeader: true,
          WaveComponent: true,
          LoadingOverlay: true,
          Progress: true,
          InterviewVoicePlayer: {
            emits: ["ready"],
            template: "<div />",
            mounted() {
              this.$emit("ready");
            },
          },
        },
      },
    });
    await flushPromises();
    const socket = ControlledWebSocket.instances[0];
    socket.open();
    await flushPromises();

    expect(() =>
      socket.receive({
        normalMessage: true,
        content: JSON.stringify({
          title: "请说明该技术选型如何验证",
          follow: true,
          index: 1,
          total: 1,
        }),
      })
    ).not.toThrow();
    await flushPromises();

    expect(wrapper.text()).toContain("追问");
    expect(wrapper.text()).toContain("请说明该技术选型如何验证");
  });

  it("starts the interview without waiting indefinitely for the optional voice socket", async () => {
    wrapper = mount(QuestionPage, {
      global: {
        stubs: {
          DefenseHeader: true,
          WaveComponent: true,
          LoadingOverlay: true,
          Progress: true,
          InterviewVoicePlayer: {
            name: "InterviewVoicePlayer",
            emits: ["ready", "playing", "broadcastingEnd"],
            template: "<div />",
          },
        },
      },
    });
    await flushPromises();
    const socket = ControlledWebSocket.instances[0];

    socket.open();
    await flushPromises();

    expect(socket.send.mock.calls.map(([payload]) => JSON.parse(payload))).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ action: "start-assessment", message: "2" }),
      ])
    );
  });

  it("starts answering promptly when question voice playback is unavailable", async () => {
    wrapper = mount(QuestionPage, {
      global: {
        stubs: {
          DefenseHeader: true,
          WaveComponent: true,
          LoadingOverlay: true,
          Progress: true,
          InterviewVoicePlayer: {
            name: "InterviewVoicePlayer",
            emits: ["ready", "playing", "broadcastingEnd"],
            template: "<div />",
          },
        },
      },
    });
    await flushPromises();
    const socket = ControlledWebSocket.instances[0];
    socket.open();
    await flushPromises();

    socket.receive({
      normalMessage: true,
      content: JSON.stringify({
        title: "请说明缓存策略",
        follow: false,
        index: 1,
        total: 1,
      }),
    });
    await flushPromises();

    await vi.advanceTimersByTimeAsync(5_000);
    await flushPromises();

    expect(socket.send.mock.calls.map(([payload]) => JSON.parse(payload))).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ action: "start-answer", message: "2" }),
      ])
    );
  });

  it("starts answering promptly when the voice socket is ready but playback never begins", async () => {
    wrapper = mount(QuestionPage, {
      global: {
        stubs: {
          DefenseHeader: true,
          WaveComponent: true,
          LoadingOverlay: true,
          Progress: true,
          InterviewVoicePlayer: {
            name: "InterviewVoicePlayer",
            emits: ["ready", "playing", "broadcastingEnd"],
            template: "<div />",
            mounted() {
              this.$emit("ready");
            },
          },
        },
      },
    });
    await flushPromises();
    const socket = ControlledWebSocket.instances[0];
    socket.open();
    await flushPromises();

    socket.receive({
      normalMessage: true,
      content: JSON.stringify({
        title: "请说明降级策略",
        follow: false,
        index: 1,
        total: 1,
      }),
    });
    await flushPromises();

    await vi.advanceTimersByTimeAsync(5_000);
    await flushPromises();

    expect(socket.send.mock.calls.map(([payload]) => JSON.parse(payload))).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ action: "start-answer", message: "2" }),
      ])
    );
  });
});
