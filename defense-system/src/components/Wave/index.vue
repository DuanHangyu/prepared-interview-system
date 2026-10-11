<template>
  <div
    class="size-full flex items-center justify-center space-x-[3px]"
    v-if="loading"
  >
    <div
      class="w-1 bg-[#fff] rounded-3xl wave-bar"
      v-for="(item, index) in bars"
      :key="index"
      :style="{
        '--height': item.height + 'px',
        '--duration': item.duration + 'ms',
        '--delay': item.delay + 'ms',
      }"
    ></div>
  </div>
  <div class="size-full flex items-center justify-center space-x-[3px]" v-else>
    <div
      class="w-1 bg-[#fff] rounded-3xl"
      v-for="(item, index) in bars"
      :key="index"
      :style="{
        height: item.height + 'px',
      }"
    ></div>
  </div>
  <!-- <video
    v-if="loading"
    loop
    autoplay
    muted
    playsinline
    :controls="false"
    class="size-full"
    :src="waveWebm"
    alt=""
  />
  <img v-else :src="waveSvg" class="size-full" alt="" /> -->
</template>

<script lang="ts" setup>
import { ref } from "vue";
import waveGif from "@/assets/wave.gif";
import waveSvg from "@/assets/wave.svg";
import waveMp4 from "@/assets/wave.mp4";
import waveWebm from "@/assets/wave.webm";

const props = defineProps({
  barCount: {
    type: Number,
    default: 80,
  },
  min: {
    type: Number,
    default: 4,
  },
  max: {
    type: Number,
    default: 72,
  },
  loading: {
    type: Boolean,
    default: false,
  },
});
const randomInt = (min: number, max: number) =>
  Math.floor(Math.random() * (max - min + 1)) + min;
// const bars = ref(
//   Array(props.barCount)
//     .fill(0)
//     .map(() => {
//       return {
//         height: randomInt(props.min, props.max),
//         duration: Math.random() * 200 + 300,
//         delay: Math.random() * 1000,
//       };
//     })
// );
const bars = ref(
  [
    13, 6, 6, 13, 7, 13, 6, 12, 18, 10, 16, 6, 33, 15, 8, 31, 13, 36, 20, 13,
    19, 10, 40, 13, 23, 20, 39, 10, 20, 13, 20, 15, 30, 46, 13, 27, 20, 58, 6,
    20, 6, 31, 10, 5, 13, 32, 20, 47, 6, 14, 36, 6, 6, 14, 25, 14, 37, 20, 10,
    23, 20, 10, 20, 14, 10, 5, 5, 20, 10, 14, 8,
  ].map((item) => {
    return {
      height: item,
      duration: Math.random() * 200 + 150,
      delay: Math.random() * 300,
    };
  })
);
</script>
<style scoped>
.wave-bar {
  transform-origin: center center;
  animation: sound var(--duration) var(--delay) linear infinite alternate;
  height: var(--height);
}
.wave-2 {
  transform-origin: center center;
  animation: sound var(--duration) var(--delay) linear infinite alternate;
  height: var(--height);
}
@keyframes sound {
  0% {
    transform: scaleY(0.3);
  }
  100% {
    transform: scaleY(1);
  }
}
</style>
