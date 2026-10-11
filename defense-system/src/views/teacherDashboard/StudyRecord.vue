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
        <FormItem label="学生姓名" name="studentName">
          <Input placeholder="请输入" v-model:value="params.studentName" />
        </FormItem>
        <FormItem label="考核名称" name="theme">
          <Input placeholder="请输入" v-model:value="params.theme" />
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
          <h2 class="management-toolbar-title">考核记录</h2>
          <p class="management-toolbar-desc">预置面试进入专用评阅页；旧版记录保留核分和报告导出</p>
        </div>
        <div class="management-toolbar-right">
          <Button type="primary" class="w-fit" @click="exportFn">
            <DownloadOutlined />
            导出学生报告
          </Button>
        </div>
      </div>
      <div class="management-table" ref="tableContainer">
        <Table
          :columns="columns"
          :row-selection="{
            selectedRowKeys: selectedRowKeys,
            onChange: onSelectChange,
            getCheckboxProps: (record) => ({ disabled: record.prepared }),
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
          row-key="id"
          @change="changeFn"
          :loading="loading"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'score'">
              <span v-if="record.prepared">在预置评阅页查看</span>
              <div v-else class="score-orb">
                <span class="score-orb-value">
                  {{ record?.score ?? 0 }}
                </span>
                <span class="score-orb-unit">分</span>
              </div>
            </template>
            <template v-if="column.key === 'checkScore'">
              <span v-if="record.prepared">在预置评阅页查看</span>
              <div v-else class="flex items-center space-x-[10px]">
                <span>{{ record?.checkScore ?? record?.score ?? 0 }}分</span>
                <EditOutlined
                  class="cursor-pointer score-edit"
                  @click="() => updateScore(record)"
                />
              </div>
            </template>
            <template v-if="column.key === 'recordStatus'">
              <span>{{ record.prepared ? preparedStatus(record.preparedState) : '旧版已完成' }}</span>
            </template>
            <template v-if="column.key === 'action'">
              <div class="action-cell">
                <Button v-if="record.prepared" type="primary" @click="goPreparedReview(record)">
                  预置面试评阅
                </Button>
                <Button v-else type="primary" @click="goDetail(record)">
                  查看详情
                </Button>
                <Button
                  type="primary"
                  danger
                  @click="retakeFn(record)"
                  v-if="!record.prepared && record?.state != 3"
                >
                  重考
                </Button>
              </div>
            </template>
          </template>
        </Table>
      </div>
    </section>
    <UpdateTeacherScoreModal ref="updateTeacherScore" @success="onLoad" />
  </div>
</template>
<script lang="ts" setup>
import {
  Form,
  Table,
  FormItem,
  Input,
  Button,
  TablePaginationConfig,
  message,
  Modal,
} from "ant-design-vue";
import { createVNode, nextTick, onMounted, onUnmounted, ref } from "vue";
import {
  DownloadOutlined,
  EditOutlined,
  ExclamationCircleOutlined,
  SearchOutlined,
} from "@ant-design/icons-vue";
import UpdateTeacherScoreModal from "./components/UpdateTeacherScoreModal.vue";
import { useRouter } from "vue-router";
import {
  exportStudentRecord,
  getStudyRecordList,
  retakeApi,
} from "@/api/studyRecord";
import dayjs from "dayjs";
import saveAs from "file-saver";
defineOptions({
  name: "StudyRecord",
});

const loading = ref(false);
const router = useRouter();
const formRef = ref();
const updateTeacherScore = ref();
const params = ref({
  page: 1,
  size: 20,
  studentName: "",
  theme: "",
});
const total = ref(0);

type RecordRow = {
  id: number; assessmentId?: number; prepared?: boolean; preparedState?: string;
  score?: number | null; checkScore?: number | null; state?: number;
  [key: string]: unknown;
};
const preparedStatus = (state?: string) => ({
  IN_PROGRESS: '面试进行中', UPLOAD_PENDING: '待补传原录音', SUBMITTED: '待评阅',
  REQUIRES_REVIEW: '需人工核验', CANCELLED: '已取消',
}[state || ''] || '状态待核验');
const failureMessage = (error: unknown, fallback: string) => error instanceof Error ? error.message : fallback;

const formatAssessmentTime = (
  value?: string | number | Date | null
): string => {
  if (!value) return "--";
  const assessmentTime = dayjs(value);
  return assessmentTime.isValid()
    ? assessmentTime.format("YYYY/MM/DD HH:mm")
    : "--";
};

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
    dataIndex: "studentName",
    key: "studentName",
    width: 140,
  },
  {
    title: "考核名称",
    dataIndex: "theme",
    key: "theme",
    width: 200,
    ellipsis: true,
  },
  {
    title: "考核总分",
    dataIndex: "totalScore",
    key: "totalScore",
    width: 100,
  },
  {
    title: "考核得分",
    dataIndex: "score",
    key: "score",
    width: 150,
    customRender: (e: { value: any }) => {
      return e?.value == -1 ? "--" : e?.value;
    },
  },
  {
    title: "教师核分",
    key: "checkScore",
    dataIndex: "checkScore",
    width: 150,
  },
  {
    title: "考核时间",
    key: "assessmentTime",
    dataIndex: "assessmentTime",
    width: 160,
    customRender: (e: { value?: string | number | Date | null }) => {
      return formatAssessmentTime(e?.value);
    },
  },
  {
    title: "记录状态",
    key: "recordStatus",
    width: 140,
  },
  {
    title: "操作",
    key: "action",
    width: 200,
    fixed: "right" as const,
  },
];

const dataSource = ref<RecordRow[]>([]);
const tableContainer = ref();
const tableHeight = ref(0);
const listenResize = () => {
  tableHeight.value = tableContainer.value?.offsetHeight - 55 - 64;
};

let loadEpoch = 0;
const onLoad = async () => {
  const epoch = ++loadEpoch;
  loading.value = true;
  selectedRowKeys.value = [];
  dataSource.value = [];
  total.value = 0;
  try {
      const res = await getStudyRecordList({ ...params.value });
      if (epoch !== loadEpoch) return;
      dataSource.value = res?.data?.records || [];
      total.value = res?.data?.total || 0;
  } catch (error) {
      if (epoch === loadEpoch) message.error(failureMessage(error, '考核记录读取失败，请重试'));
  } finally {
      if (epoch === loadEpoch) loading.value = false;
  }
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

const changeFn = (e: TablePaginationConfig) => {
  params.value.page = e?.current || 1;
  params.value.size = e?.pageSize || 20;
  onLoad();
};

onMounted(() => {
  onLoad();
  nextTick(() => {
    listenResize();
  });
  window.addEventListener("resize", listenResize);
});

onUnmounted(() => {
  loadEpoch++;
  window.removeEventListener("resize", listenResize);
});

const updateScore = (e: Record<string, any>) => {
  if (e.prepared) { message.warning('请使用预置面试评阅，不使用旧版核分'); return; }
  updateTeacherScore.value?.openModal(e || {});
};

const goDetail = (e: Record<string, any>) => {
  if (e.prepared) { goPreparedReview(e); return; }
  router.push(`/studyDetail?id=${e?.id}`);
};
const goPreparedReview = (e: Record<string, any>) => {
  if (!Number.isSafeInteger(e.assessmentId) || Number(e.assessmentId) < 1) {
    message.warning('原考核归属无法核验，请刷新记录后重试'); return;
  }
  router.push(`/prepared-review/${e.assessmentId}`);
};

const selectedRowKeys = ref<Array<string | number>>([]);
const onSelectChange = (keys: Array<string | number>) => {
  selectedRowKeys.value = keys.filter(key => dataSource.value.some(row => String(row.id) === String(key) && !row.prepared));
};

const exportFn = async () => {
  if (!selectedRowKeys.value?.length) {
    message.warning("请先选择数据");
    return;
  }
  if (selectedRowKeys.value.some(key => !dataSource.value.some(row => String(row.id) === String(key) && !row.prepared))) {
    selectedRowKeys.value = []; message.warning('请选择当前页面的旧版记录；预置面试不能使用旧报告'); return;
  }
  try {
  const response = await exportStudentRecord({
    recordIds: selectedRowKeys.value,
  });
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
    saveAs(blob, organFileName || "考核记录报告.xlsx");
    selectedRowKeys.value = [];
  } catch (error) { message.error(failureMessage(error, '报告导出失败，请重试')); }
};

const retakeFn = (e: Record<string, any>) => {
  if (e.prepared) { message.warning('预置面试只允许故障恢复，旧版重考不可用'); return; }
  Modal.confirm({
    title: "确认重考吗？",
    icon: createVNode(ExclamationCircleOutlined),
    okText: "重考",
    okButtonProps: {
      type: "primary",
    },
    onOk: async () => {
      try {
      await retakeApi({
        recordId: e?.id,
      });
      message.success("已修改");
      await onLoad();
      } catch (error) { message.error(failureMessage(error, '旧版重考操作失败，请刷新后重试')); }
    },
  });
};
</script>
<style scoped>
:deep(.ant-form-item-row) {
  display: flex;
  align-items: center;
  flex-wrap: nowrap;
}
</style>
