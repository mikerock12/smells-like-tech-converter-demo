export type FormatKind = "image" | "audio" | "video" | "pdf" | "legenda" | "texto" | "documento";

export type FormatId =
  | "jpg"
  | "png"
  | "webp"
  | "avif"
  | "gif"
  | "bmp"
  | "ico"
  | "tiff"
  | "heic"
  | "mp3"
  | "wav"
  | "m4a"
  | "ogg"
  | "flac"
  | "mp4"
  | "webm"
  | "mkv"
  | "mov"
  | "avi"
  | "pdf"
  | "srt"
  | "vtt"
  | "txt"
  | "md"
  | "docx"
  | "html";

export interface FormatDescriptor {
  readonly id: FormatId;
  readonly label: string;
  readonly kind: FormatKind;
  readonly mime: string;
  readonly extensions: readonly string[];
  readonly canDecodeLocally: boolean;
  readonly canEncodeLocally: boolean;
  readonly supportsAlpha: boolean;
  readonly supportsQuality: boolean;
}

export type ResizeMode = "none" | "width" | "height" | "percent" | "exact";
export type FitMode = "cover" | "contain" | "stretch";

export interface ImageOptions {
  format: FormatId;
  quality: number;
  resizeMode: ResizeMode;
  width: number | null;
  height: number | null;
  percent: number | null;
  fit: FitMode;
  background: string;
  keepTransparency: boolean;
}

export interface AudioOptions {
  format: FormatId;
  /** kbps, dentro de AUDIO_BITRATES. */
  bitrate: number;
}

export type ValidationResult =
  | { ok: true; options: Readonly<ImageOptions> }
  | { ok: false; code: string; message: string };

export type AudioValidationResult =
  | { ok: true; options: Readonly<AudioOptions> }
  | { ok: false; code: string; message: string };

export declare const FORMATS: readonly FormatDescriptor[];
export declare const FORMAT_REGISTRY: Readonly<Record<FormatId, FormatDescriptor>>;
export declare const IMAGE_OUTPUT_FORMATS: readonly FormatId[];
export declare const IMAGE_INPUT_FORMATS: readonly FormatId[];
export declare const AUDIO_OUTPUT_FORMATS: readonly FormatId[];
export declare const AUDIO_INPUT_FORMATS: readonly FormatId[];
export declare const AUDIO_BITRATES: readonly number[];
export declare const MP3_SAMPLE_RATES: readonly number[];
export declare const RESIZE_MODES: readonly ResizeMode[];
export declare const FIT_MODES: readonly FitMode[];

export declare const LIMITS: Readonly<{
  minDimension: number;
  maxDimension: number;
  minPercent: number;
  maxPercent: number;
  minQuality: number;
  maxQuality: number;
  warnBytes: number;
}>;

export declare const ERRORS: Readonly<Record<string, string>>;
export declare const DEFAULT_IMAGE_OPTIONS: Readonly<ImageOptions>;
export declare const DEFAULT_AUDIO_OPTIONS: Readonly<AudioOptions>;

export declare function detectFormat(bytes: Uint8Array | ArrayBuffer): FormatId | null;
export declare function formatFromFileName(fileName: string): FormatId | null;
export declare function getFormat(id: string): FormatDescriptor | null;
export declare function isImageFormat(id: string): boolean;
export declare function isAudioFormat(id: string): boolean;
export declare function isVideoFormat(id: string): boolean;
export declare function isPdfFormat(id: string): boolean;
export declare function isSubtitleFormat(id: string): boolean;
export declare function isTextFormat(id: string): boolean;
export declare function kindOf(id: string): FormatKind | null;
export declare const VIDEO_OUTPUT_FORMATS: readonly FormatId[];
export declare const VIDEO_INPUT_FORMATS: readonly FormatId[];
export declare function sampleRateDoArquivo(
  bytes: Uint8Array | ArrayBuffer | null | undefined,
  formato: FormatId | null,
): number | null;
export declare function sampleRateParaMp3(taxaDeOrigem: number | null | undefined): number | null;
export declare function suggestOutputFormat(inputFormat: FormatId | null): FormatId;
export declare function validateImageOptions(input: Partial<ImageOptions> | null | undefined): ValidationResult;
export declare function validateAudioOptions(
  input: Partial<AudioOptions> | null | undefined,
): AudioValidationResult;
export declare function resolveTargetSize(
  sourceWidth: number,
  sourceHeight: number,
  options: Pick<ImageOptions, "resizeMode" | "width" | "height" | "percent">,
): { width: number; height: number } | null;
export declare function outputFileName(inputName: string, outputFormat: FormatId): string;
export declare function formatBytes(bytes: number): string;
export declare function savingsPercent(inputBytes: number, outputBytes: number): number;
