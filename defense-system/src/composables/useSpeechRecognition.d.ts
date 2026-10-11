export type SpeechConnectionLostEvent = {
  code: number;
  reason: string;
  wasClean: boolean;
};

export type SpeechRecognitionOptions = {
  onConnectionLost?: (event: SpeechConnectionLostEvent) => boolean | void;
};

export function useSpeechRecognition(
  options?: SpeechRecognitionOptions
): Record<string, any>;
