import { PreparedAudioJournal, type AnswerScope } from './localAudioJournal';
import { PreparedAnswerCapture } from './answerCapture';
import { requestPreparedDevice, DeviceAccessError } from './deviceAccess';

type Code = 'INVALID' | 'CANCELLED' | 'CAPTURE_FAILED' | 'STORAGE_FAILED' | 'DECODE_FAILED';
const messages: Record<Code, string> = {
  INVALID: '预检身份或考核参数无效，请重新核验身份',
  CANCELLED: '已取消试音',
  CAPTURE_FAILED: '试音未完整采集，请检查麦克风后重试',
  STORAGE_FAILED: '本机保存或读取检查失败，请检查浏览器存储后重试',
  DECODE_FAILED: '试音无法完整播放，请检查设备及浏览器后重试',
};
export class TrialError extends Error {
  constructor(public readonly code: Code) { super(messages[code]); this.name = 'TrialError'; }
}
export type PreflightIdentity = { origin: string; deploymentId: string; studentId: number; assessmentId: number };
export type TrialProgress = { phase: 'REQUESTING' | 'RECORDING' | 'CHECKING'; secondsRemaining: number; savedChunks: number };
export type TrialInspection = { durationSeconds: number; signalDetected: boolean; peak: number };
export type TrialResult = TrialInspection & { blob: Blob };
type Capture = Pick<PreparedAnswerCapture, 'stop' | 'interrupt' | 'snapshot'>;
export type TrialEnvironment = {
  factory?: IDBFactory;
  acquire?: (signal: AbortSignal) => Promise<MediaStream>;
  capture?: (input: Parameters<typeof PreparedAnswerCapture.start>[0]) => Promise<Capture>;
  inspect?: (blob: Blob) => Promise<TrialInspection>;
};

function release(stream?: MediaStream) {
  try { for (const track of stream?.getTracks() ?? []) { try { track.stop(); } catch { /* never expose device labels */ } } }
  catch { /* native device failed */ }
}
function bounded<T>(promise: Promise<T>, error: Code, signal?: AbortSignal, milliseconds = 5000): Promise<T> {
  return new Promise((resolve, reject) => {
    let settled = false;
    const cleanup = () => { clearTimeout(timer); signal?.removeEventListener('abort', cancel); };
    const fail = (code: Code) => { if (settled) return; settled = true; cleanup(); reject(new TrialError(code)); };
    const cancel = () => fail('CANCELLED');
    const timer = setTimeout(() => fail(error), milliseconds);
    signal?.addEventListener('abort', cancel, { once: true });
    if (signal?.aborted) cancel();
    promise.then(value => { if (!settled) { settled = true; cleanup(); resolve(value); } }, cause => {
      if (!settled) { settled = true; cleanup(); reject(cause); }
    });
  });
}

/** Validate a complete trial container. Sound detection is NOT transcription or proof of human speech. */
export async function inspectTrialAudio(blob: Blob, create: () => AudioContext = () => new AudioContext()): Promise<TrialInspection> {
  const allowed = new Set(['audio/webm;codecs=opus', 'audio/webm', 'audio/ogg;codecs=opus', 'audio/ogg', 'audio/mp4']);
  if (!(blob instanceof Blob) || blob.size < 1 || blob.size > 1024 * 1024 || !allowed.has(blob.type)) throw new TrialError('DECODE_FAILED');
  let decoder: AudioContext | undefined;
  try {
    decoder = create();
    const bytes = await bounded(blob.arrayBuffer(), 'DECODE_FAILED');
    const audio = await bounded(decoder.decodeAudioData(bytes), 'DECODE_FAILED');
    if (!Number.isFinite(audio.duration) || audio.duration < 1 || audio.duration > 12 ||
        !Number.isFinite(audio.sampleRate) || audio.sampleRate < 8000 || audio.sampleRate > 96000 ||
        !Number.isInteger(audio.numberOfChannels) || audio.numberOfChannels < 1 || audio.numberOfChannels > 2) throw new TrialError('DECODE_FAILED');
    let peak = 0;
    for (let channel = 0; channel < audio.numberOfChannels; channel++) {
      const samples = audio.getChannelData(channel);
      if (!samples.length || samples.length > 12 * 96000) throw new TrialError('DECODE_FAILED');
      for (let index = 0; index < samples.length; index++) {
        const value = samples[index];
        if (!Number.isFinite(value)) throw new TrialError('DECODE_FAILED');
        peak = Math.max(peak, Math.abs(value));
      }
    }
    return { durationSeconds: audio.duration, signalDetected: peak >= 0.01, peak };
  } catch { throw new TrialError('DECODE_FAILED'); }
  finally { try { void decoder?.close().catch(() => {}); } catch { /* unavailable decoder */ } }
}

/** Disposable, local-only trial. It grants no exam attempt and never touches the formal-answer database. */
export async function runMicrophoneTrial(identity: PreflightIdentity, signal: AbortSignal,
    onProgress: (value: TrialProgress) => void, environment: TrialEnvironment = {}): Promise<TrialResult> {
  if (!identity || !Number.isSafeInteger(identity.assessmentId) || identity.assessmentId < 1 || identity.assessmentId > 2147483647) throw new TrialError('INVALID');
  if (signal.aborted) throw new TrialError('CANCELLED');
  let journal: PreparedAudioJournal;
  try { journal = new PreparedAudioJournal(identity, environment.factory ?? globalThis.indexedDB, 'preflight'); }
  catch (error) { throw new TrialError((error as { code?: string }).code === 'INVALID' ? 'INVALID' : 'STORAGE_FAILED'); }
  const scope: AnswerScope = { assessmentId: identity.assessmentId, attemptId: crypto.randomUUID(), answerId: crypto.randomUUID() };
  let stream: MediaStream | undefined, capture: Capture | undefined;
  const cancel = () => { release(stream); if (capture) void capture.interrupt('CAPTURE_ERROR').catch(() => {}); };
  const notify = (phase: TrialProgress['phase'], secondsRemaining = 0) => {
    try { onProgress({ phase, secondsRemaining, savedChunks: capture?.snapshot().savedChunks ?? 0 }); } catch { /* UI cannot interrupt durable writes */ }
  };
  signal.addEventListener('abort', cancel, { once: true });
  try {
    notify('REQUESTING');
    const acquiring = (environment.acquire ?? (s => requestPreparedDevice('microphone', s)))(signal);
    void acquiring.then(value => { if (signal.aborted) release(value); }).catch(() => {});
    stream = await bounded(acquiring, 'CAPTURE_FAILED', signal, 16000);
    if (signal.aborted) throw new TrialError('CANCELLED');
    const starting = (environment.capture ?? (input => PreparedAnswerCapture.start(input)))({ journal, scope, ordinal: 1, stream });
    void starting.then(value => { if (signal.aborted) void value.interrupt('CAPTURE_ERROR').catch(() => {}); }).catch(() => {});
    capture = await bounded(starting, 'CAPTURE_FAILED', signal, 12000);
    if (signal.aborted) throw new TrialError('CANCELLED');
    notify('RECORDING', 6);
    await new Promise<void>((resolve, reject) => {
      const beginning = Date.now();
      const interval = setInterval(() => notify('RECORDING', Math.max(0, Math.ceil((6000 - (Date.now() - beginning)) / 1000))), 250);
      const cleanup = () => { clearInterval(interval); clearTimeout(timer); signal.removeEventListener('abort', aborted); };
      const aborted = () => { cleanup(); reject(new TrialError('CANCELLED')); };
      const timer = setTimeout(() => { cleanup(); resolve(); }, 6000);
      signal.addEventListener('abort', aborted, { once: true });
      if (signal.aborted) aborted();
    });
    notify('CHECKING');
    const ended = await bounded(capture.stop(), 'CAPTURE_FAILED', signal, 12000);
    if (ended.phase !== 'SEALED') throw new TrialError(ended.phase === 'LOCAL_SAVE_FAILED' ? 'STORAGE_FAILED' : 'CAPTURE_FAILED');
    const blob = await bounded(journal.playable(scope, ended.segmentId), 'STORAGE_FAILED', signal);
    const inspection = await bounded((environment.inspect ?? inspectTrialAudio)(blob), 'DECODE_FAILED', signal, 11000);
    if (signal.aborted) throw new TrialError('CANCELLED');
    return { ...inspection, blob };
  } catch (error) {
    if (signal.aborted) throw new TrialError('CANCELLED');
    if (error instanceof TrialError || error instanceof DeviceAccessError) throw error;
    const deviceCode = (error as { name?: string; code?: string })?.code;
    if ((error as { name?: string })?.name === 'DeviceAccessError' && ['PERMISSION_DENIED', 'NOT_FOUND', 'BUSY', 'TIMED_OUT', 'UNSUPPORTED', 'UNAVAILABLE'].includes(deviceCode ?? '')) throw new DeviceAccessError(deviceCode as ConstructorParameters<typeof DeviceAccessError>[0]);
    throw new TrialError((error as { name?: string })?.name === 'LocalAudioError' ? 'STORAGE_FAILED' : 'CAPTURE_FAILED');
  } finally {
    signal.removeEventListener('abort', cancel);
    release(stream);
    // Exact trial scope only; a formal journal rejects this operation. No unconfirmed exam content is deleted.
    try { await bounded(journal.discardPreflight(scope), 'STORAGE_FAILED'); }
    catch { throw new TrialError('STORAGE_FAILED'); }
    finally { journal.close(); }
  }
}
