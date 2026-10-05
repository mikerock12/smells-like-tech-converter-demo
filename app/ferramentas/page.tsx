import type { Metadata } from "next";

import { CATEGORIAS, FERRAMENTAS, PARES, contagem, ferramentasDaCategoria } from "@/packages/converter-core/catalogo.mjs";
import { rotulo } from "@/packages/converter-core/catalogo.mjs";

import { SiteFooter, SiteHeader } from "../SiteChrome";
import BuscaDeFerramentas from "./BuscaDeFerramentas";

export const metadata: Metadata = {
  title: "Todas as ferramentas — Smells Like Tech Converter",
  description:
    "Conversor e editor de imagem, áudio, vídeo, PDF, legenda e voz num lugar só. Tudo roda no seu computador: sem upload, sem cadastro, sem marca d'água.",
};

export default function PaginaDeFerramentas() {
  const { total, noNavegador, pares } = contagem();

  return (
    <>
      <SiteHeader page="ferramentas" />

      <main className="hub">
        <header className="hub__topo">
          <p className="converter__eyebrow">Ponto único de mídia</p>
          <h1>
            {total} ferramentas.
            <br />
            <em>Nenhuma manda seu arquivo para longe.</em>
          </h1>
          <p className="hub__lead">
            {noNavegador} funcionam direto no navegador, sem instalar nada. As outras {total - noNavegador} usam o plugin
            gratuito, que faz o trabalho pesado na sua máquina. Mais {pares} combinações de formato, cada uma com página
            própria.
          </p>
          <BuscaDeFerramentas />
        </header>

        {CATEGORIAS.map((categoria) => (
          <section key={categoria.id} className="hub__categoria" id={categoria.id} aria-labelledby={`cat-${categoria.id}`}>
            <div className="section-heading section-heading--compacta">
              <div>
                <span>{categoria.titulo}</span>
                <h2 id={`cat-${categoria.id}`}>{categoria.chamada}</h2>
              </div>
              <p className="hub__resumo">{categoria.resumo}</p>
            </div>
            <div className="ferramentas-grade">
              {ferramentasDaCategoria(categoria.id).map((ferramenta) => (
                <a key={ferramenta.id} className={`ferramenta${ferramenta.navegador ? " ferramenta--navegador" : " ferramenta--plugin"}`} href={`/converter/${ferramenta.slug}`}>
                  <div className="ferramenta__topo">
                    <span className={`ferramenta__onde ferramenta__onde--${ferramenta.navegador ? "navegador" : "plugin"}`}>
                      {ferramenta.navegador ? "no navegador" : "pelo plugin"}
                    </span>
                    {ferramenta.plano === "pro" && <span className="ferramenta__pro">Pro</span>}
                  </div>
                  <h3>{ferramenta.titulo}</h3>
                  <p>{ferramenta.resumo}</p>
                  <small>→ {ferramenta.saidas.map((saida) => rotulo(saida)).join(" · ")}</small>
                </a>
              ))}
            </div>
          </section>
        ))}

        <section className="hub__pares" aria-labelledby="pares-titulo">
          <div className="section-heading section-heading--compacta">
            <div>
              <span>Por formato</span>
              <h2 id="pares-titulo">As conversões mais procuradas</h2>
            </div>
          </div>
          <ul className="pares">
            {PARES.map((par) => (
              <li key={par.slug}>
                <a href={`/converter/${par.slug}`}>
                  {rotulo(par.de)} <span aria-hidden="true">→</span> {rotulo(par.para)}
                </a>
              </li>
            ))}
          </ul>
          <p className="capability-note">
            São {FERRAMENTAS.length} ferramentas e {PARES.length} pares. Se a combinação que você precisa não está aqui,{" "}
            <a className="converter__link" href="/converter">
              abra a oficina
            </a>{" "}
            e solte o arquivo: ela mostra o que dá para fazer com ele.
          </p>
        </section>
      </main>

      <SiteFooter />
    </>
  );
}
