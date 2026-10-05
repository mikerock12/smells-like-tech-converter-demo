"use client";

import { useCallback, useEffect, useRef, useState } from "react";

import { EMAIL_LEMBRADO, arquivoDaChave, chaveParaONavegador } from "@/packages/conta/index.mjs";
import { ferramenta as buscarFerramenta } from "@/packages/converter-core/catalogo.mjs";
import { formatBytes } from "@/packages/converter-core/index.mjs";
import { PLANOS, alcanceDoPlano, formatarReais } from "@/packages/converter-core/precos.mjs";
import { chaveLegivel } from "@/packages/licenca/index.mjs";

import { enviarUso, lembrarRegistroDeUso } from "@/lib/conta/uso";
import { usePlano } from "@/lib/plano/usePlano";

type Licenca = {
  ref: string;
  produto: string;
  plano: string;
  estado: string;
  centavos: number;
  pagoEm: string | null;
  chave: string | null;
  expira: string | null;
};

type UsoDaFerramenta = { ferramenta: string; arquivos: number; bytes: number };

type Conta = {
  email: string;
  criadaEm: string;
  admin: boolean;
  licencas: Licenca[];
  registrarUso: boolean;
  uso: UsoDaFerramenta[];
};

type Etapa =
  | { tipo: "carregando" }
  | { tipo: "fora" }
  | { tipo: "indisponivel"; motivo: string }
  /** `lidaEm` é o "agora" das datas da tela: vencida ou não, conferido na hora da leitura. */
  | { tipo: "dentro"; conta: Conta; lidaEm: number };

const data = (iso: string) => new Date(iso).toLocaleDateString("pt-BR", { timeZone: "America/Sao_Paulo" });
const nomeDoPlano = (plano: string) => PLANOS[plano as keyof typeof PLANOS]?.nome ?? plano;

function lembrarEmail(email: string | null) {
  try {
    if (email) localStorage.setItem(EMAIL_LEMBRADO, email);
    else localStorage.removeItem(EMAIL_LEMBRADO);
  } catch {
    // Sem armazenamento: o checkout só não vem preenchido.
  }
}

async function buscarConta(): Promise<Etapa> {
  // O uso anotado neste navegador vai antes, para o histórico abaixo já contar com ele.
  await enviarUso();
  try {
    const resposta = await fetch("/api/conta", { cache: "no-store" });
    const corpo = (await resposta.json()) as Conta & { entrou?: boolean; erro?: string };
    if (!resposta.ok) return { tipo: "indisponivel", motivo: corpo.erro ?? "A conta está fora do ar agora." };
    if (!corpo.entrou) return { tipo: "fora" };
    lembrarEmail(corpo.email);
    lembrarRegistroDeUso(corpo.registrarUso);
    return { tipo: "dentro", conta: corpo, lidaEm: Date.now() };
  } catch {
    return { tipo: "indisponivel", motivo: "Sem conexão com o site agora. As chaves já ativadas continuam funcionando." };
  }
}

/**
 * A conta: entrar com código, ver as licenças do e-mail e levar a chave para este
 * navegador. Ao entrar, a chave que libera o Pro no site é ativada sozinha — é ela, e não
 * a sessão, que libera o Pro, então tudo continua funcionando sem internet.
 */
export default function MinhaConta() {
  const [etapa, setEtapa] = useState<Etapa>({ tipo: "carregando" });
  // Cada entrada bem-sucedida pede a conta de novo.
  const [leitura, setLeitura] = useState(0);
  const carregar = useCallback(() => setLeitura((atual) => atual + 1), []);

  useEffect(() => {
    let vivo = true;
    void buscarConta().then((nova) => {
      if (vivo) setEtapa(nova);
    });
    return () => {
      vivo = false;
    };
  }, [leitura]);

  if (etapa.tipo === "carregando") {
    return (
      <aside className="instalacao" aria-live="polite">
        <h2>Abrindo a conta…</h2>
      </aside>
    );
  }
  if (etapa.tipo === "indisponivel") return <p className="notice notice--warning">{etapa.motivo}</p>;
  if (etapa.tipo === "fora") return <Entrar aoEntrar={carregar} />;
  return <Dentro conta={etapa.conta} agora={etapa.lidaEm} aoSair={() => setEtapa({ tipo: "fora" })} />;
}

function Entrar({ aoEntrar }: { aoEntrar: () => void }) {
  const [email, setEmail] = useState(() => {
    try {
      return localStorage.getItem(EMAIL_LEMBRADO) ?? "";
    } catch {
      return "";
    }
  });
  const [codigo, setCodigo] = useState("");
  const [enviado, setEnviado] = useState(false);
  const [ocupado, setOcupado] = useState(false);
  const [aviso, setAviso] = useState<string | null>(null);

  async function chamar(caminho: string, corpo: Record<string, string>): Promise<boolean> {
    setOcupado(true);
    setAviso(null);
    try {
      const resposta = await fetch(caminho, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(corpo),
      });
      if (resposta.ok) return true;
      const erro = ((await resposta.json().catch(() => ({}))) as { erro?: string }).erro;
      setAviso(erro ?? "Não deu certo. Tente de novo.");
      return false;
    } catch {
      setAviso("Sem conexão com o site agora.");
      return false;
    } finally {
      setOcupado(false);
    }
  }

  async function pedir(evento: React.FormEvent) {
    evento.preventDefault();
    if (await chamar("/api/conta/codigo", { email })) {
      setEnviado(true);
      setCodigo("");
    }
  }

  async function confirmar(evento: React.FormEvent) {
    evento.preventDefault();
    if (await chamar("/api/conta/entrar", { email, codigo })) aoEntrar();
  }

  if (!enviado) {
    return (
      <section className="plugin-page__secao">
        <h2 className="panel__title">Entrar</h2>
        <p className="panel__descricao">Use o e-mail da compra. Se ainda não comprou, a conta nasce vazia e as compras aparecem nela.</p>
        <form className="grid" onSubmit={pedir}>
          <label className="field">
            <span className="field__label">E-mail</span>
            <input
              type="email"
              value={email}
              onChange={(evento) => setEmail(evento.target.value)}
              placeholder="voce@exemplo.com.br"
              autoComplete="email"
              required
              disabled={ocupado}
            />
          </label>
          <div className="field field--wide">
            <div className="plugin__acoes">
              <button type="submit" className="button button--primary" disabled={ocupado}>
                {ocupado ? "Enviando…" : "Mandar o código"}
              </button>
            </div>
          </div>
        </form>
        {aviso && <p className="notice notice--warning">{aviso}</p>}
      </section>
    );
  }

  return (
    <section className="plugin-page__secao">
      <h2 className="panel__title">Digite o código</h2>
      <p className="panel__descricao">
        Mandamos 6 números para <strong>{email}</strong>. Vale por 10 minutos. Não chegou em um minuto? Olhe o spam e as
        promoções.
      </p>
      <form className="grid" onSubmit={confirmar}>
        <label className="field">
          <span className="field__label">Código</span>
          <input
            type="text"
            inputMode="numeric"
            autoComplete="one-time-code"
            pattern="[0-9 \-]{6,8}"
            maxLength={8}
            value={codigo}
            onChange={(evento) => setCodigo(evento.target.value)}
            placeholder="000000"
            required
            disabled={ocupado}
            className="chave-campo"
          />
        </label>
        <div className="field field--wide">
          <div className="plugin__acoes">
            <button type="submit" className="button button--primary" disabled={ocupado}>
              {ocupado ? "Conferindo…" : "Entrar"}
            </button>
            <button type="button" className="button button--ghost" disabled={ocupado} onClick={() => setEnviado(false)}>
              Trocar o e-mail ou pedir outro código
            </button>
          </div>
        </div>
      </form>
      {aviso && <p className="notice notice--warning">{aviso}</p>}
    </section>
  );
}

function Dentro({ conta, agora, aoSair }: { conta: Conta; agora: number; aoSair: () => void }) {
  const { estado, pronto, ativar, remover } = usePlano();
  const [aviso, setAviso] = useState<string | null>(null);
  const [saindo, setSaindo] = useState(false);
  const tentouAtivar = useRef(false);

  // Ao entrar, leva a chave do Pro para este navegador, se ainda não houver uma valendo.
  useEffect(() => {
    if (!pronto || tentouAtivar.current) return;
    tentouAtivar.current = true;
    if (estado.situacao === "valida" && estado.plano === "pro") return;
    const melhor = chaveParaONavegador(conta.licencas, agora);
    if (!melhor?.chave) return;
    void ativar(melhor.chave).then((resultado) => {
      if (resultado.situacao === "valida") {
        setAviso(`O ${nomeDoPlano(melhor.plano)} foi ativado neste navegador. Ele continua funcionando sem internet.`);
      }
    });
  }, [pronto, estado, conta.licencas, agora, ativar]);

  async function sair(tirarAChave: boolean) {
    setSaindo(true);
    // O que foi anotado até aqui ainda é desta conta.
    await enviarUso();
    try {
      await fetch("/api/conta/sair", { method: "POST" });
    } catch {
      // Sem conexão: o cookie vence sozinho. A saída local acontece do mesmo jeito.
    }
    lembrarEmail(null);
    lembrarRegistroDeUso(true);
    if (tirarAChave) remover();
    aoSair();
  }

  const ativaAqui = estado.situacao === "valida" ? estado.carga.id : null;

  return (
    <>
      <section className="plugin-page__secao">
        <div className="conta__topo">
          <p className="panel__descricao">
            Conta de <strong>{conta.email}</strong>
            {conta.admin && (
              <>
                {" · "}
                <a className="converter__link" href="/admin">
                  Painel administrativo
                </a>
              </>
            )}
          </p>
          <div className="plugin__acoes">
            <button type="button" className="button button--ghost" onClick={() => void sair(false)} disabled={saindo}>
              Sair
            </button>
            <button type="button" className="button button--ghost" onClick={() => void sair(true)} disabled={saindo}>
              Sair e tirar a chave deste navegador
            </button>
          </div>
        </div>
        {aviso && <p className="notice">{aviso}</p>}
      </section>

      <section className="plugin-page__secao">
        <h2 className="panel__title">Suas licenças</h2>
        {conta.licencas.length === 0 ? (
          <p className="panel__descricao">
            Nenhuma compra com este e-mail ainda. Comprou com outro endereço? Saia e entre com ele.{" "}
            <a className="converter__link" href="/precos">
              Ver os planos
            </a>
            .
          </p>
        ) : (
          <div className="conta__licencas">
            {conta.licencas.map((licenca) => (
              <CartaoDaLicenca key={licenca.ref} licenca={licenca} agora={agora} ativaAqui={ativaAqui === licenca.ref} ativar={ativar} />
            ))}
          </div>
        )}
      </section>

      <SeuUso conta={conta} />

      <SemInternet />
    </>
  );
}

/** O histórico de uso da conta: o que foi usado nos últimos 30 dias, e o botão de desligar. */
function SeuUso({ conta }: { conta: Conta }) {
  const [ligado, setLigado] = useState(conta.registrarUso);
  const [uso, setUso] = useState(conta.uso);
  const [confirmarApagar, setConfirmarApagar] = useState(false);
  const [ocupado, setOcupado] = useState(false);
  const [aviso, setAviso] = useState<string | null>(null);
  const total = uso.reduce((soma, linha) => soma + linha.arquivos, 0);

  async function mudar(novo: boolean) {
    setOcupado(true);
    setAviso(null);
    try {
      const resposta = await fetch("/api/conta/preferencias", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ registrarUso: novo }),
      });
      if (!resposta.ok) throw new Error();
      setLigado(novo);
      lembrarRegistroDeUso(novo);
    } catch {
      setAviso("Não deu para salvar agora. Tente de novo.");
    } finally {
      setOcupado(false);
    }
  }

  async function apagar() {
    setOcupado(true);
    setAviso(null);
    try {
      const resposta = await fetch("/api/conta/uso", { method: "DELETE" });
      if (!resposta.ok) throw new Error();
      setUso([]);
      setConfirmarApagar(false);
      setAviso("Histórico apagado.");
    } catch {
      setAviso("Não deu para apagar agora. Tente de novo.");
    } finally {
      setOcupado(false);
    }
  }

  return (
    <section className="plugin-page__secao">
      <h2 className="panel__title">Seu uso nos últimos 30 dias</h2>
      <p className="panel__descricao">
        Para você acompanhar o que usa, a conta soma quantos arquivos passaram por cada ferramenta e o tamanho total,
        por dia. Nunca o nome nem o conteúdo de arquivo nenhum. O navegador manda essa contagem quando você sai da
        página, nunca durante a conversão.
      </p>
      <label className="field field--check conta__registro">
        <input type="checkbox" checked={ligado} disabled={ocupado} onChange={(evento) => void mudar(evento.target.checked)} />
        <span>Registrar o meu uso nesta conta</span>
      </label>

      {uso.length === 0 ? (
        <p className="panel__descricao">{ligado ? "Nada registrado nos últimos 30 dias." : "O registro está desligado."}</p>
      ) : (
        <div className="conta__tabela">
          <table className="tabela-comparacao">
            <thead>
              <tr>
                <th scope="col">Ferramenta</th>
                <th scope="col" className="numero-celula">Arquivos</th>
                <th scope="col" className="numero-celula">Tamanho</th>
              </tr>
            </thead>
            <tbody>
              {uso.map((linha) => (
                <tr key={linha.ferramenta}>
                  <th scope="row">{buscarFerramenta(linha.ferramenta)?.titulo ?? linha.ferramenta}</th>
                  <td className="numero-celula">{linha.arquivos.toLocaleString("pt-BR")}</td>
                  <td className="numero-celula">{formatBytes(linha.bytes)}</td>
                </tr>
              ))}
            </tbody>
            <tfoot>
              <tr>
                <th scope="row">Total</th>
                <td className="numero-celula">{total.toLocaleString("pt-BR")}</td>
                <td className="numero-celula">{formatBytes(uso.reduce((soma, linha) => soma + linha.bytes, 0))}</td>
              </tr>
            </tfoot>
          </table>
        </div>
      )}

      {uso.length > 0 && (
        <div className="plugin__acoes">
          {confirmarApagar ? (
            <>
              <button type="button" className="button" disabled={ocupado} onClick={() => void apagar()}>
                Sim, apagar tudo
              </button>
              <button type="button" className="button button--ghost" disabled={ocupado} onClick={() => setConfirmarApagar(false)}>
                Cancelar
              </button>
            </>
          ) : (
            <button type="button" className="button button--ghost" disabled={ocupado} onClick={() => setConfirmarApagar(true)}>
              Apagar o meu histórico
            </button>
          )}
        </div>
      )}
      {aviso && <p className="notice">{aviso}</p>}
    </section>
  );
}

function CartaoDaLicenca({
  licenca,
  agora,
  ativaAqui,
  ativar,
}: {
  licenca: Licenca;
  agora: number;
  ativaAqui: boolean;
  ativar: ReturnType<typeof usePlano>["ativar"];
}) {
  const [copiada, setCopiada] = useState(false);
  const alcance = alcanceDoPlano(licenca.plano);
  const vencida = licenca.expira !== null && Date.parse(licenca.expira) < agora;
  const estornada = licenca.estado !== "pago";

  const selo = estornada
    ? "estornada"
    : ativaAqui
      ? "ativa neste navegador"
      : vencida
        ? `venceu em ${data(licenca.expira ?? "")}`
        : licenca.expira
          ? `vale até ${data(licenca.expira)}`
          : "não vence";

  function baixar() {
    if (!licenca.chave) return;
    const texto = arquivoDaChave({
      chave: licenca.chave,
      ref: licenca.ref,
      nomeDoPlano: nomeDoPlano(licenca.plano),
      expira: licenca.expira,
      site: window.location.origin,
    });
    const endereco = URL.createObjectURL(new Blob([texto], { type: "text/plain;charset=utf-8" }));
    const link = document.createElement("a");
    link.href = endereco;
    link.download = `chave-smells-like-tech-${licenca.ref}.txt`;
    link.click();
    window.setTimeout(() => URL.revokeObjectURL(endereco), 1000);
  }

  return (
    <article className={`instalacao ${ativaAqui ? "instalacao--pronta" : estornada || vencida ? "instalacao--aviso" : ""}`}>
      <span className="instalacao__selo">{selo}</span>
      <h2>{nomeDoPlano(licenca.plano)}</h2>
      <p className="instalacao__detalhe">
        Pedido {licenca.ref}
        {licenca.pagoEm && ` · ${licenca.centavos > 0 ? "pago" : "registrado"} em ${data(licenca.pagoEm)}`} · {licenca.centavos > 0 ? formatarReais(licenca.centavos) : "cortesia"}
        {" · " + [alcance.site && "site e plugin", alcance.app && "aplicativo para Windows", alcance.android && "app para Android"].filter(Boolean).join(", ")}
      </p>
      {estornada && <p className="instalacao__detalhe">O valor voltou pelo Mercado Pago, e a chave deste pedido não aparece mais.</p>}
      {licenca.chave && (
        <>
          <details className="conta__chave">
            <summary>Ver a chave</summary>
            <pre className="chave">{chaveLegivel(licenca.chave)}</pre>
          </details>
          <div className="plugin__acoes">
            {alcance.site && !vencida && !ativaAqui && (
              <button type="button" className="button button--primary" onClick={() => void ativar(licenca.chave ?? "")}>
                Usar neste navegador
              </button>
            )}
            <button
              type="button"
              className="button"
              onClick={() => void navigator.clipboard.writeText(licenca.chave ?? "").then(() => setCopiada(true))}
            >
              {copiada ? "Copiada" : "Copiar a chave"}
            </button>
            <button type="button" className="button button--ghost" onClick={baixar}>
              Baixar a chave (.txt)
            </button>
          </div>
        </>
      )}
    </article>
  );
}

/** Onde a chave precisa estar para funcionar sem internet, lugar por lugar. */
function SemInternet() {
  return (
    <section className="plugin-page__secao">
      <h2 className="panel__title">Para usar sem internet</h2>
      <p className="panel__descricao">
        A chave é um texto assinado que se confere sozinho, sem consultar a gente. Depois que ela está no lugar certo, a
        internet não faz falta.
      </p>
      <div className="grid">
        <div className="cartao">
          <strong>No site</strong>
          <p>
            Ao entrar na conta, a chave do Pro fica guardada neste navegador. Em outro navegador ou computador, entre de novo
            ou cole a chave em <a className="converter__link" href="/chave">/chave</a>. Limpar os dados do site, ou usar uma
            janela anônima, apaga a chave guardada.
          </p>
        </div>
        <div className="cartao">
          <strong>No aplicativo para Windows</strong>
          <p>
            Abra Configurações → Licença e cole a chave. É uma vez só: ela fica salva no computador e vale sem internet,
            em até 3 computadores.
          </p>
        </div>
        <div className="cartao">
          <strong>Uma cópia de segurança</strong>
          <p>
            Use “Baixar a chave” e guarde o arquivo em Documentos, no seu Drive ou no seu e-mail. Quem tem a chave usa a
            licença: não publique nem mande para outras pessoas.
          </p>
        </div>
      </div>
    </section>
  );
}
