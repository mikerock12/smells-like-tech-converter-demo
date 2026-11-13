// Restauração por instrumento, fora do tempo real e sem tocar no áudio bruto: o chamador
// processa uma cópia. A intensidade (0 a 100) escala cada ajuste; em 0 o áudio sai igual.
//
// O que cada perfil faz, e por quê (docs/criacao-musical.md, "Restauração"):
//  - voz: tira o grave que vazou, devolve presença e ar, e segura a aspereza/sibilância só
//    quando ela passa do resto da voz. Não tira reverberação nem ruído.
//  - bateria: corpo de bumbo e caixa, ataque um pouco mais firme, compressão em paralelo
//    leve, e os pratos seguros só quando estridentes.
//  - baixo: fundamental e definição de nota, menos vazamento de pratos e guitarra, e
//    harmônicos leves para o baixo aparecer em caixa pequena. Sem reforço fixo de subgrave.
//  - outros (guitarra, teclados): médios de volta, aspereza segura só quando sobra, e menos
//    vazamento de pratos.
//
// Nada aqui recria o que a separação perdeu: é equalização e dinâmica, com limites fixos.
#include "nucleo.h"

namespace slt {

namespace {

constexpr uint32_t PASSO_DA_DINAMICA = 32;

struct Coeficientes {
    double b0, b1, b2, a0, a1, a2;
};

void aplicar(Biquad& filtro, const Coeficientes& c) {
    filtro.b0 = c.b0 / c.a0;
    filtro.b1 = c.b1 / c.a0;
    filtro.b2 = c.b2 / c.a0;
    filtro.a1 = c.a1 / c.a0;
    filtro.a2 = c.a2 / c.a0;
}

double frequenciaSegura(double taxa, double frequencia) {
    return limitar(frequencia, 10.0, taxa * 0.45);
}

}  // namespace

void Biquad::pico(double taxa, double frequencia, double q, double ganhoDb) {
    const double a = potencia(10.0, ganhoDb / 40.0);
    const double w0 = 2 * PI * frequenciaSegura(taxa, frequencia) / taxa;
    const double alfa = seno(w0) / (2 * q);
    const double c = cosseno(w0);
    aplicar(*this, {1 + alfa * a, -2 * c, 1 - alfa * a, 1 + alfa / a, -2 * c, 1 - alfa / a});
}

void Biquad::prateleiraBaixa(double taxa, double frequencia, double ganhoDb) {
    const double a = potencia(10.0, ganhoDb / 40.0);
    const double w0 = 2 * PI * frequenciaSegura(taxa, frequencia) / taxa;
    const double c = cosseno(w0);
    const double beta = 2 * raiz(a) * (seno(w0) / 2) * raiz(2.0);
    aplicar(*this, {a * ((a + 1) - (a - 1) * c + beta), 2 * a * ((a - 1) - (a + 1) * c), a * ((a + 1) - (a - 1) * c - beta), (a + 1) + (a - 1) * c + beta, -2 * ((a - 1) + (a + 1) * c), (a + 1) + (a - 1) * c - beta});
}

void Biquad::prateleiraAlta(double taxa, double frequencia, double ganhoDb) {
    const double a = potencia(10.0, ganhoDb / 40.0);
    const double w0 = 2 * PI * frequenciaSegura(taxa, frequencia) / taxa;
    const double c = cosseno(w0);
    const double beta = 2 * raiz(a) * (seno(w0) / 2) * raiz(2.0);
    aplicar(*this, {a * ((a + 1) + (a - 1) * c + beta), -2 * a * ((a - 1) + (a + 1) * c), a * ((a + 1) + (a - 1) * c - beta), (a + 1) - (a - 1) * c + beta, 2 * ((a - 1) - (a + 1) * c), (a + 1) - (a - 1) * c - beta});
}

void Biquad::passaAlta(double taxa, double frequencia, double q) {
    const double w0 = 2 * PI * frequenciaSegura(taxa, frequencia) / taxa;
    const double alfa = seno(w0) / (2 * q);
    const double c = cosseno(w0);
    aplicar(*this, {(1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + alfa, -2 * c, 1 - alfa});
}

void Biquad::passaBanda(double taxa, double frequencia, double q) {
    const double w0 = 2 * PI * frequenciaSegura(taxa, frequencia) / taxa;
    const double alfa = seno(w0) / (2 * q);
    aplicar(*this, {alfa, 0, -alfa, 1 + alfa, -2 * cosseno(w0), 1 - alfa});
}

void Envelope::configurar(double taxa, double ataqueMs, double liberacaoMs) {
    ataque = exponencial(-1.0 / (taxa * ataqueMs / 1000.0));
    liberacao = exponencial(-1.0 / (taxa * liberacaoMs / 1000.0));
    valor = 0;
}

bool Restaurador::configurar(uint32_t taxa_, uint32_t perfil_, double intensidade_) {
    if (taxa_ < 8000 || taxa_ > 192000 || perfil_ > SLT_STEM_OTHER) return false;
    if (!(intensidade_ >= 0) || intensidade_ > 100) return false;
    taxa = taxa_;
    perfil = perfil_;
    intensidade = intensidade_ / 100.0;
    const double i = intensidade;
    const double t = taxa;
    quantosFixos = 0;
    temDinamica = false;
    ataqueMaximoDb = 0;
    saturacaoMistura = 0;
    paralelaMistura = 0;
    switch (perfil) {
        case SLT_STEM_VOCALS:
            fixos[quantosFixos++].prateleiraBaixa(t, 80, -6 * i);
            fixos[quantosFixos++].pico(t, 3000, 0.9, 2 * i);
            fixos[quantosFixos++].prateleiraAlta(t, 10000, 1.5 * i);
            temDinamica = true;
            dinamicaFrequencia = 6500;
            dinamicaQ = 2;
            dinamicaLimiar = 0.4;
            dinamicaMaximoDb = 5 * i;
            break;
        case SLT_STEM_DRUMS:
            fixos[quantosFixos++].prateleiraBaixa(t, 90, 3 * i);
            fixos[quantosFixos++].pico(t, 200, 1.0, 2 * i);
            temDinamica = true;
            dinamicaFrequencia = 8000;
            dinamicaQ = 1.2;
            dinamicaLimiar = 0.45;
            dinamicaMaximoDb = 4 * i;
            ataqueMaximoDb = 3 * i;
            paralelaMistura = 0.25 * i;
            break;
        case SLT_STEM_BASS:
            fixos[quantosFixos++].passaAlta(t, 25, 0.707);
            fixos[quantosFixos++].pico(t, 80, 0.9, 3 * i);
            fixos[quantosFixos++].pico(t, 800, 1.2, 2 * i);
            fixos[quantosFixos++].prateleiraAlta(t, 5000, -4 * i);
            saturacaoMistura = 0.25 * i;
            saturacaoDrive = dbParaLinear(6 * i);
            break;
        default:
            fixos[quantosFixos++].pico(t, 450, 0.8, 3 * i);
            fixos[quantosFixos++].prateleiraAlta(t, 9000, -2 * i);
            temDinamica = true;
            dinamicaFrequencia = 3500;
            dinamicaQ = 1.5;
            dinamicaLimiar = 0.35;
            dinamicaMaximoDb = 5 * i;
            saturacaoMistura = 0.1 * i;
            saturacaoDrive = dbParaLinear(4 * i);
            break;
    }
    if (temDinamica) {
        detector.passaBanda(t, dinamicaFrequencia, dinamicaQ);
        corte.pico(t, dinamicaFrequencia, dinamicaQ, 0);
        envelopeDaBanda.configurar(t, 5, 80);
        envelopeGeral.configurar(t, 50, 200);
    }
    rapido.configurar(t, 1, 20);
    lento.configurar(t, 20, 60);
    envelopeDaParalela.configurar(t, 3, 120);
    reducaoAtualDb = 0;
    contador = 0;
    energiaDeEntrada = energiaDeSaida = 0;
    picoDeEntrada = picoDeSaida = 0;
    return true;
}

void Restaurador::processar(float* esquerdo, float* direito, uint32_t quadros) {
    float* canais[2] = {esquerdo, direito};
    const int quantos = direito != nullptr ? 2 : 1;
    const bool parado = intensidade <= 0;
    const double ganhoDeAtaque = dbParaLinear(ataqueMaximoDb) - 1;
    for (uint32_t n = 0; n < quadros; n += 1) {
        double y[2] = {0, 0};
        for (int c = 0; c < quantos; c += 1) {
            const double x = canais[c][n];
            energiaDeEntrada += x * x;
            if (absoluto(x) > picoDeEntrada) picoDeEntrada = absoluto(x);
            y[c] = x;
        }
        if (!parado) {
            for (int c = 0; c < quantos; c += 1)
                for (int f = 0; f < quantosFixos; f += 1) y[c] = fixos[f].processar(y[c], c);
            if (temDinamica) {
                const double meio = quantos == 2 ? 0.5 * (y[0] + y[1]) : y[0];
                const double banda = envelopeDaBanda.seguir(absoluto(detector.processar(meio, 0)));
                const double geral = envelopeGeral.seguir(absoluto(meio));
                if (contador == 0) {
                    const double razao = banda / (geral + 1e-9);
                    double alvo = 0;
                    if (razao > dinamicaLimiar && geral > 1e-5) alvo = limitar(20 * logaritmo10(razao / dinamicaLimiar), 0, dinamicaMaximoDb);
                    reducaoAtualDb += (alvo - reducaoAtualDb) * 0.25;
                    corte.pico(taxa, dinamicaFrequencia, dinamicaQ, -reducaoAtualDb);
                }
                contador = (contador + 1) % PASSO_DA_DINAMICA;
                for (int c = 0; c < quantos; c += 1) y[c] = corte.processar(y[c], c);
            }
            const double nivel = quantos == 2 ? (absoluto(y[0]) > absoluto(y[1]) ? absoluto(y[0]) : absoluto(y[1])) : absoluto(y[0]);
            if (ataqueMaximoDb > 0) {
                const double r = rapido.seguir(nivel);
                const double l = lento.seguir(nivel);
                const double ganho = 1 + ganhoDeAtaque * limitar((r - l) / (l + 1e-6), 0, 1);
                for (int c = 0; c < quantos; c += 1) y[c] *= ganho;
            }
            if (paralelaMistura > 0) {
                // 4:1 acima de −30 dBFS, +9 dB de compensação, somada ao som direto.
                const double e = envelopeDaParalela.seguir(nivel);
                const double limiar = 0.0316;
                const double reducao = e > limiar ? potencia(e / limiar, -0.75) : 1.0;
                const double paralela = paralelaMistura * reducao * 2.818;
                for (int c = 0; c < quantos; c += 1) y[c] += y[c] * paralela;
            }
            if (saturacaoMistura > 0) {
                for (int c = 0; c < quantos; c += 1) y[c] = (1 - saturacaoMistura) * y[c] + saturacaoMistura * tangenteH(saturacaoDrive * y[c]) / saturacaoDrive;
            }
        }
        for (int c = 0; c < quantos; c += 1) {
            energiaDeSaida += y[c] * y[c];
            if (absoluto(y[c]) > picoDeSaida) picoDeSaida = absoluto(y[c]);
            canais[c][n] = static_cast<float>(y[c]);
        }
    }
}

double Restaurador::ganhoDeNivelamento() const {
    if (energiaDeSaida <= 0 || energiaDeEntrada <= 0) return 1;
    double ganho = limitar(raiz(energiaDeEntrada / energiaDeSaida), 0.2512, 3.981);
    // O nivelamento não pode criar um pico que o bruto não tinha (nem passar de 0 dBFS).
    const double teto = picoDeEntrada > 1 ? picoDeEntrada : 1;
    if (picoDeSaida * ganho > teto) ganho = teto / picoDeSaida;
    return ganho;
}

}  // namespace slt
