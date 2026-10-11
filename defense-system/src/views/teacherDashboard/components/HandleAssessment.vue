<template>
  <Modal
    v-model:open="open"
    :title="detail?.id ? '编辑预置面试考核' : '新增预置面试考核'"
    @ok="submit"
    :confirm-loading="loading"
    :after-close="
      () => {
        formRef?.resetFields();
      }
    "
    :width="720"
    :bodyStyle="{
      maxHeight: 'calc(80vh - 100px)',
      overflowY: 'auto',
      padding: '0px 10px',
    }"
  >
    <div class="h-[1px] w-full absolute bg-[#D9D9D9] left-0"></div>
    <Form name="prepared-assessment-settings" layout="vertical" :model="formState" class="pt-3" ref="formRef">
      <p class="mb-3" role="note">
        预置面试不启用实时答辩或追问，不设整场时限。
        每题回答时限在“准备题目”中逐题设置；保存考核设置不会发布试卷。
      </p>
      <p v-if="detail?.id" class="mb-3" role="note">
        历史兼容字段保留；它们不参与预置面试的题序、追问或时限。
        预置试卷最多 50 题，历史数量不会自动修改。
      </p>
      <FormItem
        label="考核主题"
        name="theme"
        :rules="[{ required: true, message: '该项为必填项' }]"
      >
        <Input v-model:value="formState.theme" placeholder="请输入" />
      </FormItem>
      <FormItem
        label="考核要求"
        name="assessmentRequirements"
        :rules="[{ required: true, message: '该项为必填项' }]"
      >
        <Textarea
          v-model:value="formState.assessmentRequirements"
          :rows="6"
          placeholder="请输入"
        />
      </FormItem>
      <FormItem
        name="assessmentCriteria"
        :rules="[{ required: true, message: '该项为必填项' }]"
      >
        <template #label>
          <div class="flex items-center">
            <span>考核标准</span>
            <Button type="link" @click="useTemplate">使用示例</Button>
          </div>
        </template>
        <Textarea
          v-model:value="formState.assessmentCriteria"
          :rows="8"
          placeholder="请输入"
        />
      </FormItem>
      <FormItem
        label="考核总分"
        name="totalScore"
        :rules="[{ required: true, message: '该项为必填项' }]"
      >
        <InputNumber
          class="w-full"
          :min="0"
          :precision="0"
          v-model:value="formState.totalScore"
          placeholder="请输入"
        />
      </FormItem>
      <FormItem
        label="及格分"
        name="passScore"
        :rules="[{ required: true, message: '该项为必填项' }]"
      >
        <InputNumber
          class="w-full"
          :min="0"
          :precision="0"
          v-model:value="formState.passScore"
          placeholder="请输入"
        />
      </FormItem>
      <FormItem
        label="预置题目数量"
        name="questionCount"
        :rules="[{ required: true, message: '该项为必填项' }]"
      >
        <InputNumber
          class="w-full"
          :min="1"
          :max="detail?.id ? 100 : 50"
          :precision="0"
          v-model:value="formState.questionCount"
          placeholder="请输入"
        />
      </FormItem>
      <FormItem
        label="是否显示结果"
        name="showResult"
        :rules="[{ required: true, message: '该项为必填项' }]"
      >
        <Switch
          v-model:checked="formState.showResult"
          :checked-value="true"
          :un-checked-value="false"
        />
      </FormItem>
      <FormItem
        label="考核文件"
        name="assessmentFiles"
        :rules="[]"
      >
        <UploadDragger
          name="file"
          :show-upload-list="{ showRemoveIcon: false, showPreviewIcon: false }"
          accept=".pdf"
          :action="action"
          multiple
          @change="handleChange"
          v-model:file-list="formState.assessmentFiles"
        >
          <p class="ant-upload-drag-icon">
            <inbox-outlined></inbox-outlined>
          </p>
          <p class="ant-upload-text">点击或拖拽上传</p>
          <template #itemRender="{ originNode, file, actions }">
            <div class="flex items-center space-x-3 h-12">
              <!-- <span :style="file.status === 'error' ? 'color: red' : ''">
                {{ file?.name }}
              </span> -->
              <component :is="originNode" />
              <div
                class="flex items-center space-x-2 pt-2"
                v-if="file?.status == 'done'"
              >
                <a href="javascript:;" @click="previewFn(file)">
                  <EyeOutlined class="cursor-pointer hover:text-[#1677FF]" />
                </a>
                <a href="javascript:;" @click="downFile(file)">
                  <DownloadOutlined
                    class="cursor-pointer hover:text-[#1677FF]"
                  />
                </a>
                <a href="javascript:;" @click="actions?.remove">
                  <DeleteOutlined class="hover:text-red-500" />
                </a>
              </div>
            </div>
          </template>
        </UploadDragger>
      </FormItem>

      <FormItem
        label="选择班级/学生"
        name="classAndStudent"
        :rules="[{ required: true, message: '该项为必填项' }]"
      >
        <TreeSelect
          :tree-data="schoolClassAndStudent"
          :field-names="{ children: 'students' }"
          :load-data="loadSchoolClassStudents"
          multiple
          placeholder="请选择"
          tree-node-filter-prop="label"
          treeCheckable
          treeCheckStrictly
          v-model:value="formState.classAndStudent"
        />
      </FormItem>
      <FormItem label="邀请教师" name="inviteTeachers">
        <Select
          v-model:value="formState.inviteTeachers"
          placeholder="请选择"
          mode="multiple"
          show-search
          :filter-option="false"
          @search="handleTeacherSearch"
          :options="teacherList"
          :field-names="{ label: 'name', value: 'id' }"
        />
      </FormItem>
      <FormItem
        label="是否需要预约"
        name="needAppoint"
        :rules="[{ required: true, message: '该项为必填项' }]"
      >
        <Switch
          v-model:checked="formState.needAppoint"
          :checked-value="true"
          :un-checked-value="false"
        />
      </FormItem>
      <!-- <FormItem
        label="考试参与学生"
        name="allStudent"
        :rules="[{ required: true, message: '该项为必填项' }]"
      >
        <FormItemRest>
          <RadioGroup v-model:value="formState.allStudent">
            <Radio :value="true">全部学生</Radio>
            <Radio :value="false">自定义范围</Radio>
          </RadioGroup>
          <Select
            v-if="formState.allStudent === false"
            v-model:value="formState.participatingStudents"
            class="mt-2"
            placeholder="请选择"
            mode="multiple"
            :options="studentList"
            :field-names="{ label: 'name', value: 'id' }"
          >
            <template #option="{ name, id }">
              <div class="flex items-center space-x-2">
                <Checkbox
                  :checked="formState.participatingStudents?.includes(id)"
                />
                <span>{{ name }}</span>
              </div>
            </template>
          </Select>
        </FormItemRest>
      </FormItem> -->
    </Form>
    <Preview ref="previewRef" />
  </Modal>
</template>
<script setup lang="ts">
import {
  Modal,
  Form,
  FormItem,
  Input,
  Textarea,
  Switch,
  UploadDragger,
  Select,
  InputNumber,
  message,
  UploadChangeParam,
  Button,
  Space,
  TreeSelect,
  UploadProps,
} from "ant-design-vue";
import { onBeforeUnmount, onMounted, ref, watch } from "vue";
import {
  InboxOutlined,
  EyeOutlined,
  DownloadOutlined,
  DeleteOutlined,
} from "@ant-design/icons-vue";
import { createAssessment, modifyAssessment } from "@/api/assessment";
import {
  getSchoolClassStudent,
  getSchoolClassStudentOptions,
} from "@/api/student";
import { getFileSign } from "@/api/common";
import axios from "axios";
import saveAs from "file-saver";
import Preview from "@/components/Preview/index.vue";
import { getAllTeachers } from "@/api/teacher";
import { initialPreparedAssessmentForm } from "@/prepared/assessmentForm";

const action = import.meta.env.VUE_APP_BASE_API + "/file/upload";
const emits = defineEmits(["success"]);
const open = ref<boolean>(false);
const detail = ref<Record<string, any>>({});
const formState = ref<Recordable>({});
const formRef = ref();
const loading = ref(false);
const teacherList = ref<Recordable[]>([]);
const schoolClassAndStudent = ref<Recordable[]>([]);
let teacherSearchTimer: ReturnType<typeof setTimeout> | undefined;
let teacherSearchSequence = 0;

const responseItems = (data: unknown): Recordable[] =>
  Array.isArray(data) ? data : [];

const submit = () => {
  formRef.value
    .validate()
    .then(async () => {
      loading.value = true;
      let isUploading = false;
      let isNoPdf = false;
      for (
        let index = 0;
        index < formState.value?.assessmentFiles?.length;
        index++
      ) {
        const element = formState.value?.assessmentFiles?.[index];
        if (!element?.response?.data?.url) {
          isUploading = true;
        }
        if (
          element?.response?.data?.url &&
          !element?.response?.data?.url?.includes(".pdf")
        ) {
          isNoPdf = true;
        }
      }
      if (isUploading) {
        message.error("文件正在上传，请稍等...");
        return;
      }
      if (isNoPdf) {
        message.error("存在非pdf文件");
        return;
      }
      const schoolClassList = [];
      const studentIds = [];
      if (!formState.value?.inviteTeachers?.length) {
        delete formState.value?.inviteTeachers;
      }
      for (
        let index = 0;
        index < formState.value?.classAndStudent?.length;
        index++
      ) {
        const element = formState.value?.classAndStudent?.[index];
        if (element?.value?.includes("**student**__")) {
          studentIds.push(element?.value?.split("**student**__")?.[1]);
        }
        if (element?.value?.includes("**class**__")) {
          const id = element?.value?.split("**class**__")?.[1];
          schoolClassList.push({
            schoolClass: id,
            students: [],
          });
        }
      }
      const params: Recordable = {
        ...formState.value,
        assessmentFiles: formState.value?.assessmentFiles
          ?.filter(
            (item: { response: { data: { url: any } } }) =>
              item?.response?.data?.url
          )
          ?.map(
            (item: {
              response: { data: { url: string; fileName: string } };
              size: number;
            }) => {
              return {
                fileUrl: item?.response?.data?.url,
                fileSize: item?.size,
                fileName: item?.response?.data?.fileName,
              };
            }
          ),
        studentIds,
        schoolClassList,
      };
      if (detail.value?.id) {
        await modifyAssessment({
          id: detail.value?.id,
          ...params,
        });
      } else {
        await createAssessment(params);
      }
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
  openModal: (e: Record<string, any> = {}) => {
    open.value = true;
    detail.value = e;
    formState.value = initialPreparedAssessmentForm(e);
    if (e?.participatingStudents?.length) {
      formState.value.participatingStudents = e?.participatingStudents?.map(
        (item: { id: number }) => item?.id
      );
    }
    if (e?.assessmentFiles?.length) {
      formState.value.assessmentFiles = e?.assessmentFiles?.map(
        (item: Recordable) => {
          return {
            uid: item?.id,
            name: item?.fileName,
            size: item?.fileSize,
            status: "done",
            response: {
              data: { url: item?.fileUrl, fileName: item?.fileName },
            },
            url: item?.fileUrl,
          };
        }
      );
    }

    if (e?.id) {
      hydrateAssessmentSelection();
      formState.value.inviteTeachers = e?.inviteTeachers?.map(
        (item: { id: any }) => item?.id
      );
    }
    if (teacherSearchTimer) {
      clearTimeout(teacherSearchTimer);
    }
    void loadTeacherOptions().catch(() => undefined);
  },
});

const handleChange = (info: UploadChangeParam) => {
  if (info.file.status === "error") {
    message.error(`上传失败`);
  }
};

const useTemplate = () => {
  formState.value.assessmentCriteria = `1. 劳动价值的主要内容与核心观点
1. 阐述劳动价值论的主要内容与核心观点
2. 评分标准：5-6分：准确界定“抽象劳动创造价值”“社会必要劳动时间”等概念，阐明“人的抽象劳动是价值创造的唯一源泉”是马克思劳动价值理论的核心逻辑；
3-4分：仅复述商品二因素或劳动二重性，未触及价值创造本质；
0-2分：概念混淆（如将使用价值与价值混同）

2. 分析劳动价值理论“过时论”观点的主要依据
1. 说明人工智能对劳动形态的影响，阐明劳动价值理论“过时论”观点的主要依据
2. 评分标准：5-6分：指出AI改变具体劳动形式，劳动价值理论“过时论”聚焦于技术变革对传统劳动价值理论的冲击，认为在智能化、自动化背景下，“人的抽象劳动是价值创造的唯一源泉”的核心逻辑被颠覆，马克思的劳动价值论难以充分阐释价值创造的新模式；
3-4分：仅描述技术现象或劳动形式的改变（如机器替代流水线工人，脑力劳动替代体力劳动），缺乏马克思主义政治经济学分析；
0-2分：未能将理论与实际关联，回答没有逻辑，不能阐明劳动价值理论“过时论”观点的主要依据

3. 辩证批判“过时论”
1. 辩证论证劳动价值论的“过时论”是否成立
2. 评分标准：大致答出此点得2分：指出“过时论”混淆了“价值创造”与“价值实现”或者“类人劳动”只是“赋予价值”而非创造价值；大致答出此点得3分：强调人工智能的“类人劳动”是人类劳动的延伸，人工智能与马克思当年描述的自动化机器一样都是生产工具，属于不变资本；数据、算法、算力等非物质生产要素，本质上仍然是人类具体劳动的产物。其价值创造显然离不开人类既有的物质和精神条件，也不可能在没有人类劳动的前提下自发形成；大致答出此点得3分：引用马克思主义政治经济学基本原理，说明人工智能时代的资本主义生产方式发生变化，但本质仍然是人的活劳动创造价值，资本增殖仍是依靠对活劳动的榨取，资本主义榨取剩余价值的本质没有变

4. 方法论与结论
1.结论需体现历史唯物主义方法论，辩证分析马克思劳动价值的当代价值
2.评分标准：4-5分：得出马克思主义劳动价值论未过时的结论，并辩证看待人工智能对社会历史发展的影响；2-3分：简单肯定/否定，缺乏矛盾分析；0-1分：脱离马克思主义立场，武断认为人工智能的出现否定或证伪了马克思劳动价值理论`;
};
const downFile = (e: Recordable) => {
  getFileSign({ url: e?.response?.data?.url }).then((res) => {
    axios.get(res?.data, { responseType: "blob" }).then((res2) => {
      saveAs(res2?.data, e?.response?.data?.fileName);
    });
  });
};
const previewRef = ref();
const previewFn = (e: Recordable) => {
  previewRef.value?.openModal(e?.response?.data?.url);
};

const toStudentNode = (student: Recordable) => ({
  ...student,
  value: `**student**__${student?.id}`,
  label: student?.name,
  isLeaf: true,
});

const toSchoolClassNode = (schoolClass: string) => ({
  schoolClass,
  value: `**class**__${schoolClass}`,
  label: schoolClass,
  isLeaf: false,
  students: undefined,
  studentsLoaded: false,
});

const hydrateAssessmentSelection = () => {
  if (!detail.value?.id || !schoolClassAndStudent.value.length) {
    return;
  }
  const selected: Recordable[] = [];
  const selectedSchoolClasses = Array.from(
    new Set<string>(
      detail.value?.schoolClassList
        ?.map((item: Recordable) => item?.schoolClass)
        ?.filter(Boolean) || []
    )
  );
  for (const schoolClass of selectedSchoolClasses) {
    let classNode = schoolClassAndStudent.value.find(
      (item) => item?.schoolClass === schoolClass
    );
    if (!classNode) {
      classNode = toSchoolClassNode(schoolClass as string);
      schoolClassAndStudent.value.push(classNode);
    }
    selected.push(classNode);
  }
  for (const student of detail.value?.students || []) {
    let classNode = schoolClassAndStudent.value.find(
      (item) => item?.schoolClass === student?.schoolClass
    );
    if (!classNode && student?.schoolClass) {
      classNode = toSchoolClassNode(student.schoolClass);
      schoolClassAndStudent.value.push(classNode);
    }
    if (!classNode) {
      continue;
    }
    const studentNode = toStudentNode(student);
    classNode.students = classNode.students || [];
    if (!classNode.students.some((item: Recordable) => item?.id === student?.id)) {
      classNode.students.push(studentNode);
    }
    selected.push(studentNode);
  }
  formState.value.classAndStudent = selected;
};

const loadSchoolClassStudents = async (treeNode: Recordable) => {
  const schoolClass = treeNode?.schoolClass || treeNode?.dataRef?.schoolClass;
  const classNode = schoolClassAndStudent.value.find(
    (item) => item?.schoolClass === schoolClass
  );
  if (!schoolClass || !classNode || classNode.studentsLoaded) {
    return;
  }
  const res = await getSchoolClassStudentOptions(schoolClass);
  classNode.students = responseItems(res?.data).map(toStudentNode);
  classNode.studentsLoaded = true;
};

const mergeTeacherOptions = (options: Recordable[]) => {
  const selectedIds = new Set(formState.value?.inviteTeachers || []);
  const selectedOptions = [
    ...(detail.value?.inviteTeachers || []),
    ...teacherList.value,
  ].filter((option: Recordable) => selectedIds.has(option?.id));
  const merged = new Map<number, Recordable>();
  for (const option of [...selectedOptions, ...options]) {
    if (option?.id != null) {
      merged.set(option.id, option);
    }
  }
  teacherList.value = Array.from(merged.values());
};

const loadTeacherOptions = async (keyword = "") => {
  const sequence = ++teacherSearchSequence;
  const res = await getAllTeachers(keyword);
  if (sequence === teacherSearchSequence) {
    mergeTeacherOptions(responseItems(res?.data));
  }
};

const handleTeacherSearch = (keyword: string) => {
  if (teacherSearchTimer) {
    clearTimeout(teacherSearchTimer);
  }
  teacherSearchTimer = setTimeout(() => {
    void loadTeacherOptions(keyword).catch(() => undefined);
  }, 250);
};

onMounted(() => {
  getSchoolClassStudent().then((res) => {
    schoolClassAndStudent.value = responseItems(res?.data).map(
      (item: Recordable) => toSchoolClassNode(item?.schoolClass)
    );
    hydrateAssessmentSelection();
  });
});

onBeforeUnmount(() => {
  if (teacherSearchTimer) {
    clearTimeout(teacherSearchTimer);
  }
});

watch(
  () => formState.value,
  () => {
    for (let index = 0; index < schoolClassAndStudent.value.length; index++) {
      const element = schoolClassAndStudent.value?.[index];
      const status = !!element.students?.find((item: { value: any }) =>
        formState.value.classAndStudent
          ?.map((ite: { value: any }) => ite.value)
          ?.includes(item?.value)
      );
      element.disabled = status;
      for (let index2 = 0; index2 < element?.students?.length; index2++) {
        const element2 = element?.students[index2];
        const status2 = formState.value.classAndStudent
          ?.map((ite: { value: any }) => ite.value)
          ?.includes(element?.value);
        element2.disabled = status2;
      }
    }
  },
  { deep: true, immediate: true }
);

const progress: UploadProps["progress"] = {
  strokeColor: {
    "0%": "#108ee9",
    "100%": "#87d068",
  },
  strokeWidth: 3,
  format: (percent) => `${parseFloat(percent?.toFixed(2) as string)}%`,
  class: "test",
};

</script>
<style scoped>
:deep(.ant-upload-list-item) {
  margin-top: 0px !important;
}
</style>
