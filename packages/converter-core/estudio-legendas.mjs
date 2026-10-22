import { lerLegenda } from './legendas.mjs';

/** Legendas do áudio já montado: os tempos não dependem mais do arquivo original. */
export function textosDaLegenda(srt,total) {
  const lista=lerLegenda(srt).map((t,i)=>({id:`legenda-${i}`,texto:t.texto.replace(/<[^>]+>/g,'').trim(),
    inicio:Math.max(0,t.inicio/1000),fim:Math.min(total,t.fim/1000),x:.5,y:.85,tamanho:.055,
    cor:'#FFFFFF',contorno:'#000000',caixa:null,fonte:'anton'})).filter(t=>t.fim>t.inicio && t.texto);
  if(!lista.length) throw new Error('O Whisper não encontrou fala para legendar.');
  if(lista.length>1000) throw new Error('Use até 1000 trechos de legenda por projeto.');
  return lista;
}
