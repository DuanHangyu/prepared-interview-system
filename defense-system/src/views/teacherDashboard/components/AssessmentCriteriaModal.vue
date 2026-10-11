<script setup lang="ts">
import { Modal, Button } from "ant-design-vue";
import { ref } from "vue";

const open = ref<boolean>(false);
const text = ref("");
const title = ref("");
defineExpose({
  openModal: (t: string, e: string) => {
    open.value = true;
    text.value = t;
    title.value = e;
  },
});
</script>

<template>
  <Modal
    v-model:open="open"
    :title="title"
    :bodyStyle="{
      maxHeight: 'calc(80vh - 100px)',
      overflowY: 'auto',
      padding: 0,
    }"
  >
    <template #footer>
      <div class="flex justify-end">
        <Button type="primary" @click="open = false">关闭</Button>
      </div>
    </template>
    <div class="h-[1px] w-full absolute bg-[#D9D9D9] left-0"></div>
    <div class="h-4"></div>
    <div class="whitespace-pre-wrap" v-text="text || ''"></div>
  </Modal>
</template>
