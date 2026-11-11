export declare const MAX_BYTES_OPENVERSE: number;
export declare const MAX_MS_OPENVERSE: number;
export declare const ID_OPENVERSE: RegExp;
export type CategoriaOpenverse = "music" | "sound_effect";
export type AudioOpenverse = {
  id: string; titulo: string; autor: string | null; fonte: string; categoria: CategoriaOpenverse;
  generos: string[]; duracaoMs: number | null; tamanho: number | null; licenca: string; origem: string; credito: string;
};
export type RevisaoOpenverse = {
  id: string; categoria: CategoriaOpenverse; estado: "APPROVED" | "BLOCKED" | "RECHECK";
  origem: string; midia: string; sha256: string; revisado_em: string; metadados: string;
};
export type ResultadoOpenverse = AudioOpenverse & (
  { estado: "APPROVED"; midia: string; sha256: string; revisadoEm: string } |
  { estado: "PENDING" | "BLOCKED" | "RECHECK" }
);
export type ProvenienciaOpenverse = Extract<ResultadoOpenverse, { estado: "APPROVED" }> & { importadoEm: string };
export declare function normalizarAudioOpenverse(item: unknown, categoria: CategoriaOpenverse): (AudioOpenverse & { midia: string | null }) | null;
export declare function decidirAudioOpenverse(item: AudioOpenverse & { midia: string | null }, revisao?: RevisaoOpenverse): ResultadoOpenverse;
