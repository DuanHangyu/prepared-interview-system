<template>
  <div
    class="student-route-shell"
    :class="{ 'student-route-shell--fullscreen': isFullscreenRoute }"
  >
    <router-view />
  </div>
</template>
<script setup lang="ts">
import { computed } from "vue";
import { useRoute } from "vue-router";

const route = useRoute();
const fullscreenRouteNames = new Set([
  "Dashboard",
  "QuestionPage",
  "ResultOverPage",
]);

const isFullscreenRoute = computed(() =>
  fullscreenRouteNames.has(String(route.name || ""))
);
</script>
<style scoped>
.student-route-shell {
  width: 100%;
  min-height: 100dvh;
  height: auto;
  overflow: visible;
}

.student-route-shell--fullscreen {
  height: 100dvh;
  overflow: hidden;
}

@media (max-width: 980px) {
  .student-route-shell--fullscreen {
    height: auto;
    overflow: visible;
  }
}
</style>
