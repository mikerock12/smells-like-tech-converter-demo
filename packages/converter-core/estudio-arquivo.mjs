import { zipSync, unzipSync, strToU8, strFromU8 } from 'fflate';
import { problemas } from './estudio.mjs';
import { arquivosDoProjeto, trocarArquivos, atualizarProjeto } from './estudio-criacao.mjs';

const LIMITE = 512 * 1024 * 1024;
const nomeSeguro = (s) => /^midias\/[0-9]{3}\.[a-z0-9]{1,10}$/.test(s);

/** ZIP portátil .sltstudio: projeto.json e mídias relativas, sem depender dos originais. */
export async function salvarProjeto(p,arquivos) {
  p=atualizarProjeto(p);
  const problemasDoProjeto=problemas(p);if(problemasDoProjeto.length) throw new Error(problemasDoProjeto.join(' '));
  const refs=arquivosDoProjeto(p), nomes=new Map(), zip={};
  let tamanho=0;
  for(const [i,ref] of refs.entries()) {
    const f=arquivos.get(ref);if(!f) throw new Error('Uma mídia do projeto não está disponível.');
    tamanho+=f.size;if(tamanho>LIMITE) throw new Error('Para projetos acima de 512 MB, salve pelo aplicativo Windows ou Android.');
    const ext=f.name.split('.').at(-1)?.toLowerCase().replace(/[^a-z0-9]/g,'').slice(0,10)||'bin';
    const nome=`midias/${String(i).padStart(3,'0')}.${ext}`;nomes.set(ref,nome);
    zip[nome]=new Uint8Array(await f.arrayBuffer());
  }
  zip['projeto.json']=strToU8(JSON.stringify(trocarArquivos({...p,versao:4},ref=>nomes.get(ref))));
  const bytes=zipSync(zip,{level:0});return new Blob([new Uint8Array(bytes).buffer],{type:'application/zip'});
}

export async function abrirProjeto(arquivo) {
  if(arquivo.size>LIMITE) throw new Error('Este projeto excede o limite de 512 MB do navegador.');
  let total=0,quantidade=0;const nomes=new Set();
  const zip=unzipSync(new Uint8Array(await arquivo.arrayBuffer()),{filter:entrada=>{
    if(++quantidade>512||nomes.has(entrada.name)||(!nomeSeguro(entrada.name)&&entrada.name!=='projeto.json')||entrada.originalSize<0||(total+=entrada.originalSize)>LIMITE)
      throw new Error('Projeto inválido ou grande demais.');
    nomes.add(entrada.name);return true;
  }});
  if(!zip['projeto.json']||zip['projeto.json'].length>4*1024*1024) throw new Error('O arquivo não contém um projeto válido.');
  const p=JSON.parse(strFromU8(zip['projeto.json']));
  const erros=problemas(p);if(erros.length) throw new Error(erros.join(' '));
  const arquivos=new Map();
  for(const ref of arquivosDoProjeto(p)) {
    if(!nomeSeguro(ref)||!zip[ref]) throw new Error('Uma mídia está ausente no projeto salvo.');
    arquivos.set(ref,new File([new Uint8Array(zip[ref]).buffer],ref.split('/').at(-1)));
  }
  return {projeto:atualizarProjeto(p),arquivos};
}
