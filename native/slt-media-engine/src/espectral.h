// FFT e STFT em trechos: a mesma matemática de packages/converter-core/musica-separacao.mjs
// (Fft e ProcessadorEspectral), para o resultado bater com o JS, o C# e o Kotlin.
#pragma once

#include "plataforma.h"

namespace slt {

/** FFT complexa radix-2 no lugar, com tabelas prontas. */
struct Fft {
    uint32_t n = 0;
    Vetor<double> cos_;
    Vetor<double> sen_;
    Vetor<uint32_t> inverso;

    bool criar(uint32_t tamanho);
    void soltar();
    /** sinal = −1 para a direta, +1 para a inversa (sem dividir por n). */
    void transformar(double* re, double* im, int sinal) const;
};

/**
 * Análise e síntese por sobreposição e soma. O sinal ganha `janela` zeros nos dois lados,
 * cada quadro é a FFT de `janela` amostras com Hann periódica, a cada `passo` amostras.
 */
struct ProcessadorEspectral {
    uint64_t comprimento = 0;
    uint32_t N = 0;  // janela
    uint32_t H = 0;  // passo
    uint32_t T = 0;  // quadros por trecho
    uint32_t B = 0;  // bandas da FFT (N/2 + 1)
    uint32_t saidas = 0;
    uint64_t quadros = 0;
    uint32_t trechos = 0;
    double normal = 1;
    Fft fft;
    Vetor<double> w;
    Vetor<float> re[2];
    Vetor<float> im[2];
    Vetor<double> br;
    Vetor<double> bi;
    Vetor<double> acumulado;  // [saidas][2][T*H + N]
    Vetor<uint64_t> emitidos;
    Vetor<uint64_t> base;

    bool criar(uint64_t comprimento_, uint32_t janela, uint32_t passo, uint32_t quadrosPorTrecho, uint32_t saidas_);
    void soltar();

    uint32_t quadrosDoTrecho(uint32_t s) const;
    /** Janela do sinal original que o trecho lê: começa em `inicio` (pode ser negativo). */
    void janelaDoTrecho(uint32_t s, int64_t* inicio, uint32_t* n) const;
    size_t tamanhoDoAcumulado() const { return static_cast<size_t>(T) * H + N; }
    /** STFT do trecho a partir das amostras já com zeros onde o sinal não existe. */
    void analisar(uint32_t s, const float* esquerdo, const float* direito);
    void magnitude(uint32_t q, uint32_t bandas, float* destino) const;
    /**
     * Aplica a máscara ([2][T*B]; nula = sem máscara) ao trecho analisado e copia as amostras
     * já completas da saída. Devolve quantas; `inicio` recebe a posição no sinal.
     */
    uint32_t sintetizar(uint32_t s, uint32_t saida, const float* const* mascara, float* esquerdo, float* direito, int64_t* inicio);
};

}  // namespace slt
