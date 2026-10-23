import type { Projeto } from "./estudio.mjs";
export declare function salvarProjeto(p:Projeto,arquivos:ReadonlyMap<string,File>):Promise<Blob>;
export declare function abrirProjeto(arquivo:File):Promise<{projeto:Projeto;arquivos:ReadonlyMap<string,File>}>;
