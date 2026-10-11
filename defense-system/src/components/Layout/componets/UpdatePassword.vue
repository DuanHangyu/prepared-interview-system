<script setup lang="ts">
import { changePassword } from "@/api/common";
import { useAuthStore } from "@/stores/auth";
import { Modal, Form, FormItem, InputPassword, message } from "ant-design-vue";
import { Rule } from "ant-design-vue/es/form";
import { ref } from "vue";

const formRef = ref();
const loading = ref(false);
const open = ref<boolean>(false);
const formState = ref({
  currentPassword: "",
  newPassword: "",
  confirmPassword: "",
});
const authStore = useAuthStore();

defineExpose({
  openModal: () => {
    open.value = true;
  },
});

const submit = () => {
  formRef.value
    .validate()
    .then(async () => {
      loading.value = true;
      await changePassword({
        currentPassword: formState.value.currentPassword,
        newPassword: formState.value.newPassword,
      });
      message.success("密码修改成功");
      open.value = false;
      // 后端已经注销该账号的全部会话，这里只清理本机状态，避免用失效
      // token 再请求一次退出接口并触发“登录已过期”的提示。
      await authStore.logout(false);
      setTimeout(() => {
        location.href = "/login";
      }, 800);
    })
    .catch(() => undefined)
    .finally(() => {
      loading.value = false;
    });
};

const validateNewPassword = async (_rule: Rule, value: string) => {
  if (value === "") {
    return Promise.reject("该项为必填项");
  } else if (value.length < 8) {
    return Promise.reject("新密码至少需要 8 个字符");
  } else if (new TextEncoder().encode(value).length > 72) {
    return Promise.reject("新密码不能超过 72 字节");
  } else if (value === formState.value.currentPassword) {
    return Promise.reject("新密码不能与当前密码相同");
  }
  if (formState.value.confirmPassword !== "") {
    formRef.value.validateFields("confirmPassword");
  }
  return Promise.resolve();
};
const validatePass2 = async (_rule: Rule, value: string) => {
  if (value === "") {
    return Promise.reject("该项为必填项");
  } else if (value !== formState.value.newPassword) {
    return Promise.reject("两次输入的密码不一致");
  } else {
    return Promise.resolve();
  }
};
</script>

<template>
  <Modal
    v-model:open="open"
    title="修改密码"
    @ok="submit"
    :confirm-loading="loading"
    :after-close="
      () => {
        formRef?.resetFields();
      }
    "
  >
    <div class="h-[1px] w-full absolute bg-[#D9D9D9] left-0"></div>
    <Form layout="vertical" class="pt-3" :model="formState" ref="formRef">
      <FormItem
        label="当前密码"
        name="currentPassword"
        :rules="[{ required: true, message: '请输入当前密码', trigger: 'change' }]"
      >
        <InputPassword
          v-model:value="formState.currentPassword"
          autocomplete="current-password"
          placeholder="请输入当前密码"
        />
      </FormItem>
      <FormItem
        label="新密码"
        name="newPassword"
        :rules="[
          { required: true, validator: validateNewPassword, trigger: 'change' },
        ]"
      >
        <InputPassword
          v-model:value="formState.newPassword"
          autocomplete="new-password"
          :maxlength="72"
          placeholder="请输入新密码"
        />
      </FormItem>
      <FormItem
        label="确认密码"
        name="confirmPassword"
        :rules="[
          { required: true, validator: validatePass2, trigger: 'change' },
        ]"
      >
        <InputPassword
          v-model:value="formState.confirmPassword"
          autocomplete="new-password"
          placeholder="请再次输入新密码"
        />
      </FormItem>
    </Form>
  </Modal>
</template>
