<script setup lang="ts">
import { changeScore } from "@/api/studyRecord";
import { Modal, Form, FormItem, InputNumber, message } from "ant-design-vue";
import { ref, unref } from "vue";
const emits = defineEmits(["success"]);

const open = ref<boolean>(false);
const formRef = ref();
const loading = ref(false);
const formState = ref<{ score: number | undefined }>({
  score: undefined,
});
const id = ref();
const detail = ref<Recordable>({});

const submit = () => {
  formRef.value
    .validate()
    .then(async () => {
      if ((formState.value?.score as any) > detail.value?.totalScore) {
        message.warning(`不能大于总分${detail.value?.totalScore}`);
        return
      }
      loading.value = true;
      changeScore({
        id: unref(id),
        checkScore: formState.value.score,
      })
        .then(() => {
          message.success("修改成功");
          emits("success");
          open.value = false;
        })
        .finally(() => {
          loading.value = false;
        });
    })
    .catch(() => undefined);
};

defineExpose({
  openModal: (e: Recordable) => {
    open.value = true;
    formState.value.score = e?.checkScore || e?.score || 0;
    id.value = e?.id;
    detail.value = e || {};
  },
});
</script>

<template>
  <Modal
    v-model:open="open"
    title="修改教师核分"
    :width="400"
    @ok="submit"
    :confirm-loading="loading"
    :after-close="
      () => {
        formRef?.resetFields();
      }
    "
  >
    <div class="h-[1px] w-full absolute bg-[#D9D9D9] left-0"></div>
    <Form layout="horizontal" class="pt-5" :model="formState" ref="formRef">
      <FormItem label="教师核分" name="score" required>
        <InputNumber
          :precision="0"
          v-model:value="formState.score"
          placeholder="请输入"
          class="w-full"
        />
      </FormItem>
    </Form>
  </Modal>
</template>
