<template>
  <div class="teacher-detail-shell">
    <StudyRecord />
  </div>
</template>
<script lang="ts" setup>
import StudyRecord from "@/views/studentDashboard/studyDetail/index.vue";
</script>
<style scoped>
.teacher-detail-shell {
  width: 100%;
  min-height: 100dvh;
  height: auto;
  overflow: visible;
}

@media (max-width: 980px) {
  .teacher-detail-shell {
    height: auto;
    overflow: visible;
  }
}
</style>
