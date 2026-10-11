import { flushPromises, mount, type VueWrapper } from "@vue/test-utils";
import { defineComponent, h } from "vue";
import { afterEach, describe, expect, it, vi } from "vitest";
import { useSpeechRecognition } from "@/composables/useSpeechRecognition.js";

describe("speech recognition lifecycle", () => {
  let wrapper: VueWrapper | undefined;

  afterEach(async () => {
    wrapper?.unmount();
    wrapper = undefined;
    await flushPromises();
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it("releases microphone resources when audio initialization fails", async () => {
    const stopTrack = vi.fn();
    const closeContext = vi.fn().mockResolvedValue(undefined);
    const getUserMedia = vi.fn().mockResolvedValue({
      getTracks: () => [{ stop: stopTrack }],
    });
    class FailingAudioContext {
      state = "running";
      audioWorklet = {
        addModule: vi.fn().mockRejectedValue(new Error("worklet unavailable")),
      };
      close = closeContext;
    }

    vi.stubGlobal("navigator", { mediaDevices: { getUserMedia } });
    vi.stubGlobal("AudioContext", FailingAudioContext);

    let speechRecognition: ReturnType<typeof useSpeechRecognition> | undefined;
    wrapper = mount(
      defineComponent({
        setup() {
          speechRecognition = useSpeechRecognition();
          return () => h("div");
        },
      })
    );

    await expect(speechRecognition!.initAudio()).resolves.toBe(false);

    expect(stopTrack).toHaveBeenCalledOnce();
    expect(closeContext).toHaveBeenCalledOnce();
  });

  it("closes an unfinished warmup connection when its component unmounts", async () => {
    const close = vi.fn();
    class PendingWebSocket {
      static OPEN = 1;
      static CLOSED = 3;
      readyState = 0;
      close = close;
    }
    vi.stubGlobal("WebSocket", PendingWebSocket);

    let speechRecognition: ReturnType<typeof useSpeechRecognition> | undefined;
    wrapper = mount(
      defineComponent({
        setup() {
          speechRecognition = useSpeechRecognition();
          return () => h("div");
        },
      })
    );

    const warmup = speechRecognition!.warmup();
    wrapper.unmount();
    wrapper = undefined;
    await flushPromises();

    expect(close).toHaveBeenCalledOnce();
    await expect(warmup).resolves.toBeUndefined();
  });

  it("releases the voice socket and microphone when the server rejects a flush", async () => {
    const sockets: ControlledWebSocket[] = [];
    const stopTrack = vi.fn();
    const closeContext = vi.fn().mockResolvedValue(undefined);

    class ControlledWebSocket {
      static OPEN = 1;
      static CLOSED = 3;
      readyState = 0;
      sent: unknown[] = [];
      onopen?: () => void;
      onmessage?: (event: { data: string }) => void;
      onclose?: (event: { code: number; reason: string; wasClean: boolean }) => void;
      close = vi.fn(() => {
        this.readyState = ControlledWebSocket.CLOSED;
      });

      constructor() {
        sockets.push(this);
      }

      send(value: unknown) {
        this.sent.push(value);
      }

      open() {
        this.readyState = ControlledWebSocket.OPEN;
        this.onopen?.();
      }

      receive(value: Record<string, unknown>) {
        this.onmessage?.({ data: JSON.stringify(value) });
      }
    }

    class WorkingAudioContext {
      state = "running";
      audioWorklet = { addModule: vi.fn().mockResolvedValue(undefined) };
      createMediaStreamSource = vi.fn(() => ({ connect: vi.fn() }));
      createAnalyser = vi.fn(() => ({
        frequencyBinCount: 1,
        getByteFrequencyData: vi.fn(),
        disconnect: vi.fn(),
      }));
      close = closeContext;
    }

    class AudioWorkletNodeStub {
      port = { onmessage: undefined };
      disconnect = vi.fn();
    }

    vi.stubGlobal("navigator", {
      mediaDevices: {
        getUserMedia: vi.fn().mockResolvedValue({
          getTracks: () => [{ stop: stopTrack }],
        }),
      },
    });
    vi.stubGlobal("AudioContext", WorkingAudioContext);
    vi.stubGlobal("AudioWorkletNode", AudioWorkletNodeStub);
    vi.stubGlobal("WebSocket", ControlledWebSocket);
    vi.stubGlobal("requestAnimationFrame", vi.fn(() => 1));
    vi.stubGlobal("cancelAnimationFrame", vi.fn());

    let speechRecognition: ReturnType<typeof useSpeechRecognition> | undefined;
    wrapper = mount(
      defineComponent({
        setup() {
          speechRecognition = useSpeechRecognition();
          return () => h("div");
        },
      })
    );

    await expect(speechRecognition!.initAudio()).resolves.toBe(true);
    sockets[0].open();

    const start = speechRecognition!.startAudio("turn-1");
    await flushPromises();
    sockets[0].receive({
      action: "audio-start-ack",
      turnId: "turn-1",
      accepted: true,
    });
    await expect(start).resolves.toBe(true);

    const finish = speechRecognition!.finishAudioTurn("turn-1");
    await flushPromises();
    sockets[0].receive({
      action: "audio-flush-ack",
      turnId: "turn-1",
      accepted: false,
      message: "flush failed",
    });
    await expect(finish).rejects.toThrow("flush failed");

    expect(sockets[0].close).toHaveBeenCalledOnce();
    expect(stopTrack).toHaveBeenCalledOnce();
    expect(closeContext).toHaveBeenCalledOnce();
    expect(speechRecognition!.audioReady.value).toBe(false);
  });

  it("recovers the same recording turn across 20 consecutive voice socket closes", async () => {
    vi.useFakeTimers();
    const sockets: RecoverableWebSocket[] = [];
    const onConnectionLost = vi.fn();

    class RecoverableWebSocket {
      static OPEN = 1;
      static CLOSED = 3;
      readyState = 0;
      sent: unknown[] = [];
      onopen?: () => void;
      onmessage?: (event: { data: string }) => void;
      onclose?: (event: { code: number; reason: string; wasClean: boolean }) => void;
      close = vi.fn(() => {
        this.readyState = RecoverableWebSocket.CLOSED;
      });

      constructor() {
        Object.defineProperty(this, "__v_skip", { value: true });
        sockets.push(this);
      }

      send(value: unknown) {
        this.sent.push(value);
      }

      open() {
        this.readyState = RecoverableWebSocket.OPEN;
        this.onopen?.();
      }

      receive(value: Record<string, unknown>) {
        this.onmessage?.({ data: JSON.stringify(value) });
      }

      disconnect() {
        this.readyState = RecoverableWebSocket.CLOSED;
        this.onclose?.({ code: 1006, reason: "network lost", wasClean: false });
      }
    }

    class WorkingAudioContext {
      state = "running";
      audioWorklet = { addModule: vi.fn().mockResolvedValue(undefined) };
      createMediaStreamSource = vi.fn(() => ({ connect: vi.fn() }));
      createAnalyser = vi.fn(() => ({
        frequencyBinCount: 1,
        getByteFrequencyData: vi.fn(),
        disconnect: vi.fn(),
      }));
      close = vi.fn().mockResolvedValue(undefined);
    }

    class AudioWorkletNodeStub {
      port = { onmessage: undefined };
      disconnect = vi.fn();
    }

    vi.stubGlobal("navigator", {
      mediaDevices: {
        getUserMedia: vi.fn().mockResolvedValue({
          getTracks: () => [{ stop: vi.fn() }],
        }),
      },
    });
    vi.stubGlobal("AudioContext", WorkingAudioContext);
    vi.stubGlobal("AudioWorkletNode", AudioWorkletNodeStub);
    vi.stubGlobal("WebSocket", RecoverableWebSocket);
    vi.stubGlobal("requestAnimationFrame", vi.fn(() => 1));
    vi.stubGlobal("cancelAnimationFrame", vi.fn());

    let speechRecognition: ReturnType<typeof useSpeechRecognition> | undefined;
    wrapper = mount(
      defineComponent({
        setup() {
          speechRecognition = useSpeechRecognition({ onConnectionLost });
          return () => h("div");
        },
      })
    );

    await expect(speechRecognition!.initAudio()).resolves.toBe(true);
    sockets[0].open();
    const start = speechRecognition!.startAudio("turn-1");
    await flushPromises();
    sockets[0].receive({
      action: "audio-start-ack",
      turnId: "turn-1",
      accepted: true,
    });
    await expect(start).resolves.toBe(true);

    for (let cycle = 0; cycle < 20; cycle += 1) {
      sockets[cycle].disconnect();
      expect(onConnectionLost).not.toHaveBeenCalled();

      await vi.advanceTimersByTimeAsync(1_000);
      await flushPromises();
      expect(sockets).toHaveLength(cycle + 2);
      sockets[cycle + 1].open();
      await vi.advanceTimersByTimeAsync(500);
      await flushPromises();
      sockets[cycle + 1].receive({
        action: "audio-start-ack",
        turnId: "turn-1",
        accepted: true,
      });
      await flushPromises();
    }

    const finish = speechRecognition!.finishAudioTurn("turn-1");
    await flushPromises();
    sockets[sockets.length - 1].receive({
      action: "audio-flush-ack",
      turnId: "turn-1",
      accepted: true,
    });
    await expect(finish).resolves.toBe(true);
    expect(onConnectionLost).not.toHaveBeenCalled();
  });
});
