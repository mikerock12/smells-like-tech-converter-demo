/** WSOLA: altera o tempo preservando a altura da voz; canais compartilham os mesmos cortes. */
export function mudarTempo(canais,taxa,velocidade) {
  if(!Number.isFinite(velocidade)||velocidade<.25||velocidade>4) throw new Error('Velocidade inválida.');
  if(velocidade===1) return canais.map(c=>c.slice());
  const origem=canais[0]?.length??0, tamanho=Math.max(1,Math.round(origem/velocidade));
  const janela=Math.max(64,Math.round(taxa*.04/2)*2), passo=janela/2, busca=Math.round(taxa*.008);
  const saida=canais.map(()=>new Float32Array(tamanho)), pesos=new Float32Array(tamanho);
  const envelope=Float32Array.from({length:janela},(_,i)=>.5-.5*Math.cos(2*Math.PI*(i+.5)/janela));
  let anterior=0;
  for(let pos=0;pos<tamanho;pos+=passo) {
    const esperado=Math.round(pos*velocidade), max=Math.max(0,origem-janela);
    let escolhido=Math.min(esperado,max), melhor=-Infinity;
    if(pos>0 && esperado<max) {
      const de=Math.max(0,esperado-busca), ate=Math.min(max,esperado+busca);
      for(let candidato=de;candidato<=ate;candidato+=4) {
        let produto=0,a=1e-9,b=1e-9;
        for(let i=0;i<passo;i+=8) {
          const x=canais[0][anterior+passo+i]??0,y=canais[0][candidato+i]??0;
          produto+=x*y;a+=x*x;b+=y*y;
        }
        const nota=produto/Math.sqrt(a*b)-Math.abs(candidato-esperado)*1e-8;
        if(nota>melhor) {melhor=nota;escolhido=candidato;}
      }
    }
    for(let i=0;i<janela && pos+i<tamanho;i++) {
      const peso=envelope[i];pesos[pos+i]+=peso;
      for(let c=0;c<canais.length;c++) saida[c][pos+i]+=(canais[c][escolhido+i]??0)*peso;
    }
    anterior=escolhido;
  }
  for(let i=0;i<tamanho;i++) if(pesos[i]>0) for(const canal of saida) canal[i]/=pesos[i];
  return saida;
}
