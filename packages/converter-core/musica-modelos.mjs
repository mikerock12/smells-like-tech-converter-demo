import { MODELO_DE_SEPARACAO } from "./musica-separacao.mjs";

export const MODO_ROFORMER_EXPERIMENTAL = {
  id: "6",
  rotulo: "RoFormer experimental · voz, bateria, baixo, guitarra, piano e resíduo",
  faixas: [
    { id: "vozes", rotulo: "Voz" },
    { id: "bateria", rotulo: "Bateria" },
    { id: "baixo", rotulo: "Baixo" },
    { id: "guitarra", rotulo: "Guitarra" },
    { id: "piano", rotulo: "Piano" },
    { id: "outros", rotulo: "Outros (resíduo)" },
  ],
};

/** Metadados de distribuição e homologação dos modelos de separação. */
export const MODELOS_DE_SEPARACAO = Object.freeze({
  legacy: {
    id: MODELO_DE_SEPARACAO.id,
    bytes: MODELO_DE_SEPARACAO.bytes,
    sha256: MODELO_DE_SEPARACAO.sha256,
    origem: MODELO_DE_SEPARACAO.url,
    licencaCodigo: "MIT",
    licencaPesos: "MIT",
    disponivel: true,
  },
  studio: {
    id: "bs-roformer-4stems-v1",
    bytes: 527385512,
    sha256: "3e9daecd70aaed5b5a0d1f861cc4d77eaa45afb3fc6301b1cf32c1be0f5868fb",
    origem: "https://github.com/ZFTurbo/Music-Source-Separation-Training/releases/tag/v1.0.12",
    licencaCodigo: "MIT",
    licencaPesos: "MIT",
    declaracaoDosPesos: "https://github.com/ZFTurbo/Music-Source-Separation-Training/issues/254",
    disponivel: false,
  },
  maximum: {
    id: "mvsep-mega53-v1",
    bytes: 1368919887,
    sha256: "c62820893bbf86d4e734f966bd142d9157cfc8bb8e79e9d8f9ea553f3ff3519f",
    origem: "https://github.com/ZFTurbo/Music-Source-Separation-Training/releases/tag/v1.0.21",
    licencaCodigo: "MIT",
    licencaPesos: "MIT",
    declaracaoDosPesos: "https://github.com/ZFTurbo/Music-Source-Separation-Training/issues/245",
    onnxExperimental: {
      id: "mvsep-mega53-v1-onnx-5s",
      bytes: 357162852,
      sha256: "308283a38fe4112aa19b8b3c86f032aac299f5523b18bbbb219ecc9feba8b7da",
    },
    disponivel: false,
  },
});
