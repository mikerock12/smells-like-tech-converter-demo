// O mixer da criação musical no núcleo nativo: a mesma conta de musica-mixer.mjs (clipes,
// efeitos por faixa, fader/pan/ajuste L/R com automação, envios pré e pós-fader, barramentos,
// master e metrônomo), feita para o callback de áudio.
//
// Duas threads, sem trava:
//  - controle: compila o projeto (JSON) num Plano imutável, cria os processadores novos e
//    publica o plano; é a única que aloca e libera.
//  - áudio: no começo de cada bloco pega o plano publicado, aplica os parâmetros novos aos
//    processadores que continuam (para o reverb e o delay não cortarem a cauda) e mistura.
//    Nunca aloca, libera, bloqueia ou faz E/S.
#pragma once

#include "../include/slt_media_api.h"
#include "efeitos.h"

namespace slt {

constexpr uint32_t BLOCO_DO_MIXER = 128;

struct FonteDeAudio {
    const float* esq = nullptr;
    const float* dir = nullptr;  // nulo = mono
    uint64_t quadros = 0;
};

struct Registro {
    Vetor<char> chave;  // dono + '\n' + id do efeito
    TipoDeEfeito tipo = TipoDeEfeito::Desconhecido;
    Processador* processador = nullptr;
#if defined(SLT_COM_VST3)
    slt_plugin_handle* plugin = nullptr;
    Vetor<char> caminhoDoPlugin;
    char classeDoPlugin[33] = {};
#endif
    uint32_t referencias = 0;
    Registro* proximo = nullptr;
};

struct EfeitoDoPlano {
    Registro* registro;
    ParametrosDoEfeito parametros;
#if defined(SLT_COM_VST3)
    struct ParametroVst3 { uint32_t id; double valor; };
    Vetor<ParametroVst3> parametrosVst3;
#endif
};

struct ClipeDoPlano {
    int64_t inicio;
    int64_t inicioNaMidia;
    int64_t duracao;
    double ganhoDb;
    double entradaSuave;
    double saidaSuave;
    double velocidade;
    bool transformada;
    FonteDeAudio fonte;
};

struct PontoDeAutomacao {
    double quadro;
    double valor;
};

struct EnvioDoPlano {
    int32_t barramento;
    double ganho;
    bool preFader;
};

struct MedidorDoMixer {
    float acumulado[4];  // exclusivo da thread de áudio
    Atomico64 revisao;
    Atomico64 picos;
    Atomico64 rms;

    void publicar(uint32_t quadros);
    void ler(float* quatro) const;
};

struct FaixaDoPlano {
    const char* id;
    uint32_t tamanhoDoId;
    double ganhoDb, pan, ajusteEsquerdoDb, ajusteDireitoDb;
    bool audivel, gravar, monitorar;
    int32_t saida;  // −1 = master
    Vetor<ClipeDoPlano> clipes;
    Vetor<EnvioDoPlano> envios;
    Vetor<PontoDeAutomacao> volume;
    Vetor<PontoDeAutomacao> panorama;
    Vetor<EfeitoDoPlano> efeitos;
    MedidorDoMixer medidor;
};

struct BarramentoDoPlano {
    const char* id;
    uint32_t tamanhoDoId;
    double ganhoEsquerdo, ganhoDireito;
    bool mudo;
    Vetor<EfeitoDoPlano> efeitos;
    Vetor<float> esq;
    Vetor<float> dir;
    MedidorDoMixer medidor;
};

struct Clique {
    int64_t quadro;
    uint32_t forte;
};

struct Plano {
    Vetor<char> textos;  // os ids, copiados
    Vetor<FaixaDoPlano> faixas;
    Vetor<BarramentoDoPlano> barramentos;
    Vetor<EfeitoDoPlano> master;
    double ganhoDoMaster = 1;
    double volumeDoMetronomo = 1;
    double quadrosPorBatida = 1;
    uint32_t batidasPorCompasso = 4;
    Vetor<Clique> contagem;
    int64_t fimDaContagem = -1;
    MedidorDoMixer medidorDoMaster{};
};

struct OpcoesDeMixagem {
    int32_t somente = -1;  // índice da faixa, ou −1
    bool semMaster = false;
    bool metronomo = false;
    const float* entradaEsquerda = nullptr;
    const float* entradaDireita = nullptr;
};

struct FonteRegistrada {
    Vetor<char> chave;
    FonteDeAudio fonte;
    FonteRegistrada* proximo = nullptr;
};

struct Misturador {
    uint32_t taxa = 0;
    Vetor<float> te, td, le, ld, me, md;
    Vetor<float> cliqueForte, cliqueFraco;
    // Da thread de áudio.
    Plano* atual = nullptr;
    // Troca entre as threads.
    Atomico64 pendente;
    Atomico64 descartado;
    // Da thread de controle.
    Registro* registros = nullptr;
    FonteRegistrada* fontes = nullptr;
    Documento projeto;
    Vetor<Clique> contagem;
    int64_t fimDaContagem = -1;
    // Transporte (escrito pelo controle, lido pelo áudio; a posição é escrita pelos dois).
    Atomico64 posicao;
    Atomico64 tocando;
    Atomico64 metronomoLigado;
    Atomico64 inicioDoLoop;
    Atomico64 fimDoLoop;

    bool criar(uint32_t taxa_);
    void soltar();

    /* ---- controle ---- */
    slt_result definirFonte(const char* chave, const float* esq, const float* dir, uint64_t quadros);
    slt_result definirProjeto(const char* json, size_t tamanho);
    slt_result definirProjeto(const No& raiz);
    slt_result definirContagem(const Clique* cliques, uint32_t quantos, int64_t fim);
    /** Recompila o último projeto (depois de trocar fontes ou a contagem) e publica. */
    slt_result atualizar();
    /** Índice da faixa pelo id no último plano publicado, ou −1. */
    int32_t indiceDaFaixa(const char* id) const;
    bool medidores(const char* id, float* quatro) const;
    void coletar();

    /* ---- áudio ---- */
    void misturar(int64_t inicio, uint32_t n, float* saidaE, float* saidaD, const OpcoesDeMixagem& opcoes);
    void tempoReal(const float* entradaE, const float* entradaD, float* saidaE, float* saidaD, uint32_t n);

   private:
    Plano* compilar(const No& raiz, slt_result* erro);
    void liberarPlano(Plano* plano);
    void liberarRegistro(Registro* registro);
    bool compilarCadeia(const No* efeitos, const char* dono, uint32_t tamanhoDoDono, Vetor<EfeitoDoPlano>& destino, slt_result* erro);
    void publicar(Plano* plano);
    void pegarPlano();
    void bloco(int64_t t0, uint32_t n, const OpcoesDeMixagem& opcoes, uint32_t deslocamentoDaEntrada);
    void metronomo(int64_t t0, uint32_t n, bool grade);
    Plano* publicado = nullptr;  // o último que o controle publicou (vivo até ser trocado)
};

}  // namespace slt
