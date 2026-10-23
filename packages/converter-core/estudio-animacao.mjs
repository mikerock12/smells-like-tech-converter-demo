/** Animação em segundos locais da mídia. Posição/zoom são aplicados após a perspectiva. */
export function validarAnimacao(t) {
  if(!Number.isFinite(t.tempoDaAnimacao??0)||(t.tempoDaAnimacao??0)<0||(t.tempoDaAnimacao??0)>86400) return "Tempo da animação inválido.";
  if (t.mascara !== undefined && !["nenhuma", "circulo", "oval", "arredondada"].includes(t.mascara)) return "Máscara desconhecida.";
  const qs = t.quadrosChave ?? [];
  if (!Array.isArray(qs) || qs.length > 100) return "Use até 100 quadros-chave por camada.";
  let anterior = -1;
  for (const q of qs) {
    if (!q || !Number.isFinite(q.tempo) || q.tempo < 0 || q.tempo <= anterior
      || !Number.isFinite(q.x) || q.x < -2 || q.x > 3 || !Number.isFinite(q.y) || q.y < -2 || q.y > 3
      || !Number.isFinite(q.escala) || q.escala < .05 || q.escala > 6
      || (q.curva !== undefined && !["linear", "suave"].includes(q.curva))) return "Quadros-chave inválidos: use tempos crescentes e posição/zoom dentro dos limites.";
    anterior = q.tempo;
  }
  return null;
}

export function movimentoNoTempo(t = {}, tempo = 0) {
  tempo += t.tempoDaAnimacao ?? 0;
  const qs = t.quadrosChave ?? [];
  if (!qs.length) return { x: t.x ?? .5, y: t.y ?? .5, escala: t.escala ?? 1 };
  if (tempo <= qs[0].tempo) return { x: qs[0].x, y: qs[0].y, escala: qs[0].escala };
  const b = qs.find(q => q.tempo > tempo);
  const a = b ? qs[qs.indexOf(b) - 1] : qs.at(-1);
  let p = b ? (tempo - a.tempo) / (b.tempo - a.tempo) : 0;
  if (a.curva === "suave") p = p * p * (3 - 2 * p);
  return Object.fromEntries(["x", "y", "escala"].map(k => [k, a[k] + ((b?.[k] ?? a[k]) - a[k]) * p]));
}

/** Expressão GEQ equivalente à interpolação usada pela prévia. Sem entradas de texto livre. */
export function expressaoDoMovimento(t, campo, tempo = "T") {
  const n = v => String(Math.round(v * 1e9) / 1e9);
  tempo = t.tempoDaAnimacao ? `(${tempo}+${n(t.tempoDaAnimacao)})` : tempo;
  const qs = t.quadrosChave ?? [];
  if (!qs.length) return n(t[campo] ?? (campo === "escala" ? 1 : .5));
  let fim = n(qs.at(-1)[campo]);
  for (let i = qs.length - 2; i >= 0; i--) {
    const a = qs[i], b = qs[i + 1];
    const p = `clip((${tempo}-${n(a.tempo)})/${n(b.tempo - a.tempo)},0,1)`;
    const curva = a.curva === "suave" ? `(${p}*${p}*(3-2*${p}))` : p;
    fim = `if(lt(${tempo},${n(b.tempo)}),${n(a[campo])}+(${n(b[campo]-a[campo])})*${curva},${fim})`;
  }
  return fim;
}

export function kenBurns(t = {}, duracao = 3, afastar = false) {
  const escala = t.escala ?? 1;
  const a = { tempo: 0, x: t.x ?? .5, y: t.y ?? .5, escala: Math.min(6, escala), curva: "suave" };
  const b = { ...a, tempo: Math.max(.1, duracao), escala: Math.min(6, escala * 1.2) };
  return { ...t, tempoDaAnimacao: 0, quadrosChave: afastar ? [{ ...b, tempo: 0 }, { ...a, tempo: b.tempo }] : [a, b] };
}

/** Os controles de posição/tamanho movem a trajetória inteira; cada quadro continua editável. */
export function mudarBaseDaAnimacao(antes, depois) {
  if(!antes.quadrosChave?.length) return depois;
  const dx=(depois.x??.5)-(antes.x??.5), dy=(depois.y??.5)-(antes.y??.5), z=(depois.escala??1)/(antes.escala??1);
  if(!dx&&!dy&&z===1) return depois;
  const limitar=(v,a,b)=>Math.max(a,Math.min(b,v));
  return {...depois,quadrosChave:antes.quadrosChave.map(q=>({...q,x:limitar(q.x+dx,-2,3),y:limitar(q.y+dy,-2,3),escala:limitar(q.escala*z,.05,6)}))};
}
