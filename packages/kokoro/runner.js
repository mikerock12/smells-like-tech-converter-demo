/* Motor dedicado: texto e PCM ficam exclusivamente neste worker do navegador. */
/* global createOfflineTts */
let engine;
let ready;
async function initialize(files) {
  ready = new Promise((resolve, reject) => {
    self.Module = { onRuntimeInitialized: resolve, onAbort: reject, print: () => {}, printErr: () => {} };
  });
  importScripts('/motores/kokoro/sherpa-onnx-tts.js', '/motores/kokoro/sherpa-onnx-wasm-main-tts.js');
  await ready;
  for (const [name, bytes] of files) {
    const parts = name.split('/');
    let parent = '/';
    for (const part of parts.slice(0, -1)) {
      self.Module.FS_createPath(parent, part, true, true);
      parent += part + '/';
    }
    self.Module.FS_createDataFile(parent, parts.at(-1), new Uint8Array(bytes), true, false, true);
  }
  engine = createOfflineTts(self.Module, {
    offlineTtsModelConfig: {
      offlineTtsKokoroModelConfig: { model: '/model.onnx', voices: '/voices.bin', tokens: '/tokens.txt', dataDir: '/espeak-ng-data', lang: 'pt-br' },
      numThreads: 1, debug: 0, provider: 'cpu',
    },
    maxNumSentences: 1,
  });
  if (!engine.handle || engine.sampleRate !== 24000) throw new Error('O Kokoro não pôde ser inicializado.');
}
/** Falha numérica: repetir em blocos menores, no máximo três níveis; nunca salvar NaN ou cortar o texto. */
function gerarBloco(text, sid, speed, attempt = 0) {
  const audio = engine.generate({ text, sid, speed });
  let peak = 0;
  let valid = audio.sampleRate === 24000 && audio.samples.length > 0;
  for (const sample of audio.samples) {
    if (!Number.isFinite(sample)) { valid = false; break; }
    peak = Math.max(peak, Math.abs(sample));
  }
  if (valid && peak > 0.00001) return audio;
  const cuts = Array.from({ length: text.length }, (_, i) => i).filter(i => i >= 8 && text.length - i >= 8 && /\s/u.test(text[i]))
    .sort((a, b) => Math.abs(a - text.length / 2) - Math.abs(b - text.length / 2));
  const halves = cuts.length ? [text.slice(0, cuts[0]).trim(), text.slice(cuts[0]).trim()] : [];
  if (attempt >= 3 || halves.length !== 2 || halves.some(part => !/[\p{L}\p{N}]/u.test(part)))
    throw new Error('Não foi possível narrar um trecho do texto, mesmo após dividi-lo. Confira o texto reconhecido ou escolha outra voz.');
  const parts = halves.map(part => gerarBloco(part, sid, speed, attempt + 1));
  const samples = new Float32Array(parts[0].samples.length + parts[1].samples.length);
  samples.set(parts[0].samples); samples.set(parts[1].samples, parts[0].samples.length);
  return { samples, sampleRate: 24000 };
}
self.onmessage = async ({ data }) => {
  try {
    if (data.type === 'initialize') {
      await initialize(data.files);
      self.postMessage({ type: 'ready' });
    } else if (data.type === 'generate') {
      if (!engine) throw new Error('Motor ainda não preparado.');
      const audio = gerarBloco(data.text, data.sid, data.speed);
      self.postMessage({ type: 'audio', samples: audio.samples.buffer, sampleRate: audio.sampleRate }, [audio.samples.buffer]);
    }
  } catch (error) {
    self.postMessage({ type: 'error', message: error instanceof Error ? error.message : 'Falha no Kokoro.' });
  }
};
