<template>
  <main
    class="size-full px-4 xl:px-9 pt-6 pb-8 defense flex flex-col overflow-auto relative"
  >
    <div class="defense-header-bar w-full flex-shrink-0">
      <DefenseHeader class="w-full" />
    </div>
    <div class="flex-grow w-full flex items-center justify-center">
      <div
        class="defense-stage flex mt-4 max-h-[800px] max-w-[1450px] h-full w-full relative"
      >
        <section
          v-if="showArr?.[0] == 1"
          class="defense-file-panel w-[800px] h-full bg-[rgba(0,0,0,0.15)] rounded-[32px] mr-4 p-4 relative fade-in flex-shrink-0"
        >
          <div
            class="h-full w-full flex flex-col items-center justify-center pb-[250px]"
            v-if="!filePreviewUrl"
          >
            <img
              src="@/assets/defense/empty.png"
              class="w-[200px] h-[147px] scale-150 translate-y-[20px]"
              alt=""
            />
            <Upload
              name="file"
              accept=".pdf"
              :showUploadList="false"
              :action="action"
              @change="handleChange"
              :beforeUpload="beforeUpload"
              class="z-10"
            >
              <div
                class="w-[140px] h-[50px] bg-[rgba(255,255,255,0.15)] hover:bg-[rgba(255,255,255,0.3)] rounded-[693px] text-lg font-medium text-white flex items-center justify-center hover:scale-105 cursor-pointer z-10"
              >
                上传文件
              </div>
            </Upload>
          </div>
          <div
            class="bg-white rounded-[32px] w-full h-full overflow-hidden"
            v-else
          >
            <iframe
              :src="filePreviewUrl"
              class="size-full"
              frameborder="0"
              sandbox=""
              referrerpolicy="no-referrer"
            ></iframe>
          </div>
          <Upload
            name="file"
            accept=".pdf"
            :showUploadList="false"
            :action="action"
            @change="handleChange"
            class="mt-3 flex-shrink-0"
            :beforeUpload="beforeUpload"
            v-if="filePreviewUrl"
          >
            <div
              class="reUpload hover:scale-105 hover:bg-[rgba(255,255,255,0.3)] absolute bottom-[190px] right-[40px] flex items-center justify-center"
            >
              重新上传
            </div>
          </Upload>
        </section>
        <section
          v-show="showArr?.[1] == 2"
          class="defense-video-panel flex-grow max-h-[600px] bg-[rgba(123,123,123,0.5)] rounded-[32px] flex flex-col p-4 fade-in videoBox"
        >
          <div class="flex-grow w-full rounded-[32px] overflow-hidden relative">
            <video
              v-show="isDebating"
              ref="videoRef"
              autoplay
              muted
              playsinline
              :style="{ opacity: isDebating ? 1 : 0.5 }"
              class="size-full"
            ></video>
            <div class="wave" v-if="isDebating">
              <div class="wave-1"></div>
              <WaveComponent
                class="relative z-20"
                :loading="volumeLevel > 10 ? true : false"
              />
            </div>
          </div>
          <div
            class="defense-action mt-5 flex-shrink-0 flex space-x-10 items-center"
          >
            <div class="flex-grow">
              <Progress
                class="!mb-0"
                :showInfo="false"
                strokeColor="#fff"
                trailColor="rgba(255,255,255,0.35)"
                :percent="(countdown / detail?.duration) * 100"
                :size="10"
              />
              <div class="flex justify-between items-center mt-1">
                <span class="text-base text-white font-normal">剩余时间</span>
                <span class="text-white">
                  <span class="text-2xl font-medium">{{ countdown }}</span>
                  <span class="text-base font-normal pl-2">秒</span>
                </span>
              </div>
            </div>
            <div
              class="defense-main-action w-[140px] h-[50px] bg-[rgba(255,255,255,0.15)] hover:bg-[rgba(255,255,255,0.3)] hover:scale-105 cursor-pointer flex items-center justify-center text-lg font-medium text-white flex-shrink-0 rounded-[693px]"
              @click="
                () => {
                  if (submitLoading) return;
                  if (!isDebating) {
                    startDebateBefore();
                  } else {
                    stopDebateBefore();
                  }
                }
              "
            >
              <LoadingOutlined class="mr-1" v-if="submitLoading" />
              {{
                !isDebating && !submitLoading
                  ? "开始答辩"
                  : `结束答辩${
                      detail.duration - countdown < 4
                        ? "(" + (4 - (detail.duration - countdown)) + "秒)"
                        : ""
                    }`
              }}
            </div>
          </div>
        </section>
        <section
          v-if="showArr?.[2] == 3"
          class="defense-bottom-panel absolute bottom-[-10px] left-0 w-full bottom-card fade-in"
        >
          <div
            class="bottom-card-in py-6 px-2 text-[#FFFFFF] font-semibold text-2xl"
          >
            <div class="w-full h-full overflow-y-auto custom-scrollbar px-4">
              {{ detail?.theme }}
            </div>
          </div>
        </section>
      </div>
    </div>
    <!-- 加载遮罩层 -->
    <LoadingOverlay v-if="!isWarmupDone && !blockingError" :text="loadingText" />
    <div
      v-if="blockingError"
      class="absolute inset-0 z-50 flex items-center justify-center bg-[rgba(95,90,87,0.86)]"
    >
      <div
        class="w-[460px] max-w-[90vw] rounded-2xl bg-white/95 px-8 py-7 text-center shadow-2xl"
      >
        <div class="text-xl font-semibold text-[#1f2937] mb-3">答辩连接异常</div>
        <div class="text-base leading-7 text-[#4b5563] whitespace-pre-line">
          {{ blockingError }}
        </div>
        <button
          class="mt-6 h-11 px-8 rounded-full bg-[#e75f49] text-white text-base font-medium hover:bg-[#f07157]"
          @click="retryBlockedStep"
        >
          重试
        </button>
      </div>
    </div>
  </main>
</template>
<script setup lang="ts">
import DefenseHeader from "@/components/Layout/componets/DefenseHeader.vue";
import WaveComponent from "@/components/Wave/index.vue";
import { computed, ref, onMounted, onUnmounted, createVNode, watch } from "vue";
import { useSpeechRecognition } from "@/composables/useSpeechRecognition.js";
import { useRoute, useRouter } from "vue-router";
import { getAssessmentDetail } from "@/api/assessment";
import {
  Upload,
  Progress,
  UploadChangeParam,
  message,
  Modal,
} from "ant-design-vue";
import { resolveWebSocketUrl } from "@/utils/websocket";
import { INTERVIEW_CAMERA_CONSTRAINTS } from "@/utils/media";
import LoadingOverlay from "@/components/LoadingOverlay/newIndex.vue";
import { FileType } from "ant-design-vue/es/upload/interface.js";
import {
  ExclamationCircleOutlined,
  LoadingOutlined,
} from "@ant-design/icons-vue";
import { uploadAssessmentFile } from "@/api/studentAssessment";
import { previewFile } from "@/api/common";

const action = import.meta.env.VUE_APP_BASE_API + "/file/upload";
const showArr = ref<number[]>([]);
// 心跳间隔（毫秒）
const HEARTBEAT_INTERVAL = 30000;
let heartbeatTimer: number | null | undefined = null;
const reconnectAttempts = ref(0);
const maxReconnectAttempts = 5;
const reconnectInterval = 3000; // 3秒重试一次
let reconnectTimer: number | null | undefined = null;
const socket = ref<WebSocket | null>(null);
const isConnected = ref(false);
const connectionStatus = ref("Disconnected");
const pageOff = ref(false);

const route = useRoute();
const router = useRouter();
const uploadProgress = ref(0);
const uploadStatus = ref<
  "success" | "normal" | "active" | "exception" | undefined
>(undefined);
const videoRef = ref<any>(null);
const isDebating = ref(false);
const countdown = ref(0);
let countdownTimer: any = null;
let videoStream: any = null;
const uploadFile = ref<Recordable>({});
const filePreviewUrl = ref("");
let previewObjectUrl = "";
const isWarmupDone = ref(false);
const detail = ref<Recordable>({});
const submitLoading = ref(false);
const blockingError = ref("");
const blockedStep = ref<"setup" | "defense">("setup");
const loadingText = ref("正在加载中，请稍后...");
let endingDefense = false;
const mediaRequestTimeoutMs = 10000;
let currentDefenseTurnId = "";

const {
  startAudio,
  finishAudioTurn,
  initAudio,
  closeAudio,
  volumeLevel,
  audioReady,
  audioError,
} = useSpeechRecognition({
  onConnectionLost: handleDefenseAudioConnectionLost,
});

async function refreshFilePreview(file?: Recordable) {
  if (previewObjectUrl) {
    URL.revokeObjectURL(previewObjectUrl);
    previewObjectUrl = "";
  }
  filePreviewUrl.value = "";
  if (!file?.fileUrl) return;
  const response = await previewFile(file.fileUrl);
  previewObjectUrl = URL.createObjectURL(response.data);
  filePreviewUrl.value = previewObjectUrl;
}

function setBlockingError(messageText: string, step: "setup" | "defense" = "setup") {
  blockingError.value = messageText;
  blockedStep.value = step;
  submitLoading.value = false;
}

function clearBlockingError() {
  blockingError.value = "";
  loadingText.value = "正在加载中，请稍后...";
}

function getErrorMessage(error: unknown, fallback: string) {
  return error instanceof Error && error.message ? error.message : fallback;
}

function requestVideoWithTimeout() {
  if (!navigator.mediaDevices?.getUserMedia) {
    return Promise.reject(new Error("当前浏览器不支持摄像头访问"));
  }

  let finished = false;
  let timeoutId: number | undefined = undefined;
  const timeoutMessage = "摄像头权限响应超时，请确认浏览器权限弹窗后重试。";
  const mediaRequest = navigator.mediaDevices
    .getUserMedia(INTERVIEW_CAMERA_CONSTRAINTS)
    .then((stream) => {
      if (finished) {
        stream.getTracks().forEach((track) => track.stop());
        throw new Error(timeoutMessage);
      }
      return stream;
    });

  const timeout = new Promise<never>((_, reject) => {
    timeoutId = window.setTimeout(() => {
      reject(new Error(timeoutMessage));
    }, mediaRequestTimeoutMs);
  });

  return Promise.race([mediaRequest, timeout]).finally(() => {
    finished = true;
    if (timeoutId) {
      window.clearTimeout(timeoutId);
    }
  });
}

function handleDefenseAudioConnectionLost() {
  if (pageOff.value || !isDebating.value) {
    return false;
  }
  clearInterval(countdownTimer);
  isDebating.value = false;
  submitLoading.value = false;
  endingDefense = false;
  setBlockingError(
    "录音连接中断，本轮答辩未保存。请检查网络和麦克风后点击重试，重新开始答辩。",
    "defense"
  );
  message.error("录音连接中断，请重新开始答辩");
  return false;
}

function clearTextHeartbeat() {
  if (heartbeatTimer) {
    clearInterval(heartbeatTimer);
    heartbeatTimer = null;
  }
}

function isTextSocketOpen() {
  return socket.value?.readyState === WebSocket.OPEN;
}

function sendAssessmentAction(action: string, turnId?: string) {
  if (!isTextSocketOpen()) {
    setBlockingError("答辩连接已断开，请重试连接后继续。", "setup");
    connect();
    return false;
  }
  socket.value?.send(
    JSON.stringify({
      message: route.query?.id,
      action,
      ...(turnId ? { turnId } : {}),
    })
  );
  return true;
}

async function loadAssessmentDetail() {
  const assessmentId = Number(route.query?.id);
  const response = await getAssessmentDetail(assessmentId);
  detail.value = response.data || {};
  countdown.value = Number(response.data?.duration || 0);
}

async function retryBlockedStep() {
  const step = blockedStep.value;
  clearBlockingError();

  if (step === "setup") {
    try {
      await loadAssessmentDetail();
    } catch (error) {
      setBlockingError(
        getErrorMessage(error, "无法加载考核信息，请返回后重试。"),
        "setup"
      );
      return;
    }
  }

  const audioOk = audioReady.value || (await initAudio());
  if (!audioOk) {
    setBlockingError(audioError.value || "无法打开麦克风，请允许浏览器麦克风权限后重试。", "setup");
    return;
  }
  const cameraOk = videoStream || (await startCamera());
  if (!cameraOk) {
    return;
  }
  if (!isConnected.value) {
    connect();
  }
  isWarmupDone.value = true;
  if (step === "defense") {
    startDebateBefore();
  }
}

async function startCamera() {
  try {
    videoStream = await requestVideoWithTimeout();
    if (videoRef.value) {
      videoRef.value.srcObject = videoStream;
      videoRef.value.onloadedmetadata = () => {
        videoRef.value.play();
      };
    }
    return true;
  } catch (err) {
    console.error("摄像头打开失败：", err);
    setBlockingError(
      getErrorMessage(err, "无法打开摄像头，请允许浏览器摄像头权限后重试。"),
      "setup"
    );
    return false;
  }
}

function stopCamera() {
  if (videoStream) {
    videoStream
      .getTracks()
      .forEach((track: { stop: () => any }) => track.stop());
    videoStream = null;
  }
}

function startDebateBefore() {
  clearBlockingError();
  currentDefenseTurnId = createAudioTurnId();
  sendAssessmentAction("start-defense", currentDefenseTurnId);
}

function stopDebateBefore() {
  if (detail.value?.duration - countdown.value > 3) {
    stopDebate();
  }
}

async function startDebate() {
  if (isDebating.value) {
    return;
  }
  const started = await startAudio(currentDefenseTurnId);
  if (!started) {
    setBlockingError(audioError.value || "录音服务连接失败，请检查网络后重试。", "defense");
    return;
  }
  isDebating.value = true;
  startCountdown();
}

async function stopDebate() {
  Modal.confirm({
    title: "确认结束答辩吗？",
    icon: createVNode(ExclamationCircleOutlined),
    okText: "确认结束",
    onOk: async () => {
      await finishDefenseTurn();
    },
  });
}

async function finishDefenseTurn() {
  if (endingDefense) {
    return;
  }
  endingDefense = true;
  submitLoading.value = true;
  clearInterval(countdownTimer);
  isDebating.value = false;
  try {
    await finishAudioTurn(currentDefenseTurnId);
  } catch (error) {
    endingDefense = false;
    submitLoading.value = false;
    setBlockingError(getErrorMessage(error, "录音上传确认失败，请重新开始答辩。"), "defense");
    return;
  }
  const sent = sendAssessmentAction("end-defense", currentDefenseTurnId);
  if (!sent) {
    submitLoading.value = false;
    endingDefense = false;
  }
}

function startCountdown() {
  countdown.value = detail.value?.duration;
  clearInterval(countdownTimer);
  countdown.value--;
  countdownTimer = setInterval(() => {
    countdown.value--;
    if (countdown.value <= 0) {
      finishDefenseTurn();
    }
  }, 1000);
}

const connect = () => {
  clearTextHeartbeat();
  if (socket.value) {
    socket.value?.close();
  }
  // 清除之前的重连定时器
  if (reconnectTimer) {
    clearTimeout(reconnectTimer);
    reconnectTimer = null;
  }
  if (reconnectAttempts.value >= maxReconnectAttempts && !isConnected.value) {
    connectionStatus.value = `Failed after ${maxReconnectAttempts} attempts`;
    setBlockingError(
      "答辩连接已断开，请检查网络后点击重试继续。",
      isDebating.value ? "defense" : "setup"
    );
    return;
  }
  connectionStatus.value = "Connecting...";

  // 创建 WebSocket 连接
  const currentSocket = new WebSocket(
    resolveWebSocketUrl(
      import.meta.env.VUE_APP_BASE_TEXT_WS,
      "/ws/defense-assessment"
    )
  );
  socket.value = currentSocket;

  currentSocket.onopen = () => {
    if (socket.value !== currentSocket) {
      return;
    }
    connectionStatus.value = "Connected";
    isConnected.value = true;
    reconnectAttempts.value = 0; // 重置重连计数器
    clearTextHeartbeat();
    heartbeatTimer = setInterval(() => {
      if (socket.value === currentSocket && currentSocket.readyState === WebSocket.OPEN) {
        currentSocket.send(JSON.stringify({ action: "ping" }));
      }
    }, HEARTBEAT_INTERVAL);
  };

  currentSocket.onmessage = (event) => {
    if (socket.value !== currentSocket) {
      return;
    }
    let messageData;
    try {
      messageData = JSON.parse(event.data);
    } catch (error) {
      console.warn("WebSocket 消息解析失败", error);
      return;
    }
    const contentText = String(messageData?.content || "");
    if (contentText == "开始答辩" && messageData?.normalMessage) {
      startDebate();
    }
    if (contentText == "结束答辩") {
      Modal.destroyAll();
      submitLoading.value = false;
      endingDefense = false;
      void closeAudio();
      stopCamera();
      // 等待完毕再跳转
      if (detail.value?.question) {
        router.push({
          name: "QuestionPage",
          query: route.query,
        });
      } else {
        sendAssessmentAction("end-assessment");
        router.push({
          name: "ResultOverPage",
          query: route.query,
        });
      }
    } else if (!messageData?.normalMessage && contentText) {
      endingDefense = false;
      message.error(contentText);
      if (contentText.includes("重新答辩") || contentText.includes("录音")) {
        setBlockingError(contentText, "defense");
      }
    }
  };

  currentSocket.onerror = (error: any) => {
    if (socket.value !== currentSocket) {
      return;
    }
    console.error("WebSocket error:", error);
    connectionStatus.value = "Error: " + error.message;
    attemptReconnect();
    isConnected.value = false;
  };

  currentSocket.onclose = () => {
    if (socket.value !== currentSocket) {
      return;
    }
    clearTextHeartbeat();
    connectionStatus.value = "Disconnected";
    isConnected.value = false;
    if (!pageOff.value) {
      attemptReconnect();
    }
  };
};
// 尝试重新连接
const attemptReconnect = () => {
  if (isConnected.value || reconnectTimer) {
    return;
  }
  if (reconnectAttempts.value >= maxReconnectAttempts) {
    setBlockingError(
      "答辩连接已断开，请检查网络后点击重试继续。",
      isDebating.value ? "defense" : "setup"
    );
    return;
  }
  reconnectAttempts.value += 1;
  connectionStatus.value = `Attempting to reconnect (${reconnectAttempts.value}/${maxReconnectAttempts})...`;

  reconnectTimer = setTimeout(() => {
    connect();
  }, reconnectInterval);
};

function createAudioTurnId() {
  if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") {
    return crypto.randomUUID();
  }
  return `${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

onMounted(async () => {
  const assessmentId = Number(route.query?.id);
  if (!Number.isInteger(assessmentId) || assessmentId <= 0) {
    message.warning("缺少有效的考核信息，已返回学生首页");
    await router.replace("/studentDashboard");
    return;
  }

  try {
    await loadAssessmentDetail();
    const audioOk = await initAudio();
    if (!audioOk) {
      setBlockingError(audioError.value || "无法打开麦克风，请允许浏览器麦克风权限后重试。", "setup");
      return;
    }
    const cameraOk = await startCamera();
    if (!cameraOk) {
      return;
    }
    connect();
    isWarmupDone.value = true;
  } catch (error) {
    setBlockingError(getErrorMessage(error, "无法加载考核信息，请返回后重试。"), "setup");
  }
});

onUnmounted(() => {
  if (previewObjectUrl) URL.revokeObjectURL(previewObjectUrl);
  pageOff.value = true;
  stopCamera();
  closeAudio();
  clearInterval(countdownTimer);
  if (socket.value) {
    socket.value.close();
  }
  clearTextHeartbeat();
  if (reconnectTimer) {
    clearTimeout(reconnectTimer);
  }
  window.removeEventListener("beforeunload", handleBeforeUnload);
});

const handleBeforeUnload = () => {
  stopCamera();
  closeAudio();
  clearInterval(countdownTimer);
};
window.addEventListener("beforeunload", handleBeforeUnload);

const beforeUpload = (file: FileType) => {
  const isPdf =
    file?.type === "application/pdf" ||
    file?.name?.toLowerCase().endsWith(".pdf");
  if (!isPdf) {
    message.error("只允许上传 PDF 文件");
    return false;
  }
  uploadProgress.value = 0;
  return true;
};
const handleChange = (info: UploadChangeParam) => {
  uploadStatus.value = "active";
  if (info.file.status == "uploading") {
    uploadProgress.value = info.file?.percent as number;
  }
  if (info.file.status == "done") {
    if (info.file?.response?.code == 200) {
      uploadStatus.value = "success";
      uploadProgress.value = 100;
      uploadFile.value = {
        fileSize: info.file?.size,
        fileUrl: info.file?.response?.data?.url,
        fileName: info.file?.response?.data?.fileName,
        presignedUrl: info.file?.response?.data?.presignedUrl,
      };
      refreshFilePreview(uploadFile.value).catch(() => {
        uploadStatus.value = "exception";
      });
      uploadAssessmentFile({
        assessmentId: route.query?.id,
        file: {
          fileSize: info.file?.size,
          fileUrl: info.file?.response?.data?.url,
          fileName: info.file?.response?.data?.fileName,
        },
      }).catch(() => {
        uploadStatus.value = "exception";
      });
    } else {
      uploadStatus.value = "exception";
    }
  } else if (info.file.status == "error") {
    uploadStatus.value = "exception";
  }
};

watch(
  () => isWarmupDone.value,
  () => {
    if (isWarmupDone.value) {
      showArr.value = [1];
      setTimeout(() => {
        showArr.value?.push(2);
      }, 400);
      setTimeout(() => {
        showArr.value?.push(3);
      }, 800);
    }
  },
  {
    deep: true,
    immediate: true,
  }
);
</script>
<style scoped>
.defense {
  background:
    radial-gradient(circle at 82% 18%, rgba(244, 207, 85, 0.18), transparent 30%),
    linear-gradient(180deg, #706f6b 0%, #5f5a57 100%);
}
.bottom-card {
  height: 265px;
  background: linear-gradient(
    180deg,
    rgba(167, 167, 167, 0.4) 0%,
    rgba(26, 26, 26, 0.8) 100%
  );
  box-shadow: 0px 20px 80px 0px rgba(0, 0, 0, 0.5),
    inset 2px 2px 0px 0px rgba(255, 255, 255, 0.6),
    inset -2px -2px 0px 0px rgba(0, 0, 0, 0.2);
  border-radius: 50px 50px 50px 50px;
  padding: 24px;
  backdrop-filter: blur(40px);
}
.bottom-card-in {
  width: 100%;
  height: 100%;
  background: linear-gradient(
    180deg,
    rgba(0, 0, 0, 0.3) 0%,
    rgba(83, 83, 83, 0.2) 100%
  );
  box-shadow: inset 0px 0px 4px 0px rgba(0, 0, 0, 0.4);
  border-radius: 32px 32px 32px 32px;
  border: 2px solid rgba(255, 255, 255, 0.3);
}
.defense-action {
  width: 100%;
  height: 95px;
  background: linear-gradient(
    180deg,
    rgba(211, 211, 211, 0.3) 0%,
    rgba(30, 30, 30, 0.2) 100%
  );
  box-shadow: 0px 20px 50px 0px rgba(0, 0, 0, 0.7);
  border-radius: 459px 459px 459px 459px;
  padding: 16px;
}
.reUpload {
  width: 140px;
  height: 50px;
  background: linear-gradient(
    270deg,
    rgba(255, 255, 255, 0.4) 0%,
    rgba(255, 255, 255, 0.8) 99%
  );
  box-shadow: 0px 10px 20px 0px rgba(18, 21, 73, 0.08);
  border-radius: 693px 693px 693px 693px;
  border: 1px solid #ffffff;
  cursor: pointer;
  font-weight: 500;
  font-size: 18px;
  color: #4866ff;
}
.wave {
  width: 100%;
  height: 128px;
  background: linear-gradient(
    0deg,
    rgba(0, 0, 0, 0.8) 0%,
    rgba(0, 0, 0, 0.6) 25%,
    rgba(0, 0, 0, 0.4) 50%,
    rgba(0, 0, 0, 0.2) 75%,
    transparent 100%
  );

  border-bottom-right-radius: 32px;
  border-bottom-left-radius: 32px;

  position: absolute;
  bottom: 0;
  left: 0;
  z-index: 10;
}

.wave-1 {
  position: absolute;
  bottom: 0;
  left: 0;
  height: 80%;
  width: 100%;
  background: linear-gradient(
    0deg,
    rgba(0, 0, 0, 0.8) 0%,
    rgba(0, 0, 0, 0.6) 25%,
    rgba(0, 0, 0, 0.4) 50%,
    rgba(0, 0, 0, 0.2) 75%,
    transparent 100%
  );
  /* backdrop-filter: blur(0.15rem); */
  z-index: 10;
}

/* Webkit浏览器滚动条 */
.custom-scrollbar::-webkit-scrollbar {
  width: 6px;
}

.custom-scrollbar::-webkit-scrollbar-track {
  background: rgba(255, 255, 255, 0.3);
  border-radius: 3px;
}

.custom-scrollbar::-webkit-scrollbar-thumb {
  background: #c0c0c0;
  border-radius: 3px;
}

.custom-scrollbar::-webkit-scrollbar-thumb:hover {
  background: #a0a0a0;
}

/* Firefox浏览器支持 */
@supports not selector(::-webkit-scrollbar) {
  .custom-scrollbar {
    scrollbar-width: thin;
    scrollbar-color: #c0c0c0 rgba(255, 255, 255, 0.3);
  }
}

video {
  width: 100%;
  height: 100%;
  object-fit: cover;
  border-radius: 5px;
  transition: opacity 0.3s ease;
}
.fade-in {
  opacity: 0;
  animation: fadeIn 0.8s ease-in forwards;
}

@keyframes fadeIn {
  0% {
    opacity: 0.1;
  }
  25% {
    opacity: 0.25;
  }
  50% {
    opacity: 0.5;
  }
  75% {
    opacity: 0.75;
  }
  100% {
    opacity: 1;
  }
  /* to {
    opacity: 1;
  } */
}
.videoBox {
  height: calc(100% - 270px);
}

.defense-header-bar {
  max-width: 1450px;
  margin: 0 auto;
}

@media (max-width: 1100px) {
  .defense {
    height: auto;
    min-height: 100dvh;
    padding: 18px 16px 24px;
  }

  .defense-stage {
    height: auto;
    max-height: none;
    flex-direction: column;
    gap: 16px;
  }

  .defense-file-panel,
  .defense-video-panel {
    width: 100% !important;
    min-height: 420px;
    max-height: none;
    margin-right: 0 !important;
  }

  .defense-video-panel {
    height: auto;
  }

  .defense-bottom-panel {
    position: relative !important;
    bottom: auto !important;
    left: auto !important;
    min-height: 220px;
    margin-top: 0;
  }

  .bottom-card {
    height: auto;
    min-height: 220px;
  }
}

@media (max-width: 640px) {
  .defense {
    padding: 12px;
  }

  .defense-file-panel,
  .defense-video-panel {
    min-height: 340px;
    border-radius: 24px;
  }

  .defense-file-panel {
    padding: 12px;
  }

  .defense-action {
    height: auto;
    min-height: 112px;
    flex-direction: column;
    gap: 14px;
    padding: 14px;
    border-radius: 28px;
  }

  .defense-main-action,
  .reUpload {
    width: 100% !important;
    max-width: 260px;
  }

  .reUpload {
    right: 24px;
    bottom: 24px;
  }

  .bottom-card {
    padding: 14px;
    border-radius: 28px;
  }

  .bottom-card-in {
    border-radius: 22px;
    font-size: 18px;
  }
}
</style>
