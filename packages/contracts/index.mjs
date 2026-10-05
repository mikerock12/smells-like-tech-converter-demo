/**
 * Shared, JSON-safe contracts for the media operations supported by the MVP.
 *
 * This module intentionally has no runtime dependencies. Values accepted here
 * are identifiers and settings only: workers must map model/voice identifiers
 * to trusted local paths and must never treat these values as command fragments.
 */

const hasOwn = (value, key) => Object.prototype.hasOwnProperty.call(value, key);

function deepFreeze(value) {
  if (!value || typeof value !== "object" || Object.isFrozen(value)) {
    return value;
  }

  for (const child of Object.values(value)) {
    deepFreeze(child);
  }

  return Object.freeze(value);
}

const enumOption = (values, defaultValue, optional = false) => {
  const definition = { type: "enum", values, optional };
  if (defaultValue !== undefined) definition.default = defaultValue;
  return definition;
};

const integerOption = (minimum, maximum, defaultValue, optional = false) => {
  const definition = { type: "integer", minimum, maximum, optional };
  if (defaultValue !== undefined) definition.default = defaultValue;
  return definition;
};

const booleanOption = (defaultValue) => ({
  type: "boolean",
  default: defaultValue,
  optional: false,
});

export const CAPABILITY_IDS = deepFreeze({
  TRANSCRIBE: "speech.transcribe",
  SYNTHESIZE: "speech.synthesize",
  EXTRACT_AUDIO: "video.extractAudio",
});

export const INPUT_FORMATS = deepFreeze({
  audio: ["mp3", "wav", "aac", "flac", "ogg", "m4a", "opus", "wma"],
  video: ["mp4", "mkv", "mov", "avi", "webm", "mpeg", "mpg", "ts", "m4v"],
  text: ["text"],
});

export const OUTPUT_FORMATS = deepFreeze({
  transcript: ["txt", "srt", "vtt", "json"],
  audio: ["mp3", "wav", "aac", "m4a", "flac", "ogg", "opus"],
});

export const TRANSCRIPTION_LANGUAGES = deepFreeze([
  "auto",
  "pt",
  "en",
  "es",
  "fr",
  "de",
  "it",
]);

export const TRANSCRIPTION_MODELS = deepFreeze(["base", "small"]);
export const TTS_VOICES = deepFreeze(["pt-BR-maria"]);
export const AUDIO_BITRATES_KBPS = deepFreeze([64, 96, 128, 160, 192, 256, 320]);
export const AUDIO_SAMPLE_RATES_HZ = deepFreeze([16000, 22050, 24000, 44100, 48000]);
export const AUDIO_CHANNEL_COUNTS = deepFreeze([1, 2]);

const transcriptionInputFormats = [
  ...INPUT_FORMATS.audio,
  ...INPUT_FORMATS.video,
];

export const CAPABILITY_REGISTRY = deepFreeze({
  [CAPABILITY_IDS.TRANSCRIBE]: {
    id: CAPABILITY_IDS.TRANSCRIBE,
    inputKinds: ["audio", "video"],
    inputFormats: transcriptionInputFormats,
    outputFormats: OUTPUT_FORMATS.transcript,
    options: {
      outputFormat: enumOption(OUTPUT_FORMATS.transcript, "txt"),
      language: enumOption(TRANSCRIPTION_LANGUAGES, "auto"),
      model: enumOption(TRANSCRIPTION_MODELS, "small"),
      useGpu: booleanOption(false),
      vad: booleanOption(false),
    },
  },
  [CAPABILITY_IDS.SYNTHESIZE]: {
    id: CAPABILITY_IDS.SYNTHESIZE,
    inputKinds: ["text"],
    inputFormats: INPUT_FORMATS.text,
    outputFormats: OUTPUT_FORMATS.audio,
    options: {
      outputFormat: enumOption(OUTPUT_FORMATS.audio, "mp3"),
      voice: enumOption(TTS_VOICES, "pt-BR-maria"),
      rate: integerOption(-10, 10, 0),
      volume: integerOption(0, 100, 100),
      audioBitrateKbps: enumOption(AUDIO_BITRATES_KBPS, undefined, true),
      sampleRateHz: enumOption(AUDIO_SAMPLE_RATES_HZ, undefined, true),
      channels: enumOption(AUDIO_CHANNEL_COUNTS, undefined, true),
    },
  },
  [CAPABILITY_IDS.EXTRACT_AUDIO]: {
    id: CAPABILITY_IDS.EXTRACT_AUDIO,
    inputKinds: ["video"],
    inputFormats: INPUT_FORMATS.video,
    outputFormats: OUTPUT_FORMATS.audio,
    options: {
      outputFormat: enumOption(OUTPUT_FORMATS.audio, "mp3"),
      audioStreamIndex: integerOption(0, 15, 0),
      audioBitrateKbps: enumOption(AUDIO_BITRATES_KBPS, undefined, true),
      sampleRateHz: enumOption(AUDIO_SAMPLE_RATES_HZ, undefined, true),
      channels: enumOption(AUDIO_CHANNEL_COUNTS, undefined, true),
      normalize: booleanOption(false),
    },
  },
});

export const CAPABILITIES = deepFreeze(Object.values(CAPABILITY_REGISTRY));

const VALIDATION_CODES = deepFreeze({
  INVALID_SELECTION: "invalid_selection",
  UNKNOWN_CAPABILITY: "unknown_capability",
  INVALID_INPUT_FORMAT: "invalid_input_format",
  INVALID_OPTIONS: "invalid_options",
  UNKNOWN_OPTION: "unknown_option",
  ACCESSOR_NOT_ALLOWED: "accessor_not_allowed",
  INVALID_TYPE: "invalid_type",
  INVALID_VALUE: "invalid_value",
  OUT_OF_RANGE: "out_of_range",
  INVALID_COMBINATION: "invalid_combination",
});

export { VALIDATION_CODES };

function issue(code, path, message, allowed) {
  const result = { code, path, message };
  if (allowed !== undefined) result.allowed = allowed;
  return result;
}

function isPlainRecord(value) {
  if (value === null || typeof value !== "object" || Array.isArray(value)) {
    return false;
  }

  const prototype = Object.getPrototypeOf(value);
  return prototype === Object.prototype || prototype === null;
}

function readDataProperties(value) {
  const descriptors = Object.getOwnPropertyDescriptors(value);
  const accessors = Object.entries(descriptors)
    .filter(([, descriptor]) => !("value" in descriptor))
    .map(([key]) => key);

  return { descriptors, accessors };
}

export function isCapabilityId(value) {
  return typeof value === "string" && hasOwn(CAPABILITY_REGISTRY, value);
}

export function getCapability(value) {
  return isCapabilityId(value) ? CAPABILITY_REGISTRY[value] : undefined;
}

export function isSupportedInputFormat(capability, format) {
  const definition = getCapability(capability);
  return Boolean(
    definition &&
      typeof format === "string" &&
      definition.inputFormats.includes(format),
  );
}

export function isSupportedOutputFormat(capability, format) {
  const definition = getCapability(capability);
  return Boolean(
    definition &&
      typeof format === "string" &&
      definition.outputFormats.includes(format),
  );
}

function validateOptionValue(path, value, definition) {
  if (definition.type === "boolean") {
    return typeof value === "boolean"
      ? undefined
      : issue(
          VALIDATION_CODES.INVALID_TYPE,
          path,
          "Expected a boolean value.",
        );
  }

  if (definition.type === "integer") {
    if (!Number.isInteger(value)) {
      return issue(
        VALIDATION_CODES.INVALID_TYPE,
        path,
        "Expected an integer value.",
      );
    }

    if (value < definition.minimum || value > definition.maximum) {
      return issue(
        VALIDATION_CODES.OUT_OF_RANGE,
        path,
        `Expected an integer from ${definition.minimum} to ${definition.maximum}.`,
        [definition.minimum, definition.maximum],
      );
    }

    return undefined;
  }

  if (!definition.values.includes(value)) {
    return issue(
      VALIDATION_CODES.INVALID_VALUE,
      path,
      "Value is not in the allowlist.",
      definition.values,
    );
  }

  return undefined;
}

function validateAudioCombinations(options, suppliedKeys) {
  const issues = [];
  const losslessFormats = ["wav", "flac"];

  if (
    suppliedKeys.has("audioBitrateKbps") &&
    losslessFormats.includes(options.outputFormat)
  ) {
    issues.push(
      issue(
        VALIDATION_CODES.INVALID_COMBINATION,
        "options.audioBitrateKbps",
        `Audio bitrate is not configurable for ${options.outputFormat} output.`,
      ),
    );
  }

  return issues;
}

export function validateOperationOptions(capability, options = {}) {
  const definition = getCapability(capability);
  if (!definition) {
    return {
      ok: false,
      issues: [
        issue(
          VALIDATION_CODES.UNKNOWN_CAPABILITY,
          "capability",
          "Capability is not registered.",
          Object.keys(CAPABILITY_REGISTRY),
        ),
      ],
    };
  }

  if (!isPlainRecord(options)) {
    return {
      ok: false,
      issues: [
        issue(
          VALIDATION_CODES.INVALID_OPTIONS,
          "options",
          "Options must be a plain object.",
        ),
      ],
    };
  }

  const { descriptors, accessors } = readDataProperties(options);
  const issues = accessors.map((key) =>
    issue(
      VALIDATION_CODES.ACCESSOR_NOT_ALLOWED,
      `options.${key}`,
      "Accessor properties are not accepted in contract input.",
    ),
  );
  const suppliedKeys = new Set(Object.keys(descriptors));

  for (const key of suppliedKeys) {
    if (!hasOwn(definition.options, key)) {
      issues.push(
        issue(
          VALIDATION_CODES.UNKNOWN_OPTION,
          `options.${key}`,
          "Option is not registered for this capability.",
          Object.keys(definition.options),
        ),
      );
    }
  }

  const normalized = {};
  for (const [key, optionDefinition] of Object.entries(definition.options)) {
    const descriptor = descriptors[key];

    if (descriptor && "value" in descriptor) {
      const optionIssue = validateOptionValue(
        `options.${key}`,
        descriptor.value,
        optionDefinition,
      );
      if (optionIssue) issues.push(optionIssue);
      else normalized[key] = descriptor.value;
      continue;
    }

    if (hasOwn(optionDefinition, "default")) {
      normalized[key] = optionDefinition.default;
    }
  }

  if (
    capability === CAPABILITY_IDS.SYNTHESIZE ||
    capability === CAPABILITY_IDS.EXTRACT_AUDIO
  ) {
    issues.push(...validateAudioCombinations(normalized, suppliedKeys));
  }

  return issues.length > 0
    ? { ok: false, issues }
    : { ok: true, value: Object.freeze(normalized) };
}

export function validateCapabilitySelection(value) {
  if (!isPlainRecord(value)) {
    return {
      ok: false,
      issues: [
        issue(
          VALIDATION_CODES.INVALID_SELECTION,
          "selection",
          "Selection must be a plain object.",
        ),
      ],
    };
  }

  const { descriptors, accessors } = readDataProperties(value);
  const issues = accessors.map((key) =>
    issue(
      VALIDATION_CODES.ACCESSOR_NOT_ALLOWED,
      `selection.${key}`,
      "Accessor properties are not accepted in contract input.",
    ),
  );
  const allowedKeys = ["capability", "inputFormat", "options"];

  for (const key of Object.keys(descriptors)) {
    if (!allowedKeys.includes(key)) {
      issues.push(
        issue(
          VALIDATION_CODES.UNKNOWN_OPTION,
          `selection.${key}`,
          "Selection property is not registered.",
          allowedKeys,
        ),
      );
    }
  }

  const capability = descriptors.capability?.value;
  const inputFormat = descriptors.inputFormat?.value;
  const options = descriptors.options?.value ?? {};

  let optionResult;

  if (!isCapabilityId(capability)) {
    issues.push(
      issue(
        VALIDATION_CODES.UNKNOWN_CAPABILITY,
        "capability",
        "Capability is not registered.",
        Object.keys(CAPABILITY_REGISTRY),
      ),
    );
  } else if (!isSupportedInputFormat(capability, inputFormat)) {
    issues.push(
      issue(
        VALIDATION_CODES.INVALID_INPUT_FORMAT,
        "inputFormat",
        "Input format is not allowed for this capability.",
        CAPABILITY_REGISTRY[capability].inputFormats,
      ),
    );
  }

  if (isCapabilityId(capability)) {
    optionResult = validateOperationOptions(capability, options);
    if (!optionResult.ok) issues.push(...optionResult.issues);
  }

  return issues.length > 0
    ? { ok: false, issues }
    : {
        ok: true,
        value: Object.freeze({
          capability,
          inputFormat,
          options: optionResult.value,
        }),
      };
}

export const validateOperationSelection = validateCapabilitySelection;
