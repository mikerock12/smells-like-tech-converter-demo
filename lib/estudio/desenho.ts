import {
  FONTES,
  LAYOUT_DO_TEXTO,
  retanguloDoAjuste,
  textosNoTempo,
  type IdDoAjuste,
  type Projeto,
  type Texto,
} from "@/packages/converter-core/estudio.mjs";

/**
 * O desenho do estúdio no navegador: o texto com o layout comum aos quatro apps
 * (LAYOUT_DO_TEXTO) e o quadro inteiro (clipe enquadrado + textos), usado pela prévia e pela
 * exportação. As fontes são as mesmas das figurinhas, servidas de /estudio/fontes.
 */

type Contexto2D = CanvasRenderingContext2D | OffscreenCanvasRenderingContext2D;
type Imagem = CanvasImageSource & { readonly width: number; readonly height: number };

let fontesCarregadas: Promise<void> | null = null;

/** Carrega as fontes do texto uma vez (no documento ou no worker). */
export function carregarFontes(): Promise<void> {
  fontesCarregadas ??= (async () => {
    const conjunto = (globalThis as unknown as { fonts?: FontFaceSet }).fonts ?? (typeof document !== "undefined" ? document.fonts : undefined);
    if (!conjunto) return;
    await Promise.all(
      FONTES.filter((fonte) => fonte.arquivo).map(async (fonte) => {
        try {
          const face = new FontFace(familia(fonte.id), `url(/estudio/fontes/${fonte.arquivo})`);
          conjunto.add(await face.load());
        } catch {
          // Sem a fonte, o texto sai na padrão; não vale travar a prévia por isso.
        }
      }),
    );
  })();
  return fontesCarregadas;
}

function familia(id: string): string {
  return `estudio-${id}`;
}

function fonteCss(id: string, tamanho: number): string {
  const fonte = FONTES.find((f) => f.id === id);
  if (!fonte?.arquivo) return `700 ${tamanho}px "Segoe UI", Roboto, Arial, sans-serif`;
  return `400 ${tamanho}px "${familia(id)}", "Segoe UI", Arial, sans-serif`;
}

/** Quebra por palavras para caber na largura; respeita as quebras digitadas. */
function quebrar(ctx: Contexto2D, texto: string, larguraMaxima: number): string[] {
  const linhas: string[] = [];
  for (const paragrafo of texto.replace(/\r\n/g, "\n").split("\n")) {
    let atual = "";
    for (const palavra of paragrafo.split(" ").filter(Boolean)) {
      const candidata = atual ? `${atual} ${palavra}` : palavra;
      if (atual && ctx.measureText(candidata).width > larguraMaxima) {
        linhas.push(atual);
        atual = palavra;
      } else {
        atual = candidata;
      }
    }
    linhas.push(atual);
  }
  return linhas;
}

/** Desenha um texto num quadro de `largura` × `altura` (o contexto já na escala do projeto). */
export function desenharTexto(ctx: Contexto2D, texto: Texto, largura: number, altura: number): void {
  const conteudo = texto.texto.trim();
  if (!conteudo) return;
  const tamanho = Math.max(4, texto.tamanho * altura);
  ctx.save();
  ctx.font = fonteCss(texto.fonte, tamanho);
  const linhas = quebrar(ctx, conteudo, largura * LAYOUT_DO_TEXTO.larguraMaxima);
  const alturaDaLinha = tamanho * LAYOUT_DO_TEXTO.entrelinha;
  const alturaDoBloco = alturaDaLinha * linhas.length;
  const larguraDoBloco = Math.max(...linhas.map((linha) => ctx.measureText(linha).width));
  const centroX = texto.x * largura;
  const topo = texto.y * altura - alturaDoBloco / 2;

  if (texto.caixa) {
    const margem = tamanho * LAYOUT_DO_TEXTO.margemDaCaixa;
    ctx.fillStyle = texto.caixa;
    ctx.beginPath();
    ctx.roundRect(centroX - larguraDoBloco / 2 - margem, topo - margem, larguraDoBloco + margem * 2, alturaDoBloco + margem * 2, tamanho * LAYOUT_DO_TEXTO.raioDaCaixa);
    ctx.fill();
  }

  ctx.textAlign = "center";
  ctx.textBaseline = "middle";
  ctx.lineJoin = "round";
  linhas.forEach((linha, i) => {
    const y = topo + (i + 0.5) * alturaDaLinha;
    if (texto.contorno) {
      ctx.strokeStyle = texto.contorno;
      ctx.lineWidth = tamanho * LAYOUT_DO_TEXTO.contorno * 2;
      ctx.strokeText(linha, centroX, y);
    }
    ctx.fillStyle = texto.cor;
    ctx.fillText(linha, centroX, y);
  });
  ctx.restore();
}

/**
 * Desenha a imagem do clipe no enquadramento escolhido. O fundo desfocado imita o da
 * exportação pelo FFmpeg: reduz a 1/8, desfoca de leve e amplia de volta.
 */
export function enquadrar(ctx: Contexto2D, imagem: Imagem, ajuste: IdDoAjuste, largura: number, altura: number): void {
  if (ajuste === "caber-desfocado") {
    const cobre = retanguloDoAjuste("preencher", imagem.width, imagem.height, largura, altura);
    const pequena = new OffscreenCanvas(Math.max(1, Math.round(largura / 8)), Math.max(1, Math.round(altura / 8)));
    const pincel = pequena.getContext("2d");
    if (pincel) {
      pincel.filter = "blur(3px)";
      pincel.drawImage(imagem, cobre.x / 8, cobre.y / 8, cobre.w / 8, cobre.h / 8);
      ctx.imageSmoothingQuality = "high";
      ctx.drawImage(pequena, 0, 0, largura, altura);
    }
  }
  const r = retanguloDoAjuste(ajuste === "preencher" ? "preencher" : "caber", imagem.width, imagem.height, largura, altura);
  ctx.drawImage(imagem, r.x, r.y, r.w, r.h);
}

/** O quadro inteiro do projeto num instante: fundo preto, o clipe (se houver imagem) e os textos. */
export function desenharQuadro(ctx: Contexto2D, projeto: Projeto, t: number, imagem: Imagem | null, ajuste: IdDoAjuste | null): void {
  const { largura, altura } = projeto;
  ctx.fillStyle = "#000";
  ctx.fillRect(0, 0, largura, altura);
  if (imagem && ajuste) enquadrar(ctx, imagem, ajuste, largura, altura);
  for (const texto of textosNoTempo(projeto, t)) desenharTexto(ctx, texto, largura, altura);
}
