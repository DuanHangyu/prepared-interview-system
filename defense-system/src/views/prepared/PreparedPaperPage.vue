<template>
  <div>
    <button type="button" class="paper-back" @click="router.back()">返回考核设置</button>
    <PreparedPaperEditor :assessment-id="Number(route.params.assessmentId)" />
  </div>
</template>
<script setup lang="ts">
import { useRoute, useRouter } from 'vue-router';
import PreparedPaperEditor from './PreparedPaperEditor.vue';
const route = useRoute();
const router = useRouter();
</script>
<style scoped>
.paper-back { margin: 20px 24px 0; padding: 8px 14px; border: 1px solid #b3c1d1; border-radius: 6px; background: white; cursor: pointer; }
</style>
