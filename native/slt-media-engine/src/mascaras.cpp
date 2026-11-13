// Máscaras de Wiener (expoente 2) a partir das estimativas do modelo de separação.
//
// O modelo só estima as primeiras `bandas` frequências (1.024 de 2.049: até ~11 kHz a
// 44,1 kHz). Acima disso a máscara precisa ser inventada. São dois perfis:
//
//  - clássico: a média da máscara do quadro em todas as bandas de baixo. É o que os núcleos
//    em JS, C# e Kotlin fazem; fica aqui para o resultado ser o mesmo.
//  - refinado: quem manda nos agudos é quem manda na oitava de cima que o modelo enxerga
//    (5,5 a 11 kHz), pesada pela energia da própria mistura; quando essa oitava está quase
//    vazia, volta para a média clássica; e logo acima do limite a máscara continua de onde
//    as últimas bandas pararam, para não haver degrau em 11 kHz.
//
// Os dois são combinações lineares da máscara de baixo com pesos que só dependem da mistura,
// então a soma das máscaras de todos os instrumentos continua 1: a soma das faixas é a mistura.
#include "nucleo.h"

namespace slt {

namespace {

constexpr double EPS = 1e-10;
constexpr uint32_t TRANSICAO = 64;      // bandas de passagem acima do limite do modelo
constexpr uint32_t BORDA = 16;          // últimas bandas do modelo, para a continuidade
constexpr double PISO_DA_CONFIANCA = 1e-3;

}  // namespace

void mascaraDaFaixa(const ProcessadorEspectral& p, const float* estimativas, uint32_t instrumentos, const uint32_t* indices, uint32_t quantosIndices, uint32_t q, uint32_t bandas, uint32_t perfil, float* const* destino) {
    const uint32_t T = p.T;
    const uint32_t B = p.B;
    const size_t plano = static_cast<size_t>(2) * T * bandas;
    for (int c = 0; c < 2; c += 1) {
        float* m = destino[c];
        for (uint32_t f = 0; f < q; f += 1) {
            const size_t base = (static_cast<size_t>(c) * T + f) * bandas;
            const size_t linha = static_cast<size_t>(f) * B;
            double somaDaLinha = 0;
            for (uint32_t k = 0; k < bandas; k += 1) {
                double total = EPS;
                double parte = 0;
                for (uint32_t i = 0; i < instrumentos; i += 1) {
                    const double e = estimativas[i * plano + base + k];
                    total += e * e;
                }
                for (uint32_t j = 0; j < quantosIndices; j += 1) {
                    const double e = estimativas[indices[j] * plano + base + k];
                    parte += e * e + EPS / instrumentos;
                }
                const double valor = parte / total;
                m[linha + k] = static_cast<float>(valor);
                somaDaLinha += valor;
            }
            const double media = somaDaLinha / bandas;
            if (perfil != SLT_MASK_REFINED || bandas < 2 * BORDA) {
                for (uint32_t k = bandas; k < B; k += 1) m[linha + k] = static_cast<float>(media);
                continue;
            }
            // Oitava de cima do que o modelo enxerga, pesada pela energia da mistura.
            double energiaDaLinha = 0;
            double energiaDeCima = 0;
            double pesada = 0;
            const float* re = p.re[c].dados + linha;
            const float* im = p.im[c].dados + linha;
            for (uint32_t k = 0; k < bandas; k += 1) {
                const double e = static_cast<double>(re[k]) * re[k] + static_cast<double>(im[k]) * im[k];
                energiaDaLinha += e;
                if (k >= bandas / 2) {
                    energiaDeCima += e;
                    pesada += e * m[linha + k];
                }
            }
            const double confianca = energiaDeCima / (energiaDeCima + PISO_DA_CONFIANCA * energiaDaLinha + 1e-20);
            const double deCima = energiaDeCima > 0 ? pesada / energiaDeCima : media;
            const double alvo = confianca * deCima + (1 - confianca) * media;
            double borda = 0;
            for (uint32_t k = bandas - BORDA; k < bandas; k += 1) borda += m[linha + k];
            borda /= BORDA;
            for (uint32_t k = bandas; k < B; k += 1) {
                const uint32_t distancia = k - bandas;
                const double t = distancia >= TRANSICAO ? 1.0 : (distancia + 1.0) / (TRANSICAO + 1.0);
                m[linha + k] = static_cast<float>(borda + (alvo - borda) * t);
            }
        }
    }
}

}  // namespace slt
