<template>
  <Modal
    v-model:open="open"
    :title="detail?.id ? '编辑' : '新增'"
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
        label="姓名"
        name="name"
        :rules="[{ required: true, message: '该项为必填项' }]"
      >
        <Input
          v-model:value="formState.name"
          aria-label="姓名"
          placeholder="请输入"
        />
      </FormItem>
      <FormItem
        label="账号"
        name="account"
        :rules="[{ required: true, message: '该项为必填项' }]"
      >
        <Input
          v-model:value="formState.account"
          aria-label="账号"
          placeholder="请输入"
        />
      </FormItem>
      <FormItem
        label="密码"
        name="password"
        :rules="passwordRules"
      >
        <InputPassword
          v-model:value="formState.password"
          aria-label="密码"
          autocomplete="off"
          placeholder="请输入"
        />
      </FormItem>
      <FormItem
        label="班级"
        name="schoolClass"
        :rules="[{ required: true, message: '该项为必填项' }]"
      >
        <Input
          v-model:value="formState.schoolClass"
          aria-label="班级"
          placeholder="请输入"
        />
      </FormItem>
    </Form>
  </Modal>
</template>
<script setup lang="ts">
import {
  Modal,
  Form,
  FormItem,
  Input,
  InputPassword,
  message,
} from "ant-design-vue";
import { computed, ref } from "vue";
import { createStudent, modifyStudent } from "@/api/student";
import {
  accountMutationPayload,
  passwordRequiredFor,
} from "@/views/adminDashboard/accountForm";
const emits = defineEmits(["success"]);

const open = ref<boolean>(false);
const detail = ref<Record<string, any>>({});
const formRef = ref();
const loading = ref(false);
const formState = ref({
  name: "",
  account: "",
  password: "",
  schoolClass: "",
});
const isEditing = computed(() => Boolean(detail.value?.id));
const passwordRules = computed(() => [
  {
    required: passwordRequiredFor(isEditing.value),
    message: "该项为必填项",
  },
]);

const submit = () => {
  formRef.value
    .validate()
    .then(async () => {
      loading.value = true;
      const payload = accountMutationPayload(formState.value, isEditing.value);
      if (isEditing.value) {
        await modifyStudent(payload);
      } else {
        await createStudent(payload);
      }
      message.success("保存成功");
      emits("success");
      open.value = false;
    })
    .catch(() => undefined)
    .finally(() => {
      loading.value = false;
    });
};

defineExpose({
  openModal: (e: Record<string, any> = {}) => {
    open.value = true;
    detail.value = e;
    formState.value = {
      name: "",
      account: "",
      password: "",
      schoolClass: "",
    };
    Object.assign(formState.value, e as any);
    formState.value.password = "";
  },
});
</script>
