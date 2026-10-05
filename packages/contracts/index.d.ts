export const CAPABILITY_IDS: Readonly<{
  TRANSCRIBE: "speech.transcribe";
  SYNTHESIZE: "speech.synthesize";
  EXTRACT_AUDIO: "video.extractAudio";
}>;

export type CapabilityId = (typeof CAPABILITY_IDS)[keyof typeof CAPABILITY_IDS];
export type AudioInputFormat = "mp3" | "wav" | "aac" | "flac" | "ogg" | "m4a" | "opus" | "wma";
export type VideoInputFormat = "mp4" | "mkv" | "mov" | "avi" | "webm" | "mpeg" | "mpg" | "ts" | "m4v";
export type TextInputFormat = "text";
export type TranscriptOutputFormat = "txt" | "srt" | "vtt" | "json";
export type AudioOutputFormat = "mp3" | "wav" | "aac" | "m4a" | "flac" | "ogg" | "opus";
export type TranscriptionLanguage = "auto" | "pt" | "en" | "es" | "fr" | "de" | "it";
export type TranscriptionModel = "base" | "small";
export type TtsVoice = "pt-BR-maria";
export type AudioBitrateKbps = 64 | 96 | 128 | 160 | 192 | 256 | 320;
export type AudioSampleRateHz = 16000 | 22050 | 24000 | 44100 | 48000;
export type AudioChannelCount = 1 | 2;

export const INPUT_FORMATS: Readonly<{
  audio: readonly AudioInputFormat[];
  video: readonly VideoInputFormat[];
  text: readonly TextInputFormat[];
}>;

export const OUTPUT_FORMATS: Readonly<{
  transcript: readonly TranscriptOutputFormat[];
  audio: readonly AudioOutputFormat[];
}>;

export const TRANSCRIPTION_LANGUAGES: readonly TranscriptionLanguage[];
export const TRANSCRIPTION_MODELS: readonly TranscriptionModel[];
export const TTS_VOICES: readonly TtsVoice[];
export const AUDIO_BITRATES_KBPS: readonly AudioBitrateKbps[];
export const AUDIO_SAMPLE_RATES_HZ: readonly AudioSampleRateHz[];
export const AUDIO_CHANNEL_COUNTS: readonly AudioChannelCount[];

export interface TranscriptionOptions {
  outputFormat: TranscriptOutputFormat;
  language: TranscriptionLanguage;
  model: TranscriptionModel;
  useGpu: boolean;
  vad: boolean;
}

export interface TextToSpeechOptions {
  outputFormat: AudioOutputFormat;
  voice: TtsVoice;
  rate: number;
  volume: number;
  audioBitrateKbps?: AudioBitrateKbps;
  sampleRateHz?: AudioSampleRateHz;
  channels?: AudioChannelCount;
}

export interface ExtractAudioOptions {
  outputFormat: AudioOutputFormat;
  audioStreamIndex: number;
  audioBitrateKbps?: AudioBitrateKbps;
  sampleRateHz?: AudioSampleRateHz;
  channels?: AudioChannelCount;
  normalize: boolean;
}

export interface CapabilityOptionsMap {
  "speech.transcribe": TranscriptionOptions;
  "speech.synthesize": TextToSpeechOptions;
  "video.extractAudio": ExtractAudioOptions;
}

export interface CapabilityInputFormatMap {
  "speech.transcribe": AudioInputFormat | VideoInputFormat;
  "speech.synthesize": TextInputFormat;
  "video.extractAudio": VideoInputFormat;
}

export type EnumOptionDefinition = Readonly<{
  type: "enum";
  values: readonly (string | number)[];
  default?: string | number;
  optional: boolean;
}>;

export type IntegerOptionDefinition = Readonly<{
  type: "integer";
  minimum: number;
  maximum: number;
  default?: number;
  optional: boolean;
}>;

export type BooleanOptionDefinition = Readonly<{
  type: "boolean";
  default: boolean;
  optional: false;
}>;

export type OptionDefinition = EnumOptionDefinition | IntegerOptionDefinition | BooleanOptionDefinition;

export interface CapabilityDefinition {
  readonly id: CapabilityId;
  readonly inputKinds: readonly ("audio" | "video" | "text")[];
  readonly inputFormats: readonly string[];
  readonly outputFormats: readonly string[];
  readonly options: Readonly<Record<string, OptionDefinition>>;
}

export const CAPABILITY_REGISTRY: Readonly<Record<CapabilityId, CapabilityDefinition>>;
export const CAPABILITIES: readonly CapabilityDefinition[];

export const VALIDATION_CODES: Readonly<{
  INVALID_SELECTION: "invalid_selection";
  UNKNOWN_CAPABILITY: "unknown_capability";
  INVALID_INPUT_FORMAT: "invalid_input_format";
  INVALID_OPTIONS: "invalid_options";
  UNKNOWN_OPTION: "unknown_option";
  ACCESSOR_NOT_ALLOWED: "accessor_not_allowed";
  INVALID_TYPE: "invalid_type";
  INVALID_VALUE: "invalid_value";
  OUT_OF_RANGE: "out_of_range";
  INVALID_COMBINATION: "invalid_combination";
}>;

export type ValidationCode = (typeof VALIDATION_CODES)[keyof typeof VALIDATION_CODES];

export interface ValidationIssue {
  readonly code: ValidationCode;
  readonly path: string;
  readonly message: string;
  readonly allowed?: readonly unknown[];
}

export type ValidationResult<T> =
  | Readonly<{ ok: true; value: Readonly<T> }>
  | Readonly<{ ok: false; issues: readonly ValidationIssue[] }>;

export type CapabilitySelection = {
  [C in CapabilityId]: Readonly<{
    capability: C;
    inputFormat: CapabilityInputFormatMap[C];
    options: Readonly<CapabilityOptionsMap[C]>;
  }>;
}[CapabilityId];

export function isCapabilityId(value: unknown): value is CapabilityId;
export function getCapability(value: unknown): CapabilityDefinition | undefined;
export function isSupportedInputFormat<C extends CapabilityId>(
  capability: C,
  format: unknown,
): format is CapabilityInputFormatMap[C];
export function isSupportedOutputFormat(capability: CapabilityId, format: unknown): boolean;
export function validateOperationOptions<C extends CapabilityId>(
  capability: C,
  options?: unknown,
): ValidationResult<CapabilityOptionsMap[C]>;
export function validateOperationOptions(
  capability: unknown,
  options?: unknown,
): ValidationResult<Record<string, unknown>>;
export function validateCapabilitySelection(value: unknown): ValidationResult<CapabilitySelection>;
export const validateOperationSelection: typeof validateCapabilitySelection;
