// Os efeitos da criação musical no núcleo nativo: a mesma conta de musica-dsp.mjs (e de
// MusicDsp.cs / DspDaMusica.kt), amostra por amostra, em double, sem alocar depois de criado.
#pragma once

#include "json.h"

namespace slt {

enum class TipoDeEfeito : uint8_t { PassaAlta, PassaBaixa, Equalizador, Compressor, Porta, Limitador, Desesser, Saturacao, Atraso, Reverberacao, Coro, Tremolo, Largura, Ganho, Desconhecido };

constexpr int PARAMETROS_POR_EFEITO = 7;

/** Os valores de um efeito do projeto, já com os padrões do catálogo. */
struct ParametrosDoEfeito {
    TipoDeEfeito tipo = TipoDeEfeito::Desconhecido;
    bool ligado = true;
    double v[PARAMETROS_POR_EFEITO] = {0, 0, 0, 0, 0, 0, 0};

    /** Lê do JSON do projeto. Falso quando o tipo não existe. */
    bool ler(const No& efeito);
};

/** Biquad estéreo na forma direta I, como o do JS (x1 x2 y1 y2 por canal). */
struct BiquadDireto {
    double b0 = 1, b1 = 0, b2 = 0, a1 = 0, a2 = 0;
    double e[8] = {0, 0, 0, 0, 0, 0, 0, 0};

    void definir(const double* c);
    double amostra(double x, int canal);
};

struct LinhaDeAtraso {
    Vetor<float> b;
    uint32_t i = 0;
    double f = 0;
};

/** O processador de um efeito. `criar` aloca (thread de controle); o resto é de tempo real. */
struct Processador {
    TipoDeEfeito tipo = TipoDeEfeito::Desconhecido;
    double taxa = 0;
    BiquadDireto bandas[4];
    bool ativas[4] = {false, false, false, false};
    // Dinâmica.
    double limiar = 0, razao = 1, joelho = 0, compensacao = 0, ataque = 0, liberacao = 0, envelope = 0;
    double detector = 0, ganho = 1, queda = 0, fechado = 0, teto = 1, maximo = 0;
    // Cor, modulação e utilidades.
    double drive = 1, normal = 1, mistura = 0, saida = 1, largura = 1, fase = 0, passo = 0, profundidade = 0;
    // Atraso e coro.
    Vetor<float> buffer[2];
    uint32_t tamanho = 0;
    uint32_t escrita = 0;
    uint32_t atraso = 1;
    double retorno = 0;
    double base = 0;
    // Reverberação: 8 pentes e 4 passa-tudo por canal.
    LinhaDeAtraso pentes[2][8];
    LinhaDeAtraso passaTudo[2][4];
    double amortecimento = 0, molhado1 = 0, molhado2 = 0, seco = 1;

    bool criar(const ParametrosDoEfeito& parametros, double taxa_);
    void soltar();
    void atualizar(const ParametrosDoEfeito& parametros);
    void processar(float* esq, float* dir, uint32_t n);
};

/** Quanto o efeito continua soando depois do sinal, em segundos. */
double caudaDoEfeito(const ParametrosDoEfeito& parametros);

void coeficientesBiquad(int tipo, double frequencia, double taxa, double q, double ganhoDb, double* c);

}  // namespace slt
