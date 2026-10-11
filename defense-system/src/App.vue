<template>
  <ConfigProvider :locale="zhCN">
    <router-view v-slot="{ Component, route }">
      <keep-alive :include="['Layout']">
        <component :is="Component" :key="route.path" />
      </keep-alive>
    </router-view>
  </ConfigProvider>
</template>

<script lang="ts">
import { useAuthStore } from "@/stores/auth";
import zhCN from "ant-design-vue/es/locale/zh_CN";
import { ConfigProvider } from "ant-design-vue";
import dayjs from "dayjs";
import "dayjs/locale/zh-cn";
dayjs.locale("zh-cn");
export default {
  name: "App",
  components: { ConfigProvider },
  setup() {
    const authStore = useAuthStore();

    const logout = () => {
      authStore.logout();
      window.location.href = "/login";
    };

    return { authStore, logout, zhCN };
  },
};
</script>

<style scoped>
.top-bar {
  background: #f0f0f0;
  padding: 10px;
  text-align: right;
}
html,
body {
  margin: 0;
  padding: 0;
  min-height: 100%;
}
</style>
