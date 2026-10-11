<template>
  <div class="management-view">
    <section class="management-filter">
      <Form
        layout="inline"
        ref="formRef"
        class="grid grid-cols-4 gap-3"
        :model="params"
        :label-col="{ style: { width: 100, flexShrink: 0 } }"
      >
        <FormItem label="学生姓名" name="name">
          <Input placeholder="请输入" v-model:value="params.name" />
        </FormItem>
        <FormItem label="学号" name="account">
          <Input placeholder="请输入" v-model:value="params.account" />
        </FormItem>
        <FormItem label="班级" name="schoolClass">
          <Input placeholder="请输入" v-model:value="params.schoolClass" />
        </FormItem>
        <FormItem>
          <Button class="w-[100px] mr-2" type="primary" @click="searchFn">
            <SearchOutlined />
            查询
          </Button>
          <Button class="w-[100px]" @click="resetForm">重置</Button>
        </FormItem>
      </Form>
    </section>
    <section class="management-card">
      <div class="management-toolbar">
        <div>
          <h2 class="management-toolbar-title">学生信息</h2>
          <p class="management-toolbar-desc">维护学生账号、班级与批量导入导出</p>
        </div>
        <div class="management-toolbar-right">
          <Button type="primary" class="w-[100px]" @click="addStudent">
            <PlusOutlined />
            新增
          </Button>
          <Button type="primary" ghost class="w-[112px]" @click="batchImport">
            <UploadOutlined />
            批量导入
          </Button>
          <Button type="primary" ghost class="w-[100px]" @click="exportFn">
            <DownloadOutlined />
            导出
          </Button>
        </div>
      </div>
      <div class="management-table tableContainer" ref="tableContainer">
        <Table
          :columns="columns"
          :row-selection="{
            selectedRowKeys: selectedRowKeys,
            onChange: onSelectChange,
          }"
          :data-source="dataSource"
          class="size-full"
          :pagination="{
            total: total,
            current: params.page,
            pageSize: params.size,
            pageSizeOptions: [20, 50, 100],
            showQuickJumper: true,
            showTotal: (t) => `共 ${t} 条`,
          }"
          :scroll="{
            x: 'max-content',
            y: tableHeight,
          }"
          :loading="loading"
          row-key="id"
          @change="changeFn"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'action'">
              <div class="action-cell">
                <Button type="primary" @click="() => updateStudent(record)">
                  修改
                </Button>
                <Button type="primary" danger @click="() => delFn(record)">
                  删除
                </Button>
              </div>
            </template>
          </template>
        </Table>
      </div>
    </section>
    <HandleStudentModal ref="handleStudent" @success="onLoad" />
    <BatchImportModal ref="batchImportRef" @success="onLoad" />
  </div>
</template>
<script lang="ts" setup>
import {
  Form,
  Table,
  FormItem,
  Input,
  Button,
  Modal,
  message,
  TablePaginationConfig,
} from "ant-design-vue";
import { createVNode, nextTick, onMounted, onUnmounted, ref, watch } from "vue";
import HandleStudentModal from "./components/HandleStudentModal.vue";
import BatchImportModal from "./components/BatchImportModal.vue";
import {
  DownloadOutlined,
  ExclamationCircleOutlined,
  PlusOutlined,
  SearchOutlined,
  UploadOutlined,
} from "@ant-design/icons-vue";
import {
  deleteStudent,
  exportStudent,
  getStudentPageList,
} from "@/api/student";
import { saveAs } from "file-saver";

const columns = [
  {
    title: "序号",
    dataIndex: "index",
    key: "index",
    customRender: (e: { index: any }) => {
      return (params.value.page - 1) * params.value.size + e.index + 1;
    },
    width: 80,
  },
  {
    title: "学生姓名",
    dataIndex: "name",
    key: "name",
  },
  {
    title: "学号",
    dataIndex: "account",
    key: "account",
  },
  // {
  //   title: "登录密码",
  //   key: "password",
  //   dataIndex: "password",
  // },
  {
    title: "班级",
    dataIndex: "schoolClass",
    key: "schoolClass",
  },
  {
    title: "创建时间",
    key: "createTime",
    dataIndex: "createTime",
  },
  {
    title: "操作",
    key: "action",
    width: 200,
  },
];

defineOptions({
  name: "StudentManage",
});
const loading = ref(false);
const handleStudent = ref();
const batchImportRef = ref();
const formRef = ref();
const params = ref({
  page: 1,
  size: 20,
  name: "",
  schoolClass: "",
  account: "",
});
const total = ref(0);
const dataSource = ref<Record<string, any>[]>([]);
const selectedRowKeys = ref<Array<string | number>>([]);
const onSelectChange = (keys: Array<string | number>) => {
  selectedRowKeys.value = keys;
};

const searchFn = () => {
  params.value.page = 1;
  onLoad();
};
const resetForm = () => {
  formRef.value.resetFields();
  params.value.page = 1;
  onLoad();
};

const addStudent = () => {
  handleStudent.value?.openModal();
};
const updateStudent = (e: Recordable) => {
  handleStudent.value?.openModal(e);
};
const delFn = (e: Recordable) => {
  Modal.confirm({
    title: "确认删除吗？",
    icon: createVNode(ExclamationCircleOutlined),
    content: "此操作将永久删除用户，删除后该用户无法登录系统，是否继续？",
    okText: "确认删除",
    okButtonProps: {
      type: "primary",
      danger: true,
    },
    onOk: async () => {
      await deleteStudent({
        id: e?.id,
      });
      message.success("已删除");
      onLoad();
    },
  });
};

const onLoad = () => {
  loading.value = true;
  getStudentPageList(params.value)
    .then((res) => {
      dataSource.value = res?.data?.records || [];
      total.value = res?.data?.total || 0;
    })
    .finally(() => {
      loading.value = false;
    });
};
const tableContainer = ref();
const tableHeight = ref(0);

const listenResize = () => {
  tableHeight.value = tableContainer.value?.offsetHeight - 55 - 64;
};

onMounted(() => {
  onLoad();
  nextTick(() => {
    listenResize();
  });
  window.addEventListener("resize", listenResize);
});

onUnmounted(() => {
  window.removeEventListener("resize", listenResize);
});

const exportFn = () => {
  if (!selectedRowKeys.value?.length) {
    message.warning("请先选择数据");
    return;
  }
  exportStudent({
    ids: selectedRowKeys.value,
  }).then((response) => {
    const { data, headers } = response;
    const contentDisposition = headers["content-disposition"];
    let organFileName = "";
    try {
      if (contentDisposition) {
        const fileNameMatch = contentDisposition.match(
          /filename\*=UTF-8''([^;]+)/i
        );
        if (fileNameMatch && fileNameMatch.length > 1) {
          organFileName = decodeURIComponent(fileNameMatch[1]);
        }
      }
    } catch (error) {}
    const blob = new Blob([data]);
    saveAs(blob, organFileName || "student.xlsx");
    selectedRowKeys.value = [];
  });
};

const batchImport = () => {
  batchImportRef.value?.openModal();
};

const changeFn = (e: TablePaginationConfig) => {
  params.value.page = e?.current || 1;
  params.value.size = e?.pageSize || 20;
  onLoad();
};
</script>
<style scoped>
.tableContainer {
  min-height: 0;
}
:deep(.ant-form-item-row) {
  display: flex;
  align-items: center;
  flex-wrap: nowrap;
}
</style>
