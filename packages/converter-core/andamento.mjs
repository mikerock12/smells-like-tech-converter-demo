/**
 * O tempo que falta num processamento longo (conversão, separação, exportação), a partir da
 * fração concluída: o que já levou, proporcional ao que falta, suavizado para o número não
 * pular a cada atualização. A preparação (copiar, abrir, baixar) não entra na conta: o
 * relógio começa quando a fração começa a andar.
 *
 * Espelhos: Andamento.kt (Android) e ProgressEstimate.cs (Windows), com os mesmos textos.
 */

/**
 * Atualiza `estado` (um objeto vazio no começo) e devolve os segundos que faltam, ou null
 * enquanto ainda não dá para estimar.
 *
 * O ritmo só é recalculado quando a fração avança. Entre dois avisos o tempo apenas desce
 * com o relógio: motores que avançam em blocos (o RoFormer avisa a cada 2,5 s de áudio, e
 * um bloco pode levar minutos no celular ou no navegador) e telas que consultam o andamento
 * sem novidade faziam a conta antiga subir sem parar, porque o tempo passado crescia e a
 * fração não.
 */
export function estimarRestante(estado, fracao, agoraMs = Date.now()) {
  if (!(fracao > 0) || fracao >= 1) return null;
  if (!estado.base) estado.base = { momento: agoraMs, fracao };
  if (estado.ultimo != null && fracao <= (estado.fracao ?? 0) + 1e-9) {
    return Math.round(Math.max(0, estado.ultimo - (agoraMs - estado.momento) / 1000));
  }
  const decorrido = (agoraMs - estado.base.momento) / 1000;
  const andou = fracao - estado.base.fracao;
  if (andou < 0.02 || decorrido < 2) return null;
  const bruto = (decorrido * (1 - fracao)) / andou;
  const anterior = estado.ultimo == null ? null : Math.max(0, estado.ultimo - (agoraMs - estado.momento) / 1000);
  const suave = anterior == null ? bruto : 0.75 * anterior + 0.25 * bruto;
  estado.ultimo = suave;
  estado.momento = agoraMs;
  estado.fracao = fracao;
  return Math.round(suave);
}

/** "faltam cerca de 2 min", "faltam cerca de 1 h 10 min", "quase pronto". */
export function descreverRestante(segundos) {
  if (segundos == null || !Number.isFinite(segundos)) return null;
  if (segundos <= 5) return "quase pronto";
  if (segundos < 60) return `faltam cerca de ${Math.ceil(segundos / 5) * 5} s`;
  const minutos = Math.ceil(segundos / 60);
  if (segundos < 3600) return `faltam cerca de ${minutos} min`;
  return `faltam cerca de ${Math.floor(minutos / 60)} h${minutos % 60 ? ` ${minutos % 60} min` : ""}`;
}

/** "37% · faltam cerca de 2 min"; antes da primeira fração, "Preparando…". */
export function textoDoAndamento(fracao, segundos) {
  const porcentagem = Math.max(0, Math.min(100, Math.floor((fracao ?? 0) * 100)));
  const tempo = descreverRestante(segundos);
  if (porcentagem <= 0 && !tempo) return "Preparando…";
  if (porcentagem <= 0) return `Preparando… · ${tempo}`;
  return tempo ? `${porcentagem}% · ${tempo}` : `${porcentagem}%`;
}
