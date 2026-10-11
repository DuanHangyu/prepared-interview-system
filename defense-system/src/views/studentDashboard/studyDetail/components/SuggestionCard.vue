<template>
  <div class="p-5 space-y-4 h-full overflow-y-auto custom-scrollbar" :class="info.colorClass">
    <div class="text-xl text-[#363F50] font-semibold">建议</div>
    <section class="p-6 bg-[rgba(255,255,255,0.8)] rounded-xl space-y-4">
      <div
        class="flex space-x-3"
        v-for="(item, index) in info?.suggestions"
        :key="index"
      >
        <span class="flex-shrink-0 text-base font-semibold text-[#756DF4]">
          {{ String(Number(index) + 1).padStart(2, "0") }}
        </span>
        <div
          class="flex-grow whitespace-wrap text-sm text-[rgba(0,0,0,0.7)] font-normal"
        >
          {{ item }}
        </div>
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
