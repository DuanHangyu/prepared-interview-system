import { flushPromises, mount, type VueWrapper } from "@vue/test-utils";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import DefensePage from "@/views/studentDashboard/defense/index2.vue";

const testState = vi.hoisted(() => ({
  route: { query: { id: "2" } },
  routerPush: vi.fn(),
  routerReplace: vi.fn(),
  getAssessmentDetail: vi.fn(),
  initAudio: vi.fn(),
  closeAudio: vi.fn(),
  getUserMedia: vi.fn(),
}));

vi.mock("vue-router", () => ({
  useRoute: () => testState.route,
  useRouter: () => ({
    push: testState.routerPush,
    replace: testState.routerReplace,
  }),
}));

vi.mock("@/api/assessment", () => ({
  getAssessmentDetail: testState.getAssessmentDetail,
}));

vi.mock("@/api/studentAssessment", () => ({
  uploadAssessmentFile: vi.fn(),
}));

vi.mock("@/api/common", () => ({
  previewFile: vi.fn(),
}));

vi.mock("@/composables/useSpeechRecognition.js", async () => {
  const { ref } = await import("vue");
  return {
    useSpeechRecognition: () => ({
      startAudio: vi.fn().mockResolvedValue(true),
      finishAudioTurn: vi.fn().mockResolvedValue(true),
      initAudio: testState.initAudio,
      closeAudio: testState.closeAudio,
      volumeLevel: ref(0),
      audioReady: ref(false),
      audioError: ref(""),
    }),
  };
});

class PendingWebSocket {
  static OPEN = 1;
  static CLOSED = 3;
  readyState = 0;
  send = vi.fn();
  close = vi.fn(() => {
    this.readyState = PendingWebSocket.CLOSED;
  });
}

describe("defense page setup recovery", () => {
  let wrapper: VueWrapper | undefined;

  beforeEach(() => {
    testState.routerPush.mockReset();
    testState.routerReplace.mockReset();
    testState.getAssessmentDetail.mockReset();
    testState.initAudio.mockReset().mockResolvedValue(true);
    testState.closeAudio.mockReset().mockResolvedValue(undefined);
    testState.getUserMedia.mockReset().mockResolvedValue({
      getTracks: () => [{ stop: vi.fn() }],
    });
    vi.stubGlobal("WebSocket", PendingWebSocket);
    vi.stubGlobal("navigator", {
      mediaDevices: {
        getUserMedia: testState.getUserMedia,
      },
    });
  });

  afterEach(async () => {
    wrapper?.unmount();
    wrapper = undefined;
    await flushPromises();
    vi.unstubAllGlobals();
  });

  it("reloads assessment details when setup is retried after a request failure", async () => {
    testState.getAssessmentDetail
      .mockRejectedValueOnce(new Error("系统接口请求超时"))
      .mockResolvedValueOnce({
        data: {
          duration: 30,
          theme: "完整面试流程测试",
          defense: true,
          question: true,
        },
      });

    wrapper = mount(DefensePage, {
      global: {
        stubs: {
          DefenseHeader: true,
          WaveComponent: true,
          LoadingOverlay: true,
          Upload: true,
          Progress: true,
          LoadingOutlined: true,
          ExclamationCircleOutlined: true,
        },
      },
    });
    await flushPromises();

    expect(wrapper.text()).toContain("系统接口请求超时");
    expect(testState.getAssessmentDetail).toHaveBeenCalledTimes(1);

    const retry = wrapper
      .findAll("button")
      .find((button) => button.text().includes("重试"));
    expect(retry).toBeDefined();
    await retry!.trigger("click");
    await flushPromises();

    expect(testState.getAssessmentDetail).toHaveBeenCalledTimes(2);
    expect(wrapper.text()).not.toContain("系统接口请求超时");
    expect(testState.initAudio).toHaveBeenCalledOnce();
    expect(testState.getUserMedia).toHaveBeenCalledWith({
      video: {
        width: { ideal: 320, max: 640 },
        height: { ideal: 240, max: 480 },
        frameRate: { ideal: 8, max: 12 },
        facingMode: { ideal: "user" },
      },
    });
  });
});
