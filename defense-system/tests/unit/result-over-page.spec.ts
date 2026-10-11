import { flushPromises, mount, VueWrapper } from "@vue/test-utils";
import { nextTick } from "vue";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import ResultOverPage from "@/views/ResultOverPage.vue";

const testState = vi.hoisted(() => ({
  route: { query: {} as Record<string, unknown> },
  routerPush: vi.fn(),
  getStudentAssessmentDetail: vi.fn(),
}));

vi.mock("vue-router", () => ({
  useRoute: () => testState.route,
  useRouter: () => ({ push: testState.routerPush }),
}));

vi.mock("@/api/studentAssessment", () => ({
  getStudentAssessmentDetail: testState.getStudentAssessmentDetail,
}));

describe("ResultOverPage", () => {
  let wrapper: VueWrapper | undefined;

  beforeEach(() => {
    vi.useFakeTimers();
    testState.route.query = {};
    testState.routerPush.mockReset();
    testState.getStudentAssessmentDetail.mockReset();
  });

  afterEach(() => {
    wrapper?.unmount();
    wrapper = undefined;
    vi.clearAllTimers();
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it("rejects a result route without an assessment id before making a request", async () => {
    wrapper = await mountPage();

    expect(wrapper.text()).toContain("需要重新检查");
    expect(testState.getStudentAssessmentDetail).not.toHaveBeenCalled();
  });

  it("shows a completed result and keeps the assessment id when opening details", async () => {
    testState.route.query = { id: "17" };
    testState.getStudentAssessmentDetail.mockResolvedValue({
      data: { state: 1, showResult: true },
    });
    wrapper = await mountPage();

    expect(testState.getStudentAssessmentDetail).toHaveBeenCalledWith({ id: "17" });
    expect(wrapper.text()).toContain("考核分析已完成");
    await findButton(wrapper, "查看分析结果").trigger("click");

    expect(testState.routerPush).toHaveBeenCalledWith({
      name: "StudentStudyDetail",
      query: { id: "17" },
    });
    expect(vi.getTimerCount()).toBe(0);
  });

  it("does not expose the report button when the teacher has hidden the result", async () => {
    testState.route.query = { id: "18" };
    testState.getStudentAssessmentDetail.mockResolvedValue({
      data: { state: 1, showResult: false },
    });
    wrapper = await mountPage();

    expect(wrapper.text()).toContain("报告已归档");
    expect(wrapper.text()).toContain("结果暂不对学生展示");
    expect(findOptionalButton(wrapper, "查看分析结果")).toBeUndefined();
  });

  it("schedules another check while automatic analysis retry is pending", async () => {
    testState.route.query = { id: "19" };
    testState.getStudentAssessmentDetail.mockResolvedValue({
      data: { state: 2, reAnalysis: false, showResult: true },
    });
    wrapper = await mountPage();

    expect(wrapper.text()).toContain("自动重试中");
    expect(vi.getTimerCount()).toBe(1);
    wrapper.unmount();
    wrapper = undefined;
    expect(vi.getTimerCount()).toBe(0);
  });

  it("keeps polling when analysis has not started yet", async () => {
    testState.route.query = { id: "20" };
    testState.getStudentAssessmentDetail.mockResolvedValue({
      data: { state: 0, showResult: true },
    });
    wrapper = await mountPage();

    expect(wrapper.text()).toContain("分析处理中");
    expect(vi.getTimerCount()).toBe(1);
  });

  it("shows a recoverable error and schedules a retry after a request failure", async () => {
    testState.route.query = { id: "21" };
    testState.getStudentAssessmentDetail.mockRejectedValue(new Error("offline"));
    vi.spyOn(console, "error").mockImplementation(() => undefined);
    wrapper = await mountPage();

    expect(wrapper.text()).toContain("需要重新检查");
    expect(wrapper.text()).toContain("网络恢复后再次确认");
    expect(vi.getTimerCount()).toBe(1);
  });

  it("returns to the dashboard through the explicit action", async () => {
    wrapper = await mountPage();

    await findButton(wrapper, "返回主界面").trigger("click");
    expect(testState.routerPush).toHaveBeenCalledWith("/");
  });

  async function mountPage() {
    const mounted = mount(ResultOverPage, {
      global: {
        stubs: {
          Button: {
            emits: ["click"],
            template:
              '<button type="button" @click="$emit(\'click\')"><slot /></button>',
          },
          StudentHeader: true,
          LoadingOverlay: true,
          CheckCircleOutlined: true,
          ClockCircleOutlined: true,
          ExclamationCircleOutlined: true,
          FileSearchOutlined: true,
          HomeOutlined: true,
          ReloadOutlined: true,
        },
      },
    });
    await flushPromises();
    await nextTick();
    return mounted;
  }

  function findButton(target: VueWrapper, text: string) {
    const button = findOptionalButton(target, text);
    if (!button) {
      throw new Error(`Button not found: ${text}`);
    }
    return button;
  }

  function findOptionalButton(target: VueWrapper, text: string) {
    return target.findAll("button").find((button) => button.text().includes(text));
  }
});
