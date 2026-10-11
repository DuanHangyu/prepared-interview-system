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
        <FormItem label="教师姓名" name="name">
          <Input placeholder="请输入" v-model:value="params.name" />
        </FormItem>
        <FormItem label="教师手机号" name="phone">
          <Input placeholder="请输入" v-model:value="params.phone" />
        </FormItem>
        <FormItem label="账号" name="account">
          <Input placeholder="请输入" v-model:value="params.account" />
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
          <h2 class="management-toolbar-title">教师账号</h2>
          <p class="management-toolbar-desc">管理教师登录账号、联系方式和创建记录</p>
        </div>
        <div class="management-toolbar-right">
          <Button type="primary" class="w-[100px]" @click="addTeacher">
            <PlusOutlined />
            新增
          </Button>
        </div>
      </div>
      <div class="management-table tableContainer" ref="tableContainer">
        <Table
          :columns="columns"
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
                <Button type="primary" @click="() => updateTeacher(record)">
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
    <HandleModal ref="handleRef" @success="onLoad" />
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
import HandleModal from "./components/HandleModal.vue";
import {
  ExclamationCircleOutlined,
  PlusOutlined,
  SearchOutlined,
} from "@ant-design/icons-vue";
import { getTeacherPageList, deleteTeacher } from "@/api/teacher";

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
    title: "教师姓名",
    dataIndex: "name",
    key: "name",
  },
  {
    title: "账号",
    dataIndex: "account",
    key: "account",
  },
  {
    title: "教师手机号",
    dataIndex: "phone",
    key: "phone",
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
const handleRef = ref();
const formRef = ref();
const params = ref({ page: 1, size: 20, name: "", phone: "", account: "" });
const total = ref(0);
const dataSource = ref<Record<string, any>[]>([]);

const searchFn = () => {
  params.value.page = 1;
  onLoad();
};
const resetForm = () => {
  formRef.value.resetFields();
  params.value.page = 1;
  onLoad();
};

const addTeacher = () => {
  handleRef.value?.openModal();
};
const updateTeacher = (e: Recordable) => {
  handleRef.value?.openModal(e);
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
      await deleteTeacher({
        id: e?.id,
      });
      message.success("已删除");
      onLoad();
    },
  });
};

const onLoad = () => {
  loading.value = true;
  getTeacherPageList(params.value)
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
