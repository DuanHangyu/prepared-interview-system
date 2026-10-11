import { onUnmounted, ref } from "vue";
import { mittEmit } from "@/utils/appMitt";
import { resolveWebSocketUrl } from "@/utils/websocket";

/**
 * @param {{ onConnectionLost?: (event: { code: number, reason: string, wasClean: boolean }) => boolean | void }} [options]
 */
export function useSpeechRecognition(options = {}) {
  const transcriptionText = ref("");
  const audioReady = ref(false);
  const audioError = ref("");
  const mediaRequestTimeoutMs = 10000;
  let ws = ref(null);
  let audioContext = null;
  let micNode = null;
  let audioStream = null;
  let isStreaming = false;

  const audioChunks = [];
  let sendInterval = null;
  let heartbeatInterval = null;

  let manuallyClosed = false;
  let reconnectTimer = null;
  let initPromise = null;
  let warmupSession = null;
  const handlers = options || {};
  const pendingTurnCommands = new Map();
  const turnAckTimeoutMs = 30000;
  const maxReconnectAttempts = 5;
  let activeTurnId = null;
  let reconnectAttempts = 0;
  let reconnectingTurnId = null;
  let lastCloseEvent = null;

  function mergeChunks(chunks) {
    let totalLength = 0;
    chunks.forEach((chunk) => (totalLength += chunk.length));
    const merged = new Uint8Array(totalLength);
    let offset = 0;
    chunks.forEach((chunk) => {
      merged.set(chunk, offset);
      offset += chunk.length;
    });
    return merged;
  }

  function rejectPendingTurnCommands(reason) {
    pendingTurnCommands.forEach(({ reject, timeoutId }) => {
      clearTimeout(timeoutId);
      reject(new Error(reason));
    });
    pendingTurnCommands.clear();
  }

  function sendTurnCommand(action, turnId) {
    if (!turnId) {
      return Promise.reject(new Error("缺少音频轮次标识"));
    }
    if (!ws.value || ws.value.readyState !== WebSocket.OPEN) {
      return Promise.reject(new Error("录音连接不可用"));
    }
    const ackAction = `${action}-ack`;
    const key = `${ackAction}:${turnId}`;
    return new Promise((resolve, reject) => {
      const previous = pendingTurnCommands.get(key);
      if (previous) {
        clearTimeout(previous.timeoutId);
        previous.reject(new Error("音频轮次命令已被新的请求替换"));
      }
      const timeoutId = setTimeout(() => {
        pendingTurnCommands.delete(key);
        reject(new Error("等待录音确认超时，请重新录制"));
      }, turnAckTimeoutMs);
      pendingTurnCommands.set(key, { resolve, reject, timeoutId });
      ws.value.send(JSON.stringify({ action, turnId }));
    });
  }

  function handleTurnAcknowledgement(data) {
    if (!data || typeof data !== "object" || !data.action || !data.turnId) {
      return false;
    }
    const key = `${data.action}:${data.turnId}`;
    const pending = pendingTurnCommands.get(key);
    if (!pending) {
      return data.action === "audio-start-ack" || data.action === "audio-flush-ack";
    }
    clearTimeout(pending.timeoutId);
    pendingTurnCommands.delete(key);
    if (data.accepted) {
      pending.resolve(data);
    } else {
      pending.reject(new Error(data.message || "录音轮次未被服务器接受"));
    }
    return true;
  }

  function requestMediaWithTimeout(constraints, timeoutMessage) {
    if (!navigator.mediaDevices?.getUserMedia) {
      return Promise.reject(new Error("当前浏览器不支持麦克风访问"));
    }

    let finished = false;
    let timeoutId = null;
    const mediaRequest = navigator.mediaDevices
      .getUserMedia(constraints)
      .then((stream) => {
        if (finished) {
          stream.getTracks().forEach((track) => track.stop());
          throw new Error(timeoutMessage);
        }
        return stream;
      });

    const timeout = new Promise((_, reject) => {
      timeoutId = setTimeout(() => {
        reject(new Error(timeoutMessage));
      }, mediaRequestTimeoutMs);
    });

    return Promise.race([mediaRequest, timeout]).finally(() => {
      finished = true;
      if (timeoutId) {
        clearTimeout(timeoutId);
      }
    });
  }

  async function startAudio(turnId, options = {}) {
    const { preserveOnFailure = false } = options;
    const maxAttempts = 10;
    let attempt = 0;
    while (
      (!ws.value || ws.value.readyState !== WebSocket.OPEN) &&
      attempt < maxAttempts
    ) {
      await new Promise((resolve) => setTimeout(resolve, 500));
      attempt++;
    }
    if (ws.value && ws.value.readyState === WebSocket.OPEN) {
      try {
        await sendTurnCommand("audio-start", turnId);
        activeTurnId = turnId;
        isStreaming = true;
        if (!sendInterval) {
          sendInterval = setInterval(() => {
            flushAudioChunks();
          }, 100);
        }
        return true;
      } catch (error) {
        audioError.value = error?.message || "录音轮次启动失败，请重试";
        if (preserveOnFailure) {
          manuallyClosed = true;
          rejectPendingTurnCommands("录音连接已关闭");
          const failedSocket = ws.value;
          ws.value = null;
          if (failedSocket && failedSocket.readyState !== WebSocket.CLOSED) {
            failedSocket.close(1000, "Reconnect retry");
          }
          audioReady.value = false;
          activeTurnId = turnId;
          manuallyClosed = false;
        } else {
          manuallyClosed = true;
          await cleanupAudioResources({ closeSocket: true, clearChunks: true });
        }
        return false;
      }
    }
    audioError.value = "录音服务连接失败，请检查网络后重试";
    if (preserveOnFailure) {
      const unavailableSocket = ws.value;
      ws.value = null;
      if (
        unavailableSocket &&
        unavailableSocket.readyState !== WebSocket.CLOSED
      ) {
        unavailableSocket.close(1000, "Reconnect timeout");
      }
      audioReady.value = false;
      activeTurnId = turnId;
    }
    return false;
  }

  function flushAudioChunks() {
    if (
      audioChunks.length > 0 &&
      ws.value &&
      ws.value.readyState === WebSocket.OPEN
    ) {
      const merged = mergeChunks(audioChunks);
      ws.value.send(merged);
      audioChunks.length = 0;
      return true;
    }
    return false;
  }

  function stopAudio(options = {}) {
    const { flush = true } = options;
    if (flush) {
      flushAudioChunks();
    }
    isStreaming = false;
    if (sendInterval) {
      clearInterval(sendInterval);
      sendInterval = null;
    }
    audioChunks.length = 0;
    activeTurnId = null;
  }

  async function finishAudioTurn(turnId = activeTurnId) {
    if (!turnId || turnId !== activeTurnId) {
      throw new Error("当前录音轮次已失效，请重新录制");
    }
    isStreaming = false;
    if (sendInterval) {
      clearInterval(sendInterval);
      sendInterval = null;
    }
    flushAudioChunks();
    audioChunks.length = 0;
    try {
      await sendTurnCommand("audio-flush", turnId);
      activeTurnId = null;
      return true;
    } catch (error) {
      audioError.value = error?.message || "录音上传确认失败，请重新录制";
      manuallyClosed = true;
      await cleanupAudioResources({ closeSocket: true, clearChunks: true });
      throw error;
    }
  }

  function startHeartbeat() {
    if (
      heartbeatInterval ||
      !ws.value ||
      ws.value.readyState !== WebSocket.OPEN
    )
      return;
    heartbeatInterval = setInterval(() => {
      if (ws.value.readyState === WebSocket.OPEN) {
        try {
          ws.value.send(JSON.stringify({ action: "ping" }));
        } catch (e) {
          console.warn("Failed to send heartbeat", e);
        }
      }
    }, 30000); // 每 30 秒发送一次心跳
  }

  function stopHeartbeat() {
    if (heartbeatInterval) {
      clearInterval(heartbeatInterval);
      heartbeatInterval = null;
    }
  }

  let analyser = null;
  let animationFrameId = null;
  const volumeLevel = ref(0);
  // 音量检测函数
  const checkVolume = () => {
    if (!analyser) return;

    const dataArray = new Uint8Array(analyser.frequencyBinCount);
    analyser.getByteFrequencyData(dataArray);

    // 计算平均音量 (0-255)
    let sum = 0;
    for (const value of dataArray) sum += value;
    const average = sum / dataArray.length;

    // 转换为百分比 (0-100)
    volumeLevel.value = Math.min(Math.round((average / 255) * 100), 100);
    animationFrameId = requestAnimationFrame(checkVolume);
  };

  async function cleanupAudioResources({
    closeSocket = false,
    clearChunks = true,
    preserveTurn = false,
  } = {}) {
    audioReady.value = false;
    isStreaming = false;
    stopHeartbeat();
    finishWarmup();
    if (sendInterval) {
      clearInterval(sendInterval);
      sendInterval = null;
    }
    if (reconnectTimer) {
      clearTimeout(reconnectTimer);
      reconnectTimer = null;
    }
    rejectPendingTurnCommands("录音连接已关闭");
    if (!preserveTurn) {
      activeTurnId = null;
    }
    if (animationFrameId) {
      cancelAnimationFrame(animationFrameId);
      animationFrameId = null;
    }

    if (analyser) {
      try {
        analyser.disconnect();
      } catch (e) {}
      analyser = null;
    }

    if (micNode) {
      try {
        micNode.disconnect();
      } catch (e) {}
      micNode = null;
    }

    if (audioStream) {
      audioStream.getTracks().forEach((track) => track.stop());
      audioStream = null;
    }

    if (audioContext) {
      try {
        if (audioContext.state !== "closed") {
          await audioContext.close();
        }
      } catch (e) {
        console.warn("关闭 AudioContext 失败", e);
      }
      audioContext = null;
    }

    if (closeSocket && ws.value) {
      if (ws.value.readyState !== WebSocket.CLOSED) {
        ws.value.close(1000, "Client close");
      }
      ws.value = null;
    }

    if (clearChunks) {
      audioChunks.length = 0;
    }
    volumeLevel.value = 0;
  }

  function failReconnect() {
    const interruptedTurnId = reconnectingTurnId;
    const closeEvent = lastCloseEvent || { code: 1006, reason: "", wasClean: false };
    reconnectingTurnId = null;
    lastCloseEvent = null;
    reconnectAttempts = 0;
    audioError.value = "录音连接多次恢复失败，请检查网络后重试";
    manuallyClosed = true;
    stopAudio({ flush: false });
    void cleanupAudioResources({ closeSocket: true, clearChunks: true });
    if (interruptedTurnId && typeof handlers.onConnectionLost === "function") {
      try {
        handlers.onConnectionLost(closeEvent);
      } catch (error) {
        console.error("录音连接中断处理失败", error);
      }
    }
  }

  function scheduleReconnect(turnId = null, closeEvent = null) {
    if (manuallyClosed || reconnectTimer) {
      return;
    }
    if (turnId) {
      reconnectingTurnId = turnId;
    }
    if (closeEvent) {
      lastCloseEvent = closeEvent;
    }
    if (reconnectAttempts >= maxReconnectAttempts) {
      failReconnect();
      return;
    }
    const delay = Math.min(1000 * 2 ** reconnectAttempts, 4000);
    reconnectAttempts += 1;
    console.warn(`WebSocket 被动关闭，尝试重新连接 (${reconnectAttempts}/${maxReconnectAttempts})...`);
    reconnectTimer = setTimeout(async () => {
      reconnectTimer = null;
      const turnToResume = reconnectingTurnId;
      manuallyClosed = false;
      const connected = connectVoiceSocket();
      if (!connected) {
        manuallyClosed = false;
        scheduleReconnect(turnToResume, lastCloseEvent);
        return;
      }
      if (!turnToResume) {
        reconnectAttempts = 0;
        return;
      }
      const resumed = await startAudio(turnToResume, { preserveOnFailure: true });
      if (resumed) {
        reconnectAttempts = 0;
        reconnectingTurnId = null;
        lastCloseEvent = null;
        audioError.value = "";
        return;
      }
      manuallyClosed = false;
      scheduleReconnect(turnToResume, lastCloseEvent);
    }, delay);
  }

  async function initAudio() {
    if (initPromise) {
      return initPromise;
    }
    initPromise = doInitAudio().finally(() => {
      initPromise = null;
    });
    return initPromise;
  }

  async function doInitAudio() {
    try {
      audioError.value = "";
      audioReady.value = false;
      manuallyClosed = false;
      audioStream = await requestMediaWithTimeout(
        { audio: true },
        "麦克风权限响应超时，请确认浏览器权限弹窗后重试"
      );
      // { sampleRate: 16000 }
      audioContext = new window.AudioContext({ sampleRate: 16000 });

      await audioContext.audioWorklet.addModule("processor.js");

      const source = audioContext.createMediaStreamSource(audioStream);
      micNode = new AudioWorkletNode(audioContext, "mic-processor");

      micNode.port.onmessage = (event) => {
        if (!isStreaming) return;
        const chunk = new Uint8Array(event.data);
        audioChunks.push(chunk);
      };

      source.connect(micNode);

      // -----------------------------------
      // 2. 单独初始化 AnalyserNode (处理音量检测)
      analyser = audioContext.createAnalyser();
      analyser.fftSize = 256;
      analyser.smoothingTimeConstant = 0.2;
      source.connect(analyser); // 注意：同一个 source 可以连接多个节点

      // 启动音量检测循环
      animationFrameId = requestAnimationFrame(checkVolume);
      // -----------------------------------
      return connectVoiceSocket();
    } catch (err) {
      audioReady.value = false;
      audioError.value =
        err?.message || "无法打开麦克风，请允许浏览器麦克风权限后重试";
      console.error("麦克风打开失败：", err);
      manuallyClosed = true;
      await cleanupAudioResources({ closeSocket: true, clearChunks: true });
      return false;
    }
  }

  function connectVoiceSocket() {
    try {
      const socket = new WebSocket(
        resolveWebSocketUrl(
          import.meta.env.VUE_APP_BASE_VOICE_WS,
          "/ws/voice/defense-assessment"
        )
      );
      ws.value = socket;

      socket.onopen = () => {
        audioReady.value = true;
        audioError.value = "";
        if (!reconnectingTurnId) {
          reconnectAttempts = 0;
        }
        startHeartbeat();
      };

      socket.onmessage = (event) => {
        // mittEmit("SOURCE:BLOB", event.data);
        try {
          const result = event.data;
          if (typeof result === "string" && result.startsWith("{")) {
            const protocolMessage = JSON.parse(result);
            if (handleTurnAcknowledgement(protocolMessage)) {
              return;
            }
          }
          if (result) {
            transcriptionText.value += result + " ";
          }
        } catch (e) {
          console.error("WebSocket onmessage", e);
        }
      };

      socket.onerror = (e) => {
        audioReady.value = false;
        audioError.value = "录音服务连接异常，请检查网络后重试";
        console.error("WebSocket error", e);
      };

      socket.onclose = function (event) {
        if (ws.value !== socket) {
          return;
        }
        stopHeartbeat();
        const interruptedTurnId = !manuallyClosed && isStreaming ? activeTurnId : null;
        if (sendInterval) {
          clearInterval(sendInterval);
          sendInterval = null;
        }
        isStreaming = false;
        ws.value = null;
        audioReady.value = false;

        if (!manuallyClosed) {
          scheduleReconnect(interruptedTurnId, {
            code: event.code,
            reason: event.reason,
            wasClean: event.wasClean,
          });
        }
      };
      return true;
    } catch (err) {
      audioReady.value = false;
      audioError.value =
        err?.message || "录音服务连接失败，请检查网络后重试";
      console.error("录音连接创建失败：", err);
      return false;
    }
  }

  async function closeAudio() {
    try {
      manuallyClosed = true; // 主动关闭标志
      reconnectingTurnId = null;
      lastCloseEvent = null;
      reconnectAttempts = 0;
      stopAudio({ flush: false });
      await cleanupAudioResources({ closeSocket: true, clearChunks: true });
    } catch (err) {
      console.error("停止音频资源出错：", err);
    }
  }

  function finishWarmup(session = warmupSession) {
    if (!session || session.completed) {
      return;
    }
    session.completed = true;
    if (session.timeoutId) {
      clearTimeout(session.timeoutId);
    }
    if (warmupSession === session) {
      warmupSession = null;
    }
    if (session.socket.readyState !== WebSocket.CLOSED) {
      try {
        session.socket.close(1000, "Warmup done");
      } catch (e) {}
    }
    session.resolve();
  }

  function warmup() {
    finishWarmup();
    return new Promise((resolve) => {
      const warmupWs = new WebSocket(
        resolveWebSocketUrl(
          import.meta.env.VUE_APP_BASE_VOICE_WS,
          "/ws/voice/defense-assessment"
        )
      );
      const session = {
        socket: warmupWs,
        resolve,
        timeoutId: setTimeout(() => finishWarmup(session), 5000),
        completed: false,
      };
      warmupSession = session;
      warmupWs.onopen = () => {
        try {
          const silence = new Int16Array(48000 * 3).fill(0);
          warmupWs.send(new Uint8Array(silence.buffer));
          clearTimeout(session.timeoutId);
          session.timeoutId = setTimeout(() => finishWarmup(session), 3000);
        } catch (error) {
          finishWarmup(session);
        }
      };
      warmupWs.onerror = () => finishWarmup(session);
      warmupWs.onclose = () => finishWarmup(session);
    });
  }

  async function restartAudio(turnId) {
    await closeAudio();
    await initAudio();
    await startAudio(turnId);
  }

  onUnmounted(() => {
    closeAudio();
    cancelAnimationFrame(animationFrameId);
  });

  return {
    transcriptionText,
    startAudio,
    stopAudio,
    finishAudioTurn,
    initAudio,
    closeAudio,
    warmup,
    startHeartbeat,
    stopHeartbeat,
    restartAudio,
    volumeLevel,
    audioReady,
    audioError,
  };
}
