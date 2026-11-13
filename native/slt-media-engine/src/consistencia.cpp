// Consistência com a mistura: o que a soma das faixas não explica (o resíduo) volta para
// as faixas por região tempo-frequência, na proporção da energia de cada uma, ou fica
// separado quando o chamador prefere não adivinhar de quem é.
//
// Serve para modelos que devolvem áudio (não máscaras): com máscaras de Wiener a soma já é
// a mistura. Somar certo não quer dizer separar bem; isto só garante que nada se perdeu.
#include "nucleo.h"

namespace slt {

namespace {

constexpr uint32_t QUADROS_POR_TRECHO = 64;

void carregar(const float* origem, uint64_t comprimento, int64_t inicio, uint32_t n, float* destino) {
    memset(destino, 0, static_cast<size_t>(n) * sizeof(float));
    if (origem == nullptr) return;
    const int64_t a = inicio < 0 ? 0 : inicio;
    const int64_t fim = inicio + static_cast<int64_t>(n);
    const int64_t b = fim > static_cast<int64_t>(comprimento) ? static_cast<int64_t>(comprimento) : fim;
    if (b <= a) return;
    memcpy(destino + (a - inicio), origem + a, static_cast<size_t>(b - a) * sizeof(float));
}

void residuoNoTempo(const float* misturaEsquerda, const float* misturaDireita, float* const* faixas, uint32_t quantasFaixas, uint64_t quadros, float* residuoEsquerdo, float* residuoDireito) {
    const float* mistura[2] = {misturaEsquerda, misturaDireita != nullptr ? misturaDireita : misturaEsquerda};
    float* residuo[2] = {residuoEsquerdo, residuoDireito};
    for (int c = 0; c < 2; c += 1) {
        if (residuo[c] == nullptr) continue;
        for (uint64_t i = 0; i < quadros; i += 1) {
            double soma = 0;
            for (uint32_t f = 0; f < quantasFaixas; f += 1) {
                const float* canal = faixas[f * 2 + c] != nullptr ? faixas[f * 2 + c] : faixas[f * 2];
                soma += canal[i];
            }
            residuo[c][i] = static_cast<float>(mistura[c][i] - soma);
        }
    }
}

}  // namespace

slt_result consistenciaDaMistura(const slt_consistency_config* config, const float* misturaEsquerda, const float* misturaDireita, float* const* faixas, uint32_t quantasFaixas, uint64_t quadros, float* residuoEsquerdo, float* residuoDireito) {
    if (config == nullptr || config->struct_size < sizeof(slt_consistency_config)) return SLT_ERROR_UNSUPPORTED_VERSION;
    if (misturaEsquerda == nullptr || faixas == nullptr || quantasFaixas == 0 || quantasFaixas > 16 || quadros == 0) return SLT_ERROR_INVALID_ARGUMENT;
    for (uint32_t f = 0; f < quantasFaixas; f += 1)
        if (faixas[f * 2] == nullptr) return SLT_ERROR_INVALID_ARGUMENT;
    if (config->keep_residual != 0) {
        if (residuoEsquerdo == nullptr) return SLT_ERROR_INVALID_ARGUMENT;
        residuoNoTempo(misturaEsquerda, misturaDireita, faixas, quantasFaixas, quadros, residuoEsquerdo, residuoDireito);
        return SLT_OK;
    }
    const uint32_t janela = config->window == 0 ? 2048 : config->window;
    const uint32_t passo = config->hop == 0 ? janela / 4 : config->hop;
    // A sobreposição e soma só reconstrói o sinal com Hann e passo de até um quarto da janela.
    if (janela < 64 || (janela & (janela - 1)) != 0 || passo == 0 || passo > janela / 4 || janela % passo != 0) return SLT_ERROR_INVALID_ARGUMENT;

    ProcessadorEspectral mistura;
    Vetor<ProcessadorEspectral> processadores;
    Vetor<float> entrada[2];
    Vetor<float> saida[2];
    slt_result resultado = SLT_ERROR_OUT_OF_MEMORY;
    bool ok = mistura.criar(quadros, janela, passo, QUADROS_POR_TRECHO, 1) && processadores.criar(quantasFaixas);
    if (ok) {
        // A memória zerada de `criar` já é um ProcessadorEspectral vazio válido, menos `normal`.
        for (uint32_t f = 0; f < quantasFaixas && ok; f += 1) ok = processadores[f].criar(quadros, janela, passo, QUADROS_POR_TRECHO, 1);
    }
    const size_t tamanhoDaJanela = static_cast<size_t>(QUADROS_POR_TRECHO - 1) * passo + janela;
    for (int c = 0; c < 2 && ok; c += 1) ok = entrada[c].criar(tamanhoDaJanela) && saida[c].criar(mistura.tamanhoDoAcumulado());
    if (ok) {
        const uint32_t B = mistura.B;
        for (uint32_t s = 0; s < mistura.trechos; s += 1) {
            int64_t inicio = 0;
            uint32_t n = 0;
            mistura.janelaDoTrecho(s, &inicio, &n);
            carregar(misturaEsquerda, quadros, inicio, n, entrada[0].dados);
            carregar(misturaDireita != nullptr ? misturaDireita : misturaEsquerda, quadros, inicio, n, entrada[1].dados);
            mistura.analisar(s, entrada[0].dados, entrada[1].dados);
            for (uint32_t f = 0; f < quantasFaixas; f += 1) {
                carregar(faixas[f * 2], quadros, inicio, n, entrada[0].dados);
                carregar(faixas[f * 2 + 1] != nullptr ? faixas[f * 2 + 1] : faixas[f * 2], quadros, inicio, n, entrada[1].dados);
                processadores[f].analisar(s, entrada[0].dados, entrada[1].dados);
            }
            const uint32_t q = mistura.quadrosDoTrecho(s);
            const size_t celulas = static_cast<size_t>(q) * B;
            for (int c = 0; c < 2; c += 1) {
                for (size_t i = 0; i < celulas; i += 1) {
                    double somaRe = 0;
                    double somaIm = 0;
                    double energia = 0;
                    for (uint32_t f = 0; f < quantasFaixas; f += 1) {
                        const double re = processadores[f].re[c][i];
                        const double im = processadores[f].im[c][i];
                        somaRe += re;
                        somaIm += im;
                        energia += re * re + im * im;
                    }
                    const double residuoRe = mistura.re[c][i] - somaRe;
                    const double residuoIm = mistura.im[c][i] - somaIm;
                    for (uint32_t f = 0; f < quantasFaixas; f += 1) {
                        const double re = processadores[f].re[c][i];
                        const double im = processadores[f].im[c][i];
                        const double peso = energia > 1e-20 ? (re * re + im * im) / energia : 1.0 / quantasFaixas;
                        processadores[f].re[c][i] = static_cast<float>(re + peso * residuoRe);
                        processadores[f].im[c][i] = static_cast<float>(im + peso * residuoIm);
                    }
                }
            }
            // O que sai aqui fica antes da janela do próximo trecho: escrever no lugar é seguro.
            for (uint32_t f = 0; f < quantasFaixas; f += 1) {
                int64_t de = 0;
                const uint32_t prontas = processadores[f].sintetizar(s, 0, nullptr, saida[0].dados, saida[1].dados, &de);
                if (prontas == 0) continue;
                memcpy(faixas[f * 2] + de, saida[0].dados, static_cast<size_t>(prontas) * sizeof(float));
                if (faixas[f * 2 + 1] != nullptr) memcpy(faixas[f * 2 + 1] + de, saida[1].dados, static_cast<size_t>(prontas) * sizeof(float));
            }
        }
        residuoNoTempo(misturaEsquerda, misturaDireita, faixas, quantasFaixas, quadros, residuoEsquerdo, residuoDireito);
        resultado = SLT_OK;
    }
    for (int c = 0; c < 2; c += 1) {
        entrada[c].soltar();
        saida[c].soltar();
    }
    for (size_t f = 0; f < processadores.n; f += 1) processadores[f].soltar();
    processadores.soltar();
    mistura.soltar();
    return resultado;
}

}  // namespace slt
