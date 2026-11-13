// Declarações internas do núcleo (o que api.cpp expõe pela C ABI).
#pragma once

#include "../include/slt_media_api.h"
#include "espectral.h"

namespace slt {

void mascaraDaFaixa(const ProcessadorEspectral& p, const float* estimativas, uint32_t instrumentos, const uint32_t* indices, uint32_t quantosIndices, uint32_t q, uint32_t bandas, uint32_t perfil, float* const* destino);

/* ---------------------------------------------------------------- filtros */

/** Biquad (forma direta transposta II) com os coeficientes do Audio EQ Cookbook. */
struct Biquad {
    double b0 = 1, b1 = 0, b2 = 0, a1 = 0, a2 = 0;
    double z1[2] = {0, 0};
    double z2[2] = {0, 0};

    void pico(double taxa, double frequencia, double q, double ganhoDb);
    void prateleiraBaixa(double taxa, double frequencia, double ganhoDb);
    void prateleiraAlta(double taxa, double frequencia, double ganhoDb);
    void passaAlta(double taxa, double frequencia, double q);
    void passaBanda(double taxa, double frequencia, double q);
    inline double processar(double x, int canal) {
        const double y = b0 * x + z1[canal];
        z1[canal] = b1 * x - a1 * y + z2[canal];
        z2[canal] = b2 * x - a2 * y;
        return y;
    }
};

/** Seguidor de envelope de um polo, com ataque e liberação em milissegundos. */
struct Envelope {
    double ataque = 0;
    double liberacao = 0;
    double valor = 0;

    void configurar(double taxa, double ataqueMs, double liberacaoMs);
    inline double seguir(double x) {
        const double coeficiente = x > valor ? ataque : liberacao;
        valor = x + (valor - x) * coeficiente;
        return valor;
    }
};

/* ---------------------------------------------------------------- restauração */

struct Restaurador {
    uint32_t taxa = 0;
    uint32_t perfil = 0;
    double intensidade = 0;  // 0 a 1
    // Equalização fixa do perfil (até 4 bandas) e corte de subgrave.
    Biquad fixos[4];
    int quantosFixos = 0;
    // Banda dinâmica: corta a aspereza só quando ela passa do resto do som.
    bool temDinamica = false;
    double dinamicaFrequencia = 0;
    double dinamicaQ = 1;
    double dinamicaLimiar = 0;
    double dinamicaMaximoDb = 0;
    Biquad detector;
    Biquad corte;
    Envelope envelopeDaBanda;
    Envelope envelopeGeral;
    double reducaoAtualDb = 0;
    uint32_t contador = 0;
    // Ataques (bateria).
    double ataqueMaximoDb = 0;
    Envelope rapido;
    Envelope lento;
    // Saturação leve em paralelo.
    double saturacaoMistura = 0;
    double saturacaoDrive = 1;
    // Compressão em paralelo.
    double paralelaMistura = 0;
    Envelope envelopeDaParalela;
    // Medição para o A/B nivelado.
    double energiaDeEntrada = 0;
    double energiaDeSaida = 0;
    double picoDeEntrada = 0;
    double picoDeSaida = 0;

    bool configurar(uint32_t taxa_, uint32_t perfil_, double intensidade_);
    void processar(float* esquerdo, float* direito, uint32_t quadros);
    double ganhoDeNivelamento() const;
};

/* ---------------------------------------------------------------- consistência */

slt_result consistenciaDaMistura(const slt_consistency_config* config, const float* misturaEsquerda, const float* misturaDireita, float* const* faixas, uint32_t quantasFaixas, uint64_t quadros, float* residuoEsquerdo, float* residuoDireito);

/* ---------------------------------------------------------------- motor */

struct Motor {
    uint32_t taxa = 0;
    uint32_t canais = 0;
    Atomico64 alvo;       // dois float32 (ganho esquerdo e direito) num valor só
    float atual[2] = {1, 1};
    float passoDaRampa = 0;

    bool configurar(uint32_t taxa_, uint32_t canais_);
    bool definir(float ganhoDb, float pan);
    void processar(const float* entrada, float* saida, uint32_t quadros);
};

}  // namespace slt
