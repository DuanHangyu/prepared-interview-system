<template>
  <div
    class="p-5 space-y-4 h-full overflow-y-auto custom-scrollbar"
    :class="info.colorClass"
  >
    <div class="text-xl text-[#363F50] font-semibold">
      第{{ numberToChinese(info.questionIndex as number) }}题
    </div>
    <div class="text-xl text-[#363F50] font-semibold">
      {{ info?.question }}
    </div>
    <section class="p-4 bg-[rgba(255,255,255,0.8)] rounded-xl">
      <div class="flex items-center space-x-[10px]">
        <img src="@/assets/studentDetail/hdIcon.png" class="w-8 h-8" alt="" />
        <span class="text-xl text-[#363F50] font-semibold">学生回答</span>
      </div>
      <div
        class="pl-[42px] mt-2 text-sm text-[rgba(0,0,0,0.7)] font-normal leading-[22px]"
      >
        {{ info?.answer || "-" }}
      </div>
      <div class="pl-[42px] mt-2">
        <AudioPlayer
          ref="audioPlayer"
          v-if="info?.answerFile"
          :url="info?.answerFile"
          @stop-player="stopPlayer"
          :key="info?.answerFile"
        />
      </div>
    </section>
    <div class="space-y-4" v-if="followQuestionAnswers.length">
      <template
        v-for="item in followQuestionAnswers"
        :key="item.followQuestion || item.followAnswerFile"
      >
        <section
          class="p-4 bg-[rgba(255,255,255,0.8)] rounded-xl"
          v-if="item?.followQuestion"
        >
          <div class="flex items-center space-x-[10px]">
            <img
              src="@/assets/studentDetail/zwIcon.png"
              class="w-8 h-8"
              alt=""
            />
            <span class="text-xl text-[#363F50] font-semibold">追问</span>
          </div>
          <div
            class="pl-[42px] mt-2 text-sm text-[rgba(0,0,0,0.7)] font-normal"
          >
            {{ item?.followQuestion || "-" }}
          </div>
        </section>
        <section
          class="p-4 bg-[rgba(255,255,255,0.8)] rounded-xl"
          v-if="item?.followQuestion || item?.followAnswer || item?.followAnswerFile"
        >
          <div class="flex items-center space-x-[10px]">
            <img
              src="@/assets/studentDetail/hdIcon.png"
              class="w-8 h-8"
              alt=""
            />
            <span class="text-xl text-[#363F50] font-semibold">
              学生追问回答
            </span>
          </div>
          <div
            class="pl-[42px] mt-2 text-sm text-[rgba(0,0,0,0.7)] font-normal leading-[22px]"
          >
            {{ item?.followAnswer || "-" }}
          </div>
          <div class="pl-[42px] mt-2">
            <AudioPlayer
              v-if="item?.followAnswerFile"
              :url="item?.followAnswerFile"
              ref="audioPlayer2"
              @stop-player="stopPlayer2"
              :key="item?.followAnswerFile"
            />
          </div>
        </section>
      </template>
    </div>
  </div>
</template>
<script lang="ts" setup>
import { computed, onUnmounted, ref } from "vue";
import AudioPlayer from "./AudioPlayer.vue";
const props = defineProps({
  info: {
    type: Object,
    default: () => ({}),
  },
});

const followQuestionAnswers = computed(() => {
  if (Array.isArray(props.info?.followQuestionAnswers)) {
    return props.info.followQuestionAnswers;
  }
  if (
    props.info?.followQuestion ||
    props.info?.followAnswer ||
    props.info?.followAnswerFile
  ) {
    return [
      {
        followQuestion: props.info?.followQuestion,
        followAnswer: props.info?.followAnswer,
        followAnswerFile: props.info?.followAnswerFile,
      },
    ];
  }
  return [];
});

const audioPlayer = ref();
const audioPlayer2 = ref();
const numberToChinese = (num: number): string => {
  // 添加边界检查，确保至少为1
  if (num < 1) num = 1;

  const chineseNumbers = [
    "零",
    "一",
    "二",
    "三",
    "四",
    "五",
    "六",
    "七",
    "八",
    "九",
  ];
  const chineseUnits = ["", "十", "百", "千", "万", "十", "百", "千", "亿"];

  if (num === 0) return chineseNumbers[0];
  if (num < 0) return `负${numberToChinese(-num)}`;

  let result = "";
  let unitPos = 0;
  let needZero = false;

  while (num > 0) {
    let section = num % 10000;
    if (needZero) {
      result = chineseNumbers[0] + result;
    }

    let sectionResult = "";
    let hasLeadingZero = false;

    for (let i = 0; i < 4 && section > 0; i++) {
      const digit = section % 10;
      if (digit > 0) {
        sectionResult = chineseNumbers[digit] + chineseUnits[i] + sectionResult;
        hasLeadingZero = false;
      } else {
        if (!hasLeadingZero && sectionResult !== "") {
          sectionResult = chineseNumbers[0] + sectionResult;
        }
        hasLeadingZero = true;
      }
      section = Math.floor(section / 10);
    }

    if (unitPos > 0) {
      sectionResult += chineseUnits[unitPos * 4] || "";
    }

    result = sectionResult + result;
    num = Math.floor(num / 10000);
    unitPos++;
    needZero = section < 1000 && section > 0;
  }

  // 处理特殊情况：一十 -> 十
  if (result.startsWith("一十") && result.length > 2) {
    result = result.substring(1);
  }

  return result;
};

const stopPlayer = () => {
  audioPlayer.value?.stopPlayer();
  for (let index = 0; index < audioPlayer2.value?.length; index++) {
    const element = audioPlayer2.value?.[index];
    element?.stopPlayer();
  }
};
const stopPlayer2 = () => {
  audioPlayer.value?.stopPlayer();
  for (let index = 0; index < audioPlayer2.value?.length; index++) {
    const element = audioPlayer2.value?.[index];
    element?.stopPlayer();
  }
};

onUnmounted(() => {
  try {
    audioPlayer.value?.stopPlayer();
    for (let index = 0; index < audioPlayer2.value?.length; index++) {
      const element = audioPlayer2.value?.[index];
      element?.stopPlayer();
    }
  } catch (error) {}
});
</script>
