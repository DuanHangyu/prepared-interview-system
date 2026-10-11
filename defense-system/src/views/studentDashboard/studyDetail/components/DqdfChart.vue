<template>
  <div class="w-full h-full" style="max-height: 290px" ref="chart"></div>
</template>
<script lang="ts" setup>
import { onBeforeUnmount, onMounted, ref } from "vue";
import { GaugeChart } from "echarts/charts";
import * as echarts from "echarts/core";
import { CanvasRenderer } from "echarts/renderers";

echarts.use([GaugeChart, CanvasRenderer]);

const chart = ref();
let chartInstance: ReturnType<typeof echarts.init> | undefined;

const resizeChart = () => {
  chartInstance?.resize();
};

const props = defineProps({
  info: {
    type: Object,
    default: () => {},
  },
});

const init = () => {
  const tmpOption = {
    series: [
      {
        z: 1,
        center: ["50%", "78%"],
        radius: "86%",
        type: "gauge",
        startAngle: 180,
        endAngle: 0,
        min: 0,
        max: props.info?.totalScore || 0,
        splitNumber: 4,
        itemStyle: {
          color: new echarts.graphic.LinearGradient(0, 1, 0, 0, [
            {
              offset: 0,
              color: "rgba(234, 189, 164, 1)",
            },
            {
              offset: 1,
              color: "rgba(241, 226, 152, 1)",
            },
          ]),
        },
        progress: {
          show: true,
          width: 28,
        },
        pointer: {
          show: false,
        },
        axisLine: {
          lineStyle: {
            width: 32,
            color: [
              [0, "#fff"],
              [1, "#fff"],
            ],
          },
        },
        axisTick: {
          show: false,
        },
        splitLine: {
          show: false,
        },
        axisLabel: {
          distance: -50,
          color: "rgba(0,0,0,0.88)",
          fontSize: 16,
          fontWeight: 400,
          formatter: function (value: number) {
            return Math.round(value);
          },
        },
        title: {
          show: false,
        },
        detail: {
          offsetCenter: [0, "-26%"],
          valueAnimation: true,
          formatter: function (value: number) {
            return "{value|" + value.toFixed(0) + "}\n{unit|当前得分}";
          },
          rich: {
            value: {
              fontSize: 50,
              fontWeight: 600,
              color: "rgba(30, 27, 57, 1)",
            },
            unit: {
              fontSize: 14,
              color: "#4D4E58",
              fontWeight: 400,
              padding: [10, 0, 0, 10],
            },
          },
        },
        data: [
          {
            value: props.info?.score || 0,
          },
        ],
      },
      {
        z: 2,
        center: ["50%", "78%"],
        radius: "86%",
        type: "gauge",
        startAngle: 180,
        endAngle: 0,
        min: 0,
        max: props.info?.totalScore || 0,
        splitNumber: 4,
        itemStyle: {
          color: new echarts.graphic.LinearGradient(0, 1, 0, 0, [
            {
              offset: 0,
              color: "rgba(234, 189, 164, 1)",
            },
            {
              offset: 1,
              color: "rgba(241, 226, 152, 1)",
            },
          ]),
        },
        progress: {
          show: false,
          width: 28,
        },
        pointer: {
          // icon: "roundRect",
          icon: "image://data:image/png;base64,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",
          length: 52,
          width: 22,
          offsetCenter: [0, "-72%"],
          itemStyle: {
            // color: "#000",
            borderCap: "round",
            borderColor: "rgba(255, 255, 255, 1)",
            shadowColor: "rgba(0, 0, 0, 0.08)",
            shadowOffsetX: "4px",
            shadowOffsetY: "10px",
          },
        },
        axisLine: {
          show: false,
        },
        axisTick: {
          show: false,
        },
        splitLine: {
          show: false,
        },
        axisLabel: {
          show: false,
        },
        title: {
          show: false,
        },
        detail: { show: false },
        data: [
          {
            value: props.info?.score || 0,
          },
        ],
      },
    ],
  };

  chartInstance = echarts.init(chart.value);
  chartInstance.setOption(tmpOption);
  window.addEventListener("resize", resizeChart);
  resizeChart();
};
onMounted(() => {
  init();
});
onBeforeUnmount(() => {
  window.removeEventListener("resize", resizeChart);
  chartInstance?.dispose();
  chartInstance = undefined;
});
</script>
