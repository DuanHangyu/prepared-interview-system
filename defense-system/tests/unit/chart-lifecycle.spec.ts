import { mount, type VueWrapper } from "@vue/test-utils";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import DfmxChart from "@/views/studentDashboard/studyDetail/components/DfmxChart.vue";
import DqdfChart from "@/views/studentDashboard/studyDetail/components/DqdfChart.vue";

const chartMocks = vi.hoisted(() => ({
  instances: [] as Array<{
    setOption: ReturnType<typeof vi.fn>;
    resize: ReturnType<typeof vi.fn>;
    dispose: ReturnType<typeof vi.fn>;
  }>,
}));

vi.mock("echarts/charts", () => ({
  GaugeChart: {},
  PieChart: {},
}));

vi.mock("echarts/components", () => ({
  GridComponent: {},
  LegendComponent: {},
  TooltipComponent: {},
}));

vi.mock("echarts/renderers", () => ({
  CanvasRenderer: {},
}));

vi.mock("echarts/core", () => ({
  use: vi.fn(),
  init: vi.fn(() => {
    const instance = {
      setOption: vi.fn(),
      resize: vi.fn(),
      dispose: vi.fn(),
    };
    chartMocks.instances.push(instance);
    return instance;
  }),
  graphic: {
    LinearGradient: class LinearGradient {},
  },
  format: {
    truncateText: (text: string) => text,
  },
}));

describe("study detail chart lifecycle", () => {
  let wrapper: VueWrapper | undefined;
  let originalResizeHandler: typeof window.onresize;

  beforeEach(() => {
    chartMocks.instances.length = 0;
    originalResizeHandler = window.onresize;
  });

  afterEach(() => {
    wrapper?.unmount();
    wrapper = undefined;
    window.onresize = originalResizeHandler;
  });

  it.each([
    ["score gauge", DqdfChart, { score: 80, totalScore: 100 }],
    ["score breakdown", DfmxChart, { value: [{ name: "表达", value: 80 }] }],
  ])("cleans up the %s chart without replacing global resize behavior", async (_name, component, info) => {
    const existingHandler = vi.fn();
    window.onresize = existingHandler;

    wrapper = mount(component, { props: { info } });
    const chart = chartMocks.instances.at(-1);
    expect(chart).toBeDefined();
    expect(window.onresize).toBe(existingHandler);

    const callsAfterMount = chart!.resize.mock.calls.length;
    window.dispatchEvent(new Event("resize"));
    expect(chart!.resize).toHaveBeenCalledTimes(callsAfterMount + 1);

    wrapper.unmount();
    wrapper = undefined;
    expect(chart!.dispose).toHaveBeenCalledOnce();

    window.dispatchEvent(new Event("resize"));
    expect(chart!.resize).toHaveBeenCalledTimes(callsAfterMount + 1);
  });
});
