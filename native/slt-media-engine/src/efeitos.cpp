#include "efeitos.h"

namespace slt {

namespace {

constexpr double DOIS_PI = PI * 2;
constexpr double RAIZ_DE_MEIO = 0.7071067811865476;
constexpr double RAIZ_DE_DOIS = 1.4142135623730951;

inline double anti(double valor) { return absoluto(valor) < 1e-25 ? 0 : valor; }
inline double coeficiente(double ms, double taxa) { return ms <= 0 ? 0 : exponencial(-1 / ((taxa * ms) / 1000)); }
inline double db(double valor) { return potencia(10.0, valor / 20); }
inline double maior(double a, double b) { return a > b ? a : b; }
inline double menor(double a, double b) { return a < b ? a : b; }
inline double arredondar(double x) { return piso(x + 0.5); }

struct DefinicaoDoParametro {
    const char* id;
    double padrao;
};

struct DefinicaoDoEfeito {
    const char* tipo;
    DefinicaoDoParametro parametros[PARAMETROS_POR_EFEITO];
};

// A ordem dos parâmetros é a de EFEITOS_DA_MUSICA em musica-dsp.mjs.
const DefinicaoDoEfeito CATALOGO[] = {
    {"passa-alta", {{"frequencia", 80}}},
    {"passa-baixa", {{"frequencia", 12000}}},
    {"equalizador", {{"gravesDb", 0}, {"mediosFrequencia", 500}, {"mediosDb", 0}, {"mediosQ", 1}, {"presencaFrequencia", 3000}, {"presencaDb", 0}, {"agudosDb", 0}}},
    {"compressor", {{"limiarDb", -18}, {"razao", 3}, {"ataqueMs", 10}, {"liberacaoMs", 120}, {"joelhoDb", 6}, {"ganhoDb", 0}}},
    {"porta", {{"limiarDb", -50}, {"faixaDb", -60}, {"ataqueMs", 1}, {"liberacaoMs", 100}}},
    {"limitador", {{"tetoDb", -1}, {"liberacaoMs", 50}}},
    {"desesser", {{"frequencia", 6500}, {"limiarDb", -30}, {"reducaoMaxDb", 10}}},
    {"saturacao", {{"driveDb", 6}, {"mistura", 1}, {"saidaDb", 0}}},
    {"atraso", {{"tempoMs", 350}, {"retorno", 0.35}, {"mistura", 0.25}}},
    {"reverberacao", {{"tamanho", 0.6}, {"amortecimento", 0.4}, {"largura", 1}, {"mistura", 0.25}}},
    {"coro", {{"taxaHz", 0.8}, {"profundidadeMs", 3}, {"mistura", 0.35}}},
    {"tremolo", {{"taxaHz", 5}, {"profundidade", 0.5}}},
    {"largura", {{"largura", 1}}},
    {"ganho", {{"ganhoDb", 0}}},
};

const uint32_t PENTES[8] = {1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617};
const uint32_t PASSA_TUDO[4] = {556, 441, 341, 225};
constexpr uint32_t ESPALHAMENTO = 23;

enum { BIQUAD_PASSA_BAIXA, BIQUAD_PASSA_ALTA, BIQUAD_PICO, BIQUAD_PRATELEIRA_GRAVE, BIQUAD_PRATELEIRA_AGUDA };

}  // namespace

bool ParametrosDoEfeito::ler(const No& efeito) {
    const No* nome = efeito.campo("tipo");
    tipo = TipoDeEfeito::Desconhecido;
    if (nome == nullptr) return false;
    for (int t = 0; t < static_cast<int>(TipoDeEfeito::Desconhecido); t += 1) {
        if (!nome->textoIgual(CATALOGO[t].tipo)) continue;
        tipo = static_cast<TipoDeEfeito>(t);
        for (int i = 0; i < PARAMETROS_POR_EFEITO; i += 1) v[i] = CATALOGO[t].parametros[i].id != nullptr ? efeito.numeroDe(CATALOGO[t].parametros[i].id, CATALOGO[t].parametros[i].padrao) : 0;
        ligado = efeito.booleanoDe("ligado", true);
        return true;
    }
    return false;
}

double caudaDoEfeito(const ParametrosDoEfeito& p) {
    if (!p.ligado) return 0;
    if (p.tipo == TipoDeEfeito::Reverberacao) return 1 + 5 * p.v[0];
    if (p.tipo == TipoDeEfeito::Atraso) {
        const double retorno = p.v[1];
        const double tempo = p.v[0] / 1000;
        // log(0,001)/log(retorno), escrito com log10 (a razão é a mesma em qualquer base).
        return retorno <= 0 ? tempo : menor(10, tempo * (-3.0 / logaritmo10(retorno)) + tempo);
    }
    if (p.tipo == TipoDeEfeito::Coro) return 0.05;
    return 0;
}

void coeficientesBiquad(int tipo, double frequencia, double taxa, double q, double ganhoDb, double* c) {
    const double f = limitar(frequencia, 10, taxa * 0.45);
    const double w0 = (DOIS_PI * f) / taxa;
    const double cosw = cosseno(w0);
    const double sinw = seno(w0);
    const double A = potencia(10.0, ganhoDb / 40);
    double b0, b1, b2, a0, a1, a2;
    if (tipo == BIQUAD_PASSA_BAIXA || tipo == BIQUAD_PASSA_ALTA) {
        const double alpha = sinw / (2 * q);
        const double s = tipo == BIQUAD_PASSA_BAIXA ? 1 - cosw : 1 + cosw;
        b0 = s / 2;
        b1 = tipo == BIQUAD_PASSA_BAIXA ? s : -s;
        b2 = s / 2;
        a0 = 1 + alpha;
        a1 = -2 * cosw;
        a2 = 1 - alpha;
    } else if (tipo == BIQUAD_PICO) {
        const double alpha = sinw / (2 * q);
        b0 = 1 + alpha * A;
        b1 = -2 * cosw;
        b2 = 1 - alpha * A;
        a0 = 1 + alpha / A;
        a1 = -2 * cosw;
        a2 = 1 - alpha / A;
    } else {
        const double alpha = (sinw / 2) * RAIZ_DE_DOIS;
        const double r = 2 * raiz(A) * alpha;
        if (tipo == BIQUAD_PRATELEIRA_GRAVE) {
            b0 = A * (A + 1 - (A - 1) * cosw + r);
            b1 = 2 * A * (A - 1 - (A + 1) * cosw);
            b2 = A * (A + 1 - (A - 1) * cosw - r);
            a0 = A + 1 + (A - 1) * cosw + r;
            a1 = -2 * (A - 1 + (A + 1) * cosw);
            a2 = A + 1 + (A - 1) * cosw - r;
        } else {
            b0 = A * (A + 1 + (A - 1) * cosw + r);
            b1 = -2 * A * (A - 1 + (A + 1) * cosw);
            b2 = A * (A + 1 + (A - 1) * cosw - r);
            a0 = A + 1 - (A - 1) * cosw + r;
            a1 = 2 * (A - 1 - (A + 1) * cosw);
            a2 = A + 1 - (A - 1) * cosw - r;
        }
    }
    c[0] = b0 / a0;
    c[1] = b1 / a0;
    c[2] = b2 / a0;
    c[3] = a1 / a0;
    c[4] = a2 / a0;
}

void BiquadDireto::definir(const double* c) {
    b0 = c[0];
    b1 = c[1];
    b2 = c[2];
    a1 = c[3];
    a2 = c[4];
}

double BiquadDireto::amostra(double x, int canal) {
    const int k = canal * 4;
    const double y = anti(b0 * x + b1 * e[k] + b2 * e[k + 1] - a1 * e[k + 2] - a2 * e[k + 3]);
    e[k + 1] = e[k];
    e[k] = x;
    e[k + 3] = e[k + 2];
    e[k + 2] = y;
    return y;
}

bool Processador::criar(const ParametrosDoEfeito& parametros, double taxa_) {
    tipo = parametros.tipo;
    taxa = taxa_;
    bool ok = true;
    if (tipo == TipoDeEfeito::Porta) queda = coeficiente(10, taxa);
    if (tipo == TipoDeEfeito::Desesser) {
        ataque = coeficiente(1, taxa);
        liberacao = coeficiente(60, taxa);
    }
    if (tipo == TipoDeEfeito::Atraso) {
        tamanho = static_cast<uint32_t>(taxa * 2 + 0.999999) + 1;  // Math.ceil(taxa * 2) + 1
        ok = buffer[0].criar(tamanho) && buffer[1].criar(tamanho);
    }
    if (tipo == TipoDeEfeito::Coro) {
        const double exato = taxa * 0.05;
        const uint32_t arredondado = static_cast<uint32_t>(exato) + (exato > static_cast<uint32_t>(exato) ? 1 : 0);
        tamanho = arredondado + 2;
        base = taxa * 0.015;
        ok = buffer[0].criar(tamanho) && buffer[1].criar(tamanho);
    }
    if (tipo == TipoDeEfeito::Reverberacao) {
        const double escala = taxa / 44100;
        for (int c = 0; c < 2 && ok; c += 1) {
            const uint32_t extra = c == 0 ? 0 : ESPALHAMENTO;
            for (int i = 0; i < 8 && ok; i += 1) ok = pentes[c][i].b.criar(static_cast<size_t>(maior(1, arredondar((PENTES[i] + extra) * escala))));
            for (int i = 0; i < 4 && ok; i += 1) ok = passaTudo[c][i].b.criar(static_cast<size_t>(maior(1, arredondar((PASSA_TUDO[i] + extra) * escala))));
        }
    }
    if (!ok) {
        soltar();
        return false;
    }
    atualizar(parametros);
    return true;
}

void Processador::soltar() {
    buffer[0].soltar();
    buffer[1].soltar();
    for (int c = 0; c < 2; c += 1) {
        for (int i = 0; i < 8; i += 1) pentes[c][i].b.soltar();
        for (int i = 0; i < 4; i += 1) passaTudo[c][i].b.soltar();
    }
}

void Processador::atualizar(const ParametrosDoEfeito& p) {
    double c[5];
    switch (tipo) {
        case TipoDeEfeito::PassaAlta:
        case TipoDeEfeito::PassaBaixa:
            coeficientesBiquad(tipo == TipoDeEfeito::PassaBaixa ? BIQUAD_PASSA_BAIXA : BIQUAD_PASSA_ALTA, p.v[0], taxa, RAIZ_DE_MEIO, 0, c);
            bandas[0].definir(c);
            break;
        case TipoDeEfeito::Equalizador:
            coeficientesBiquad(BIQUAD_PRATELEIRA_GRAVE, 100, taxa, RAIZ_DE_MEIO, p.v[0], c);
            bandas[0].definir(c);
            coeficientesBiquad(BIQUAD_PICO, p.v[1], taxa, p.v[3], p.v[2], c);
            bandas[1].definir(c);
            coeficientesBiquad(BIQUAD_PICO, p.v[4], taxa, 1, p.v[5], c);
            bandas[2].definir(c);
            coeficientesBiquad(BIQUAD_PRATELEIRA_AGUDA, 8000, taxa, RAIZ_DE_MEIO, p.v[6], c);
            bandas[3].definir(c);
            ativas[0] = p.v[0] != 0;
            ativas[1] = p.v[2] != 0;
            ativas[2] = p.v[5] != 0;
            ativas[3] = p.v[6] != 0;
            break;
        case TipoDeEfeito::Compressor:
            limiar = p.v[0];
            razao = p.v[1];
            ataque = coeficiente(p.v[2], taxa);
            liberacao = coeficiente(p.v[3], taxa);
            joelho = p.v[4];
            compensacao = p.v[5];
            break;
        case TipoDeEfeito::Porta:
            limiar = p.v[0];
            fechado = db(p.v[1]);
            ataque = coeficiente(p.v[2], taxa);
            liberacao = coeficiente(p.v[3], taxa);
            break;
        case TipoDeEfeito::Limitador:
            teto = db(p.v[0]);
            liberacao = coeficiente(p.v[1], taxa);
            break;
        case TipoDeEfeito::Desesser:
            coeficientesBiquad(BIQUAD_PASSA_ALTA, p.v[0], taxa, RAIZ_DE_MEIO, 0, c);
            bandas[0].definir(c);
            limiar = p.v[1];
            maximo = p.v[2];
            break;
        case TipoDeEfeito::Saturacao:
            drive = db(p.v[0]);
            normal = tangenteH(drive);
            mistura = p.v[1];
            saida = db(p.v[2]);
            break;
        case TipoDeEfeito::Atraso:
            atraso = static_cast<uint32_t>(limitar(arredondar((p.v[0] * taxa) / 1000), 1, tamanho - 1));
            retorno = p.v[1];
            mistura = p.v[2];
            break;
        case TipoDeEfeito::Reverberacao: {
            retorno = p.v[0] * 0.28 + 0.7;
            amortecimento = p.v[1] * 0.4;
            const double molhado = p.v[3] * 3;
            molhado1 = molhado * (p.v[2] / 2 + 0.5);
            molhado2 = molhado * ((1 - p.v[2]) / 2);
            seco = 1 - p.v[3];
            break;
        }
        case TipoDeEfeito::Coro:
            passo = (DOIS_PI * p.v[0]) / taxa;
            profundidade = (p.v[1] * taxa) / 1000;
            mistura = p.v[2];
            break;
        case TipoDeEfeito::Tremolo:
            passo = (DOIS_PI * p.v[0]) / taxa;
            profundidade = p.v[1];
            break;
        case TipoDeEfeito::Largura:
            largura = p.v[0];
            break;
        case TipoDeEfeito::Ganho:
            ganho = db(p.v[0]);
            break;
        default:
            break;
    }
}

namespace {

inline double lerDoCoro(const float* buffer, uint32_t tamanho, uint32_t escrita, double atraso) {
    double posicao = escrita - atraso;
    while (posicao < 0) posicao += tamanho;
    const uint32_t i0 = static_cast<uint32_t>(posicao);
    const double fracao = posicao - i0;
    const uint32_t i1 = i0 + 1 == tamanho ? 0 : i0 + 1;
    return buffer[i0] + (static_cast<double>(buffer[i1]) - buffer[i0]) * fracao;
}

inline double canalDoReverb(Processador& r, double entrada, int c) {
    double saida = 0;
    const double d1 = r.amortecimento;
    const double d2 = 1 - d1;
    for (int k = 0; k < 8; k += 1) {
        LinhaDeAtraso& pente = r.pentes[c][k];
        const double valor = pente.b[pente.i];
        pente.f = anti(valor * d2 + pente.f * d1);
        pente.b[pente.i] = static_cast<float>(entrada + pente.f * r.retorno);
        pente.i += 1;
        if (pente.i == pente.b.n) pente.i = 0;
        saida += valor;
    }
    for (int k = 0; k < 4; k += 1) {
        LinhaDeAtraso& filtro = r.passaTudo[c][k];
        const double valor = filtro.b[filtro.i];
        filtro.b[filtro.i] = static_cast<float>(anti(saida + valor * 0.5));
        saida = valor - saida;
        filtro.i += 1;
        if (filtro.i == filtro.b.n) filtro.i = 0;
    }
    return saida;
}

}  // namespace

void Processador::processar(float* esq, float* dir, uint32_t n) {
    switch (tipo) {
        case TipoDeEfeito::PassaAlta:
        case TipoDeEfeito::PassaBaixa:
            for (uint32_t i = 0; i < n; i += 1) {
                esq[i] = static_cast<float>(bandas[0].amostra(esq[i], 0));
                dir[i] = static_cast<float>(bandas[0].amostra(dir[i], 1));
            }
            break;
        case TipoDeEfeito::Equalizador:
            for (int b = 0; b < 4; b += 1) {
                if (!ativas[b]) continue;
                for (uint32_t i = 0; i < n; i += 1) {
                    esq[i] = static_cast<float>(bandas[b].amostra(esq[i], 0));
                    dir[i] = static_cast<float>(bandas[b].amostra(dir[i], 1));
                }
            }
            break;
        case TipoDeEfeito::Compressor: {
            const double inclinacao = 1 / razao - 1;
            for (uint32_t i = 0; i < n; i += 1) {
                const double nivel = maior(absoluto(esq[i]), absoluto(dir[i]));
                const double acima = 20 * logaritmo10(nivel + 1e-12) - limiar;
                double alvo;
                if (joelho > 0 && 2 * absoluto(acima) <= joelho) alvo = (inclinacao * (acima + joelho / 2) * (acima + joelho / 2)) / (2 * joelho);
                else alvo = acima > 0 ? inclinacao * acima : 0;
                const double c = alvo < envelope ? ataque : liberacao;
                envelope = c * envelope + (1 - c) * alvo;
                const double g = potencia(10.0, (envelope + compensacao) / 20);
                esq[i] = static_cast<float>(esq[i] * g);
                dir[i] = static_cast<float>(dir[i] * g);
            }
            break;
        }
        case TipoDeEfeito::Porta:
            for (uint32_t i = 0; i < n; i += 1) {
                const double nivel = maior(absoluto(esq[i]), absoluto(dir[i]));
                detector = maior(nivel, detector * queda);
                const double alvo = 20 * logaritmo10(detector + 1e-12) > limiar ? 1 : fechado;
                const double c = alvo > ganho ? ataque : liberacao;
                ganho = anti(c * ganho + (1 - c) * alvo);
                esq[i] = static_cast<float>(esq[i] * ganho);
                dir[i] = static_cast<float>(dir[i] * ganho);
            }
            break;
        case TipoDeEfeito::Limitador:
            for (uint32_t i = 0; i < n; i += 1) {
                const double pico = maior(absoluto(esq[i]), absoluto(dir[i]));
                const double alvo = pico > teto ? teto / pico : 1;
                ganho = alvo < ganho ? alvo : liberacao * ganho + (1 - liberacao) * alvo;
                esq[i] = static_cast<float>(esq[i] * ganho);
                dir[i] = static_cast<float>(dir[i] * ganho);
            }
            break;
        case TipoDeEfeito::Desesser:
            for (uint32_t i = 0; i < n; i += 1) {
                const double he = bandas[0].amostra(esq[i], 0);
                const double hd = bandas[0].amostra(dir[i], 1);
                const double nivel = maior(absoluto(he), absoluto(hd));
                const double c = nivel > envelope ? ataque : liberacao;
                envelope = anti(c * envelope + (1 - c) * nivel);
                const double reducao = menor(maximo, maior(0, 20 * logaritmo10(envelope + 1e-12) - limiar));
                const double g = potencia(10.0, -reducao / 20);
                esq[i] = static_cast<float>(esq[i] - he + he * g);
                dir[i] = static_cast<float>(dir[i] - hd + hd * g);
            }
            break;
        case TipoDeEfeito::Saturacao:
            for (uint32_t i = 0; i < n; i += 1) {
                esq[i] = static_cast<float>((esq[i] * (1 - mistura) + (tangenteH(drive * esq[i]) / normal) * mistura) * saida);
                dir[i] = static_cast<float>((dir[i] * (1 - mistura) + (tangenteH(drive * dir[i]) / normal) * mistura) * saida);
            }
            break;
        case TipoDeEfeito::Ganho:
            for (uint32_t i = 0; i < n; i += 1) {
                esq[i] = static_cast<float>(esq[i] * ganho);
                dir[i] = static_cast<float>(dir[i] * ganho);
            }
            break;
        case TipoDeEfeito::Largura:
            for (uint32_t i = 0; i < n; i += 1) {
                const double meio = (static_cast<double>(esq[i]) + dir[i]) * 0.5;
                const double lado = (static_cast<double>(esq[i]) - dir[i]) * 0.5 * largura;
                esq[i] = static_cast<float>(meio + lado);
                dir[i] = static_cast<float>(meio - lado);
            }
            break;
        case TipoDeEfeito::Tremolo:
            for (uint32_t i = 0; i < n; i += 1) {
                const double g = 1 - profundidade * 0.5 * (1 + seno(fase));
                esq[i] = static_cast<float>(esq[i] * g);
                dir[i] = static_cast<float>(dir[i] * g);
                fase += passo;
                if (fase >= DOIS_PI) fase -= DOIS_PI;
            }
            break;
        case TipoDeEfeito::Atraso: {
            float* be = buffer[0].dados;
            float* bd = buffer[1].dados;
            for (uint32_t i = 0; i < n; i += 1) {
                const uint32_t leitura = escrita >= atraso ? escrita - atraso : escrita + tamanho - atraso;
                const double de = be[leitura];
                const double dd = bd[leitura];
                be[escrita] = static_cast<float>(anti(esq[i] + de * retorno));
                bd[escrita] = static_cast<float>(anti(dir[i] + dd * retorno));
                esq[i] = static_cast<float>(esq[i] * (1 - mistura) + de * mistura);
                dir[i] = static_cast<float>(dir[i] * (1 - mistura) + dd * mistura);
                escrita += 1;
                if (escrita == tamanho) escrita = 0;
            }
            break;
        }
        case TipoDeEfeito::Coro: {
            float* be = buffer[0].dados;
            float* bd = buffer[1].dados;
            for (uint32_t i = 0; i < n; i += 1) {
                be[escrita] = esq[i];
                bd[escrita] = dir[i];
                const double ae = base + profundidade * seno(fase);
                const double ad = base + profundidade * seno(fase + PI / 2);
                const double we = lerDoCoro(be, tamanho, escrita, ae);
                const double wd = lerDoCoro(bd, tamanho, escrita, ad);
                esq[i] = static_cast<float>(esq[i] * (1 - mistura) + we * mistura);
                dir[i] = static_cast<float>(dir[i] * (1 - mistura) + wd * mistura);
                escrita += 1;
                if (escrita == tamanho) escrita = 0;
                fase += passo;
                if (fase >= DOIS_PI) fase -= DOIS_PI;
            }
            break;
        }
        case TipoDeEfeito::Reverberacao:
            for (uint32_t i = 0; i < n; i += 1) {
                const double entrada = (static_cast<double>(esq[i]) + dir[i]) * 0.015;
                const double oe = canalDoReverb(*this, entrada, 0);
                const double od = canalDoReverb(*this, entrada, 1);
                const double le = esq[i];
                const double ld = dir[i];
                esq[i] = static_cast<float>(oe * molhado1 + od * molhado2 + le * seco);
                dir[i] = static_cast<float>(od * molhado1 + oe * molhado2 + ld * seco);
            }
            break;
        default:
            break;
    }
}

}  // namespace slt
