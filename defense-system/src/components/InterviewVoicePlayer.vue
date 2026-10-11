<template>
  <div class="hidden" aria-hidden="true"></div>
</template>

<script setup>
import { onMounted, onUnmounted, ref, watch } from "vue";
import { resolveWebSocketUrl } from "@/utils/websocket";
import PCMPlayer from "pcm-player";

const props = defineProps({
  playbackEnabled: {
    type: Boolean,
    default: true,
  },
});
const emit = defineEmits(["ready", "playing", "broadcastingEnd"]);
const AUDIO_SAMPLE_RATE = 24000;
const MAX_PENDING_AUDIO_PAYLOADS = 256;
const MAX_PENDING_AUDIO_BASE64_CHARS = 24 * 1024 * 1024;

const audioLength = ref(0);
const fragmentAllLength = ref(0);
const isWsEnd = ref(false);
const time = ref(0);
const timer = ref(null);

let player = null;
let socketVoice = null;
let heartbeatInterval = null;
let ignorePayloadUntilEnd = false;
let ignorePayloadTimer = null;
let manuallyClosed = false;
let reconnectTimer = null;
let reconnectAttempts = 0;
let pendingAudioPayloads = [];
let pendingAudioChars = 0;
let pendingAudioDiscarded = false;
let pendingAudioEnd = false;
const maxReconnectAttempts = 5;

function clearPendingAudio() {
  pendingAudioPayloads = [];
  pendingAudioChars = 0;
  pendingAudioDiscarded = false;
  pendingAudioEnd = false;
}

function resetPlaybackState() {
  audioLength.value = 0;
  fragmentAllLength.value = 0;
  isWsEnd.value = false;
  time.value = 0;
  if (timer.value) {
    clearInterval(timer.value);
    timer.value = null;
  }
}

function clearIgnorePayload() {
  ignorePayloadUntilEnd = false;
  if (ignorePayloadTimer) {
    clearTimeout(ignorePayloadTimer);
    ignorePayloadTimer = null;
  }
}

function ignorePayloadsUntilEndOrTimeout() {
  ignorePayloadUntilEnd = true;
  if (ignorePayloadTimer) {
    clearTimeout(ignorePayloadTimer);
  }
  ignorePayloadTimer = setTimeout(() => {
    ignorePayloadUntilEnd = false;
    ignorePayloadTimer = null;
  }, 5000);
}

function broadcastingEnd() {
  clearPendingAudio();
  emit("broadcastingEnd");
  emit("playing", false);
  resetPlaybackState();
}

function base64ToArrayBuffer(base64, isSlice) {
  const binaryString = atob(base64);
  const length = binaryString.length;
  let bytes = new Uint8Array(length);

  for (let i = 0; i < length; i++) {
    bytes[i] = binaryString.charCodeAt(i);
  }

  if (isSlice) {
    const wavHeaderLength = 44;
    if (bytes.length > wavHeaderLength) {
      bytes = bytes.slice(wavHeaderLength);
    }
  }

  return bytes.buffer;
}

function ensurePlayer() {
  if (player) {
    return;
  }

  player = new PCMPlayer({
    encoding: "16bitInt",
    channels: 1,
    sampleRate: AUDIO_SAMPLE_RATE,
    flushingTime: 500,
    onstatechange: () => {
      emit("playing", true);
      if (timer.value) {
        clearInterval(timer.value);
        timer.value = null;
      }
      time.value = 0;
      timer.value = setInterval(() => {
        time.value += 1;
        if (
          time.value >= audioLength.value / AUDIO_SAMPLE_RATE / 2 + 1 &&
          isWsEnd.value
        ) {
          broadcastingEnd();
        }
      }, 1000);
    },
    onended: (node) => {
      fragmentAllLength.value +=
        node?.buffer?.byteLength || node?.buffer?.length * 2 || 0;
      if (
        fragmentAllLength.value >= audioLength.value &&
        fragmentAllLength.value &&
        isWsEnd.value
      ) {
        broadcastingEnd();
      }
    },
  });
}

function handleAudioPayload(payload) {
  if (ignorePayloadUntilEnd) {
    return;
  }

  ensurePlayer();

  const pcmData = base64ToArrayBuffer(payload, true);
  audioLength.value += pcmData.byteLength;

  if (player?.gainNode?.gain && player?.audioCtx) {
    player.gainNode.gain.setValueAtTime(1.2, player.audioCtx.currentTime);
  }
  player.feed(pcmData);
}

function handleAudioEnd() {
  if (ignorePayloadUntilEnd) {
    clearIgnorePayload();
    clearPendingAudio();
    resetPlaybackState();
    return;
  }

  isWsEnd.value = true;
  if (audioLength.value === 0) {
    broadcastingEnd();
  }
}

function handleIncomingAudioPayload(payload) {
  if (!props.playbackEnabled) {
    if (pendingAudioDiscarded) {
      return;
    }
    if (
      pendingAudioPayloads.length >= MAX_PENDING_AUDIO_PAYLOADS ||
      pendingAudioChars + payload.length > MAX_PENDING_AUDIO_BASE64_CHARS
    ) {
      pendingAudioPayloads = [];
      pendingAudioChars = 0;
      pendingAudioDiscarded = true;
      return;
    }
    pendingAudioPayloads.push(payload);
    pendingAudioChars += payload.length;
    return;
  }

  handleAudioPayload(payload);
}

function handleIncomingAudioEnd() {
  if (!props.playbackEnabled) {
    pendingAudioEnd = true;
    return;
  }

  handleAudioEnd();
}

function flushPendingAudio() {
  if (!props.playbackEnabled) {
    return;
  }

  const payloads = pendingAudioDiscarded ? [] : pendingAudioPayloads.splice(0);
  pendingAudioChars = 0;
  pendingAudioDiscarded = false;
  for (const payload of payloads) {
    handleAudioPayload(payload);
  }
  if (pendingAudioEnd) {
    pendingAudioEnd = false;
    handleAudioEnd();
  }
}

function startHeartbeat() {
  if (
    heartbeatInterval ||
    !socketVoice ||
    socketVoice.readyState !== WebSocket.OPEN
  ) {
    return;
  }

  heartbeatInterval = setInterval(() => {
    if (socketVoice?.readyState === WebSocket.OPEN) {
      socketVoice.send(JSON.stringify({ action: "ping" }));
    }
  }, 30000);
}

function stopHeartbeat() {
  if (heartbeatInterval) {
    clearInterval(heartbeatInterval);
    heartbeatInterval = null;
  }
}

function scheduleReconnect() {
  if (manuallyClosed || reconnectTimer || reconnectAttempts >= maxReconnectAttempts) {
    return;
  }
  reconnectAttempts += 1;
  reconnectTimer = setTimeout(() => {
    reconnectTimer = null;
    connectVoiceSocket();
  }, Math.min(1000 * reconnectAttempts, 5000));
}

function closeSocket() {
  if (socketVoice) {
    try {
      socketVoice.close(1000, "Client close");
    } catch (e) {}
    socketVoice = null;
  }
}

const stopVoice = () => {
  clearPendingAudio();
  if (audioLength.value > 0) {
    ignorePayloadsUntilEndOrTimeout();
  }

  if (player) {
    player.destroy();
    player = null;
  }

  if (socketVoice?.readyState === WebSocket.OPEN) {
    socketVoice.send(JSON.stringify({ action: "stop" }));
  }

  resetPlaybackState();
};

function connectVoiceSocket() {
  if (manuallyClosed) {
    return;
  }
  closeSocket();
  socketVoice = new WebSocket(
    resolveWebSocketUrl(
      import.meta.env.VUE_APP_BASE_PLAYVOICE_WS,
      "/ws/voice-play/defense-assessment"
    )
  );

  socketVoice.addEventListener("open", () => {
    reconnectAttempts = 0;
    emit("ready");
    startHeartbeat();
  });

  socketVoice.addEventListener("message", (event) => {
    if (
      event?.data?.includes("audio:end") &&
      event?.data?.includes("normalMessage")
    ) {
      handleIncomingAudioEnd();
      return;
    }

    if (event?.data?.includes("normalMessage")) {
      return;
    }

    if (event?.data && !/^\s*$/.test(event.data)) {
      handleIncomingAudioPayload(event.data);
    }
  });

  socketVoice.addEventListener("error", (event) => {
    console.warn("voice playback websocket error", event);
  });

  socketVoice.addEventListener("close", () => {
    stopHeartbeat();
    clearIgnorePayload();
    if (audioLength.value > 0 && !ignorePayloadUntilEnd) {
      if (player) {
        player.destroy();
        player = null;
      }
      broadcastingEnd();
    }
    if (!manuallyClosed) {
      scheduleReconnect();
    }
  });
}

onMounted(() => {
  manuallyClosed = false;
  connectVoiceSocket();
});

watch(
  () => props.playbackEnabled,
  (enabled) => {
    if (enabled) {
      flushPendingAudio();
    }
  }
);

onUnmounted(() => {
  manuallyClosed = true;
  clearIgnorePayload();
  clearPendingAudio();
  if (reconnectTimer) {
    clearTimeout(reconnectTimer);
    reconnectTimer = null;
  }
  stopVoice();
  closeSocket();
  stopHeartbeat();
});

defineExpose({
  stopVoice,
  stopVideo: stopVoice,
});
</script>
