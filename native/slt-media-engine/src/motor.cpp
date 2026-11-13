// Motor de um barramento: ganho e pan com a lei do projeto (potência constante sem reforço,
// a de ganhosEstereo em musica.mjs), suavizados em ~10 ms. É o primeiro bloco do mixer
// nativo e o único código daqui feito para o callback de áudio: sem alocação, trava ou E/S.
#include "nucleo.h"

namespace slt {

namespace {

int64_t empacotar(float esquerdo, float direito) {
    uint32_t a = 0;
    uint32_t b = 0;
    memcpy(&a, &esquerdo, sizeof(a));
    memcpy(&b, &direito, sizeof(b));
    return static_cast<int64_t>((static_cast<uint64_t>(a) << 32) | b);
}

void desempacotar(int64_t valor, float* esquerdo, float* direito) {
    const uint32_t a = static_cast<uint32_t>(static_cast<uint64_t>(valor) >> 32);
    const uint32_t b = static_cast<uint32_t>(static_cast<uint64_t>(valor) & 0xFFFFFFFFull);
    memcpy(esquerdo, &a, sizeof(a));
    memcpy(direito, &b, sizeof(b));
}

inline float aproximar(float atual, float alvo, float passo) {
    const float falta = alvo - atual;
    if (falta > passo) return atual + passo;
    if (falta < -passo) return atual - passo;
    return alvo;
}

}  // namespace

bool Motor::configurar(uint32_t taxa_, uint32_t canais_) {
    if (taxa_ < 8000 || taxa_ > 192000 || canais_ < 1 || canais_ > 2) return false;
    taxa = taxa_;
    canais = canais_;
    passoDaRampa = static_cast<float>(1.0 / (0.010 * taxa));
    atual[0] = atual[1] = 1;
    alvo.guardar(empacotar(1, 1));
    return true;
}

bool Motor::definir(float ganhoDb, float pan) {
    if (!(ganhoDb >= -120.0f) || ganhoDb > 24.0f || !(pan >= -100.0f) || pan > 100.0f) return false;
    const double ganho = ganhoDb <= -60.0f ? 0.0 : dbParaLinear(ganhoDb);
    const double angulo = ((pan + 100.0) / 200.0) * (PI / 2);
    const double raizDeDois = 1.4142135623730951;
    double esquerdo = raizDeDois * cosseno(angulo);
    double direito = raizDeDois * seno(angulo);
    if (esquerdo > 1) esquerdo = 1;
    if (direito > 1) direito = 1;
    if (canais == 1) esquerdo = direito = 1;
    alvo.guardar(empacotar(static_cast<float>(ganho * esquerdo), static_cast<float>(ganho * direito)));
    return true;
}

void Motor::processar(const float* entrada, float* saida, uint32_t quadros) {
    float alvos[2];
    desempacotar(alvo.ler(), &alvos[0], &alvos[1]);
    float esquerdo = atual[0];
    float direito = atual[1];
    if (canais == 1) {
        for (uint32_t i = 0; i < quadros; i += 1) {
            esquerdo = aproximar(esquerdo, alvos[0], passoDaRampa);
            saida[i] = entrada[i] * esquerdo;
        }
        direito = esquerdo;
    } else {
        for (uint32_t i = 0; i < quadros; i += 1) {
            esquerdo = aproximar(esquerdo, alvos[0], passoDaRampa);
            direito = aproximar(direito, alvos[1], passoDaRampa);
            saida[2 * i] = entrada[2 * i] * esquerdo;
            saida[2 * i + 1] = entrada[2 * i + 1] * direito;
        }
    }
    atual[0] = esquerdo;
    atual[1] = direito;
}

}  // namespace slt
