"use client";

import { AJUSTE_MAXIMO_DB, AJUSTE_MINIMO_DB, GANHO_MAXIMO_DB, GANHO_MINIMO_DB, PASSOS_DA_MUSICA, passoDoParametro, type EfeitoDaMusica, type ProjetoMusical } from "@/packages/converter-core/musica.mjs";
import { EFEITOS_DA_MUSICA, definicaoDoEfeito, novoEfeito, parametro } from "@/packages/converter-core/musica-dsp.mjs";
import type { EstadoDoMotor } from "@/lib/musica/motor";
import { novoId, type Mudar } from "./CriacaoMusical";
import { AjusteMusical } from "./AjusteMusical";

const db = (v: number) => (v <= GANHO_MINIMO_DB ? "−∞" : `${v > 0 ? "+" : ""}${v.toFixed(1)}`);

function Medidor({ valores }: { valores?: number[] }) {
  const barra = (v: number) => `${Math.min(100, Math.max(0, (20 * Math.log10(v + 1e-9) + 60) / 60) * 100)}%`;
  const [l, r, rl, rr] = valores ?? [0, 0, 0, 0];
  return (
    <div className="musica__medidor" aria-hidden>
      {[[l, rl], [r, rr]].map(([pico, rms], i) => (
        <span key={i} className={pico >= 1 ? "musica__medidor--estouro" : ""}>
          <i style={{ height: barra(rms) }} />
          <b style={{ bottom: barra(pico) }} />
        </span>
      ))}
    </div>
  );
}

export function MixerMusical({ projeto, estado, mudar, abrirEfeitos }: { projeto: ProjetoMusical; estado: EstadoDoMotor; mudar: Mudar; abrirEfeitos: (id: string) => void }) {
  const mudarFaixa = (id: string, f: (x: ProjetoMusical["faixas"][number]) => ProjetoMusical["faixas"][number], juntar?: string) => mudar((p) => ({ ...p, faixas: p.faixas.map((x) => (x.id === id ? f(x) : x)) }), juntar);
  const mudarBus = (id: string, f: (x: ProjetoMusical["barramentos"][number]) => ProjetoMusical["barramentos"][number], juntar?: string) => mudar((p) => ({ ...p, barramentos: p.barramentos.map((x) => (x.id === id ? f(x) : x)) }), juntar);
  return (
    <div className="musica__mixer" role="region" aria-label="Mixer">
      {projeto.faixas.filter((f) => !f.oculta).map((f) => (
        <div key={f.id} className={`musica__canal${f.mudo ? " musica__canal--mudo" : ""}`} style={{ borderTopColor: f.cor }}>
          <strong title={f.nome}>{f.nome}</strong>
          <div className="musica__linha">
            <button type="button" className={`musica__mini musica__mini--solo${f.solo ? " musica__mini--ligado" : ""}`} aria-pressed={f.solo} onClick={() => mudarFaixa(f.id, (x) => ({ ...x, solo: !x.solo }))}>S</button>
            <button type="button" className={`musica__mini musica__mini--mudo${f.mudo ? " musica__mini--ligado" : ""}`} aria-pressed={f.mudo} onClick={() => mudarFaixa(f.id, (x) => ({ ...x, mudo: !x.mudo }))}>M</button>
            <button type="button" className="musica__mini" onClick={() => abrirEfeitos(f.id)}>FX{f.efeitos.length ? ` ${f.efeitos.length}` : ""}</button>
          </div>
          <div className="musica__fader">
            <AjusteMusical vertical valor={f.ganhoDb} min={GANHO_MINIMO_DB} max={GANHO_MAXIMO_DB} passo={PASSOS_DA_MUSICA.ganhoDb} rotulo={`volume de ${f.nome}`} textoDoValor={`${db(f.ganhoDb)} dB`} padrao={0} mudar={(v) => mudarFaixa(f.id, (x) => ({ ...x, ganhoDb: v }), `vol-${f.id}`)} />
            <Medidor valores={estado.medidores[f.id]} />
          </div>
          <small>{db(f.ganhoDb)} dB</small>
          <label className="musica__rotulo">Pan {f.pan === 0 ? "C" : f.pan < 0 ? `L${-f.pan}` : `R${f.pan}`}
            <AjusteMusical valor={f.pan} min={-100} max={100} passo={PASSOS_DA_MUSICA.pan} rotulo={`pan de ${f.nome}`} padrao={0} mudar={(v) => mudarFaixa(f.id, (x) => ({ ...x, pan: v }), `pan-${f.id}`)} />
          </label>
          <label className="musica__rotulo">L {f.ajusteEsquerdoDb} dB
            <AjusteMusical valor={f.ajusteEsquerdoDb} min={AJUSTE_MINIMO_DB} max={AJUSTE_MAXIMO_DB} passo={PASSOS_DA_MUSICA.ajusteDb} rotulo={`ajuste do canal esquerdo de ${f.nome}`} padrao={0} mudar={(v) => mudarFaixa(f.id, (x) => ({ ...x, ajusteEsquerdoDb: v }), `l-${f.id}`)} />
          </label>
          <label className="musica__rotulo">R {f.ajusteDireitoDb} dB
            <AjusteMusical valor={f.ajusteDireitoDb} min={AJUSTE_MINIMO_DB} max={AJUSTE_MAXIMO_DB} passo={PASSOS_DA_MUSICA.ajusteDb} rotulo={`ajuste do canal direito de ${f.nome}`} padrao={0} mudar={(v) => mudarFaixa(f.id, (x) => ({ ...x, ajusteDireitoDb: v }), `r-${f.id}`)} />
          </label>
          <label className="musica__rotulo">Saída
            <select value={f.saida} onChange={(e) => mudarFaixa(f.id, (x) => ({ ...x, saida: e.target.value }))}>
              <option value="master">Master</option>
              {projeto.barramentos.map((b) => <option key={b.id} value={b.id}>{b.nome}</option>)}
            </select>
          </label>
          {projeto.barramentos.map((b) => {
            const envio = f.envios.find((e) => e.barramento === b.id);
            return (
              <label key={b.id} className="musica__rotulo" title="Envio ao barramento (pré-fader não segue o volume da faixa)">
                → {b.nome} {envio ? `${envio.nivelDb} dB` : "desligado"}
                <span className="musica__linha">
                  <AjusteMusical valor={envio?.nivelDb ?? -61} min={-61} max={6} passo={PASSOS_DA_MUSICA.envioDb} rotulo={`envio de ${f.nome} para ${b.nome}`} mudar={(nivel) => {
                    mudarFaixa(f.id, (x) => ({ ...x, envios: nivel <= -61 ? x.envios.filter((y) => y.barramento !== b.id) : x.envios.some((y) => y.barramento === b.id) ? x.envios.map((y) => (y.barramento === b.id ? { ...y, nivelDb: nivel } : y)) : [...x.envios, { barramento: b.id, nivelDb: nivel, preFader: false }] }), `envio-${f.id}-${b.id}`);
                  }} />
                  {envio && <button type="button" className={`musica__mini${envio.preFader ? " musica__mini--ligado" : ""}`} aria-pressed={envio.preFader} onClick={() => mudarFaixa(f.id, (x) => ({ ...x, envios: x.envios.map((y) => (y.barramento === b.id ? { ...y, preFader: !y.preFader } : y)) }))}>PRÉ</button>}
                </span>
              </label>
            );
          })}
        </div>
      ))}
      {projeto.barramentos.map((b) => (
        <div key={b.id} className={`musica__canal musica__canal--bus${b.mudo ? " musica__canal--mudo" : ""}`}>
          <input className="musica__nome" aria-label="Nome do barramento" value={b.nome} onChange={(e) => mudarBus(b.id, (x) => ({ ...x, nome: e.target.value }), `nome-${b.id}`)} />
          <div className="musica__linha">
            <button type="button" className={`musica__mini musica__mini--mudo${b.mudo ? " musica__mini--ligado" : ""}`} aria-pressed={b.mudo} onClick={() => mudarBus(b.id, (x) => ({ ...x, mudo: !x.mudo }))}>M</button>
            <button type="button" className="musica__mini" onClick={() => abrirEfeitos(b.id)}>FX{b.efeitos.length ? ` ${b.efeitos.length}` : ""}</button>
            <button type="button" className="musica__mini" aria-label={`Remover ${b.nome}`} onClick={() => mudar((p) => ({ ...p, barramentos: p.barramentos.filter((x) => x.id !== b.id), faixas: p.faixas.map((f) => ({ ...f, saida: f.saida === b.id ? "master" : f.saida, envios: f.envios.filter((e) => e.barramento !== b.id) })) }))}>✕</button>
          </div>
          <div className="musica__fader">
            <AjusteMusical vertical valor={b.ganhoDb} min={GANHO_MINIMO_DB} max={GANHO_MAXIMO_DB} passo={PASSOS_DA_MUSICA.ganhoDb} rotulo={`volume de ${b.nome}`} textoDoValor={`${db(b.ganhoDb)} dB`} padrao={0} mudar={(v) => mudarBus(b.id, (x) => ({ ...x, ganhoDb: v }), `vol-${b.id}`)} />
            <Medidor valores={estado.medidores[b.id]} />
          </div>
          <small>{db(b.ganhoDb)} dB</small>
          <div className="musica__rotulo">Pan<AjusteMusical valor={b.pan} min={-100} max={100} passo={PASSOS_DA_MUSICA.pan} rotulo={`pan de ${b.nome}`} padrao={0} mudar={(v) => mudarBus(b.id, (x) => ({ ...x, pan: v }), `pan-${b.id}`)} /></div>
        </div>
      ))}
      <div className="musica__canal musica__canal--master">
        <strong>Master</strong>
        <button type="button" className="musica__mini" onClick={() => abrirEfeitos("master")}>FX{projeto.master.efeitos.length ? ` ${projeto.master.efeitos.length}` : ""}</button>
        <div className="musica__fader">
          <AjusteMusical vertical valor={projeto.master.ganhoDb} min={GANHO_MINIMO_DB} max={GANHO_MAXIMO_DB} passo={PASSOS_DA_MUSICA.ganhoDb} rotulo="volume do master" textoDoValor={`${db(projeto.master.ganhoDb)} dB`} padrao={0} mudar={(v) => mudar((p) => ({ ...p, master: { ...p.master, ganhoDb: v } }), "vol-master")} />
          <Medidor valores={estado.medidores.master} />
        </div>
        <small>{db(projeto.master.ganhoDb)} dB</small>
        <button type="button" className="button button--ghost" onClick={() => mudar((p) => ({ ...p, barramentos: [...p.barramentos, { id: novoId("bus"), nome: `Bus ${p.barramentos.length + 1}`, ganhoDb: 0, pan: 0, mudo: false, efeitos: [] }] }))}>+ Barramento</button>
      </div>
    </div>
  );
}

function cadeiaDe(projeto: ProjetoMusical, alvo: string): { nome: string; efeitos: EfeitoDaMusica[] } | null {
  if (alvo === "master") return { nome: "Master", efeitos: projeto.master.efeitos };
  const faixa = projeto.faixas.find((f) => f.id === alvo);
  if (faixa) return { nome: faixa.nome, efeitos: faixa.efeitos };
  const bus = projeto.barramentos.find((b) => b.id === alvo);
  return bus ? { nome: bus.nome, efeitos: bus.efeitos } : null;
}

function trocarCadeia(p: ProjetoMusical, alvo: string, efeitos: EfeitoDaMusica[]): ProjetoMusical {
  if (alvo === "master") return { ...p, master: { ...p.master, efeitos } };
  return { ...p, faixas: p.faixas.map((f) => (f.id === alvo ? { ...f, efeitos } : f)), barramentos: p.barramentos.map((b) => (b.id === alvo ? { ...b, efeitos } : b)) };
}

export function EfeitosMusicais({ projeto, alvo, escolher, mudar }: { projeto: ProjetoMusical; alvo: string; escolher: (id: string) => void; mudar: Mudar }) {
  const cadeia = cadeiaDe(projeto, alvo) ?? cadeiaDe(projeto, "master")!;
  const mudarEfeitos = (f: (e: EfeitoDaMusica[]) => EfeitoDaMusica[], juntar?: string) => mudar((p) => trocarCadeia(p, alvo, f(cadeiaDe(p, alvo)?.efeitos ?? [])), juntar);
  const grupos = [...new Set(EFEITOS_DA_MUSICA.map((e) => e.grupo))];
  return (
    <div className="musica__efeitos">
      <div className="musica__escolha-de-efeitos">
        <label className="musica__campo">Cadeia de
          <select value={alvo} onChange={(e) => escolher(e.target.value)}>
            {projeto.faixas.map((f) => <option key={f.id} value={f.id}>Faixa: {f.nome}</option>)}
            {projeto.barramentos.map((b) => <option key={b.id} value={b.id}>Barramento: {b.nome}</option>)}
            <option value="master">Master</option>
          </select>
        </label>
        <label className="musica__campo">Acrescentar
          <select value="" onChange={(e) => { const tipo = e.target.value; if (tipo) mudarEfeitos((lista) => [...lista, novoEfeito(tipo, novoId("fx"))]); }}>
            <option value="">Escolha um efeito…</option>
            {grupos.map((g) => <optgroup key={g} label={g}>{EFEITOS_DA_MUSICA.filter((e) => e.grupo === g).map((e) => <option key={e.tipo} value={e.tipo}>{e.rotulo}</option>)}</optgroup>)}
          </select>
        </label>
      </div>
      {cadeia.efeitos.length === 0 && <p className="estudio__dica">Nenhum efeito em {cadeia.nome}. A ordem conta: o sinal passa de cima para baixo. Os efeitos não têm atraso (latência zero), então não há compensação a fazer.</p>}
      {cadeia.efeitos.map((efeito, i) => {
        const def = definicaoDoEfeito(efeito.tipo);
        if (!def) return null;
        return (
          <fieldset key={efeito.id} className={`musica__efeito${efeito.ligado ? "" : " musica__efeito--desligado"}`}>
            <legend>
              <label className="estudio__chave"><input type="checkbox" checked={efeito.ligado} onChange={(e) => mudarEfeitos((l) => l.map((x) => (x.id === efeito.id ? { ...x, ligado: e.target.checked } : x)))} /> {def.rotulo}</label>
              <button type="button" className="estudio__icone" aria-label="Subir" disabled={i === 0} onClick={() => mudarEfeitos((l) => { const c = [...l]; [c[i - 1], c[i]] = [c[i], c[i - 1]]; return c; })}>↑</button>
              <button type="button" className="estudio__icone" aria-label="Descer" disabled={i === cadeia.efeitos.length - 1} onClick={() => mudarEfeitos((l) => { const c = [...l]; [c[i + 1], c[i]] = [c[i], c[i + 1]]; return c; })}>↓</button>
              <button type="button" className="estudio__icone" aria-label={`Remover ${def.rotulo}`} onClick={() => mudarEfeitos((l) => l.filter((x) => x.id !== efeito.id))}>✕</button>
            </legend>
            {def.parametros.map((par) => {
              const valor = parametro(efeito, par.id);
              const { passo, relativo } = passoDoParametro(par);
              const casas = relativo ? 0 : passo < 0.1 ? 2 : passo < 1 ? 1 : 0;
              return (
                <label key={par.id} className="musica__rotulo">{par.rotulo}: {valor.toFixed(casas)}{par.unidade ? ` ${par.unidade}` : ""}
                  <AjusteMusical valor={valor} min={par.min} max={par.max} passo={passo} relativo={relativo} rotulo={`${def.rotulo}: ${par.rotulo}`} padrao={par.padrao} mudar={(v) => mudarEfeitos((l) => l.map((x) => (x.id === efeito.id ? { ...x, [par.id]: v } : x)), `${efeito.id}-${par.id}`)} />
                </label>
              );
            })}
          </fieldset>
        );
      })}
    </div>
  );
}
