<template>
  <div class="p-5 space-y-4 h-full overflow-y-auto custom-scrollbar" :class="info.colorClass">
    <div class="text-xl text-[#363F50] font-semibold">亮点与不足</div>
    <section class="p-4 bg-[rgba(255,255,255,0.8)] rounded-xl">
      <div class="flex items-center space-x-[10px]">
        <img src="@/assets/studentDetail/ld.png" class="w-8 h-8" alt="" />
        <span class="text-xl text-[#363F50] font-semibold">亮点</span>
      </div>
      <div
        v-for="(item, index) in info?.strengths"
        :key="index"
        class="pl-[42px] mt-2 text-sm text-[rgba(0,0,0,0.7)] font-normal"
      >
        {{ item }}
      </div>
    </section>
    <section class="p-4 bg-[rgba(255,255,255,0.8)] rounded-xl">
      <div class="flex items-center space-x-[10px]">
        <img src="@/assets/studentDetail/bz.png" class="w-8 h-8" alt="" />
        <span class="text-xl text-[#363F50] font-semibold">不足</span>
      </div>
      <div
        v-for="(item, index) in info?.weaknesses"
        :key="index"
        class="pl-[42px] mt-2 text-sm text-[rgba(0,0,0,0.7)] font-normal"
      >
        {{ item }}
      </div>
    </section>
  </div>
</template>
<script lang="ts" setup>
const props = defineProps({
  info: {
    type: Object,
    default: () => ({}),
  },
});
</script>
