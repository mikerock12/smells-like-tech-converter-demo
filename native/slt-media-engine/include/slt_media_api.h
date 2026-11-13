/*
 * SLT Native Media Engine — C ABI pública e versionada.
 *
 * Um núcleo só, em C++20, compilado para slt_native.dll (Windows x64), libslt_native.so
 * (Android) e slt_native.wasm (navegador). Só atravessam a fronteira tipos de C: inteiros de
 * tamanho fixo, float, ponteiros para buffers do chamador e handles opacos. Nenhuma exceção
 * sai daqui: toda função devolve slt_result ou um handle nulo.
 *
 * Regras de uso (docs/api/native-abi.md):
 *  - Quem aloca libera: os buffers são sempre do chamador; o núcleo só aloca o que guarda
 *    dentro dos handles e libera no *_destroy.
 *  - Um handle não é seguro para chamadas simultâneas; handles diferentes são independentes.
 *  - Áudio em float32. "Planar" são dois ponteiros (esquerdo, direito); "interleaved" é
 *    L R L R... O texto de cada função diz qual.
 *  - slt_engine_process é a única função pensada para o callback de áudio: não aloca, não
 *    bloqueia e não faz E/S. As demais são de trabalho fora do tempo real.
 */
#ifndef SLT_MEDIA_API_H
#define SLT_MEDIA_API_H

#include <stddef.h>
#include <stdint.h>

#if defined(__wasm__)
  #define SLT_API __attribute__((visibility("default")))
#elif defined(_WIN32)
  #if defined(SLT_BUILD_DLL)
    #define SLT_API __declspec(dllexport)
  #elif defined(SLT_STATIC)
    #define SLT_API
  #else
    #define SLT_API __declspec(dllimport)
  #endif
#else
  #define SLT_API __attribute__((visibility("default")))
#endif

#ifdef __cplusplus
extern "C" {
#endif

/* Versão da ABI: maior << 16 | menor. A maior muda quando algo deixa de ser compatível. */
#define SLT_API_VERSION_MAJOR 1u
#define SLT_API_VERSION_MINOR 1u
#define SLT_API_VERSION ((SLT_API_VERSION_MAJOR << 16) | SLT_API_VERSION_MINOR)

typedef enum slt_result {
    SLT_OK = 0,
    SLT_ERROR_INVALID_ARGUMENT = 1,
    SLT_ERROR_UNSUPPORTED_VERSION = 2,
    SLT_ERROR_OUT_OF_MEMORY = 3,
    SLT_ERROR_BUFFER_TOO_SMALL = 4,
    SLT_ERROR_INVALID_STATE = 5,
    SLT_ERROR_DEVICE = 6,      /* o aparelho de áudio não abriu, sumiu ou recusou o formato */
    SLT_ERROR_UNSUPPORTED = 7, /* não existe nesta plataforma ou nesta compilação */
    SLT_ERROR_INTERNAL = 100
} slt_result;

/* Capacidades compiladas nesta biblioteca (slt_get_capabilities). */
#define SLT_CAP_ENGINE        (1u << 0) /* ganho, pan e medição em blocos */
#define SLT_CAP_SEPARATOR     (1u << 1) /* STFT, máscaras e reconstrução da separação */
#define SLT_CAP_REFINED_MASK  (1u << 2) /* máscara refinada acima das bandas do modelo */
#define SLT_CAP_RESTORER      (1u << 3) /* restauração por instrumento */
#define SLT_CAP_CONSISTENCY   (1u << 4) /* consistência com a mistura e resíduo explícito */
#define SLT_CAP_MIXER         (1u << 5) /* mixer multipista: clipes, efeitos, barramentos, master */
#define SLT_CAP_DEVICE        (1u << 6) /* dispositivo de áudio nativo (WASAPI/ASIO/Oboe) */
#define SLT_CAP_VST3          (1u << 7) /* host de efeitos VST3 (Windows com SDK) */
#define SLT_CAP_COMPLEX_STFT  (1u << 8) /* STFT centrada e síntese por máscara complexa */

SLT_API uint32_t slt_get_api_version(void);
SLT_API uint32_t slt_get_capabilities(void);
/* Texto fixo (ASCII, terminado em zero) do código de erro; nunca nulo, nunca liberar. */
SLT_API const char* slt_result_message(slt_result result);

/* ------------------------------------------------------------------ motor (tempo real) */

typedef struct slt_engine_handle slt_engine_handle;

typedef struct slt_measures {
    float peak;      /* maior valor absoluto de amostra, linear */
    float rms;       /* RMS de todos os canais, linear */
    uint32_t clipped; /* amostras com |x| > 1 */
} slt_measures;

/* Motor de um barramento: 1 ou 2 canais. Devolve nulo se os parâmetros forem inválidos. */
SLT_API slt_engine_handle* slt_engine_create(uint32_t sample_rate, uint32_t channel_count);
/* Não pode haver chamada em andamento no handle. Aceita nulo. */
SLT_API void slt_engine_destroy(slt_engine_handle* engine);
/* Ganho em dB (−120 a +24) e pan de −100 (esquerda) a +100 (direita), lei de potência
 * constante. A mudança entra suavizada em ~10 ms no próximo bloco (sem estalo). Pode ser
 * chamada de outra thread que não a do processamento. */
SLT_API slt_result slt_engine_set_gain(slt_engine_handle* engine, float gain_db, float pan);
/* Bloco interleaved float32: input e output com frame_count * channel_count amostras.
 * input == output é permitido. Sem alocação, sem bloqueio, sem E/S. */
SLT_API slt_result slt_engine_process(slt_engine_handle* engine, const float* input, float* output, uint32_t frame_count);
/* Medição de um bloco interleaved (sem estado). */
SLT_API slt_result slt_measure(const float* interleaved, uint32_t frame_count, uint32_t channel_count, slt_measures* out);

/* ------------------------------------------------------------------ separação (fora do tempo real) */

typedef struct slt_separator_handle slt_separator_handle;

typedef enum slt_mask_profile {
    SLT_MASK_LEGACY = 0,  /* acima das bandas do modelo: média da linha (igual ao núcleo JS/C#/Kotlin) */
    SLT_MASK_REFINED = 1  /* acima das bandas do modelo: oitava de cima, pesada pela energia da mistura */
} slt_mask_profile;

typedef struct slt_separator_config {
    uint32_t struct_size;      /* sizeof(slt_separator_config): versiona a estrutura */
    uint64_t length;           /* amostras por canal do sinal na taxa do modelo */
    uint32_t window;           /* tamanho da FFT, potência de 2 (4096 no Spleeter) */
    uint32_t hop;              /* passo entre quadros (1024) */
    uint32_t frames_per_chunk; /* quadros por trecho de inferência (512) */
    uint32_t bands;            /* bandas que o modelo estima (1024) */
    uint32_t instruments;      /* instrumentos nas estimativas (4) */
    uint32_t outputs;          /* faixas de saída (2 ou 4) */
    uint32_t mask_profile;     /* slt_mask_profile */
} slt_separator_config;

SLT_API slt_separator_handle* slt_separator_create(const slt_separator_config* config);
SLT_API void slt_separator_destroy(slt_separator_handle* separator);
SLT_API uint32_t slt_separator_chunk_count(const slt_separator_handle* separator);
/* Janela do sinal original que o trecho precisa: start pode ser negativo e start + count
 * pode passar do fim; o chamador preenche com zeros o que ficar fora do sinal. frames é a
 * quantidade de quadros reais do trecho. */
SLT_API slt_result slt_separator_chunk_window(const slt_separator_handle* separator, uint32_t chunk, int64_t* start, uint32_t* count, uint32_t* frames);
/* STFT do trecho. left/right: count amostras (mono: o mesmo ponteiro nos dois).
 * magnitude recebe [2, frames_per_chunk, bands] (zeros depois do último quadro). */
SLT_API slt_result slt_separator_analyze(slt_separator_handle* separator, uint32_t chunk, const float* left, const float* right, uint32_t count, float* magnitude, uint32_t magnitude_count);
/* Aplica a máscara da saída `output` (soma dos instrumentos em indices) ao trecho analisado
 * e devolve as amostras já completas em out_left/out_right (planar), a partir de *out_start
 * do sinal. capacity precisa ser pelo menos frames_per_chunk * hop + window.
 * estimates: [instruments, 2, frames_per_chunk, bands], o que o modelo devolveu. */
SLT_API slt_result slt_separator_synthesize(slt_separator_handle* separator, uint32_t chunk, uint32_t output, const float* estimates, uint32_t estimates_count, const uint32_t* indices, uint32_t index_count, float* out_left, float* out_right, uint32_t capacity, int64_t* out_start, uint32_t* out_count);

typedef struct slt_complex_stft_handle slt_complex_stft_handle;

/* Análise de um bloco estéreo fixo, com Hann periódica e reflexão de window/2
 * amostras de cada lado. window é potência de 2 de 16 a 65536, hop está em
 * [1, window/2] e length é maior que window/2. Até 4096 quadros por bloco. */
SLT_API slt_complex_stft_handle* slt_complex_stft_create(uint32_t window, uint32_t hop, uint32_t length);
SLT_API void slt_complex_stft_destroy(slt_complex_stft_handle* stft);
/* Quantidade de floats para cada espectro ou máscara, 0 para handle nulo. */
SLT_API uint32_t slt_complex_stft_size(const slt_complex_stft_handle* stft);
/* Entradas planares de length amostras. Espectro em [frame, bin, channel, re/im],
 * com floor(length/hop)+1 quadros, window/2+1 bins e dois canais. */
SLT_API slt_result slt_complex_stft_analyze(slt_complex_stft_handle* stft, const float* left, const float* right,
    uint32_t length, float* spectrum, uint32_t spectrum_count);
/* Multiplica espectro e máscara complexa e reconstrói length amostras por
 * canal. mask nulo usa ganho unitário. zero_dc igual a 1 zera o bin DC.
 * As saídas são disjuntas dos buffers de entrada e uma da outra. */
SLT_API slt_result slt_complex_stft_synthesize(slt_complex_stft_handle* stft, const float* spectrum,
    const float* mask, uint32_t spectrum_count, uint32_t zero_dc, float* left, float* right, uint32_t length);

/* ------------------------------------------------------------------ restauração por instrumento */

typedef struct slt_restorer_handle slt_restorer_handle;

typedef enum slt_stem_profile {
    SLT_STEM_VOCALS = 0,
    SLT_STEM_DRUMS = 1,
    SLT_STEM_BASS = 2,
    SLT_STEM_OTHER = 3 /* guitarra, teclados e o resto: tira a aspereza, devolve os médios */
} slt_stem_profile;

/* Versão do desenho da restauração: entra no manifesto do projeto. Muda quando o som muda. */
#define SLT_RESTORATION_VERSION 1u

typedef struct slt_restorer_config {
    uint32_t struct_size;
    uint32_t sample_rate;  /* 8000 a 192000 */
    uint32_t profile;      /* slt_stem_profile */
    float intensity;       /* 0 a 100; 0 devolve o áudio igual */
} slt_restorer_config;

SLT_API slt_restorer_handle* slt_restorer_create(const slt_restorer_config* config);
SLT_API void slt_restorer_destroy(slt_restorer_handle* restorer);
/* Processa no lugar, em blocos sucessivos (planar; mono: right nulo). */
SLT_API slt_result slt_restorer_process(slt_restorer_handle* restorer, float* left, float* right, uint32_t frame_count);
/* Ganho linear que iguala o volume (RMS) do que saiu ao do que entrou até aqui, limitado a
 * ±12 dB: é o que deixa o A/B "bruto x restaurado" nivelado. */
SLT_API slt_result slt_restorer_matching_gain(const slt_restorer_handle* restorer, float* gain);

/* ------------------------------------------------------------------ consistência com a mistura */

typedef struct slt_consistency_config {
    uint32_t struct_size;
    uint32_t window;        /* potência de 2; 0 = 2048 */
    uint32_t hop;           /* 0 = window / 4 */
    uint32_t keep_residual; /* 1: não redistribui; só calcula o resíduo */
} slt_consistency_config;

/*
 * Resíduo R = mistura − soma das faixas. Com keep_residual = 0, cada faixa recebe a parte
 * dela do resíduo por região tempo-frequência (peso = energia da faixa / energia de todas),
 * de modo que a soma das faixas volta a ser a mistura. Com keep_residual = 1, as faixas não
 * mudam e o resíduo sai inteiro em residual_left/right (obrigatórios nesse caso).
 * stems: stem_count * 2 ponteiros planares (L e R de cada faixa), alterados no lugar.
 * residual_*: opcionais com keep_residual = 0 (recebem o que sobrou depois de redistribuir).
 */
SLT_API slt_result slt_mixture_consistency(const slt_consistency_config* config, const float* mix_left, const float* mix_right, float* const* stems, uint32_t stem_count, uint64_t frame_count, float* residual_left, float* residual_right);

/* ------------------------------------------------------------------ mixer multipista */

/*
 * O mixer da criação musical: recebe o projeto no mesmo JSON do .sltmusic e mistura as
 * faixas (clipes, 14 efeitos, fader/pan com automação, envios, barramentos, master e
 * metrônomo) com a mesma conta dos núcleos em JS, C# e Kotlin.
 *
 * Duas threads, sem trava entre elas:
 *  - controle: create/destroy, set_source, set_project, set_count_in, refresh, meters,
 *    play/stop/set_loop/set_metronome. É a única que aloca e libera.
 *  - áudio: slt_mixer_process (transporte, para o callback do dispositivo) ou
 *    slt_mixer_render (posição explícita, para exportar). Nunca aloca, bloqueia ou faz E/S.
 *    Só uma das duas por vez no mesmo handle.
 *
 * As fontes são do chamador: os ponteiros de set_source precisam continuar válidos até a
 * fonte ser trocada ou removida, o mixer ser atualizado (refresh/set_project) e um bloco
 * de áudio ter rodado depois disso; ou até o destroy.
 */
typedef struct slt_mixer_handle slt_mixer_handle;

typedef struct slt_click {
    int64_t frame;   /* quadro da linha do tempo */
    uint32_t strong; /* 1 = tempo forte */
    uint32_t reserved;
} slt_click;

typedef struct slt_mix_options {
    uint32_t struct_size;
    uint32_t no_master;      /* 1: pula os efeitos do master (exportar faixa crua) */
    uint32_t metronome;      /* 1: soma o clique do metrônomo */
    uint32_t reserved;
    const char* only_track;  /* id de uma faixa para tocar sozinha, ou nulo */
    const float* input_left; /* entrada ao vivo (frame_count amostras), ouvida nas faixas armadas com MON; ou nulo */
    const float* input_right;
} slt_mix_options;

SLT_API slt_mixer_handle* slt_mixer_create(uint32_t sample_rate);
SLT_API void slt_mixer_destroy(slt_mixer_handle* mixer);
/* Registra (ou troca) a fonte de áudio de uma mídia. key: id da mídia (UTF-8, terminado em
 * zero) ou a chave que o cliente pôs em clipe._fonte. right nulo = mono. left nulo remove. */
SLT_API slt_result slt_mixer_set_source(slt_mixer_handle* mixer, const char* key, const float* left, const float* right, uint64_t frame_count);
/* Compila e publica o projeto. O texto é copiado. Projeto inválido não troca o que toca. */
SLT_API slt_result slt_mixer_set_project(slt_mixer_handle* mixer, const char* json_utf8, uint32_t length);
/* Cliques da contagem antes da gravação (tocam mesmo com o metrônomo desligado). */
SLT_API slt_result slt_mixer_set_count_in(slt_mixer_handle* mixer, const slt_click* clicks, uint32_t count, int64_t end_frame);
/* Recompila o último projeto com as fontes e a contagem atuais. */
SLT_API slt_result slt_mixer_refresh(slt_mixer_handle* mixer);
/* Mistura [start, start + frame_count) em out_left/out_right (planar). options pode ser nulo. */
SLT_API slt_result slt_mixer_render(slt_mixer_handle* mixer, int64_t start, uint32_t frame_count, float* out_left, float* out_right, const slt_mix_options* options);
/* Pico esquerdo, pico direito, RMS esquerdo e RMS direito do último bloco misturado, de
 * uma faixa, de um barramento ou de "master". */
SLT_API slt_result slt_mixer_meters(const slt_mixer_handle* mixer, const char* id, float* four);

SLT_API slt_result slt_mixer_play(slt_mixer_handle* mixer, int64_t position);
SLT_API slt_result slt_mixer_stop(slt_mixer_handle* mixer);
SLT_API int64_t slt_mixer_position(const slt_mixer_handle* mixer);
/* end <= start desliga o loop. */
SLT_API slt_result slt_mixer_set_loop(slt_mixer_handle* mixer, int64_t start, int64_t end);
SLT_API slt_result slt_mixer_set_metronome(slt_mixer_handle* mixer, uint32_t enabled);
/* Um bloco do callback de áudio (planar): avança o transporte quando está tocando; parado,
 * devolve silêncio. in_left/in_right podem ser nulos. */
SLT_API slt_result slt_mixer_process(slt_mixer_handle* mixer, const float* in_left, const float* in_right, float* out_left, float* out_right, uint32_t frame_count);

/* ------------------------------------------------------------------ dispositivo de áudio */

/*
 * O dispositivo de áudio dentro do núcleo: a thread de áudio é nativa e chama o mixer direto,
 * sem passar por código gerenciado. Backends: WASAPI (Windows, modo compartilhado, por
 * eventos), ASIO (Windows, quando há driver instalado) e Oboe (Android). Não existe no
 * WebAssembly: lá o áudio é do AudioWorklet do navegador.
 *
 * O fluxo roda na taxa do mixer (o WASAPI converte quando o aparelho está em outra). A
 * captura, quando pedida, entra no mixer (monitoração das faixas armadas) e fica disponível
 * por slt_stream_read, com a posição da linha do tempo de cada pedaço.
 */
typedef enum slt_backend {
    SLT_BACKEND_AUTO = 0, /* o padrão da plataforma: WASAPI no Windows, Oboe no Android */
    SLT_BACKEND_WASAPI = 1,
    SLT_BACKEND_ASIO = 2,
    SLT_BACKEND_OBOE = 3
} slt_backend;

typedef struct slt_device_info {
    uint32_t backend;
    uint32_t input_channels;   /* 0 = não captura */
    uint32_t output_channels;  /* 0 = não toca */
    uint32_t sample_rate;      /* a taxa em que o aparelho está, quando conhecida */
    uint32_t is_default;       /* 1 = padrão do sistema para o que ele faz */
    uint32_t reserved;
    char id[512];              /* UTF-8, terminado em zero: é o que slt_stream_config recebe */
    char name[256];            /* UTF-8, para mostrar ao usuário */
} slt_device_info;

typedef struct slt_stream_config {
    uint32_t struct_size;
    uint32_t backend;        /* slt_backend */
    const char* output_id;   /* nulo = saída padrão */
    const char* input_id;    /* nulo = entrada padrão (com use_input) */
    uint32_t use_input;      /* 1: abre a captura junto */
    uint32_t sample_rate;    /* 0 ou a taxa do mixer */
    uint32_t buffer_frames;  /* tamanho de bloco desejado; 0 = o padrão do aparelho */
    uint32_t reserved;
} slt_stream_config;

typedef struct slt_stream_info {
    uint32_t backend;
    uint32_t sample_rate;
    uint32_t buffer_frames;          /* o bloco que o aparelho entrega de verdade */
    uint32_t output_channels;
    uint32_t input_channels;         /* 0 quando a captura não abriu */
    uint32_t output_latency_frames;  /* do que o aparelho informa; não mede conversores nem Bluetooth */
    uint32_t input_latency_frames;
    uint32_t running;                /* 0 também quando o aparelho sumiu (ver lost) */
    uint32_t lost;                   /* 1: desconectado ou formato trocado; feche e abra de novo */
    uint32_t low_latency;            /* 1: o caminho de baixa latência do sistema foi concedido */
} slt_stream_info;

typedef struct slt_stream_stats {
    uint64_t callbacks;
    uint64_t frames;
    uint32_t xruns;               /* blocos atrasados ou descontinuidades avisadas pelo sistema */
    uint32_t max_callback_us;     /* pior tempo dentro do callback */
    uint32_t average_callback_us;
    uint32_t capture_overflows;   /* blocos de captura perdidos porque ninguém leu a tempo */
} slt_stream_stats;

typedef struct slt_stream_handle slt_stream_handle;

/* Bits de 1 << slt_backend com os backends compilados nesta biblioteca. */
SLT_API uint32_t slt_device_backends(void);
/* Lista os aparelhos de um backend. *count recebe quantos existem (pode passar de capacity). */
SLT_API slt_result slt_device_list(uint32_t backend, slt_device_info* devices, uint32_t capacity, uint32_t* count);
/* Abre o aparelho ligado ao mixer. O mixer precisa viver mais que o fluxo. */
SLT_API slt_result slt_stream_open(const slt_stream_config* config, slt_mixer_handle* mixer, slt_stream_handle** stream);
SLT_API slt_result slt_stream_start(slt_stream_handle* stream);
SLT_API slt_result slt_stream_stop(slt_stream_handle* stream);
SLT_API void slt_stream_close(slt_stream_handle* stream);
SLT_API slt_result slt_stream_get_info(const slt_stream_handle* stream, slt_stream_info* info);
SLT_API slt_result slt_stream_get_stats(const slt_stream_handle* stream, slt_stream_stats* stats);
/* Liga ou desliga a gravação da captura no anel (a monitoração não depende disto). */
SLT_API slt_result slt_stream_capture(slt_stream_handle* stream, uint32_t enabled);
/* Tira do anel até capacity quadros de um mesmo bloco do aparelho. *position recebe o
 * quadro da linha do tempo do primeiro (−1 se o transporte estava parado). Devolve quantos. */
SLT_API uint32_t slt_stream_read(slt_stream_handle* stream, float* left, float* right, uint32_t capacity, int64_t* position);

/* ------------------------------------------------------------------ plugins VST3 */

#if defined(_WIN32)
typedef struct slt_plugin_handle slt_plugin_handle;

typedef struct slt_plugin_info {
    char path[1024];   /* caminho UTF-8 do bundle */
    char class_id[33]; /* 32 dígitos hexadecimais e terminador */
    char name[128];
    char vendor[128];
    char version[64];
} slt_plugin_info;

typedef struct slt_plugin_parameter {
    uint32_t id;
    uint32_t flags;        /* ParameterInfo::flags do VST3 */
    double default_value; /* normalizado, de 0 a 1 */
    double value;
    char name[128];
    char units[64];
} slt_plugin_parameter;

/* path nulo pesquisa os diretórios padrão do SDK. A capacidade pode ser zero. */
SLT_API slt_result slt_plugin_scan(const char* path, slt_plugin_info* plugins, uint32_t capacity, uint32_t* count);
SLT_API slt_result slt_plugin_open(const char* path, const char* class_id, uint32_t sample_rate, uint32_t max_frames, slt_plugin_handle** plugin);
SLT_API void slt_plugin_close(slt_plugin_handle* plugin);
SLT_API uint32_t slt_plugin_latency(const slt_plugin_handle* plugin);
SLT_API uint32_t slt_plugin_parameter_count(const slt_plugin_handle* plugin);
SLT_API slt_result slt_plugin_get_parameter(const slt_plugin_handle* plugin, uint32_t index, slt_plugin_parameter* parameter);
SLT_API slt_result slt_plugin_set_parameter(slt_plugin_handle* plugin, uint32_t id, double value);
/* Buffers float32 planares no lugar. Em falha, preserva a entrada. */
SLT_API slt_result slt_plugin_process(slt_plugin_handle* plugin, float* left, float* right, uint32_t frame_count);
/* Estado do processador e do controlador, versionado. Chame fora do processamento. */
SLT_API slt_result slt_plugin_get_state(slt_plugin_handle* plugin, void* data, uint32_t capacity, uint32_t* size);
SLT_API slt_result slt_plugin_set_state(slt_plugin_handle* plugin, const void* data, uint32_t size);
#endif

/* ------------------------------------------------------------------ memória (só WebAssembly) */

/* No navegador o chamador precisa de memória dentro do módulo para os buffers. Fora do
 * WebAssembly estas funções existem, mas o chamador usa a própria memória. */
SLT_API void* slt_alloc(size_t bytes);
SLT_API void slt_free(void* pointer);

#ifdef __cplusplus
}
#endif

#endif /* SLT_MEDIA_API_H */
