<template>
  <div class="loading-overlay" :style="{ background: bg }">
    <div class="loader"></div>
    <div class="mt-10 text-sm text-[#fff] font-medium">{{ text }}</div>
  </div>
</template>

<script setup lang="ts">
defineProps({
  text: {
    type: String,
    default: "正在加载设备，请稍后...",
  },
  bg: {
    type: String,
    default: "rgba(95, 90, 87, 1)",
  },
});
</script>
<style scoped>
.loading-overlay {
  position: fixed;
  top: 0;
  left: 0;
  width: 100vw;
  height: 100vh;
  backdrop-filter: blur(4px);
  z-index: 9999;
  display: flex;
  justify-content: center;
  align-items: center;
  flex-direction: column;
  overflow: hidden;
}
/* HTML: <div class="loader"></div> */
.loader {
  width: 4px;
  color: #fff;
  aspect-ratio: 1;
  border-radius: 50%;
  box-shadow: 19px -19px 0 0px, 38px -19px 0 0px, 57px -19px 0 0px, 19px 0 0 5px,
    38px 0 0 5px, 57px 0 0 5px, 19px 19px 0 0px, 38px 19px 0 0px,
    57px 19px 0 0px;
  transform: translateX(-38px);
  animation: l26 2s infinite linear;
}
@keyframes l26 {
  12.5% {
    box-shadow: 19px -19px 0 0px, 38px -19px 0 0px, 57px -19px 0 5px,
      19px 0 0 5px, 38px 0 0 0px, 57px 0 0 5px, 19px 19px 0 0px, 38px 19px 0 0px,
      57px 19px 0 0px;
  }
  25% {
    box-shadow: 19px -19px 0 5px, 38px -19px 0 0px, 57px -19px 0 5px,
      19px 0 0 0px, 38px 0 0 0px, 57px 0 0 0px, 19px 19px 0 0px, 38px 19px 0 5px,
      57px 19px 0 0px;
  }
  50% {
    box-shadow: 19px -19px 0 5px, 38px -19px 0 5px, 57px -19px 0 0px,
      19px 0 0 0px, 38px 0 0 0px, 57px 0 0 0px, 19px 19px 0 0px, 38px 19px 0 0px,
      57px 19px 0 5px;
  }
  62.5% {
    box-shadow: 19px -19px 0 0px, 38px -19px 0 0px, 57px -19px 0 0px,
      19px 0 0 5px, 38px 0 0 0px, 57px 0 0 0px, 19px 19px 0 0px, 38px 19px 0 5px,
      57px 19px 0 5px;
  }
  75% {
    box-shadow: 19px -19px 0 0px, 38px -19px 0 5px, 57px -19px 0 0px,
      19px 0 0 0px, 38px 0 0 0px, 57px 0 0 5px, 19px 19px 0 0px, 38px 19px 0 0px,
      57px 19px 0 5px;
  }
  87.5% {
    box-shadow: 19px -19px 0 0px, 38px -19px 0 5px, 57px -19px 0 0px,
      19px 0 0 0px, 38px 0 0 5px, 57px 0 0 0px, 19px 19px 0 5px, 38px 19px 0 0px,
      57px 19px 0 0px;
  }
}
</style>
