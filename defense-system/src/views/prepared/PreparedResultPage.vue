<template>
  <main>
    <h1>面试结果与处理状态</h1>
    <PreparedStudentResult v-if="allowed" :student-id="studentId" :assessment-id="assessmentId" />
    <p v-else role="alert">学生身份或考核标识无法核验，请重新登录后从本人考核列表进入。</p>
    <button @click="router.push('/studentDashboard')">返回考核列表</button>
    <button v-if="allowed" @click="router.push({name:'StudentStudyDetail',query:{id:assessmentId}})">查看旧版报告（仅旧考核）</button>
  </main>
</template>
<script setup lang="ts">
import {computed} from 'vue';
import {useRoute,useRouter} from 'vue-router';
import {useAuthStore} from '@/stores/auth';
import PreparedStudentResult from './PreparedStudentResult.vue';
const route=useRoute(),router=useRouter(),auth=useAuthStore();
const assessmentId=computed(()=>{
  const value=String(route.params.assessmentId??'');return /^[1-9][0-9]{0,9}$/.test(value)&&Number(value)<=2147483647?Number(value):0;
});
const studentId=computed(()=>Number(auth.id));
const allowed=computed(()=>auth.authenticated&&auth.role==='STUDENT'&&Number.isSafeInteger(studentId.value)&&studentId.value>0&&studentId.value<=2147483647&&assessmentId.value>0);
</script>
<style scoped>main{padding:24px}h1,main>button,main>p{margin:16px}button{padding:8px 14px}</style>
