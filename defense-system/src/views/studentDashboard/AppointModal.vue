<template>
  <Modal
    v-model:open="open"
    title="预约设置"
    :after-close="
      () => {
        formRef?.resetFields();
      }
    "
    :confirm-loading="loading"
    ok-text="预约"
    @ok="submit"
  >
    <div class="h-[1px] w-full absolute bg-[#D9D9D9] left-0 mt-2"></div>
    <div
      class="h-[1px] w-full absolute bg-[#D9D9D9] mb-[6px] left-0 bottom-[66px]"
    ></div>
    <Form layout="vertical" :model="formState" class="pb-3 pt-6" ref="formRef">
      <FormItem
        label="可预约时间段"
        name="timePeriod"
        :rules="[{ required: true, message: '该项为必填项' }]"
      >
        <Select
          v-model:value="formState.timePeriod"
          placeholder="请选择"
          :options="points"
          :fieldNames="{ label: 'timePeriodLabel', value: 'timePeriod' }"
        />
      </FormItem>
    </Form>
  </Modal>
</template>
<script setup lang="ts">
import { appointment } from "@/api/studentAssessment";
import { Modal, Form, FormItem, Select, message } from "ant-design-vue";
import { ref } from "vue";
import dayjs, { type Dayjs } from "dayjs";
import { isAppointmentTimeAvailable } from "./assessmentTime";

const emits = defineEmits(["success"]);

const open = ref<boolean>(false);
const formRef = ref();
const loading = ref(false);
const formState = ref<Recordable>({
  timePeriod: undefined,
});
const points = ref<Recordable[]>([]);

defineExpose({
  openModal: (e: Record<string, any> = {}, now: Dayjs = dayjs()) => {
    points.value =
      e?.canAppointmentTimes
        ?.filter((item: Recordable) => isAppointmentTimeAvailable(item, now))
        .map((item: Recordable) => ({
          ...item,
          timePeriodLabel: `${item?.timePeriod}${
            item?.location ? ` ${item?.location}` : ""
          }（剩余${item?.remainCount}个预约名额）`,
          disabled: false,
        })) || [];
    if (!points.value.length) {
      message.info("暂无可预约时间段，请联系教师开放新的预约时间");
      return;
    }
    open.value = true;
    formState.value = { timePeriod: undefined, id: e?.id };
  },
});
const submit = () => {
  formRef.value
    .validate()
    .then(async () => {
      loading.value = true;
      await appointment({
        assessmentId: formState.value?.id,
        timePeriod: formState.value.timePeriod,
      });
      message.success("预约成功");
      emits("success");
      open.value = false;
    })
    .catch(() => undefined)
    .finally(() => {
      loading.value = false;
    });
};
</script>
