/** Cabeçalho WAV de 44 bytes (PCM ou float32), igual ao de musica-master.mjs. */
export function cabecalhoWav(canais: number, taxa: number, bits: number, quadros: number): Uint8Array {
  const bytes = bits / 8;
  const dados = Math.min(0xffffffff, quadros * canais * bytes);
  const b = new Uint8Array(44);
  const v = new DataView(b.buffer);
  const texto = (p: number, s: string) => {
    for (let i = 0; i < s.length; i += 1) b[p + i] = s.charCodeAt(i);
  };
  texto(0, "RIFF");
  v.setUint32(4, Math.min(0xffffffff, 36 + dados), true);
  texto(8, "WAVE");
  texto(12, "fmt ");
  v.setUint32(16, 16, true);
  v.setUint16(20, bits === 32 ? 3 : 1, true);
  v.setUint16(22, canais, true);
  v.setUint32(24, taxa, true);
  v.setUint32(28, taxa * canais * bytes, true);
  v.setUint16(32, canais * bytes, true);
  v.setUint16(34, bits, true);
  texto(36, "data");
  v.setUint32(40, dados, true);
  return b;
}
