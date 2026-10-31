import type { Projeto } from './estudio.mjs';
export declare const MODELOS_DO_ESTUDIO: readonly {id:string;titulo:string}[];
export declare function aplicarModelo(p:Projeto,id:string):Projeto;
export declare function adicionarContagem(p:Projeto,inicio?:number,quantidade?:number):Projeto;
export declare function dividirClipe(p:Projeto,id:string,tempo:number,novoId:string):Projeto;
export declare function dividirCamada(p:Projeto,id:string,tempo:number,novoId:string):Projeto;
export declare function arquivosDoProjeto(p:Projeto):string[];
export declare function trocarArquivos(p:Projeto,trocar:(arquivo:string)=>string):Projeto;
export declare function inicioDoClipe(p:Projeto,id:string):number;

export declare function atualizarProjeto(p:Projeto):Projeto;
