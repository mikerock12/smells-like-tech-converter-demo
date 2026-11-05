import { movimentoNoTempo } from "@/packages/converter-core/estudio-animacao.mjs";
import { blocosDoQuadro, estadoDoEfeito, pedacosDoQuadro, ruidoDoBloco } from "@/packages/converter-core/estudio-efeitos.mjs";
import { cantosDaCamada, homografia, matrizDeCor, TRANSFORMACAO_PADRAO, type Transformacao } from "@/packages/converter-core/camadas.mjs";

type Contexto = CanvasRenderingContext2D | OffscreenCanvasRenderingContext2D;
type Imagem = CanvasImageSource & { readonly width: number; readonly height: number };
const mesclas: Record<string, GlobalCompositeOperation> = { normal: "source-over", multiplicar: "multiply", clarear: "screen", sobrepor: "overlay" };

/** Um contexto reutilizado: perspectiva por fragmento, sem subdividir a imagem em centenas de triângulos. */
class Projetor {
  readonly tela = new OffscreenCanvas(1, 1);
  readonly gl: WebGLRenderingContext;
  readonly programa: WebGLProgram;
  readonly textura: WebGLTexture;

  constructor() {
    const gl = this.tela.getContext("webgl", { alpha: true, premultipliedAlpha: false, preserveDrawingBuffer: true });
    if (!gl) throw new Error("Não foi possível iniciar a prévia em perspectiva neste navegador.");
    this.gl = gl;
    const shader = (tipo: number, codigo: string) => {
      const s = gl.createShader(tipo)!;
      gl.shaderSource(s, codigo); gl.compileShader(s);
      if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw new Error("Não foi possível preparar a perspectiva.");
      return s;
    };
    const vs = shader(gl.VERTEX_SHADER, "attribute vec2 uv; uniform mat3 h; uniform vec2 quadro; varying vec2 ponto; void main(){vec3 p=h*vec3(uv,1.0);gl_Position=vec4(2.0*p.x/quadro.x-p.z,p.z-2.0*p.y/quadro.y,0.0,p.z);ponto=uv;}");
    const fs = shader(gl.FRAGMENT_SHADER, "precision mediump float; uniform sampler2D imagem; uniform vec4 r; uniform vec4 g; uniform vec4 b; varying vec2 ponto; void main(){vec4 c=texture2D(imagem,ponto);vec4 v=vec4(c.rgb,1.0);gl_FragColor=vec4(clamp(vec3(dot(r,v),dot(g,v),dot(b,v)),0.0,1.0),c.a);}");
    this.programa = gl.createProgram()!;
    gl.attachShader(this.programa, vs); gl.attachShader(this.programa, fs); gl.linkProgram(this.programa);
    gl.deleteShader(vs); gl.deleteShader(fs);
    if (!gl.getProgramParameter(this.programa, gl.LINK_STATUS)) throw new Error("Não foi possível preparar a perspectiva.");
    gl.useProgram(this.programa);
    const buffer = gl.createBuffer();
    gl.bindBuffer(gl.ARRAY_BUFFER, buffer);
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([0,0, 1,0, 0,1, 1,1]), gl.STATIC_DRAW);
    const uv = gl.getAttribLocation(this.programa, "uv");
    gl.enableVertexAttribArray(uv); gl.vertexAttribPointer(uv, 2, gl.FLOAT, false, 0, 0);
    this.textura = gl.createTexture()!;
    gl.bindTexture(gl.TEXTURE_2D, this.textura);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
  }

  desenhar(imagem: Imagem, largura: number, altura: number, t: Partial<Transformacao>, tempo = 0): OffscreenCanvas | null {
    const q = movimentoNoTempo(t,tempo), z = q.escala / (t.escala ?? 1);
    const cantos = cantosDaCamada(imagem.width, imagem.height, largura, altura, t).map(p => ({
      x: (p.x - largura * (t.x ?? .5)) * z + largura * q.x,
      y: (p.y - altura * (t.y ?? .5)) * z + altura * q.y,
    }));
    const h = homografia(cantos);
    if (!h) return null;
    const { gl, programa } = this;
    if (gl.isContextLost()) throw new Error("A prévia perdeu acesso à placa gráfica. Reabra o editor.");
    this.tela.width = largura; this.tela.height = altura;
    gl.viewport(0, 0, largura, altura);
    gl.clearColor(0,0,0,0); gl.clear(gl.COLOR_BUFFER_BIT);
    gl.useProgram(programa);
    gl.uniform2f(gl.getUniformLocation(programa, "quadro"), largura, altura);
    gl.uniformMatrix3fv(gl.getUniformLocation(programa, "h"), false, new Float32Array([h[0],h[3],h[6],h[1],h[4],h[7],h[2],h[5],h[8]]));
    const cores = matrizDeCor(t);
    ["r","g","b"].forEach((nome, i) => gl.uniform4f(gl.getUniformLocation(programa, nome), cores[i*4], cores[i*4+1], cores[i*4+2], cores[i*4+3]/255));
    gl.bindTexture(gl.TEXTURE_2D, this.textura);
    gl.pixelStorei(gl.UNPACK_PREMULTIPLY_ALPHA_WEBGL, false);
    gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, false);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, imagem as TexImageSource);
    gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
    return this.tela;
  }
}

let projetor: Projetor | null = null;
/** Desintegrar: só os blocos sorteados até `q` continuam, como na máscara da exportação. */
function comParticulas(fonte: OffscreenCanvas, largura: number, altura: number, q: number): OffscreenCanvas {
  const { mw, mh } = blocosDoQuadro(largura, altura);
  const mascara = new OffscreenCanvas(mw, mh), pontos = new ImageData(mw, mh);
  for (let y = 0; y < mh; y++) for (let x = 0; x < mw; x++)
    if (ruidoDoBloco(x, y) * .7 + x / mw * .3 < q) pontos.data[(y * mw + x) * 4 + 3] = 255;
  mascara.getContext("2d")!.putImageData(pontos, 0, 0);
  const tela = new OffscreenCanvas(largura, altura), c = tela.getContext("2d")!;
  c.drawImage(fonte, 0, 0);
  c.globalCompositeOperation = "destination-in";
  c.imageSmoothingEnabled = false;
  c.drawImage(mascara, 0, 0, largura, altura);
  return tela;
}

export function desenharCamada(ctx: Contexto, imagem: Imagem, largura: number, altura: number, transformacao: Partial<Transformacao> = {}, tempo = 0, duracao = 0, pivo: { x: number; y: number } | null = null): void {
  const t = { ...TRANSFORMACAO_PADRAO, ...transformacao };
  if (t.mascara && t.mascara !== "nenhuma") {
    const tela = new OffscreenCanvas(largura,altura), c = tela.getContext("2d")!;
    const z = Math.min(largura/imagem.width,altura/imagem.height);
    c.beginPath();
    if(t.mascara === "arredondada") c.roundRect(0,0,largura,altura,Math.min(largura,altura)*.15);
    else c.ellipse(largura/2,altura/2,t.mascara === "circulo" ? Math.min(largura,altura)/2 : largura/2,t.mascara === "circulo" ? Math.min(largura,altura)/2 : altura/2,0,0,Math.PI*2);
    c.clip();c.drawImage(imagem,(largura-imagem.width*z)/2,(altura-imagem.height*z)/2,imagem.width*z,imagem.height*z);
    imagem = tela;
  }
  const desenho = (projetor ??= new Projetor()).desenhar(imagem, largura, altura, t, tempo);
  if (!desenho) return;
  // Efeitos: as fórmulas de estudio-efeitos.mjs, as mesmas da exportação pelo FFmpeg.
  const e = estadoDoEfeito(t, tempo, duracao);
  const px = (pivo?.x ?? t.x) * largura, py = (pivo?.y ?? t.y) * altura;
  const fonte = e.particulas < 1 ? comParticulas(desenho, largura, altura, e.particulas) : desenho;
  ctx.save();
  ctx.globalAlpha = t.opacidade * e.alfa;
  ctx.globalCompositeOperation = mesclas[t.mescla] ?? "source-over";
  if (e.matiz) ctx.filter = `hue-rotate(${e.matiz % 360}deg)`;
  if (e.pedacos > 0) {
    for (const p of pedacosDoQuadro(largura, altura, px, py).lista) {
      if (!p) continue;
      ctx.save();
      ctx.translate(p.cx + p.vx * e.pedacos, p.cy + p.vy * e.pedacos);
      ctx.rotate(p.volta * e.pedacos);
      ctx.drawImage(fonte, p.x0, p.y0, p.cw, p.ch, -p.cw / 2, -p.ch / 2, p.cw, p.ch);
      ctx.restore();
    }
  } else {
    if (e.dx || e.dy || e.giro || e.sx !== 1 || e.sy !== 1) {
      ctx.translate(px + e.dx * largura, py + e.dy * altura);
      ctx.rotate(e.giro);
      ctx.scale(e.sx, e.sy);
      ctx.translate(-px, -py);
    }
    ctx.drawImage(fonte, 0, 0);
  }
  ctx.restore();
}
