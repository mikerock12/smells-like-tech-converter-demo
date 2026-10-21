/**
 * Política comum do catálogo: a cota real pertence à chave no GIPHY, não ao cliente.
 * Nunca mistura resultados dos fornecedores. O consumidor chama buscar novamente
 * em tentarEm, enquanto o seletor estiver aberto, para retornar automaticamente.
 */
export const HORA_DO_CATALOGO = 3_600_000;
export const LIMITE_LOCAL_DO_GIPHY = 100;

export function esperaDoGiphy(retryAfter, agora, esperaPadrao = 60_000) {
  const valor = String(retryAfter ?? "").trim();
  if (valor && /^\d+(\.\d+)?$/.test(valor)) return Math.max(1_000, Number(valor) * 1000);
  const data = valor ? Date.parse(valor) : NaN;
  return Number.isFinite(data) && data > agora ? data - agora : esperaPadrao;
}

export function avisoDoCatalogo(motivo) {
  switch (motivo) {
    case "limite": return "O GIPHY atingiu o limite de consultas. Mostrando Google Noto por enquanto. O GIPHY voltará automaticamente quando estiver disponível.";
    case "indisponivel": return "O GIPHY está indisponível. Mostrando Google Noto enquanto tentamos restabelecer a busca.";
    default: return null;
  }
}

/** Estado serializável; sem chave, URLs de mídia ou resultados guardados. */
export function estadoDoCatalogo() {
  return { tentativas: [], tentarEm: 0, motivo: null, falhas: 0 };
}

export function proximoCatalogo(estado, agora, configurado = true) {
  const tentativas = estado.tentativas.filter((t) => Number.isFinite(t) && t > agora - HORA_DO_CATALOGO && t <= agora);
  const atual = { ...estado, tentativas };
  if (!configurado) return { estado: atual, fonte: "noto", motivo: "sem-chave", tentarEm: null };
  if (atual.tentarEm > agora) return { estado: atual, fonte: "noto", motivo: atual.motivo, tentarEm: atual.tentarEm };
  if (tentativas.length >= LIMITE_LOCAL_DO_GIPHY) {
    const tentarEm = Math.min(...tentativas) + HORA_DO_CATALOGO;
    return { estado: { ...atual, tentarEm, motivo: "limite" }, fonte: "noto", motivo: "limite", tentarEm };
  }
  return { estado: atual, fonte: "giphy", motivo: null, tentarEm: null };
}

export function registrarResposta(estado, status, agora, retryAfter = null) {
  if (status === 200) return { ...estado, tentarEm: 0, motivo: null, falhas: 0 };
  const falhas = estado.falhas + 1;
  const espera = status === 429
    ? esperaDoGiphy(retryAfter, agora, Math.min(900_000, 60_000 * 2 ** Math.min(falhas - 1, 4)))
    : status === 401 || status === 403 ? HORA_DO_CATALOGO : Math.min(300_000, 30_000 * 2 ** Math.min(falhas - 1, 4));
  return { ...estado, falhas, motivo: status === 429 ? "limite" : "indisponivel", tentarEm: agora + espera };
}

/**
 * Transporte direto do cliente, substituível nos testes. Uma instância por chave/seção,
 * compartilhada entre os seletores. O chamador persiste só o estado e cancela buscas antigas.
 */
export class CatalogoDeStickers {
  constructor({ chave = "", buscarNoto, fetch: requisitar = globalThis.fetch, agora = Date.now, estado = estadoDoCatalogo(), salvarEstado = () => {} }) {
    this.chave = chave;
    this.buscarNoto = buscarNoto;
    this.requisitar = requisitar;
    this.agora = agora;
    this.estado = estado;
    this.salvarEstado = salvarEstado;
    this.emCurso = null;
  }

  async buscar(consulta = "", { signal, pagina = 0 } = {}) {
    // Serializa as chamadas da mesma chave para contar o orçamento e observar o 429
    // antes de enviar outra. Falha/cancelamento de uma busca não bloqueia as seguintes.
    const anterior = this.emCurso;
    const trabalho = (async () => {
      if (anterior) await anterior.catch(() => {});
      signal?.throwIfAborted();
      return this.consultar(consulta, pagina, signal);
    })();
    this.emCurso = trabalho;
    try { return await trabalho; }
    finally { if (this.emCurso === trabalho) this.emCurso = null; }
  }

  guardar(estado) {
    this.estado = estado;
    // Armazenamento cheio/indisponível não deve interromper a busca.
    try { this.salvarEstado(estado); } catch { /* Continua com o estado em memória. */ }
  }

  async noto(consulta, motivo, tentarEm, signal) {
    const itens = await this.buscarNoto(consulta, signal);
    signal?.throwIfAborted();
    return { fonte: "noto", itens, aviso: avisoDoCatalogo(motivo), tentarEm, proximaPagina: null };
  }

  async consultar(consulta, pagina, signal) {
    const decisao = proximoCatalogo(this.estado, this.agora(), Boolean(this.chave));
    this.guardar(decisao.estado);
    if (decisao.fonte === "noto") return this.noto(consulta, decisao.motivo, decisao.tentarEm, signal);

    const url = new URL(`https://api.giphy.com/v1/stickers/${consulta.trim() ? "search" : "trending"}`);
    url.searchParams.set("api_key", this.chave);
    url.searchParams.set("limit", "24");
    url.searchParams.set("offset", String(Math.max(0, Math.min(480, Math.trunc(pagina) * 24))));
    url.searchParams.set("rating", "pg");
    if (consulta.trim()) {
      url.searchParams.set("q", [...consulta].slice(0, 50).join(""));
      url.searchParams.set("lang", "pt");
    }
    this.guardar({ ...this.estado, tentativas: [...this.estado.tentativas, this.agora()] });
    let status = 0;
    let retryAfter = null;
    try {
      const resposta = await this.requisitar(url, { signal, credentials: "omit", referrerPolicy: "no-referrer" });
      status = resposta.status;
      retryAfter = resposta.headers.get("Retry-After");
      const corpo = resposta.ok ? await resposta.json() : null;
      if (corpo?.meta?.status && corpo.meta.status !== 200) status = Number(corpo.meta.status);
      if (status === 200 && Array.isArray(corpo?.data)) {
        this.guardar(registrarResposta(this.estado, 200, this.agora()));
        // Mantém ordem e URLs exatas recebidas, inclusive parâmetros. Sem cache/proxy.
        const itens = corpo.data.map((item) => ({
          id: String(item.id),
          nome: item.title || "Sticker do GIPHY",
          previa: item.images?.fixed_height?.webp || item.images?.fixed_height?.url || "",
          url: item.images?.original?.url || "",
          pagina: item.url || "",
          autor: item.user?.display_name || item.username || "GIPHY",
          fonte: "giphy",
        }));
        const paginacao = corpo.pagination;
        return {
          fonte: "giphy", itens, aviso: null, tentarEm: null,
          proximaPagina: paginacao && paginacao.offset + paginacao.count < paginacao.total_count && pagina < 20 ? pagina + 1 : null,
        };
      }
      if (status === 200) status = 502;
    } catch {
      signal?.throwIfAborted();
      status = 0;
    }
    this.guardar(registrarResposta(this.estado, status, this.agora(), retryAfter));
    return this.noto(consulta, this.estado.motivo, this.estado.tentarEm, signal);
  }
}
