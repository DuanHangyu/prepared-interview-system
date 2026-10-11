<template>
  <div
    class="p-5 space-y-4 h-full overflow-y-auto custom-scrollbar"
    :class="info.colorClass"
  >
    <div class="text-xl text-[#363F50] font-semibold">分析</div>
    <section class="p-6 bg-[rgba(255,255,255,0.8)] rounded-xl space-y-4">
      <div
        class="flex space-x-3"
        v-for="(item, index) in info?.analysis"
        :key="index"
      >
        <span class="flex-shrink-0 text-base font-semibold text-[#756DF4]">
          {{ String(Number(index) + 1).padStart(2, "0") }}
        </span>
        <div class="flex-grow">
          <div class="whitespace-wrap text-sm text-[rgba(0,0,0,0.7)] font-bold">
            {{ item?.name }}（{{ item?.score }}分）：
          </div>
          <div
            class="whitespace-wrap text-sm text-[rgba(0,0,0,0.7)] font-normal mt-1"
          >
            {{ item?.description }}
          </div>
        </div>
      </div>
      <Divider class="!my-4" />
      <div>
        <div class="text-center font-semibold text-base text-[#756DF4]">
          总结
        </div>
        <div class="mt-1 text-sm text-[rgba(0,0,0,0.7)] font-normal">
          {{ summary }}
        </div>
      </div>
    </section>
  </div>
</template>
<script lang="ts" setup>
import { Divider } from "ant-design-vue";

const props = defineProps({
  info: {
    type: Object,
    default: () => ({}),
  },
  summary: {
    type: String,
    default: "",
  },
});
</script>
