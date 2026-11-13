// Dispositivo de áudio: o que é igual em todo backend (WASAPI, ASIO, Oboe). O backend abre o
// aparelho e chama `Fluxo::bloco` no callback; o resto (mixer, captura, estatísticas) fica aqui.
//
// A captura sai da thread de áudio por um anel sem trava (um produtor, um consumidor), junto
// com a posição da linha do tempo de cada bloco: é o que alinha a tomada gravada ao que tocava.
#pragma once

#include "misturador.h"

namespace slt {

constexpr uint32_t BLOCO_MAXIMO_DO_FLUXO = 8192;

/** Relógio monotônico em microssegundos (por plataforma). */
uint64_t agoraEmMicros();

struct Segmento {
    int64_t posicao;   // quadro da linha do tempo do primeiro quadro; −1 com o transporte parado
    uint32_t quadros;
    uint32_t reservado;
};

struct Fluxo {
    Misturador* misturador = nullptr;
    slt_stream_info info = {};
    // Estatísticas (escritas pela thread de áudio, lidas pelo controle).
    Atomico64 callbacks;
    Atomico64 quadrosTocados;
    Atomico64 falhas;
    Atomico64 maiorCallback;
    Atomico64 somaDosCallbacks;
    Atomico64 transbordos;
    Atomico64 perdido;  // o dispositivo sumiu (desconectado, formato trocado)
    Atomico64 falhasDoAparelho;  // falhas que o próprio sistema conta (Oboe/AAudio)
    // Captura.
    Atomico64 capturando;
    Vetor<float> anelE, anelD;
    Vetor<Segmento> segmentos;
    Atomico64 escritos;           // quadros já postos no anel (só a thread de áudio escreve)
    Atomico64 lidos;              // quadros já tirados (só o controle escreve)
    Atomico64 segmentosEscritos;
    Atomico64 segmentosLidos;
    uint32_t consumidoDoSegmento = 0;  // do controle
    // Rascunho planar do callback.
    Vetor<float> saidaE, saidaD, entradaE, entradaD;

    bool preparar(Misturador* misturador_, uint32_t capacidadeDaCaptura);
    void soltarComum();
    /**
     * Um bloco do callback: mistura em saidaE/saidaD (planar, até BLOCO_MAXIMO_DO_FLUXO) a
     * partir de entradaE/entradaD (ou silêncio quando `temEntrada` é falso) e grava a captura.
     */
    void bloco(uint32_t quadros, bool temEntrada);
    void anotarFalha() { falhas.guardar(falhas.ler() + 1); }
    uint32_t ler(float* esq, float* dir, uint32_t capacidade, int64_t* posicao);

    // O backend.
    virtual ~Fluxo() {}
    /** Atualiza o que só se sabe com o fluxo rodando (latência medida, falhas do sistema). */
    virtual void atualizarInfo() {}
    virtual slt_result iniciar() = 0;
    virtual slt_result parar() = 0;
    virtual void fechar() = 0;
};

uint32_t backendsDisponiveis();
slt_result listarDispositivos(uint32_t backend, slt_device_info* dispositivos, uint32_t capacidade, uint32_t* quantos);
slt_result abrirFluxo(const slt_stream_config& config, Misturador* misturador, Fluxo** fluxo);

#if defined(_WIN32)
slt_result listarWasapi(slt_device_info* dispositivos, uint32_t capacidade, uint32_t* quantos);
slt_result abrirWasapi(const slt_stream_config& config, Misturador* misturador, Fluxo** fluxo);
slt_result listarAsio(slt_device_info* dispositivos, uint32_t capacidade, uint32_t* quantos);
slt_result abrirAsio(const slt_stream_config& config, Misturador* misturador, Fluxo** fluxo);
#endif
#if defined(__ANDROID__)
slt_result listarOboe(slt_device_info* dispositivos, uint32_t capacidade, uint32_t* quantos);
slt_result abrirOboe(const slt_stream_config& config, Misturador* misturador, Fluxo** fluxo);
#endif

}  // namespace slt
