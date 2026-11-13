// A biblioteca de verdade, pela ABI pública, como um cliente a carrega: versão, ciclo de
// vida repetido (criar e destruir muitas vezes) e erros no lugar de travamentos.
#include <stdio.h>
#include <cmath>

#include "slt_media_api.h"

static bool testar_espectro_complexo() {
    constexpr uint32_t janela = 16;
    constexpr uint32_t passo = 5;
    constexpr uint32_t n = 39;
    constexpr uint32_t tamanho = (n / passo + 1) * (janela / 2 + 1) * 4;
    float e[n] = {}, d[n] = {}, se[n] = {}, sd[n] = {}, x[tamanho] = {}, m[tamanho] = {};
    for (uint32_t i = 0; i < n; ++i) {
        e[i] = static_cast<float>(i) / n - 0.3f;
        d[i] = -0.2f;
    }
    for (uint32_t i = 0; i < tamanho; i += 4) {
        m[i] = 0.5f;
        m[i + 2] = -0.25f;
    }
    auto* stft = slt_complex_stft_create(janela, passo, n);
    bool ok = stft != nullptr && slt_complex_stft_size(stft) == tamanho &&
        slt_complex_stft_analyze(stft, x, d, n, x, tamanho) == SLT_ERROR_INVALID_ARGUMENT &&
        slt_complex_stft_analyze(stft, e, d, n, x, tamanho - 1) == SLT_ERROR_BUFFER_TOO_SMALL &&
        slt_complex_stft_analyze(stft, e, d, n, x, tamanho) == SLT_OK &&
        slt_complex_stft_synthesize(stft, x, m, tamanho, 0, se, se + 1, n) == SLT_ERROR_INVALID_ARGUMENT &&
        slt_complex_stft_synthesize(stft, x, m, tamanho, 0, se, sd, n) == SLT_OK;
    for (uint32_t i = 0; i < n; ++i) {
        ok = ok && std::abs(se[i] - e[i] * 0.5f) < 1e-6f && std::abs(sd[i] + d[i] * 0.25f) < 1e-6f;
    }
    for (float& ganho : m) ganho = 0;
    for (uint32_t i = 0; i < tamanho; i += (janela / 2 + 1) * 4) m[i + 2] = 1;
    ok = ok && slt_complex_stft_synthesize(stft, x, m, tamanho, 0, se, sd, n) == SLT_OK;
    ok = ok && std::abs(sd[n / 2]) > 0.01f;
    ok = ok && slt_complex_stft_synthesize(stft, x, m, tamanho, 1, se, sd, n) == SLT_OK;
    for (float amostra : sd) ok = ok && std::abs(amostra) < 1e-6f;
    slt_complex_stft_destroy(stft);
    return ok;
}

int main() {
    int falhas = 0;
    if (slt_get_api_version() >> 16 != SLT_API_VERSION_MAJOR) {
        printf("versao maior inesperada: %u\n", slt_get_api_version());
        falhas += 1;
    }
    const uint32_t esperadas = SLT_CAP_ENGINE | SLT_CAP_SEPARATOR | SLT_CAP_REFINED_MASK |
        SLT_CAP_RESTORER | SLT_CAP_CONSISTENCY | SLT_CAP_COMPLEX_STFT;
    if ((slt_get_capabilities() & esperadas) != esperadas) {
        printf("capacidades faltando: %u\n", slt_get_capabilities());
        falhas += 1;
    }
    for (int i = 0; i < 2000; i += 1) {
        slt_engine_handle* motor = slt_engine_create(48000, 2);
        slt_restorer_config restauracao = {sizeof(slt_restorer_config), 48000, static_cast<uint32_t>(i % 4), 60};
        slt_restorer_handle* restaurador = slt_restorer_create(&restauracao);
        slt_separator_config separacao = {};
        separacao.struct_size = sizeof(separacao);
        separacao.length = 20000;
        separacao.window = 1024;
        separacao.hop = 256;
        separacao.frames_per_chunk = 32;
        separacao.bands = 256;
        separacao.instruments = 4;
        separacao.outputs = 4;
        slt_separator_handle* separador = i % 20 == 0 ? slt_separator_create(&separacao) : nullptr;
        if (motor == nullptr || restaurador == nullptr || (i % 20 == 0 && separador == nullptr)) {
            printf("criacao falhou na volta %d\n", i);
            falhas += 1;
            break;
        }
        float bloco[256] = {0};
        float direito[128] = {0};
        if (slt_engine_process(motor, bloco, bloco, 128) != SLT_OK || slt_restorer_process(restaurador, bloco, direito, 128) != SLT_OK) falhas += 1;
        slt_separator_destroy(separador);
        slt_restorer_destroy(restaurador);
        slt_engine_destroy(motor);
    }
    if (slt_engine_process(nullptr, nullptr, nullptr, 0) != SLT_ERROR_INVALID_ARGUMENT) falhas += 1;
    if (slt_separator_chunk_count(nullptr) != 0) falhas += 1;
    if (slt_restorer_process(nullptr, nullptr, nullptr, 0) != SLT_ERROR_INVALID_ARGUMENT) falhas += 1;
    if (!testar_espectro_complexo()) {
        printf("STFT complexa: reconstrução divergente\n");
        falhas += 1;
    }
    void* memoria = slt_alloc(1024);
    if (memoria == nullptr) falhas += 1;
    slt_free(memoria);
    slt_free(nullptr);
    printf("abi: %d falhas\n", falhas);
    return falhas == 0 ? 0 : 1;
}
