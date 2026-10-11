<template>
  <audio
    ref="audioPlayer"
    class="hidden"
    :src="url"
    preload="metadata"
    @durationchange="updateDuration"
    @loadedmetadata="updateDuration"
    @play="playerStatus = 'play'"
    @playing="playerStatus = 'play'"
    @pause="playerStatus = 'stop'"
    @ended="playerStatus = 'stop'"
    @waiting="playerStatus = 'stop'"
    @error="playerStatus = 'stop'"
    @timeupdate="updatePlaybackTime"
  ></audio>
  <div
    class="player cursor-pointer flex items-center justify-between px-4 space-x-2"
  >
    <img
      :src="playerStatus == 'play' ? PlayIcon : StopIcon"
      class="w-6 h-6 flex-shrink-0 cursor-pointer"
      alt=""
      @click="startPlayer()"
    />
    <div class="flex-grow">
      <Slider
        :value="currentTime"
        @update:value="updateCurrentTime"
        @afterChange="blurFn"
        :max="audioTime"
        :tooltipOpen="false"
      />
    </div>
    <span class="text-sm font-bold text-white flex-shrink-0">
      {{ audioTime == Infinity ? 0 : audioTime }}s''
    </span>
    <!-- <img
      :src="
        playerStatus == 'stop'
          ? Audio3
          : currentTime % 3 == 2
          ? Audio3
          : currentTime % 3 == 1
          ? Audio2
          : Audio1
      "
      class="w-[23px] h-[24px]"
      alt=""
    /> -->
  </div>
</template>
<script lang="ts" setup>
import { Slider } from "ant-design-vue";
import { onUnmounted, ref } from "vue";
import PlayIcon from "@/assets/studentDetail/play.png";
import StopIcon from "@/assets/studentDetail/stop.png";

const emits = defineEmits(["stopPlayer"]);

const props = defineProps({
  url: {
    type: String,
    default: "",
  },
});

const audioPlayer = ref<HTMLAudioElement>();
const audioTime = ref(0);
const playerStatus = ref("stop");
const currentTime = ref(0);

const updateDuration = () => {
  const duration = audioPlayer.value?.duration;
  audioTime.value = Number.isFinite(duration) ? Math.floor(duration || 0) : 0;
};

const updatePlaybackTime = () => {
  currentTime.value = audioPlayer.value?.currentTime || 0;
};

const startPlayer = async () => {
  emits("stopPlayer");
  if (!audioPlayer.value) {
    return;
  }
  if (audioPlayer.value.paused) {
    try {
      await audioPlayer.value.play();
    } catch {
      playerStatus.value = "stop";
    }
  } else {
    audioPlayer.value.pause();
  }
};

const updateCurrentTime = (value: number | [number, number]) => {
  if (Array.isArray(value)) {
    return;
  }
  emits("stopPlayer");
  currentTime.value = value;
};
const blurFn = () => {
  if (audioPlayer.value) {
    audioPlayer.value.currentTime = currentTime.value;
  }
};

const stopPlayer = () => {
  audioPlayer.value?.pause();
};

onUnmounted(() => {
  stopPlayer();
  if (audioPlayer.value) {
    audioPlayer.value.removeAttribute("src");
    audioPlayer.value.load();
  }
});

defineExpose({
  stopPlayer,
});
</script>
<style scoped>
:deep(.ant-slider-handle) {
  width: 16px;
  height: 16px;
  border-radius: 100%;
  background: linear-gradient(180deg, #6ae5df 0%, #6f7cf0 87%);
  box-sizing: border-box;
  border: 1px solid #ffffff;
  box-shadow: 0px 4px 10px 0px rgba(0, 0, 0, 0.12);
  top: -1px;
  &::before {
    display: none;
  }
  &::after {
    display: none;
  }
}

:deep(.ant-slider-track) {
  background: white;
}
:deep(.ant-slider-rail) {
  background: rgba(255, 255, 255, 0.7);
}
</style>
<style>
.player {
  width: 400px;
  height: 38px;
  background: linear-gradient(270deg, #69f7dc 0%, #7169f4 100%);
  box-shadow: 0px 9px 12px 0px rgba(18, 21, 73, 0.08);
  border-radius: 693px 693px 693px 693px;
}
</style>
