#include "dispositivo.h"

#if defined(_WIN32)
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#else
#include <time.h>
#endif

namespace slt {

uint64_t agoraEmMicros() {
#if defined(_WIN32)
    static LARGE_INTEGER frequencia = {};
    if (frequencia.QuadPart == 0) QueryPerformanceFrequency(&frequencia);
    LARGE_INTEGER agora;
    QueryPerformanceCounter(&agora);
    return static_cast<uint64_t>(agora.QuadPart / frequencia.QuadPart) * 1000000ull + static_cast<uint64_t>(agora.QuadPart % frequencia.QuadPart) * 1000000ull / static_cast<uint64_t>(frequencia.QuadPart);
#else
    timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return static_cast<uint64_t>(t.tv_sec) * 1000000ull + static_cast<uint64_t>(t.tv_nsec) / 1000ull;
#endif
}

bool Fluxo::preparar(Misturador* misturador_, uint32_t capacidadeDaCaptura) {
    misturador = misturador_;
    bool ok = saidaE.criar(BLOCO_MAXIMO_DO_FLUXO) && saidaD.criar(BLOCO_MAXIMO_DO_FLUXO) && entradaE.criar(BLOCO_MAXIMO_DO_FLUXO) && entradaD.criar(BLOCO_MAXIMO_DO_FLUXO);
    // Potência de 2, para o índice do anel ser uma máscara.
    uint32_t capacidade = 1;
    while (capacidade < capacidadeDaCaptura) capacidade <<= 1;
    return ok && anelE.criar(capacidade) && anelD.criar(capacidade) && segmentos.criar(4096);
}

void Fluxo::soltarComum() {
    saidaE.soltar();
    saidaD.soltar();
    entradaE.soltar();
    entradaD.soltar();
    anelE.soltar();
    anelD.soltar();
    segmentos.soltar();
}

void Fluxo::bloco(uint32_t quadros, bool temEntrada) {
    const uint64_t inicio = agoraEmMicros();
    const int64_t posicao = misturador->tocando.ler() != 0 ? misturador->posicao.ler() : -1;
    misturador->tempoReal(temEntrada ? entradaE.dados : nullptr, temEntrada ? entradaD.dados : nullptr, saidaE.dados, saidaD.dados, quadros);
    if (temEntrada && capturando.ler() != 0) {
        const uint64_t escrito = static_cast<uint64_t>(escritos.ler());
        const uint64_t lido = static_cast<uint64_t>(lidos.ler());
        const uint64_t segmentoEscrito = static_cast<uint64_t>(segmentosEscritos.ler());
        const uint64_t segmentoLido = static_cast<uint64_t>(segmentosLidos.ler());
        if (escrito - lido + quadros > anelE.n || segmentoEscrito - segmentoLido >= segmentos.n) {
            // O consumidor não deu conta: este bloco se perde, e a perda fica contada.
            transbordos.guardar(transbordos.ler() + 1);
        } else {
            const size_t mascara = anelE.n - 1;
            for (uint32_t i = 0; i < quadros; i += 1) {
                anelE[(escrito + i) & mascara] = entradaE[i];
                anelD[(escrito + i) & mascara] = entradaD[i];
            }
            segmentos[segmentoEscrito % segmentos.n] = {posicao, quadros, 0};
            escritos.guardar(static_cast<int64_t>(escrito + quadros));
            segmentosEscritos.guardar(static_cast<int64_t>(segmentoEscrito + 1));
        }
    }
    const uint64_t gasto = agoraEmMicros() - inicio;
    callbacks.guardar(callbacks.ler() + 1);
    quadrosTocados.guardar(quadrosTocados.ler() + quadros);
    somaDosCallbacks.guardar(somaDosCallbacks.ler() + static_cast<int64_t>(gasto));
    if (static_cast<int64_t>(gasto) > maiorCallback.ler()) maiorCallback.guardar(static_cast<int64_t>(gasto));
    // Gastar mais que um período do aparelho dentro do callback é falha audível garantida.
    // (Blocos menores que o período acontecem na partida, com o buffer ainda cheio.)
    const uint32_t periodo = quadros > info.buffer_frames ? quadros : info.buffer_frames;
    if (info.sample_rate > 0 && gasto > static_cast<uint64_t>(periodo) * 1000000ull / info.sample_rate) anotarFalha();
}

uint32_t Fluxo::ler(float* esq, float* dir, uint32_t capacidade, int64_t* posicao) {
    const uint64_t segmentoLido = static_cast<uint64_t>(segmentosLidos.ler());
    if (segmentoLido == static_cast<uint64_t>(segmentosEscritos.ler()) || capacidade == 0) return 0;
    const Segmento segmento = segmentos[segmentoLido % segmentos.n];
    const uint32_t resto = segmento.quadros - consumidoDoSegmento;
    const uint32_t n = resto < capacidade ? resto : capacidade;
    const uint64_t lido = static_cast<uint64_t>(lidos.ler());
    const size_t mascara = anelE.n - 1;
    for (uint32_t i = 0; i < n; i += 1) {
        if (esq != nullptr) esq[i] = anelE[(lido + i) & mascara];
        if (dir != nullptr) dir[i] = anelD[(lido + i) & mascara];
    }
    if (posicao != nullptr) *posicao = segmento.posicao < 0 ? -1 : segmento.posicao + consumidoDoSegmento;
    consumidoDoSegmento += n;
    lidos.guardar(static_cast<int64_t>(lido + n));
    if (consumidoDoSegmento == segmento.quadros) {
        consumidoDoSegmento = 0;
        segmentosLidos.guardar(static_cast<int64_t>(segmentoLido + 1));
    }
    return n;
}

uint32_t backendsDisponiveis() {
#if defined(_WIN32)
    return (1u << SLT_BACKEND_WASAPI) | (1u << SLT_BACKEND_ASIO);
#elif defined(__ANDROID__)
    return 1u << SLT_BACKEND_OBOE;
#else
    return 0;
#endif
}

slt_result listarDispositivos(uint32_t backend, slt_device_info* dispositivos, uint32_t capacidade, uint32_t* quantos) {
    if (quantos == nullptr || (capacidade > 0 && dispositivos == nullptr)) return SLT_ERROR_INVALID_ARGUMENT;
    *quantos = 0;
#if defined(_WIN32)
    if (backend == SLT_BACKEND_WASAPI || backend == SLT_BACKEND_AUTO) return listarWasapi(dispositivos, capacidade, quantos);
    if (backend == SLT_BACKEND_ASIO) return listarAsio(dispositivos, capacidade, quantos);
#elif defined(__ANDROID__)
    if (backend == SLT_BACKEND_OBOE || backend == SLT_BACKEND_AUTO) return listarOboe(dispositivos, capacidade, quantos);
#endif
    (void)backend;
    return SLT_ERROR_UNSUPPORTED;
}

slt_result abrirFluxo(const slt_stream_config& config, Misturador* misturador, Fluxo** fluxo) {
    if (misturador == nullptr || fluxo == nullptr) return SLT_ERROR_INVALID_ARGUMENT;
    *fluxo = nullptr;
    if (config.sample_rate != 0 && config.sample_rate != misturador->taxa) return SLT_ERROR_INVALID_ARGUMENT;
#if defined(_WIN32)
    if (config.backend == SLT_BACKEND_WASAPI || config.backend == SLT_BACKEND_AUTO) return abrirWasapi(config, misturador, fluxo);
    if (config.backend == SLT_BACKEND_ASIO) return abrirAsio(config, misturador, fluxo);
#elif defined(__ANDROID__)
    if (config.backend == SLT_BACKEND_OBOE || config.backend == SLT_BACKEND_AUTO) return abrirOboe(config, misturador, fluxo);
#endif
    return SLT_ERROR_UNSUPPORTED;
}

}  // namespace slt
