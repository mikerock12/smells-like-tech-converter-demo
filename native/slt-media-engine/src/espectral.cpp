#include "espectral.h"
#include "slt_media_api.h"

namespace slt {

bool Fft::criar(uint32_t tamanho) {
    soltar();
    if (tamanho < 2 || (tamanho & (tamanho - 1)) != 0) return false;
    n = tamanho;
    if (!cos_.criar(n / 2) || !sen_.criar(n / 2) || !inverso.criar(n)) {
        soltar();
        return false;
    }
    for (uint32_t i = 0; i < n / 2; i += 1) {
        cos_[i] = cosseno((2 * PI * i) / n);
        sen_[i] = seno((2 * PI * i) / n);
    }
    uint32_t bits = 0;
    while ((1u << bits) < n) bits += 1;
    for (uint32_t i = 0; i < n; i += 1) {
        uint32_t r = 0;
        for (uint32_t b = 0; b < bits; b += 1) r |= ((i >> b) & 1u) << (bits - 1 - b);
        inverso[i] = r;
    }
    return true;
}

void Fft::soltar() {
    cos_.soltar();
    sen_.soltar();
    inverso.soltar();
    n = 0;
}

void Fft::transformar(double* re, double* im, int sinal) const {
    for (uint32_t i = 0; i < n; i += 1) {
        const uint32_t j = inverso[i];
        if (j > i) {
            double t = re[i];
            re[i] = re[j];
            re[j] = t;
            t = im[i];
            im[i] = im[j];
            im[j] = t;
        }
    }
    for (uint32_t tamanho = 2; tamanho <= n; tamanho *= 2) {
        const uint32_t metade = tamanho / 2;
        const uint32_t passo = n / tamanho;
        for (uint32_t inicio = 0; inicio < n; inicio += tamanho) {
            for (uint32_t k = 0; k < metade; k += 1) {
                const double wr = cos_[k * passo];
                const double wi = sinal * sen_[k * passo];
                const uint32_t a = inicio + k;
                const uint32_t b = a + metade;
                const double tr = re[b] * wr - im[b] * wi;
                const double ti = re[b] * wi + im[b] * wr;
                re[b] = re[a] - tr;
                im[b] = im[a] - ti;
                re[a] += tr;
                im[a] += ti;
            }
        }
        if (tamanho == n) break;  // evita o estouro de `tamanho *= 2` quando n = 2^31
    }
}

bool ProcessadorEspectral::criar(uint64_t comprimento_, uint32_t janela, uint32_t passo, uint32_t quadrosPorTrecho, uint32_t saidas_) {
    soltar();
    if (janela < 16 || janela > (1u << 16) || passo == 0 || passo > janela || quadrosPorTrecho == 0 || quadrosPorTrecho > 4096 || saidas_ == 0 || saidas_ > 16) return false;
    if (comprimento_ > (static_cast<uint64_t>(1) << 40)) return false;
    comprimento = comprimento_;
    N = janela;
    H = passo;
    T = quadrosPorTrecho;
    B = janela / 2 + 1;
    saidas = saidas_;
    quadros = (comprimento + N) / H + 1;
    const uint64_t quantos = (quadros + T - 1) / T;
    if (quantos > 0xFFFFFFFFull) return false;
    trechos = static_cast<uint32_t>(quantos);
    bool ok = fft.criar(N) && w.criar(N) && br.criar(N) && bi.criar(N);
    const size_t plano = static_cast<size_t>(T) * B;
    for (int c = 0; c < 2 && ok; c += 1) ok = re[c].criar(plano) && im[c].criar(plano);
    ok = ok && acumulado.criar(static_cast<size_t>(saidas) * 2 * tamanhoDoAcumulado()) && emitidos.criar(saidas) && base.criar(saidas);
    if (!ok) {
        soltar();
        return false;
    }
    double energia = 0;
    for (uint32_t i = 0; i < N; i += 1) {
        w[i] = 0.5 - 0.5 * cosseno((2 * PI * i) / N);
        energia += w[i] * w[i];
    }
    normal = energia / H;
    return true;
}

void ProcessadorEspectral::soltar() {
    fft.soltar();
    w.soltar();
    br.soltar();
    bi.soltar();
    for (int c = 0; c < 2; c += 1) {
        re[c].soltar();
        im[c].soltar();
    }
    acumulado.soltar();
    emitidos.soltar();
    base.soltar();
    trechos = 0;
}

uint32_t ProcessadorEspectral::quadrosDoTrecho(uint32_t s) const {
    const uint64_t resto = quadros - static_cast<uint64_t>(s) * T;
    return resto < T ? static_cast<uint32_t>(resto) : T;
}

void ProcessadorEspectral::janelaDoTrecho(uint32_t s, int64_t* inicio, uint32_t* n) const {
    const uint32_t q = quadrosDoTrecho(s);
    *inicio = static_cast<int64_t>(static_cast<uint64_t>(s) * T * H) - static_cast<int64_t>(N);
    *n = (q - 1) * H + N;
}

void ProcessadorEspectral::analisar(uint32_t s, const float* esquerdo, const float* direito) {
    const uint32_t q = quadrosDoTrecho(s);
    const float* canais[2] = {esquerdo, direito};
    for (int c = 0; c < 2; c += 1) {
        const float* amostras = canais[c];
        float* r = re[c].dados;
        float* i_ = im[c].dados;
        for (uint32_t f = 0; f < q; f += 1) {
            const float* quadro = amostras + static_cast<size_t>(f) * H;
            for (uint32_t i = 0; i < N; i += 1) {
                br[i] = quadro[i] * w[i];
                bi[i] = 0;
            }
            fft.transformar(br.dados, bi.dados, -1);
            const size_t linha = static_cast<size_t>(f) * B;
            for (uint32_t k = 0; k < B; k += 1) {
                r[linha + k] = static_cast<float>(br[k]);
                i_[linha + k] = static_cast<float>(bi[k]);
            }
        }
    }
}

void ProcessadorEspectral::magnitude(uint32_t q, uint32_t bandas, float* destino) const {
    memset(destino, 0, static_cast<size_t>(2) * T * bandas * sizeof(float));
    for (int c = 0; c < 2; c += 1) {
        for (uint32_t f = 0; f < q; f += 1) {
            const size_t linha = static_cast<size_t>(f) * B;
            float* saida = destino + (static_cast<size_t>(c) * T + f) * bandas;
            for (uint32_t k = 0; k < bandas; k += 1) saida[k] = static_cast<float>(hipotenusa(re[c][linha + k], im[c][linha + k]));
        }
    }
}

uint32_t ProcessadorEspectral::sintetizar(uint32_t s, uint32_t saida, const float* const* mascara, float* esquerdo, float* direito, int64_t* inicio) {
    const uint32_t q = quadrosDoTrecho(s);
    const uint64_t f0 = static_cast<uint64_t>(s) * T;
    const bool ultimo = s == trechos - 1;
    const size_t tamanho = tamanhoDoAcumulado();
    float* destinos[2] = {esquerdo, direito};
    for (int c = 0; c < 2; c += 1) {
        double* acc = acumulado.dados + (static_cast<size_t>(saida) * 2 + c) * tamanho;
        const float* m = mascara != nullptr ? mascara[c] : nullptr;
        for (uint32_t f = 0; f < q; f += 1) {
            const size_t linha = static_cast<size_t>(f) * B;
            for (uint32_t k = 0; k < B; k += 1) {
                const double peso = m != nullptr ? m[linha + k] : 1.0;
                br[k] = re[c][linha + k] * peso;
                bi[k] = im[c][linha + k] * peso;
            }
            for (uint32_t k = 1; k < N / 2; k += 1) {
                br[N - k] = br[k];
                bi[N - k] = -bi[k];
            }
            fft.transformar(br.dados, bi.dados, 1);
            const size_t posicao = static_cast<size_t>((f0 + f) * H - base[saida]);
            for (uint32_t i = 0; i < N; i += 1) acc[posicao + i] += ((br[i] / N) * w[i]) / normal;
        }
    }
    // Amostras prontas: até o começo do próximo quadro (no sinal com zeros), ou tudo no fim.
    const uint64_t fimDosQuadros = (f0 + q) * H;
    uint64_t prontoAte = ultimo ? comprimento : (fimDosQuadros > N ? fimDosQuadros - N : 0);
    if (prontoAte > comprimento) prontoAte = comprimento;
    const uint64_t de = emitidos[saida];
    uint32_t n = 0;
    if (prontoAte > de) {
        n = static_cast<uint32_t>(prontoAte - de);
        const size_t desloc = static_cast<size_t>(de + N - base[saida]);
        for (int c = 0; c < 2; c += 1) {
            const double* acc = acumulado.dados + (static_cast<size_t>(saida) * 2 + c) * tamanho;
            float* destino = destinos[c];
            if (destino == nullptr) continue;
            for (uint32_t i = 0; i < n; i += 1) destino[i] = static_cast<float>(acc[desloc + i]);
        }
        emitidos[saida] = prontoAte;
    }
    *inicio = static_cast<int64_t>(de);
    // Só a cauda dos últimos quadros continua valendo: o resto já saiu.
    const size_t desloc = static_cast<size_t>(fimDosQuadros - base[saida]);
    for (int c = 0; c < 2; c += 1) {
        double* acc = acumulado.dados + (static_cast<size_t>(saida) * 2 + c) * tamanho;
        memmove(acc, acc + desloc, static_cast<size_t>(N) * sizeof(double));
        memset(acc + N, 0, (tamanho - N) * sizeof(double));
    }
    base[saida] = fimDosQuadros;
    return n;
}

}  // namespace slt

struct slt_complex_stft_handle {
    uint32_t janela;
    uint32_t passo;
    uint32_t comprimento;
    uint32_t quadros;
    slt::Fft fft;
    slt::Vetor<double> hann;
    slt::Vetor<double> real;
    slt::Vetor<double> imag;
    slt::Vetor<double> soma;
    slt::Vetor<double> energia;
};

namespace {

bool sobrepoem(const float* a, uint32_t na, const float* b, uint32_t nb) {
    const auto pa = reinterpret_cast<uintptr_t>(a);
    const auto pb = reinterpret_cast<uintptr_t>(b);
    return pa <= pb ? pb - pa < static_cast<uintptr_t>(na) * sizeof(float)
                    : pa - pb < static_cast<uintptr_t>(nb) * sizeof(float);
}

} // namespace

extern "C" {

SLT_API void slt_complex_stft_destroy(slt_complex_stft_handle* stft) {
    if (stft == nullptr) return;
    stft->fft.soltar();
    stft->hann.soltar();
    stft->real.soltar();
    stft->imag.soltar();
    stft->soma.soltar();
    stft->energia.soltar();
    slt::liberar(stft);
}

SLT_API slt_complex_stft_handle* slt_complex_stft_create(uint32_t window, uint32_t hop, uint32_t length) {
    if (window < 16 || window > 65536 || hop == 0 || hop > window / 2 ||
        length <= window / 2 || length / hop >= 4096) return nullptr;
    auto* stft = slt::criarObjeto<slt_complex_stft_handle>();
    if (stft == nullptr) return nullptr;
    stft->janela = window;
    stft->passo = hop;
    stft->comprimento = length;
    stft->quadros = length / hop + 1;
    const size_t tamanho = static_cast<size_t>(length) + window;
    if (!stft->fft.criar(window) || !stft->hann.criar(window) || !stft->real.criar(window) ||
        !stft->imag.criar(window) || !stft->soma.criar(tamanho) || !stft->energia.criar(tamanho)) {
        slt_complex_stft_destroy(stft);
        return nullptr;
    }
    for (uint32_t i = 0; i < window; ++i) stft->hann[i] = 0.5 - 0.5 * slt::cosseno(2 * slt::PI * i / window);
    for (uint32_t quadro = 0; quadro < stft->quadros; ++quadro) {
        const size_t inicio = static_cast<size_t>(quadro) * hop;
        for (uint32_t i = 0; i < window; ++i) stft->energia[inicio + i] += stft->hann[i] * stft->hann[i];
    }
    return stft;
}

SLT_API uint32_t slt_complex_stft_size(const slt_complex_stft_handle* stft) {
    return stft == nullptr ? 0 : stft->quadros * (stft->janela / 2 + 1) * 4;
}

SLT_API slt_result slt_complex_stft_analyze(slt_complex_stft_handle* stft, const float* left, const float* right,
    uint32_t length, float* spectrum, uint32_t spectrum_count) {
    if (stft == nullptr || left == nullptr || right == nullptr || spectrum == nullptr ||
        length != stft->comprimento) return SLT_ERROR_INVALID_ARGUMENT;
    const uint32_t tamanho = slt_complex_stft_size(stft);
    if (spectrum_count < tamanho) return SLT_ERROR_BUFFER_TOO_SMALL;
    if (sobrepoem(left, length, spectrum, tamanho) || sobrepoem(right, length, spectrum, tamanho)) {
        return SLT_ERROR_INVALID_ARGUMENT;
    }

    const uint32_t metade = stft->janela / 2;
    const float* canais[] = {left, right};
    for (uint32_t quadro = 0; quadro < stft->quadros; ++quadro) {
        for (uint32_t canal = 0; canal < 2; ++canal) {
            const int64_t inicio = static_cast<int64_t>(quadro) * stft->passo - metade;
            for (uint32_t i = 0; i < stft->janela; ++i) {
                int64_t posicao = inicio + i;
                if (posicao < 0) posicao = -posicao;
                if (posicao >= length) posicao = 2 * static_cast<int64_t>(length) - 2 - posicao;
                stft->real[i] = canais[canal][static_cast<size_t>(posicao)] * stft->hann[i];
                stft->imag[i] = 0;
            }
            stft->fft.transformar(stft->real.dados, stft->imag.dados, -1);
            for (uint32_t bin = 0; bin <= metade; ++bin) {
                const size_t indice = (static_cast<size_t>(quadro) * (metade + 1) + bin) * 4 + canal * 2;
                spectrum[indice] = static_cast<float>(stft->real[bin]);
                spectrum[indice + 1] = static_cast<float>(stft->imag[bin]);
            }
        }
    }
    return SLT_OK;
}

SLT_API slt_result slt_complex_stft_synthesize(slt_complex_stft_handle* stft, const float* spectrum,
    const float* mask, uint32_t spectrum_count, uint32_t zero_dc, float* left, float* right, uint32_t length) {
    if (stft == nullptr || spectrum == nullptr || left == nullptr || right == nullptr ||
        length != stft->comprimento || zero_dc > 1) return SLT_ERROR_INVALID_ARGUMENT;
    const uint32_t tamanho = slt_complex_stft_size(stft);
    if (spectrum_count < tamanho) return SLT_ERROR_BUFFER_TOO_SMALL;
    if (sobrepoem(left, length, right, length) || sobrepoem(left, length, spectrum, tamanho) ||
        sobrepoem(right, length, spectrum, tamanho) || (mask != nullptr &&
            (sobrepoem(left, length, mask, tamanho) || sobrepoem(right, length, mask, tamanho)))) {
        return SLT_ERROR_INVALID_ARGUMENT;
    }

    const uint32_t metade = stft->janela / 2;
    float* canais[] = {left, right};
    for (uint32_t canal = 0; canal < 2; ++canal) {
        stft->soma.zerar();
        for (uint32_t quadro = 0; quadro < stft->quadros; ++quadro) {
            for (uint32_t bin = 0; bin <= metade; ++bin) {
                const size_t indice = (static_cast<size_t>(quadro) * (metade + 1) + bin) * 4 + canal * 2;
                const double real = spectrum[indice];
                const double imag = spectrum[indice + 1];
                const double ganho = mask == nullptr ? 1.0 : mask[indice];
                const double fase = mask == nullptr ? 0.0 : mask[indice + 1];
                stft->real[bin] = real * ganho - imag * fase;
                stft->imag[bin] = real * fase + imag * ganho;
            }
            if (zero_dc) stft->real[0] = 0;
            stft->imag[0] = 0;
            stft->imag[metade] = 0;
            for (uint32_t bin = 1; bin < metade; ++bin) {
                stft->real[stft->janela - bin] = stft->real[bin];
                stft->imag[stft->janela - bin] = -stft->imag[bin];
            }
            stft->fft.transformar(stft->real.dados, stft->imag.dados, 1);
            const size_t inicio = static_cast<size_t>(quadro) * stft->passo;
            for (uint32_t i = 0; i < stft->janela; ++i) {
                stft->soma[inicio + i] += stft->real[i] * stft->hann[i] / stft->janela;
            }
        }
        for (uint32_t i = 0; i < length; ++i) {
            const size_t posicao = static_cast<size_t>(i) + metade;
            canais[canal][i] = static_cast<float>(stft->soma[posicao] / stft->energia[posicao]);
        }
    }
    return SLT_OK;
}

} // extern "C"
