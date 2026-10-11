<template>
  <div class="loading-overlay">
    <div class="dot-spinner">
      <div class="dot"></div>
      <div class="dot"></div>
      <div class="dot"></div>
    </div>
    <span style="margin-top: 16px">{{ text }}</span>
  </div>
</template>

<script setup lang="ts">
defineProps({
  text: {
    type: String,
    default: "正在加载设备，请稍后...",
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
  background-color: rgba(255, 255, 255, 0.6);
  backdrop-filter: blur(4px);
  z-index: 9999;
  display: flex;
  justify-content: center;
  align-items: center;
  flex-direction: column;
  overflow: hidden;
}

.dot-spinner {
  display: flex;
  justify-content: center;
  align-items: center;
  gap: 10px;
}

.dot-spinner .dot {
  width: 12px;
  height: 12px;
  background-color: #409eff;
  border-radius: 50%;
  animation: dotPulse 1.4s infinite ease-in-out;
}

.dot-spinner .dot:nth-child(2) {
  animation-delay: 0.2s;
}
.dot-spinner .dot:nth-child(3) {
  animation-delay: 0.4s;
}

@keyframes dotPulse {
  0%,
  80%,
  100% {
    transform: scale(0.6);
    opacity: 0.6;
  }
  40% {
    transform: scale(1);
    opacity: 1;
  }
}
</style>
