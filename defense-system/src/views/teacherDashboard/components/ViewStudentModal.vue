<script setup lang="ts">
import { Modal, Table, Button } from "ant-design-vue";
import { ref } from "vue";

const open = ref<boolean>(false);
const columns = [
  {
    title: "序号",
    dataIndex: "index",
    key: "index",
    customRender: (e: { index: any }) => {
      return e?.index + 1;
    },
  },
  {
    title: "学生姓名",
    dataIndex: "studentName",
    key: "studentName",
  },
  {
    title: "班级",
    dataIndex: "schoolClass",
    key: "schoolClass",
  },
];

const dataSource = ref<Recordable[]>([]);

defineExpose({
  openModal: (e: Recordable[]) => {
    open.value = true;
    dataSource.value = e;
  },
});
</script>

<template>
  <Modal
    v-model:open="open"
    title="考试学生"
    :bodyStyle="{
      maxHeight: 'calc(80vh - 100px)',
      overflowY: 'auto',
      padding: '0px 10px',
    }"
  >
    <template #footer>
      <div class="flex justify-end">
        <Button type="primary" @click="open = false">关闭</Button>
      </div>
    </template>
    <div class="h-[1px] w-full absolute bg-[#D9D9D9] left-0"></div>
    <div class="pt-3">
      <Table
        :columns="columns"
        :data-source="dataSource"
        class="size-full"
        :pagination="false"
        size="small"
      >
      </Table>
    </div>
  </Modal>
</template>
