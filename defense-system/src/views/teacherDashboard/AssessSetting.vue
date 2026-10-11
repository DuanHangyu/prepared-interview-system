<template>
  <div class="management-view">
    <section class="management-filter">
      <Form
        layout="inline"
        ref="formRef"
        class="flex gap-3"
        :model="params"
        :label-col="{ style: { width: 100, flexShrink: 0 } }"
      >
        <FormItem label="考核主题" name="theme">
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
          <h2 class="management-toolbar-title">考核设置</h2>
          <p class="management-toolbar-desc">配置预置面试基础信息、题目数量、学生分配与预约安排</p>
        </div>
        <div class="management-toolbar-right">
          <Button type="primary" class="w-[100px]" @click="addConfig">
            <PlusOutlined />
            新增
          </Button>
        </div>
      </div>
      <div
        class="settings-list"
        v-loading="loading"
        :class="
          dataSource.length == 0 && isResult
            ? 'flex flex-col items-center justify-center'
            : ''
        "
      >
        <template v-if="dataSource.length">
          <article
            v-for="item in dataSource"
            :key="item?.id"
            class="setting-card"
          >
            <div class="setting-card-header">
              <div class="setting-card-title">
                <span>{{ item?.theme }}</span>
              </div>
              <div class="setting-actions">
                <Button type="primary" ghost @click="$router.push(`/prepared-paper/${item.id}`)">
                  准备题目
                </Button>
                <Button type="primary" ghost @click="$router.push(`/prepared-review/${item.id}`)">
                  预置面试评阅
                </Button>
                <Button type="primary" @click="() => updateConfig(item)">
                  <EditOutlined />
                  修改
                </Button>
                <Button
                  type="primary"
                  ghost
                  @click="() => openSubscribe(item)"
                  v-if="item?.needAppoint"
                >
                  <CalendarOutlined />
                  预约设置
                </Button>
                <Button type="primary" danger @click="() => delFn(item)">
                  <DeleteOutlined />
                  删除
                </Button>
              </div>
            </div>
            <div class="setting-grid">
              <section class="setting-field">
                <div class="setting-label">考核总分</div>
                <div class="setting-value">{{ item?.totalScore }}</div>
              </section>
              <section class="setting-field">
                <div class="setting-label">及格分</div>
                <div class="setting-value">{{ item?.passScore }}</div>
              </section>
              <section class="setting-field">
                <div class="setting-label">预置题目数量</div>
                <div class="setting-value">{{ item?.questionCount ?? "未设置" }}</div>
              </section>
              <section class="setting-field">
                <div class="setting-label">回答时限</div>
                <div class="setting-value">逐题设置（准备题目）</div>
              </section>
              <section class="setting-field">
                <div class="setting-label">预置面试追问</div>
                <div class="setting-value">不启用</div>
              </section>
              <section class="setting-field" v-if="item?.defense || item?.followUp">
                <div class="setting-label">历史兼容设置</div>
                <div class="setting-value">保留旧字段，不作为预置面试规则</div>
              </section>
              <section class="setting-field">
                <div class="setting-label">是否显示结果</div>
                <div class="setting-value">{{ item?.showResult ? "是" : "否" }}</div>
              </section>
              <section class="setting-field" v-if="item?.assessmentRequirements">
                <div class="setting-label">考核要求</div>
                <div class="setting-value">
                  <button
                    class="setting-link"
                    type="button"
                    @click="() => openView(item?.assessmentRequirements, '考核要求')"
                  >
                    查看全部
                  </button>
                </div>
              </section>
              <section class="setting-field" v-if="item?.assessmentCriteria">
                <div class="setting-label">考核标准</div>
                <div class="setting-value">
                  <button
                    class="setting-link"
                    type="button"
                    @click="() => openView(item?.assessmentCriteria, '考核标准')"
                  >
                    查看全部
                  </button>
                </div>
              </section>
              <section class="setting-field">
                <div class="setting-label">考试参与学生</div>
                <div class="setting-value">
                  <button
                    class="setting-link"
                    type="button"
                    @click="() => viewOpenStudent(item)"
                  >
                    查看学生
                  </button>
                </div>
              </section>
              <section class="setting-field" v-if="item?.needAppoint">
                <div class="setting-label">预约设置</div>
                <div class="setting-value">
                  <span>是</span>
                  <button
                    class="setting-link setting-link-inline"
                    type="button"
                    @click="openSubscribeDetail(item)"
                  >
                    预约详情
                  </button>
                </div>
              </section>
              </div>
          </article>
        </template>
        <Empty v-if="!dataSource.length && isResult" />
      </div>
      <div class="setting-pagination">
        <Pagination
          show-quick-jumper
          :total="total"
          :show-total="(t) => `共 ${t} 条`"
          v-model:current="params.page"
          v-model:page-size="params.size"
        />
      </div>
    </section>
    <AssessmentCriteriaModal ref="assessmentCriteria" />
    <HandleAssessment ref="handleAssessmentRef" @success="onLoad" />
    <ViewStudentModal ref="viewRef" />
    <SubscribeSettingModal ref="subscribeRef" @success="onLoad" />
    <SubscribeDetailModal ref="subscribeDetailRef" />
  </div>
</template>
<script lang="ts" setup>
import {
  Form,
  Pagination,
  FormItem,
  Input,
  Button,
  Modal,
  message,
  Empty,
} from "ant-design-vue";
import { createVNode, onMounted, ref, watch } from "vue";
import {
  CalendarOutlined,
  DeleteOutlined,
  EditOutlined,
  ExclamationCircleOutlined,
  PlusOutlined,
  SearchOutlined,
} from "@ant-design/icons-vue";
import AssessmentCriteriaModal from "./components/AssessmentCriteriaModal.vue";
import HandleAssessment from "./components/HandleAssessment.vue";
import ViewStudentModal from "./components/ViewStudentModal.vue";
import { getAssessmentList, removeAssessment } from "@/api/assessment";
import SubscribeSettingModal from "./components/SubscribeSettingModal.vue";
import SubscribeDetailModal from "./components/SubscribeDetailModal.vue";

defineOptions({
  name: "AssessSetting",
});
const assessmentCriteria = ref();
const viewRef = ref();
const handleAssessmentRef = ref();
const formRef = ref();
const total = ref(0);
const params = ref({ page: 1, size: 20, theme: "" });
const loading = ref(false);
const dataSource = ref<Recordable[]>([]);
const isResult = ref(false);
const subscribeRef = ref();
const subscribeDetailRef = ref();

const searchFn = () => {
  params.value.page = 1;
  onLoad();
};
const resetForm = () => {
  formRef.value.resetFields();
  params.value.page = 1;
  onLoad();
};

const addConfig = () => {
  handleAssessmentRef.value?.openModal();
};
const updateConfig = (e: Recordable) => {
  handleAssessmentRef.value?.openModal(e);
};
const delFn = (e: Recordable) => {
  Modal.confirm({
    title: "确认删除吗？",
    icon: createVNode(ExclamationCircleOutlined),
    content: "此操作将永久删除，是否继续？",
    okText: "确认删除",
    okButtonProps: {
      type: "primary",
      danger: true,
    },
    onOk: async () => {
      await removeAssessment({
        id: e?.id,
      });
      message.success("已删除");
      onLoad();
    },
  });
};

const openView = (t: string, title: string) => {
  assessmentCriteria.value?.openModal(t, title);
};
const viewOpenStudent = (item: Recordable) => {
  viewRef.value?.openModal(item?.participatingStudents || []);
};

const onLoad = () => {
  loading.value = true;
  isResult.value = false;
  getAssessmentList(params.value)
    .then((res) => {
      dataSource.value = res?.data?.records || [];
      total.value = res?.data?.total || 0;
    })
    .finally(() => {
      loading.value = false;
      isResult.value = true;
    });
};
watch(
  () => [params.value.page, params.value?.size],
  () => {
    onLoad();
  }
);

onMounted(() => {
  onLoad();
});

const openSubscribe = (record: Recordable) => {
  subscribeRef.value?.openModal(record);
};
const openSubscribeDetail = (record: Recordable) => {
  subscribeDetailRef.value?.openModal(record);
};
</script>
<style scoped>
:deep(.ant-form-item-row) {
  display: flex;
  align-items: center;
  flex-wrap: nowrap;
}
.setting-link-inline {
  margin-left: 12px;
}
</style>
