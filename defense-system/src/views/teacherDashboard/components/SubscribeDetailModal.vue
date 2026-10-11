<template>
  <Modal
    v-model:open="open"
    title="预约详情"
    :bodyStyle="{
      maxHeight: 'calc(80vh - 100px)',
      overflowY: 'auto',
      padding: '0px 10px',
    }"
    :width="700"
  >
    <template #footer>
      <div class="flex justify-end">
        <Button type="primary" @click="open = false">关闭</Button>
      </div>
    </template>
    <div class="h-[1px] w-full absolute bg-[#D9D9D9] left-0"></div>
    <Tabs v-model:activeKey="activeKey" class="w-full">
      <TabPane
        key="1"
        :tab="`已预约(${info?.alreadyAppointCount || 0})`"
      ></TabPane>
      <TabPane key="2" :tab="`未预约(${info?.notAppointCount || 0})`"></TabPane>
    </Tabs>
    <div v-if="activeKey == '1'">
      <div class="flex items-center space-x-2 mb-4">
        <span>预约时间段</span>
        <Select
          v-model:value="current"
          :options="appointment"
          placeholder="请选择"
          class="min-w-[300px]"
          @change="changeTimePeriod"
        />
      </div>

      <div
        class="h-8 bg-[#F5F5F5] rounded-lg p-[2px] flex mb-4 w-fit"
        v-if="isStartExam"
      >
        <div class="tab" :class="tab == 1 ? 'activeTab' : ''" @click="tab = 1">
          已考核({{
            appointment?.[current]?.alreadyAssessStudents?.length || 0
          }})
        </div>
        <div class="tab" :class="tab == 2 ? 'activeTab' : ''" @click="tab = 2">
          预约未考({{ appointment?.[current]?.expiredStudents?.length || 0 }})
        </div>
      </div>
    </div>
    <Table
      :columns="columns"
      :data-source="
        activeKey == '2'
          ? info?.notAppointStudents
          : isStartExam
          ? tab == 2
            ? appointment?.[current]?.expiredStudents || []
            : appointment?.[current]?.alreadyAssessStudents || []
          : appointment?.[current]?.appointmentStudents || []
      "
      class="size-full"
      :pagination="{
        pageSizeOptions: [20, 50, 100],
        showQuickJumper: true,
        showTotal: (t) => `共 ${t} 条`,
      }"
      size="small"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'punishState'">
          {{
            record?.punishState == 1
              ? "已解除惩罚"
              : record?.punishState == 0
              ? "处罚中"
              : "-"
          }}
        </template>
        <template v-if="column.key === 'action'">
          <Button
            v-if="record?.punishState == 0"
            type="link"
            @click="() => punishmentFn(record)"
          >
            解除惩罚
          </Button>
        </template>
        <template v-if="column.key === 'action2'">
          <Button type="link" @click="() => cancelSubscribe(record)">
            取消预约
          </Button>
        </template>
      </template>
    </Table>
  </Modal>
</template>
<script setup lang="ts">
import {
  cancelStudentAppointment,
  getSettingAppointment,
  liftPunish,
} from "@/api/assessment";
import { ExclamationCircleOutlined } from "@ant-design/icons-vue";
import {
  Modal,
  Table,
  Button,
  Select,
  message,
  Tabs,
  TabPane,
} from "ant-design-vue";
import dayjs from "dayjs";
import { computed, createVNode, ref } from "vue";

const activeKey = ref("1");
const open = ref<boolean>(false);
const columns = computed(() => {
  return [
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
    ...(activeKey.value == "1" && tab.value == 2 && isStartExam
      ? [
          {
            title: "状态",
            dataIndex: "punishState",
            key: "punishState",
          },
          {
            title: "操作",
            key: "action",
            width: 100,
          },
        ]
      : []),
    ...(activeKey.value == "1" && !isStartExam.value
      ? [
          {
            title: "操作",
            key: "action2",
            width: 100,
          },
        ]
      : []),
  ];
});

const detail = ref<Recordable>({});
const current = ref<number>(0);
const appointment = ref<Recordable[]>([]);
const isStartExam = ref(false);
const tab = ref(1);
const info = ref<Recordable>({});

const changeTimePeriod = () => {
  const timePeriod = appointment.value[current.value]?.timePeriod;
  const startExam_timePeriod = dayjs(timePeriod).add(10, "minute");
  isStartExam.value = dayjs().isAfter(startExam_timePeriod);
};

const onLoad = async () => {
  const res = await getSettingAppointment(detail.value?.id);
  info.value = res?.data || {};
  appointment.value =
    res?.data?.timePeriods?.map((item: Recordable, index: number) => {
      item.value = index;
      item.label =
        dayjs(item.timePeriod).format("YYYY年MM月DD日 HH:mm") +
        (item?.location ? ` ${item?.location}` : "");
      return item;
    }) || [];
};

const punishmentFn = (e: Recordable) => {
  Modal.confirm({
    title: "确认解除惩罚吗？",
    icon: createVNode(ExclamationCircleOutlined, { class: "!text-blue-500" }),
    okText: "解除惩罚",
    okButtonProps: {
      type: "primary",
    },
    onOk: async () => {
      await liftPunish({
        id: e?.id,
      });
      message.success("已解除惩罚");
      onLoad();
    },
  });
};

const cancelSubscribe = (e: Recordable) => {
  Modal.confirm({
    title: "确认解除预约吗？",
    icon: createVNode(ExclamationCircleOutlined, { class: "!text-[#1677FF]" }),
    okText: "解除预约",
    onOk: async () => {
      await cancelStudentAppointment(e?.id);
      message.success("已解除预约");
      onLoad();
    },
  });
};

defineExpose({
  openModal: async (e: Recordable) => {
    open.value = true;
    detail.value = e;
    activeKey.value = "1";
    tab.value = 1;
    await onLoad();
    if (appointment.value?.length) {
      current.value = 0;
      changeTimePeriod();
    } else {
      current.value = undefined as any;
    }
  },
});
</script>
<style scoped>
.tab {
  padding: 0px 12px;
  height: 100%;
  display: flex;
  align-items: center;
  font-weight: 400;
  font-size: 14px;
  color: rgba(0, 0, 0, 0.88);
  cursor: pointer;
}
.activeTab {
  font-weight: 600;
  font-size: 14px;
  color: #1677ff;
  background: white;
  border-radius: 6px;
}
</style>
