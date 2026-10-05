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
      offlineTtsKokoroModelConfig: { model: '/model.onnx', voices: '/voices.bin', tokens: '/tokens.txt', dataDir: '/espeak-ng-data', lang: 'pt' },
      numThreads: 1, debug: 0, provider: 'cpu',
    },
    maxNumSentences: 1,
  });
  if (!engine.handle || engine.sampleRate !== 24000) throw new Error('O Kokoro não pôde ser inicializado.');
}
self.onmessage = async ({ data }) => {
  try {
    if (data.type === 'initialize') {
      await initialize(data.files);
      self.postMessage({ type: 'ready' });
    } else if (data.type === 'generate') {
      if (!engine) throw new Error('Motor ainda não preparado.');
      const audio = engine.generate({ text: data.text, sid: data.sid, speed: data.speed });
      if (!audio.samples.length) throw new Error('O Kokoro não produziu áudio.');
      let peak = 0;
      for (const sample of audio.samples) {
        if (!Number.isFinite(sample)) throw new Error('O Kokoro produziu amostras inválidas.');
        peak = Math.max(peak, Math.abs(sample));
      }
      if (peak < 0.00001) throw new Error('O Kokoro produziu uma saída silenciosa. Tente novamente ou use o aplicativo.');
      self.postMessage({ type: 'audio', samples: audio.samples.buffer, sampleRate: audio.sampleRate }, [audio.samples.buffer]);
    }
  } catch (error) {
    self.postMessage({ type: 'error', message: error instanceof Error ? error.message : 'Falha no Kokoro.' });
  }
};
