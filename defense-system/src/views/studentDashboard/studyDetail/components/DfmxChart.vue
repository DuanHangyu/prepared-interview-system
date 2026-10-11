<template>
  <div class="w-full h-full" ref="chart"></div>
</template>
<script lang="ts" setup>
import { onBeforeUnmount, onMounted, ref } from "vue";
import { PieChart } from "echarts/charts";
import {
  GridComponent,
  LegendComponent,
  TooltipComponent,
} from "echarts/components";
import * as echarts from "echarts/core";
import { CanvasRenderer } from "echarts/renderers";

echarts.use([
  PieChart,
  GridComponent,
  LegendComponent,
  TooltipComponent,
  CanvasRenderer,
]);

const props = defineProps({
  info: {
    type: Object,
    default: () => {},
  },
});

const dfmxColor = [
  "#EAD9A9",
  "#B3C4CD",
  "#C4C8E2",
  "#B3D0B8",
  "#F3E8C0",
  "#C5D1D9",
  "#D1D5F0",
  "#C2E0C8",
  "#D9C890",
  "#A0B0BA",
  "#A9AECE",
  "#9ABBA3",
  "#F8EDD4",
  "#DCE3E8",
  "#DBE0F7",
  "#D1EBD7",
];
const chart = ref();
let chartInstance: ReturnType<typeof echarts.init> | undefined;

const resizeChart = () => {
  chartInstance?.resize();
};

const init = (list: Record<string, any>[]) => {
  const tmpOption = {
    tooltip: {
      show: true,
      trigger: "item",
      extraCssText: "width:200px;height:auto;white-space: wrap;",
    },
    legend: {
      top: "bottom",
      textStyle: {
        color: "#4D4E58",
        fontSize: "0.8rem",
      },
      itemWidth: 12,
      itemHeight: 8,
      itemGap: 18,
      icon: "rect",
      // formatter: function (name: string) {
      //   return name.length > 8 ? name.substring(0, 8) + '...' : name;
      // },
      // tooltip: {
      //   show: true
      // },
      formatter: function (name: string) {
        return echarts.format.truncateText(
          name,
          90,
          "14px Microsoft Yahei",
          "…"
        );
      },
      tooltip: {
        show: true,
      },
      data: props.info?.value || [],
    },
    grid: {
      containLabel: true,
      top: "5%",
      right: "5%",
      bottom: "6%",
      left: "5%",
    },
    series: [
      {
        name: "",
        type: "pie",
        radius: ["30%", "60%"],
        center: ["50%", "40%"],
        roseType: "area",
        itemStyle: {
          borderRadius: 6,
          color: function (params: Recordable) {
            return dfmxColor[params.dataIndex];
          },
        },
        padAngle: 2,
        // label: {
        //   color: "#4D4E58",
        //   fontSize: "1rem",
        //   // formatter: "{c}分",
        //   formatter: (parmas: Recordable) => {
        //     return parmas?.data?.originValule + "分";
        //   },
        // },
        label: {
          formatter: "{name|{b}}\n{line|}\n{score|{c} 分}",
          edgeDistance: 40,
          lineHeight: 13,
          rich: {
            name: {
              fontSize: 12,
              color: "#363F50",
            },
            line: {
              height: 7,
              padding: [0, 5],
              backgroundColor: "inherit",
            },
            score: {
              fontSize: 11,
              color: "#363F50",
            },
          },
        },
        data: props.info?.value || [],
      },
    ],
  };

  chartInstance = echarts.init(chart.value);
  chartInstance.setOption(tmpOption);
  window.addEventListener("resize", resizeChart);
  resizeChart();
};
onMounted(() => {
  init([
    { name: "项目类型1", value: 100 },
    { name: "项目类型2", value: 1 },
    { name: "项目类型3", value: 0 },
    { name: "项目类型4", value: 100 },
  ]);
});
onBeforeUnmount(() => {
  window.removeEventListener("resize", resizeChart);
  chartInstance?.dispose();
  chartInstance = undefined;
});
</script>
