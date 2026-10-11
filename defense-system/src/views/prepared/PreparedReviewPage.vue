<template><div><button class="review-back" @click="router.back()">返回考核管理</button><PreparedReviewPanel :assessment-id="Number(route.params.assessmentId)" /></div></template>
<script setup lang="ts">
import { useRoute,useRouter } from 'vue-router';
import PreparedReviewPanel from './PreparedReviewPanel.vue';
const route=useRoute(),router=useRouter();
</script>
<style scoped>.review-back{margin:20px 24px 0;padding:8px 14px;border:1px solid #b3c1d1;border-radius:6px;background:white;cursor:pointer}</style>
