<template>
  <Modal
    v-model:open="open"
    title="预约设置"
    :bodyStyle="{
      padding: '0px 10px',
    }"
    okText="保存"
    :confirm-loading="loading"
    @ok="submit"
    :width="700"
    :after-close="
      () => {
        formRef?.resetFields();
      }
    "
  >
    <div class="h-[1px] w-full absolute mt-2 bg-[#D9D9D9] left-0"></div>
    <div
      class="h-[1px] w-full absolute mb-2 bg-[#D9D9D9] left-0 bottom-[64px]"
    ></div>
    <div class="py-4">
      <Form layout="vertical" class="pt-3" :model="formState" ref="formRef">
        <main class="space-y-2">
          <div
            class="bg-[#F5F5F5] rounded-lg p-4 flex gap-x-3"
            v-for="item in timePeriods"
            :key="item"
          >
            <FormItem
              label="可预约时间段"
              :name="`timePeriod${item}`"
              :rules="[{ required: true, message: '该项为必填项' }]"
              class="mb-0"
            >
              <DatePicker
                v-model:value="formState[`timePeriod${item}`]"
                placeholder="请选择"
                class="w-full"
                value-format="YYYY-MM-DD HH:mm:00"
                format="YYYY-MM-DD HH:mm"
                :show-time="{}"
                :disabled-date="
                  (current) => current && current < dayjs().startOf('day')
                "
                :locale="locale"
              />
            </FormItem>
            <FormItem
              label="最大人数限制"
              :name="`participantLimit${item}`"
              :rules="[{ required: true, message: '该项为必填项' }]"
              class="mb-0"
            >
              <InputNumber
                :step="1"
                :precision="0"
                :min="0"
                v-model:value="formState[`participantLimit${item}`]"
                placeholder="请输入"
                class="w-full"
              />
            </FormItem>
            <FormItem
              label="考试地点"
              :name="`location${item}`"
              :rules="[{ required: true, message: '该项为必填项' }]"
              class="mb-0"
            >
              <Input
                v-model:value="formState[`location${item}`]"
                placeholder="请输入"
                class="w-full"
              />
            </FormItem>
            <FormItem
              label=" "
              name=""
              class="mb-0"
              v-if="timePeriods.length > 1"
            >
              <Button
                type="link"
                danger
                class="!p-0"
                @click="deleteIndex(item)"
              >
                删除
              </Button>
            </FormItem>
          </div>
        </main>
        <Button
          class="flex items-center my-4"
          type="primary"
          ghost
          @click="addIndex"
        >
          <PlusOutlined />
          <span>添加时间段</span>
        </Button>
        <FormItem
          label="启用过期未考，过期未预约惩罚"
          name="assessmentFailPunish"
          :rules="[{ required: true, message: '该项为必填项' }]"
        >
          <Switch
            v-model:checked="formState.assessmentFailPunish"
            :checked-value="true"
            :un-checked-value="false"
          />
        </FormItem>
        <FormItem
          label="重新预约规则设置"
          name="rescheduleAppointTime"
          :rules="[{ required: true, message: '该项为必填项' }]"
          v-if="formState.assessmentFailPunish"
        >
          <div
            class="flex items-center text-sm text-[rgba(0,0,0,0.88)] space-x-1"
          >
            <!-- <span>考核失败</span> -->
            <!-- <InputNumber
              :step="1"
              :precision="0"
              :min="0"
              v-model:value="formState.punishDays"
              placeholder="请输入"
              class="w-[120px]"
            /> -->
            <DatePicker
              v-model:value="formState.rescheduleAppointTime"
              placeholder="请选择"
              class=""
              value-format="YYYY-MM-DD HH:mm:00"
              format="YYYY-MM-DD HH:mm"
              show-time
              :disabled-date="
                (current) => current && current < dayjs().startOf('day')
              "
              :locale="locale"
            />
            <span>后，可重新预约</span>
          </div>
        </FormItem>
      </Form>
    </div>
  </Modal>
</template>
<script setup lang="ts">
import {
  Modal,
  Form,
  FormItem,
  Switch,
  InputNumber,
  Button,
  DatePicker,
  message,
  Input,
} from "ant-design-vue";
import { ref } from "vue";
import { PlusOutlined } from "@ant-design/icons-vue";
import { settingAppointment } from "@/api/assessment";
import dayjs from "dayjs";
import zh_CN from "ant-design-vue/es/date-picker/locale/zh_CN";

const locale = ref(zh_CN);
const emits = defineEmits(["success"]);
const open = ref<boolean>(false);
const formState = ref<Recordable>({});
const detail = ref<Recordable>({});
const timePeriods = ref<number[]>([]);
const loading = ref(false);
const formRef = ref();

const addIndex = () => {
  const maxCount = Math.max(...timePeriods.value);
  timePeriods.value.push(maxCount + 1);
};
const deleteIndex = (item: number) => {
  delete formState.value?.[`timePeriod${item}`];
  delete formState.value?.[`participantLimit${item}`];
  delete formState.value?.[`location${item}`];
  timePeriods.value = timePeriods.value?.filter((ite) => ite != item);
};

const submit = () => {
  formRef.value
    .validate()
    .then(async () => {
      loading.value = true;
      const params: Recordable = {
        assessmentId: detail.value?.id,
        timePeriods: timePeriods.value?.map((item) => {
          const p: Recordable = {
            timePeriod: formState.value?.[`timePeriod${item}`],
            participantLimit: formState.value?.[`participantLimit${item}`],
            location: formState.value?.[`location${item}`],
          };
          if (formState.value?.[`id${item}`]) {
            p.id = formState.value?.[`id${item}`];
          }
          return p;
        }),
        assessmentFailPunish: formState.value.assessmentFailPunish,
      };
      if (formState.value.assessmentFailPunish) {
        params.rescheduleAppointTime = formState.value.rescheduleAppointTime;
      }
      await settingAppointment(params);
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
  openModal: (e: Recordable) => {
    open.value = true;
    detail.value = e || {};
    formState.value = {};
    timePeriods.value = [];
    if (e.appointmentSetting?.timePeriods?.length) {
      formState.value.assessmentFailPunish =
        e.appointmentSetting?.assessmentFailPunish;
      formState.value.rescheduleAppointTime =
        e.appointmentSetting?.rescheduleAppointTime;
      for (
        let index = 0;
        index < e.appointmentSetting?.timePeriods.length;
        index++
      ) {
        const element = e.appointmentSetting?.timePeriods?.[index];
        timePeriods.value?.push(index);
        formState.value[`timePeriod${index}`] = element.timePeriod;
        formState.value[`participantLimit${index}`] = element.participantLimit;
        formState.value[`location${index}`] = element.location;
        formState.value[`id${index}`] = element.id;
      }
    } else {
      timePeriods.value = [0];
      formState.value.assessmentFailPunish = false;
    }
  },
});
</script>
