// A interface de um driver ASIO, declarada aqui a partir do contrato binário público do ASIO
// 2 (a ordem da vtable e as estruturas, com empacotamento de 4 bytes). Não usa o SDK da
// Steinberg: só o necessário para um host falar com o driver instalado na máquina.
// ASIO é marca da Steinberg Media Technologies GmbH.
#pragma once

#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>

#include <unknwn.h>

#include "../../dispositivo.h"

namespace slt {

#pragma pack(push, 4)

typedef long AsioErro;      // 0 = ASE_OK; 0x3f4847a0 = ASE_SUCCESS (resposta de `future`)
typedef long AsioBooleano;  // 0 ou 1
typedef double AsioTaxa;

struct Asio64 {
    unsigned long alto;
    unsigned long baixo;
};

struct AsioFonteDeRelogio {
    long indice;
    long canalAssociado;
    long grupoAssociado;
    AsioBooleano atual;
    char nome[32];
};

enum AsioTipoDeAmostra : long { ASIO_INT16 = 16, ASIO_INT24 = 17, ASIO_INT32 = 18, ASIO_FLOAT32 = 19, ASIO_FLOAT64 = 20 };

struct AsioInfoDoCanal {
    long canal;
    AsioBooleano entrada;
    AsioBooleano ativo;
    long grupo;
    long tipo;
    char nome[32];
};

struct AsioInfoDoBuffer {
    AsioBooleano entrada;
    long canal;
    void* buffers[2];
};

struct AsioTempo;

struct AsioCallbacks {
    void (*trocaDeBuffer)(long indice, AsioBooleano direto);
    void (*taxaMudou)(AsioTaxa taxa);
    long (*mensagem)(long seletor, long valor, void* mensagem, double* opcional);
    AsioTempo* (*trocaDeBufferComTempo)(AsioTempo* tempo, long indice, AsioBooleano direto);
};

#pragma pack(pop)

enum { ASIO_SELETOR_SUPORTADO = 1, ASIO_VERSAO_DO_MOTOR = 2, ASIO_PEDIDO_DE_REINICIO = 3, ASIO_TAMANHO_MUDOU = 4, ASIO_RESSINCRONIA = 5, ASIO_LATENCIAS_MUDARAM = 6, ASIO_ACEITA_TEMPO = 7 };

/** A vtable de um driver ASIO: IUnknown e, nesta ordem, as funções do ASIO 2. */
struct IDriverAsio : IUnknown {
    virtual AsioBooleano __thiscall init(void* janela) = 0;
    virtual void __thiscall getDriverName(char* nome) = 0;
    virtual long __thiscall getDriverVersion() = 0;
    virtual void __thiscall getErrorMessage(char* texto) = 0;
    virtual AsioErro __thiscall start() = 0;
    virtual AsioErro __thiscall stop() = 0;
    virtual AsioErro __thiscall getChannels(long* entradas, long* saidas) = 0;
    virtual AsioErro __thiscall getLatencies(long* entrada, long* saida) = 0;
    virtual AsioErro __thiscall getBufferSize(long* minimo, long* maximo, long* preferido, long* granularidade) = 0;
    virtual AsioErro __thiscall canSampleRate(AsioTaxa taxa) = 0;
    virtual AsioErro __thiscall getSampleRate(AsioTaxa* taxa) = 0;
    virtual AsioErro __thiscall setSampleRate(AsioTaxa taxa) = 0;
    virtual AsioErro __thiscall getClockSources(AsioFonteDeRelogio* fontes, long* quantas) = 0;
    virtual AsioErro __thiscall setClockSource(long indice) = 0;
    virtual AsioErro __thiscall getSamplePosition(Asio64* amostras, Asio64* carimbo) = 0;
    virtual AsioErro __thiscall getChannelInfo(AsioInfoDoCanal* info) = 0;
    virtual AsioErro __thiscall createBuffers(AsioInfoDoBuffer* buffers, long canais, long tamanho, AsioCallbacks* callbacks) = 0;
    virtual AsioErro __thiscall disposeBuffers() = 0;
    virtual AsioErro __thiscall controlPanel() = 0;
    virtual AsioErro __thiscall future(long seletor, void* opcional) = 0;
    virtual AsioErro __thiscall outputReady() = 0;
};

/** Abre o fluxo sobre um driver já criado (o fluxo fica com a referência). Usado pelos testes. */
slt_result abrirAsioCom(IDriverAsio* driver, const slt_stream_config& config, Misturador* misturador, Fluxo** fluxo);

}  // namespace slt
