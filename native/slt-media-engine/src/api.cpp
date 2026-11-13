// A C ABI: valida o que chega, guarda o estado em handles opacos e nunca deixa uma exceção
// atravessar (o núcleo é compilado sem exceções). Contrato em include/slt_media_api.h.
#include "misturador.h"
#include "nucleo.h"

// O dispositivo de áudio só existe onde há um backend: Windows e Android.
#if defined(_WIN32) || defined(__ANDROID__)
#define SLT_COM_DISPOSITIVO 1
#include "dispositivo.h"
#else
#define SLT_COM_DISPOSITIVO 0
#endif

using namespace slt;

struct slt_engine_handle {
    Motor motor;
};

struct slt_separator_handle {
    ProcessadorEspectral processador;
    Vetor<float> mascara[2];
    uint32_t bandas = 0;
    uint32_t instrumentos = 0;
    uint32_t perfil = 0;
    uint32_t analisado = 0xFFFFFFFFu;  // trecho que está na memória
};

struct slt_restorer_handle {
    Restaurador restaurador;
};

struct slt_mixer_handle {
    Misturador misturador;
};

extern "C" {

SLT_API uint32_t slt_get_api_version(void) { return SLT_API_VERSION; }

SLT_API uint32_t slt_get_capabilities(void) {
    uint32_t capacidades = SLT_CAP_ENGINE | SLT_CAP_SEPARATOR | SLT_CAP_REFINED_MASK |
        SLT_CAP_RESTORER | SLT_CAP_CONSISTENCY | SLT_CAP_MIXER | SLT_CAP_COMPLEX_STFT |
        (SLT_COM_DISPOSITIVO ? SLT_CAP_DEVICE : 0u);
#if defined(SLT_COM_VST3)
    capacidades |= SLT_CAP_VST3;
#endif
    return capacidades;
}

#if defined(_WIN32) && !defined(SLT_COM_VST3)
SLT_API slt_result slt_plugin_scan(const char*, slt_plugin_info*, uint32_t, uint32_t* count) {
    if (count != nullptr) *count = 0;
    return SLT_ERROR_UNSUPPORTED;
}
SLT_API slt_result slt_plugin_open(const char*, const char*, uint32_t, uint32_t, slt_plugin_handle** plugin) {
    if (plugin != nullptr) *plugin = nullptr;
    return SLT_ERROR_UNSUPPORTED;
}
SLT_API void slt_plugin_close(slt_plugin_handle*) {}
SLT_API uint32_t slt_plugin_latency(const slt_plugin_handle*) { return 0; }
SLT_API uint32_t slt_plugin_parameter_count(const slt_plugin_handle*) { return 0; }
SLT_API slt_result slt_plugin_get_parameter(const slt_plugin_handle*, uint32_t, slt_plugin_parameter*) { return SLT_ERROR_UNSUPPORTED; }
SLT_API slt_result slt_plugin_set_parameter(slt_plugin_handle*, uint32_t, double) { return SLT_ERROR_UNSUPPORTED; }
SLT_API slt_result slt_plugin_process(slt_plugin_handle*, float*, float*, uint32_t) { return SLT_ERROR_UNSUPPORTED; }
SLT_API slt_result slt_plugin_get_state(slt_plugin_handle*, void*, uint32_t, uint32_t*) { return SLT_ERROR_UNSUPPORTED; }
SLT_API slt_result slt_plugin_set_state(slt_plugin_handle*, const void*, uint32_t) { return SLT_ERROR_UNSUPPORTED; }
#endif

SLT_API const char* slt_result_message(slt_result result) {
    switch (result) {
        case SLT_OK: return "ok";
        case SLT_ERROR_INVALID_ARGUMENT: return "invalid argument";
        case SLT_ERROR_UNSUPPORTED_VERSION: return "unsupported structure version";
        case SLT_ERROR_OUT_OF_MEMORY: return "out of memory";
        case SLT_ERROR_BUFFER_TOO_SMALL: return "buffer too small";
        case SLT_ERROR_INVALID_STATE: return "invalid state";
        case SLT_ERROR_DEVICE: return "audio device unavailable";
        case SLT_ERROR_UNSUPPORTED: return "not supported on this platform";
        default: return "internal error";
    }
}

/* ------------------------------------------------------------------ motor */

SLT_API slt_engine_handle* slt_engine_create(uint32_t sample_rate, uint32_t channel_count) {
    slt_engine_handle* engine = criarObjeto<slt_engine_handle>();
    if (engine == nullptr) return nullptr;
    if (!engine->motor.configurar(sample_rate, channel_count)) {
        liberar(engine);
        return nullptr;
    }
    return engine;
}

SLT_API void slt_engine_destroy(slt_engine_handle* engine) {
    if (engine != nullptr) liberar(engine);
}

SLT_API slt_result slt_engine_set_gain(slt_engine_handle* engine, float gain_db, float pan) {
    if (engine == nullptr) return SLT_ERROR_INVALID_ARGUMENT;
    return engine->motor.definir(gain_db, pan) ? SLT_OK : SLT_ERROR_INVALID_ARGUMENT;
}

SLT_API slt_result slt_engine_process(slt_engine_handle* engine, const float* input, float* output, uint32_t frame_count) {
    if (engine == nullptr || input == nullptr || output == nullptr) return SLT_ERROR_INVALID_ARGUMENT;
    engine->motor.processar(input, output, frame_count);
    return SLT_OK;
}

SLT_API slt_result slt_measure(const float* interleaved, uint32_t frame_count, uint32_t channel_count, slt_measures* out) {
    if (interleaved == nullptr || out == nullptr || channel_count < 1 || channel_count > 8) return SLT_ERROR_INVALID_ARGUMENT;
    double energia = 0;
    double pico = 0;
    uint32_t cortadas = 0;
    const uint64_t total = static_cast<uint64_t>(frame_count) * channel_count;
    for (uint64_t i = 0; i < total; i += 1) {
        const double x = absoluto(interleaved[i]);
        energia += x * x;
        if (x > pico) pico = x;
        if (x > 1.0) cortadas += 1;
    }
    out->peak = static_cast<float>(pico);
    out->rms = total > 0 ? static_cast<float>(raiz(energia / static_cast<double>(total))) : 0;
    out->clipped = cortadas;
    return SLT_OK;
}

/* ------------------------------------------------------------------ separação */

SLT_API slt_separator_handle* slt_separator_create(const slt_separator_config* config) {
    if (config == nullptr || config->struct_size < sizeof(slt_separator_config)) return nullptr;
    if (config->bands == 0 || config->bands > config->window / 2 + 1 || config->instruments == 0 || config->instruments > 16) return nullptr;
    if (config->outputs == 0 || config->outputs > 16 || config->mask_profile > SLT_MASK_REFINED) return nullptr;
    slt_separator_handle* separator = criarObjeto<slt_separator_handle>();
    if (separator == nullptr) return nullptr;
    bool ok = separator->processador.criar(config->length, config->window, config->hop, config->frames_per_chunk, config->outputs);
    const size_t plano = ok ? static_cast<size_t>(separator->processador.T) * separator->processador.B : 0;
    ok = ok && separator->mascara[0].criar(plano) && separator->mascara[1].criar(plano);
    if (!ok) {
        slt_separator_destroy(separator);
        return nullptr;
    }
    separator->bandas = config->bands;
    separator->instrumentos = config->instruments;
    separator->perfil = config->mask_profile;
    return separator;
}

SLT_API void slt_separator_destroy(slt_separator_handle* separator) {
    if (separator == nullptr) return;
    separator->processador.soltar();
    separator->mascara[0].soltar();
    separator->mascara[1].soltar();
    liberar(separator);
}

SLT_API uint32_t slt_separator_chunk_count(const slt_separator_handle* separator) { return separator != nullptr ? separator->processador.trechos : 0; }

SLT_API slt_result slt_separator_chunk_window(const slt_separator_handle* separator, uint32_t chunk, int64_t* start, uint32_t* count, uint32_t* frames) {
    if (separator == nullptr || start == nullptr || count == nullptr || chunk >= separator->processador.trechos) return SLT_ERROR_INVALID_ARGUMENT;
    separator->processador.janelaDoTrecho(chunk, start, count);
    if (frames != nullptr) *frames = separator->processador.quadrosDoTrecho(chunk);
    return SLT_OK;
}

SLT_API slt_result slt_separator_analyze(slt_separator_handle* separator, uint32_t chunk, const float* left, const float* right, uint32_t count, float* magnitude, uint32_t magnitude_count) {
    if (separator == nullptr || left == nullptr || right == nullptr || magnitude == nullptr || chunk >= separator->processador.trechos) return SLT_ERROR_INVALID_ARGUMENT;
    ProcessadorEspectral& p = separator->processador;
    int64_t inicio = 0;
    uint32_t n = 0;
    p.janelaDoTrecho(chunk, &inicio, &n);
    if (count < n || static_cast<uint64_t>(magnitude_count) < static_cast<uint64_t>(2) * p.T * separator->bandas) return SLT_ERROR_BUFFER_TOO_SMALL;
    p.analisar(chunk, left, right);
    p.magnitude(p.quadrosDoTrecho(chunk), separator->bandas, magnitude);
    separator->analisado = chunk;
    return SLT_OK;
}

SLT_API slt_result slt_separator_synthesize(slt_separator_handle* separator, uint32_t chunk, uint32_t output, const float* estimates, uint32_t estimates_count, const uint32_t* indices, uint32_t index_count, float* out_left, float* out_right, uint32_t capacity, int64_t* out_start, uint32_t* out_count) {
    if (separator == nullptr || estimates == nullptr || indices == nullptr || index_count == 0 || out_left == nullptr || out_right == nullptr || out_start == nullptr || out_count == nullptr) return SLT_ERROR_INVALID_ARGUMENT;
    ProcessadorEspectral& p = separator->processador;
    if (chunk >= p.trechos || output >= p.saidas) return SLT_ERROR_INVALID_ARGUMENT;
    if (separator->analisado != chunk) return SLT_ERROR_INVALID_STATE;
    for (uint32_t i = 0; i < index_count; i += 1)
        if (indices[i] >= separator->instrumentos) return SLT_ERROR_INVALID_ARGUMENT;
    if (static_cast<uint64_t>(estimates_count) < static_cast<uint64_t>(separator->instrumentos) * 2 * p.T * separator->bandas) return SLT_ERROR_BUFFER_TOO_SMALL;
    if (capacity < p.tamanhoDoAcumulado()) return SLT_ERROR_BUFFER_TOO_SMALL;
    float* mascara[2] = {separator->mascara[0].dados, separator->mascara[1].dados};
    mascaraDaFaixa(p, estimates, separator->instrumentos, indices, index_count, p.quadrosDoTrecho(chunk), separator->bandas, separator->perfil, mascara);
    *out_count = p.sintetizar(chunk, output, mascara, out_left, out_right, out_start);
    return SLT_OK;
}

/* ------------------------------------------------------------------ restauração */

SLT_API slt_restorer_handle* slt_restorer_create(const slt_restorer_config* config) {
    if (config == nullptr || config->struct_size < sizeof(slt_restorer_config)) return nullptr;
    slt_restorer_handle* restorer = criarObjeto<slt_restorer_handle>();
    if (restorer == nullptr) return nullptr;
    if (!restorer->restaurador.configurar(config->sample_rate, config->profile, config->intensity)) {
        liberar(restorer);
        return nullptr;
    }
    return restorer;
}

SLT_API void slt_restorer_destroy(slt_restorer_handle* restorer) {
    if (restorer != nullptr) liberar(restorer);
}

SLT_API slt_result slt_restorer_process(slt_restorer_handle* restorer, float* left, float* right, uint32_t frame_count) {
    if (restorer == nullptr || left == nullptr) return SLT_ERROR_INVALID_ARGUMENT;
    restorer->restaurador.processar(left, right, frame_count);
    return SLT_OK;
}

SLT_API slt_result slt_restorer_matching_gain(const slt_restorer_handle* restorer, float* gain) {
    if (restorer == nullptr || gain == nullptr) return SLT_ERROR_INVALID_ARGUMENT;
    *gain = static_cast<float>(restorer->restaurador.ganhoDeNivelamento());
    return SLT_OK;
}

/* ------------------------------------------------------------------ consistência */

SLT_API slt_result slt_mixture_consistency(const slt_consistency_config* config, const float* mix_left, const float* mix_right, float* const* stems, uint32_t stem_count, uint64_t frame_count, float* residual_left, float* residual_right) {
    return consistenciaDaMistura(config, mix_left, mix_right, stems, stem_count, frame_count, residual_left, residual_right);
}

/* ------------------------------------------------------------------ mixer */

SLT_API slt_mixer_handle* slt_mixer_create(uint32_t sample_rate) {
    slt_mixer_handle* mixer = criarObjeto<slt_mixer_handle>();
    if (mixer == nullptr) return nullptr;
    if (!mixer->misturador.criar(sample_rate)) {
        mixer->misturador.soltar();
        liberar(mixer);
        return nullptr;
    }
    return mixer;
}

SLT_API void slt_mixer_destroy(slt_mixer_handle* mixer) {
    if (mixer == nullptr) return;
    mixer->misturador.soltar();
    liberar(mixer);
}

SLT_API slt_result slt_mixer_set_source(slt_mixer_handle* mixer, const char* key, const float* left, const float* right, uint64_t frame_count) {
    if (mixer == nullptr) return SLT_ERROR_INVALID_ARGUMENT;
    return mixer->misturador.definirFonte(key, left, right, frame_count);
}

SLT_API slt_result slt_mixer_set_project(slt_mixer_handle* mixer, const char* json_utf8, uint32_t length) {
    if (mixer == nullptr || json_utf8 == nullptr) return SLT_ERROR_INVALID_ARGUMENT;
    return mixer->misturador.definirProjeto(json_utf8, length);
}

SLT_API slt_result slt_mixer_set_count_in(slt_mixer_handle* mixer, const slt_click* clicks, uint32_t count, int64_t end_frame) {
    if (mixer == nullptr || (count > 0 && clicks == nullptr) || count > 4096) return SLT_ERROR_INVALID_ARGUMENT;
    Vetor<Clique> cliques;
    if (!cliques.criar(count)) return SLT_ERROR_OUT_OF_MEMORY;
    for (uint32_t i = 0; i < count; i += 1) cliques[i] = {clicks[i].frame, clicks[i].strong};
    const slt_result resultado = mixer->misturador.definirContagem(cliques.dados, count, end_frame);
    cliques.soltar();
    return resultado;
}

SLT_API slt_result slt_mixer_refresh(slt_mixer_handle* mixer) { return mixer != nullptr ? mixer->misturador.atualizar() : SLT_ERROR_INVALID_ARGUMENT; }

SLT_API slt_result slt_mixer_render(slt_mixer_handle* mixer, int64_t start, uint32_t frame_count, float* out_left, float* out_right, const slt_mix_options* options) {
    if (mixer == nullptr || out_left == nullptr || out_right == nullptr) return SLT_ERROR_INVALID_ARGUMENT;
    OpcoesDeMixagem opcoes;
    if (options != nullptr) {
        if (options->struct_size < sizeof(slt_mix_options)) return SLT_ERROR_UNSUPPORTED_VERSION;
        opcoes.semMaster = options->no_master != 0;
        opcoes.metronomo = options->metronome != 0;
        opcoes.entradaEsquerda = options->input_left;
        opcoes.entradaDireita = options->input_right;
        if (options->only_track != nullptr) {
            opcoes.somente = mixer->misturador.indiceDaFaixa(options->only_track);
            if (opcoes.somente < 0) return SLT_ERROR_INVALID_ARGUMENT;
        }
    }
    mixer->misturador.misturar(start, frame_count, out_left, out_right, opcoes);
    return SLT_OK;
}

SLT_API slt_result slt_mixer_meters(const slt_mixer_handle* mixer, const char* id, float* four) {
    if (mixer == nullptr || id == nullptr || four == nullptr) return SLT_ERROR_INVALID_ARGUMENT;
    return mixer->misturador.medidores(id, four) ? SLT_OK : SLT_ERROR_INVALID_ARGUMENT;
}

SLT_API slt_result slt_mixer_play(slt_mixer_handle* mixer, int64_t position) {
    if (mixer == nullptr || position < 0) return SLT_ERROR_INVALID_ARGUMENT;
    mixer->misturador.posicao.guardar(position);
    mixer->misturador.tocando.guardar(1);
    return SLT_OK;
}

SLT_API slt_result slt_mixer_stop(slt_mixer_handle* mixer) {
    if (mixer == nullptr) return SLT_ERROR_INVALID_ARGUMENT;
    mixer->misturador.tocando.guardar(0);
    return SLT_OK;
}

SLT_API int64_t slt_mixer_position(const slt_mixer_handle* mixer) { return mixer != nullptr ? mixer->misturador.posicao.ler() : 0; }

SLT_API slt_result slt_mixer_set_loop(slt_mixer_handle* mixer, int64_t start, int64_t end) {
    if (mixer == nullptr || start < 0) return SLT_ERROR_INVALID_ARGUMENT;
    // O fim primeiro em zero: a thread de áudio nunca vê um par meio trocado como loop válido.
    mixer->misturador.fimDoLoop.guardar(0);
    mixer->misturador.inicioDoLoop.guardar(start);
    mixer->misturador.fimDoLoop.guardar(end > start ? end : 0);
    return SLT_OK;
}

SLT_API slt_result slt_mixer_set_metronome(slt_mixer_handle* mixer, uint32_t enabled) {
    if (mixer == nullptr) return SLT_ERROR_INVALID_ARGUMENT;
    mixer->misturador.metronomoLigado.guardar(enabled != 0 ? 1 : 0);
    return SLT_OK;
}

SLT_API slt_result slt_mixer_process(slt_mixer_handle* mixer, const float* in_left, const float* in_right, float* out_left, float* out_right, uint32_t frame_count) {
    if (mixer == nullptr || out_left == nullptr || out_right == nullptr) return SLT_ERROR_INVALID_ARGUMENT;
    mixer->misturador.tempoReal(in_left, in_right, out_left, out_right, frame_count);
    return SLT_OK;
}

/* ------------------------------------------------------------------ dispositivo */

#if SLT_COM_DISPOSITIVO

struct slt_stream_handle {
    Fluxo* fluxo;
};

SLT_API uint32_t slt_device_backends(void) { return backendsDisponiveis(); }

SLT_API slt_result slt_device_list(uint32_t backend, slt_device_info* devices, uint32_t capacity, uint32_t* count) { return listarDispositivos(backend, devices, capacity, count); }

SLT_API slt_result slt_stream_open(const slt_stream_config* config, slt_mixer_handle* mixer, slt_stream_handle** stream) {
    if (config == nullptr || mixer == nullptr || stream == nullptr) return SLT_ERROR_INVALID_ARGUMENT;
    if (config->struct_size < sizeof(slt_stream_config)) return SLT_ERROR_UNSUPPORTED_VERSION;
    *stream = nullptr;
    slt_stream_handle* handle = criarObjeto<slt_stream_handle>();
    if (handle == nullptr) return SLT_ERROR_OUT_OF_MEMORY;
    const slt_result resultado = abrirFluxo(*config, &mixer->misturador, &handle->fluxo);
    if (resultado != SLT_OK) {
        liberar(handle);
        return resultado;
    }
    *stream = handle;
    return SLT_OK;
}

SLT_API slt_result slt_stream_start(slt_stream_handle* stream) { return stream != nullptr ? stream->fluxo->iniciar() : SLT_ERROR_INVALID_ARGUMENT; }

SLT_API slt_result slt_stream_stop(slt_stream_handle* stream) { return stream != nullptr ? stream->fluxo->parar() : SLT_ERROR_INVALID_ARGUMENT; }

SLT_API void slt_stream_close(slt_stream_handle* stream) {
    if (stream == nullptr) return;
    stream->fluxo->fechar();  // para, solta o aparelho e libera o próprio fluxo
    liberar(stream);
}

SLT_API slt_result slt_stream_get_info(const slt_stream_handle* stream, slt_stream_info* info) {
    if (stream == nullptr || info == nullptr) return SLT_ERROR_INVALID_ARGUMENT;
    stream->fluxo->atualizarInfo();
    *info = stream->fluxo->info;
    info->lost = stream->fluxo->perdido.ler() != 0 ? 1 : 0;
    if (info->lost != 0) info->running = 0;
    return SLT_OK;
}

SLT_API slt_result slt_stream_get_stats(const slt_stream_handle* stream, slt_stream_stats* stats) {
    if (stream == nullptr || stats == nullptr) return SLT_ERROR_INVALID_ARGUMENT;
    stream->fluxo->atualizarInfo();
    const Fluxo& f = *stream->fluxo;
    stats->callbacks = static_cast<uint64_t>(f.callbacks.ler());
    stats->frames = static_cast<uint64_t>(f.quadrosTocados.ler());
    stats->xruns = static_cast<uint32_t>(f.falhas.ler() + f.falhasDoAparelho.ler());
    stats->max_callback_us = static_cast<uint32_t>(f.maiorCallback.ler());
    stats->average_callback_us = stats->callbacks > 0 ? static_cast<uint32_t>(static_cast<uint64_t>(f.somaDosCallbacks.ler()) / stats->callbacks) : 0;
    stats->capture_overflows = static_cast<uint32_t>(f.transbordos.ler());
    return SLT_OK;
}

SLT_API slt_result slt_stream_capture(slt_stream_handle* stream, uint32_t enabled) {
    if (stream == nullptr) return SLT_ERROR_INVALID_ARGUMENT;
    if (enabled != 0 && stream->fluxo->info.input_channels == 0) return SLT_ERROR_INVALID_STATE;
    stream->fluxo->capturando.guardar(enabled != 0 ? 1 : 0);
    return SLT_OK;
}

SLT_API uint32_t slt_stream_read(slt_stream_handle* stream, float* left, float* right, uint32_t capacity, int64_t* position) { return stream != nullptr ? stream->fluxo->ler(left, right, capacity, position) : 0; }

#else

SLT_API uint32_t slt_device_backends(void) { return 0; }
SLT_API slt_result slt_device_list(uint32_t, slt_device_info*, uint32_t, uint32_t* count) {
    if (count != nullptr) *count = 0;
    return SLT_ERROR_UNSUPPORTED;
}
SLT_API slt_result slt_stream_open(const slt_stream_config*, slt_mixer_handle*, slt_stream_handle**) { return SLT_ERROR_UNSUPPORTED; }
SLT_API slt_result slt_stream_start(slt_stream_handle*) { return SLT_ERROR_UNSUPPORTED; }
SLT_API slt_result slt_stream_stop(slt_stream_handle*) { return SLT_ERROR_UNSUPPORTED; }
SLT_API void slt_stream_close(slt_stream_handle*) {}
SLT_API slt_result slt_stream_get_info(const slt_stream_handle*, slt_stream_info*) { return SLT_ERROR_UNSUPPORTED; }
SLT_API slt_result slt_stream_get_stats(const slt_stream_handle*, slt_stream_stats*) { return SLT_ERROR_UNSUPPORTED; }
SLT_API slt_result slt_stream_capture(slt_stream_handle*, uint32_t) { return SLT_ERROR_UNSUPPORTED; }
SLT_API uint32_t slt_stream_read(slt_stream_handle*, float*, float*, uint32_t, int64_t*) { return 0; }

#endif

/* ------------------------------------------------------------------ memória */

SLT_API void* slt_alloc(size_t bytes) { return alocar(bytes); }

SLT_API void slt_free(void* pointer) {
    if (pointer != nullptr) liberar(pointer);
}

}  // extern "C"
