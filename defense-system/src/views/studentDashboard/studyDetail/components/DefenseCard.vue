<template>
  <div
    class="p-5 space-y-4 h-full overflow-y-auto custom-scrollbar"
    :class="info.colorClass"
  >
    <div class="text-xl text-[#363F50] font-semibold">答辩</div>
    <section class="p-4 bg-[rgba(255,255,255,0.8)] rounded-xl">
      <div class="flex items-center space-x-[10px]">
        <img src="@/assets/studentDetail/hdIcon.png" class="w-8 h-8" alt="" />
        <span class="text-xl text-[#363F50] font-semibold">学生回答</span>
      </div>
      <div
        class="pl-[42px] mt-2 text-sm text-[rgba(0,0,0,0.7)] font-normal leading-[22px]"
      >
        {{ info?.defenseAnswer || "-" }}
      </div>
      <div class="pl-[42px] mt-2">
        <AudioPlayer
          ref="audioPlayer"
          v-if="info?.defenseAnswerFile"
          :url="info?.defenseAnswerFile"
          @stop-player="stopPlayer"
          :key="info?.defenseAnswerFile"
        />
      </div>
    </section>
  </div>
</template>
<script lang="ts" setup>
import { onUnmounted, ref } from "vue";
import AudioPlayer from "./AudioPlayer.vue";
const props = defineProps({
  info: {
    type: Object,
    default: () => ({}),
  },
});

const audioPlayer = ref();

const stopPlayer = () => {
  audioPlayer.value?.stopPlayer();
};
const stopPlayer2 = () => {
  audioPlayer.value?.stopPlayer();
};

onUnmounted(() => {
  try {
    audioPlayer.value?.stopPlayer();
  } catch (error) {}
});
</script>
