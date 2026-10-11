<template>
  <div class="flex items-center justify-between px-[24px] header">
    <div class="w-[140px] h-[54px] overflow-hidden">
      <img
        src="@/assets/defense/logo.jpg"
        alt=""
        class="w-[140px] h-[54px] cursor-pointer"
        @click="$router.push('/')"
      />
    </div>
    <Dropdown placement="bottomRight" trigger="hover" :arrow="false">
      <section
        class="text-[#fff] text-sm font-semibold flex items-center space-x-1 !cursor-pointer pl-2 pr-1 py-[5px] hover:bg-[rgba(255,255,255,0.5)] rounded-lg focus-within:outline-none"
      >
        <span>{{ authStore?.info?.name }}</span>
        <img src="@/assets/defense/arrow-down-white.png" class="size-6 mb-[2px]" alt="" />
      </section>
      <template #overlay>
        <Menu class="w-[150px]" @click="commandFn">
          <MenuItem key="1"> 修改密码 </MenuItem>
          <MenuItem key="2"> 退出登录 </MenuItem>
        </Menu>
      </template>
    </Dropdown>
    <UpdatePassword ref="updatePass" />
  </div>
</template>
<script setup lang="ts">
import { useAuthStore } from "@/stores/auth";
import { Dropdown, Menu, MenuItem, MenuProps } from "ant-design-vue";
import UpdatePassword from "./UpdatePassword.vue";
import { ref } from "vue";

defineOptions({ name: "Header" });

const authStore = useAuthStore();
const updatePass = ref();

const commandFn: MenuProps["onClick"] = ({ key }) => {
  if (key == 1) {
    updatePass.value?.openModal();
  } else {
    authStore.logout();
    window.location.href = "/login";
  }
};
</script>
<style scoped>
.header {
  height: 53px;
  background: rgba(248, 248, 240, 0.16);
  box-shadow:
    0px 12px 28px 0px rgba(32, 33, 41, 0.08),
    inset 0 1px 0 rgba(255, 255, 255, 0.18);
  border: 1px solid rgba(255, 255, 255, 0.18);
  border-radius: 12px;
}
</style>
