<template>
  <Modal
    v-model:open="open"
    title="批量导入"
    ok-text="导入"
    @ok="submit"
    :confirm-loading="loading"
  >
    <div class="h-[1px] w-full absolute bg-[#D9D9D9] left-0"></div>
    <div class="pt-3">
      <Steps direction="vertical" :current="2">
        <Step :key="1">
          <template #icon>
            <div
              class="size-8 bg-[#1677FF] rounded-full flex items-center justify-center text-white text-xs"
            >
              1
            </div>
          </template>
          <template #title>
            <div class="space-y-4 flex flex-col pb-7">
              <span class="text-base text-[rgba(0,0,0,0.88)] font-normal">
                请先下载模板，编辑后导入
              </span>
              <Button type="primary" class="w-[88px]" ghost @click="downFn">
                下载模板
              </Button>
            </div>
          </template>
        </Step>
        <Step :key="2" class="mt-7">
          <template #icon>
            <div
              class="size-8 bg-[#1677FF] rounded-full flex items-center justify-center text-white text-xs"
            >
              2
            </div>
          </template>
          <template #description>
            <UploadDragger
              v-model:file-list="fileList"
              name="file"
              :multiple="true"
              show-upload-list
              accept=".xlsx"
              :before-upload="
                () => {
                  return false;
                }
              "
            >
              <p class="ant-upload-drag-icon">
                <inbox-outlined></inbox-outlined>
              </p>
              <p class="ant-upload-text">点击或拖拽上传</p>
            </UploadDragger>
          </template>
        </Step>
      </Steps>
    </div>
  </Modal>
</template>
<script setup lang="ts">
import {
  Modal,
  Steps,
  Step,
  Button,
  UploadDragger,
  message,
} from "ant-design-vue";
import { ref, unref } from "vue";
import { InboxOutlined } from "@ant-design/icons-vue";
import { downloadTemplate, importStudent } from "@/api/student";
import saveAs from "file-saver";
const action = import.meta.env.VUE_APP_BASE_API + "/backend/student/import";

const emits = defineEmits(["success"]);
const open = ref<boolean>(false);
const fileList = ref([]);
const loading = ref(false);

defineExpose({
  openModal: () => {
    fileList.value = [];
    open.value = true;
  },
});

const downFn = () => {
  downloadTemplate().then((response) => {
    const { data, headers } = response;
    const contentDisposition = headers["content-disposition"];

    // 解析文件名
    let organFileName = "";
    if (contentDisposition) {
      const fileNameMatch = contentDisposition.match(/filename="?(.+)"?/);
      if (fileNameMatch && fileNameMatch.length > 1) {
        try {
          organFileName = decodeURIComponent(fileNameMatch[1]);
        } catch (error) {}
      }
    }
    const blob = new Blob([data]);
    saveAs(blob, organFileName || "学生导入模板.xlsx");
  });
};

const submit = () => {
  if (!unref(fileList)?.length) {
    message.warning("请先上传文件");
    return;
  }
  loading.value = true;
  Promise.all(
    fileList.value?.map((item: Recordable) => {
      const formdata = new window.FormData();
      formdata.append("file", item?.originFileObj);
      return importStudent(formdata);
    })
  )
    .then(() => {
      emits("success");
      open.value = false;
    })
    .finally(() => {
      loading.value = false;
    });
};
</script>
