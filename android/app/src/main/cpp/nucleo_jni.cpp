#include <jni.h>
#include <cstdint>
#include <cstdio>

#include "slt_media_api.h"

#define JNI_NUCLEO(nome) Java_br_com_smellsliketech_converter_musica_NucleoJni_##nome

namespace {
template<class T> T* ponteiro(jlong valor) {
    return reinterpret_cast<T*>(static_cast<intptr_t>(valor));
}

jlong handle(const void* valor) {
    return static_cast<jlong>(reinterpret_cast<intptr_t>(valor));
}

template<class T> T* direto(JNIEnv* env, jobject buffer, jlong elementos) {
    if (buffer == nullptr || elementos <= 0 ||
        env->GetDirectBufferCapacity(buffer) / static_cast<jlong>(sizeof(T)) < elementos) return nullptr;
    auto* endereco = env->GetDirectBufferAddress(buffer);
    return reinterpret_cast<uintptr_t>(endereco) % alignof(T) == 0 ? static_cast<T*>(endereco) : nullptr;
}

class Bytes {
    JNIEnv* env;
    jbyteArray vetor;
public:
    const jbyte* dados;
    Bytes(JNIEnv* env_, jbyteArray vetor_) : env(env_), vetor(vetor_),
        dados(vetor == nullptr ? nullptr : env->GetByteArrayElements(vetor, nullptr)) {}
    ~Bytes() {
        if (dados != nullptr) env->ReleaseByteArrayElements(vetor, const_cast<jbyte*>(dados), JNI_ABORT);
    }
    const char* texto() const { return reinterpret_cast<const char*>(dados); }
    jsize tamanho() const { return vetor == nullptr ? 0 : env->GetArrayLength(vetor); }
    const char* terminadoEmZero() const {
        const jsize n = tamanho();
        return dados != nullptr && n > 0 && dados[n - 1] == 0 ? texto() : nullptr;
    }
};

jlongArray inteiros(JNIEnv* env, const jlong* valores, jsize n) {
    jlongArray resultado = env->NewLongArray(n);
    if (resultado != nullptr) env->SetLongArrayRegion(resultado, 0, n, valores);
    return resultado;
}

template<class T, size_t N> jlongArray inteiros(JNIEnv* env, const T (&valores)[N]) {
    jlong convertidos[N];
    for (size_t i = 0; i < N; ++i) convertidos[i] = static_cast<jlong>(valores[i]);
    return inteiros(env, convertidos, N);
}
}

extern "C" {

JNIEXPORT jint JNICALL JNI_NUCLEO(versao)(JNIEnv*, jobject) { return slt_get_api_version(); }
JNIEXPORT jint JNICALL JNI_NUCLEO(capacidades)(JNIEnv*, jobject) { return slt_get_capabilities(); }
JNIEXPORT jint JNICALL JNI_NUCLEO(backends)(JNIEnv*, jobject) { return slt_device_backends(); }

JNIEXPORT jlong JNICALL JNI_NUCLEO(criarStftComplexa)(JNIEnv*, jobject, jint janela, jint passo, jint n) {
    return handle(slt_complex_stft_create(janela, passo, n));
}

JNIEXPORT void JNICALL JNI_NUCLEO(destruirStftComplexa)(JNIEnv*, jobject, jlong stft) {
    slt_complex_stft_destroy(ponteiro<slt_complex_stft_handle>(stft));
}

JNIEXPORT jint JNICALL JNI_NUCLEO(tamanhoStftComplexa)(JNIEnv*, jobject, jlong stft) {
    return static_cast<jint>(slt_complex_stft_size(ponteiro<slt_complex_stft_handle>(stft)));
}

JNIEXPORT jint JNICALL JNI_NUCLEO(analisarStftComplexa)(JNIEnv* env, jobject, jlong stft, jint n,
                                                        jobject esquerda, jobject direita, jobject espectro, jint tamanho) {
    return slt_complex_stft_analyze(ponteiro<slt_complex_stft_handle>(stft),
        direto<float>(env, esquerda, n), direto<float>(env, direita, n), n, direto<float>(env, espectro, tamanho), tamanho);
}

JNIEXPORT jint JNICALL JNI_NUCLEO(sintetizarStftComplexa)(JNIEnv* env, jobject, jlong stft, jobject espectro,
    jobject mascara, jint tamanho, jboolean zerarDc, jobject esquerda, jobject direita, jint n) {
    const float* ganhos = direto<float>(env, mascara, tamanho);
    if (mascara != nullptr && ganhos == nullptr) return SLT_ERROR_INVALID_ARGUMENT;
    return slt_complex_stft_synthesize(ponteiro<slt_complex_stft_handle>(stft), direto<float>(env, espectro, tamanho),
        ganhos, tamanho, zerarDc ? 1u : 0u, direto<float>(env, esquerda, n), direto<float>(env, direita, n), n);
}

JNIEXPORT jlong JNICALL JNI_NUCLEO(criarMixer)(JNIEnv*, jobject, jint taxa) {
    return handle(slt_mixer_create(taxa));
}
JNIEXPORT void JNICALL JNI_NUCLEO(destruirMixer)(JNIEnv*, jobject, jlong mixer) {
    slt_mixer_destroy(ponteiro<slt_mixer_handle>(mixer));
}
JNIEXPORT jint JNICALL JNI_NUCLEO(fonte)(JNIEnv* env, jobject, jlong mixer, jbyteArray id,
                                        jobject esquerda, jobject direita, jint quadros) {
    const Bytes chave(env, id);
    const float* e = direto<float>(env, esquerda, quadros);
    const float* d = direto<float>(env, direita, quadros);
    if (e == nullptr || (direita != nullptr && d == nullptr)) return SLT_ERROR_INVALID_ARGUMENT;
    return slt_mixer_set_source(ponteiro<slt_mixer_handle>(mixer), chave.terminadoEmZero(), e, d, quadros);
}
JNIEXPORT jint JNICALL JNI_NUCLEO(projeto)(JNIEnv* env, jobject, jlong mixer, jbyteArray json) {
    const Bytes texto(env, json);
    return slt_mixer_set_project(ponteiro<slt_mixer_handle>(mixer), texto.texto(), texto.tamanho());
}
JNIEXPORT jint JNICALL JNI_NUCLEO(removerFonte)(JNIEnv* env, jobject, jlong mixer, jbyteArray id) {
    const Bytes chave(env, id);
    return slt_mixer_set_source(ponteiro<slt_mixer_handle>(mixer), chave.terminadoEmZero(), nullptr, nullptr, 0);
}
JNIEXPORT jint JNICALL JNI_NUCLEO(atualizarMixer)(JNIEnv*, jobject, jlong mixer) {
    return slt_mixer_refresh(ponteiro<slt_mixer_handle>(mixer));
}
JNIEXPORT jint JNICALL JNI_NUCLEO(renderizar)(JNIEnv* env, jobject, jlong mixer, jlong inicio,
                                              jint quadros, jobject esquerda, jobject direita) {
    return slt_mixer_render(ponteiro<slt_mixer_handle>(mixer), inicio, quadros,
        direto<float>(env, esquerda, quadros), direto<float>(env, direita, quadros), nullptr);
}
JNIEXPORT jint JNICALL JNI_NUCLEO(tocar)(JNIEnv*, jobject, jlong mixer, jlong posicao) {
    return slt_mixer_play(ponteiro<slt_mixer_handle>(mixer), posicao);
}
JNIEXPORT jint JNICALL JNI_NUCLEO(parar)(JNIEnv*, jobject, jlong mixer) {
    return slt_mixer_stop(ponteiro<slt_mixer_handle>(mixer));
}
JNIEXPORT jlong JNICALL JNI_NUCLEO(posicao)(JNIEnv*, jobject, jlong mixer) {
    return slt_mixer_position(ponteiro<slt_mixer_handle>(mixer));
}
JNIEXPORT jint JNICALL JNI_NUCLEO(loop)(JNIEnv*, jobject, jlong mixer, jlong inicio, jlong fim) {
    return slt_mixer_set_loop(ponteiro<slt_mixer_handle>(mixer), inicio, fim);
}
JNIEXPORT jint JNICALL JNI_NUCLEO(metronomo)(JNIEnv*, jobject, jlong mixer, jboolean ligado) {
    return slt_mixer_set_metronome(ponteiro<slt_mixer_handle>(mixer), ligado);
}
JNIEXPORT jfloatArray JNICALL JNI_NUCLEO(medidores)(JNIEnv* env, jobject, jlong mixer, jbyteArray id) {
    const Bytes chave(env, id);
    float valores[4];
    if (slt_mixer_meters(ponteiro<slt_mixer_handle>(mixer), chave.terminadoEmZero(), valores) != SLT_OK) return nullptr;
    jfloatArray resultado = env->NewFloatArray(4);
    if (resultado != nullptr) env->SetFloatArrayRegion(resultado, 0, 4, valores);
    return resultado;
}

JNIEXPORT jlong JNICALL JNI_NUCLEO(abrirFluxo)(JNIEnv*, jobject, jlong mixer, jint saida,
                                               jint entrada, jboolean capturar, jint bloco) {
    if (saida < 0 || entrada < 0 || bloco < 0) return 0;
    char idSaida[12], idEntrada[12];
    snprintf(idSaida, sizeof(idSaida), "%d", saida);
    snprintf(idEntrada, sizeof(idEntrada), "%d", entrada);
    const slt_stream_config config = {sizeof(config), SLT_BACKEND_OBOE, idSaida, idEntrada,
        capturar, 0, static_cast<uint32_t>(bloco), 0};
    slt_stream_handle* fluxo = nullptr;
    const slt_result resultado = slt_stream_open(&config, ponteiro<slt_mixer_handle>(mixer), &fluxo);
    return resultado == SLT_OK ? handle(fluxo) : 0;
}
JNIEXPORT void JNICALL JNI_NUCLEO(fecharFluxo)(JNIEnv*, jobject, jlong fluxo) {
    slt_stream_close(ponteiro<slt_stream_handle>(fluxo));
}
JNIEXPORT jint JNICALL JNI_NUCLEO(iniciarFluxo)(JNIEnv*, jobject, jlong fluxo) {
    return slt_stream_start(ponteiro<slt_stream_handle>(fluxo));
}
JNIEXPORT jint JNICALL JNI_NUCLEO(pararFluxo)(JNIEnv*, jobject, jlong fluxo) {
    return slt_stream_stop(ponteiro<slt_stream_handle>(fluxo));
}
JNIEXPORT jlongArray JNICALL JNI_NUCLEO(info)(JNIEnv* env, jobject, jlong fluxo) {
    slt_stream_info info = {};
    if (slt_stream_get_info(ponteiro<slt_stream_handle>(fluxo), &info) != SLT_OK) return nullptr;
    const uint32_t valores[] = {info.backend, info.sample_rate, info.buffer_frames, info.output_channels,
        info.input_channels, info.output_latency_frames, info.input_latency_frames, info.running,
        info.lost, info.low_latency};
    return inteiros(env, valores);
}
JNIEXPORT jlongArray JNICALL JNI_NUCLEO(estatisticas)(JNIEnv* env, jobject, jlong fluxo) {
    slt_stream_stats stats = {};
    if (slt_stream_get_stats(ponteiro<slt_stream_handle>(fluxo), &stats) != SLT_OK) return nullptr;
    const uint64_t valores[] = {stats.callbacks, stats.frames, stats.xruns, stats.max_callback_us,
        stats.average_callback_us, stats.capture_overflows};
    return inteiros(env, valores);
}
JNIEXPORT jint JNICALL JNI_NUCLEO(captura)(JNIEnv*, jobject, jlong fluxo, jboolean ligar) {
    return slt_stream_capture(ponteiro<slt_stream_handle>(fluxo), ligar);
}
JNIEXPORT jlongArray JNICALL JNI_NUCLEO(ler)(JNIEnv* env, jobject, jlong fluxo, jint capacidade,
                                           jobject esquerda, jobject direita) {
    float* e = direto<float>(env, esquerda, capacidade);
    float* d = direto<float>(env, direita, capacidade);
    if (e == nullptr || d == nullptr) return nullptr;
    int64_t posicao = -1;
    const auto n = slt_stream_read(ponteiro<slt_stream_handle>(fluxo), e, d, capacidade, &posicao);
    const int64_t valores[] = {n, posicao};
    return inteiros(env, valores);
}

JNIEXPORT jlong JNICALL JNI_NUCLEO(criarRestaurador)(JNIEnv*, jobject, jint taxa, jint perfil, jfloat intensidade) {
    const slt_restorer_config config = {sizeof(config), static_cast<uint32_t>(taxa),
        static_cast<uint32_t>(perfil), intensidade};
    return handle(slt_restorer_create(&config));
}
JNIEXPORT void JNICALL JNI_NUCLEO(destruirRestaurador)(JNIEnv*, jobject, jlong restaurador) {
    slt_restorer_destroy(ponteiro<slt_restorer_handle>(restaurador));
}
JNIEXPORT jint JNICALL JNI_NUCLEO(restaurar)(JNIEnv* env, jobject, jlong restaurador, jint n,
                                            jobject esquerda, jobject direita) {
    float* e = direto<float>(env, esquerda, n);
    float* d = direto<float>(env, direita, n);
    if (e == nullptr || (direita != nullptr && d == nullptr)) return SLT_ERROR_INVALID_ARGUMENT;
    return slt_restorer_process(ponteiro<slt_restorer_handle>(restaurador), e, d, n);
}
JNIEXPORT jfloat JNICALL JNI_NUCLEO(ganhoDaRestauracao)(JNIEnv*, jobject, jlong restaurador) {
    float ganho = 0;
    slt_restorer_matching_gain(ponteiro<slt_restorer_handle>(restaurador), &ganho);
    return ganho;
}

JNIEXPORT jlong JNICALL JNI_NUCLEO(criarSeparador)(JNIEnv*, jobject, jlong amostras, jint fft, jint passo,
                                                  jint porTrecho, jint bandas, jint instrumentos, jint saidas,
                                                  jboolean refinada) {
    if (amostras <= 0 || fft <= 0 || passo <= 0 || porTrecho <= 0 || bandas <= 0 || instrumentos <= 0 || saidas <= 0) return 0;
    const slt_separator_config config = {sizeof(config), static_cast<uint64_t>(amostras),
        static_cast<uint32_t>(fft), static_cast<uint32_t>(passo), static_cast<uint32_t>(porTrecho),
        static_cast<uint32_t>(bandas), static_cast<uint32_t>(instrumentos), static_cast<uint32_t>(saidas),
        refinada ? SLT_MASK_REFINED : SLT_MASK_LEGACY};
    return handle(slt_separator_create(&config));
}
JNIEXPORT void JNICALL JNI_NUCLEO(destruirSeparador)(JNIEnv*, jobject, jlong separador) {
    slt_separator_destroy(ponteiro<slt_separator_handle>(separador));
}
JNIEXPORT jint JNICALL JNI_NUCLEO(trechos)(JNIEnv*, jobject, jlong separador) {
    return slt_separator_chunk_count(ponteiro<slt_separator_handle>(separador));
}
JNIEXPORT jlongArray JNICALL JNI_NUCLEO(janela)(JNIEnv* env, jobject, jlong separador, jint trecho) {
    int64_t inicio;
    uint32_t amostras, quadros;
    if (slt_separator_chunk_window(ponteiro<slt_separator_handle>(separador), trecho, &inicio, &amostras, &quadros) != SLT_OK) return nullptr;
    const int64_t valores[] = {inicio, amostras, quadros};
    return inteiros(env, valores);
}
JNIEXPORT jint JNICALL JNI_NUCLEO(analisar)(JNIEnv* env, jobject, jlong separador, jint trecho, jint n,
                                            jobject esquerda, jobject direita, jobject magnitude, jint tamanho) {
    return slt_separator_analyze(ponteiro<slt_separator_handle>(separador), trecho,
        direto<float>(env, esquerda, n), direto<float>(env, direita, n), n,
        direto<float>(env, magnitude, tamanho), tamanho);
}
JNIEXPORT jlongArray JNICALL JNI_NUCLEO(sintetizar)(JNIEnv* env, jobject, jlong separador, jint trecho, jint saida,
                                                   jobject estimativas, jint tamanho, jintArray instrumentos,
                                                   jobject esquerda, jobject direita, jint capacidade) {
    static_assert(sizeof(jint) == sizeof(uint32_t));
    const jsize quantos = env->GetArrayLength(instrumentos);
    jint* indices = env->GetIntArrayElements(instrumentos, nullptr);
    if (indices == nullptr) return nullptr;
    int64_t inicio;
    uint32_t n;
    const auto resultado = slt_separator_synthesize(ponteiro<slt_separator_handle>(separador), trecho, saida,
        direto<float>(env, estimativas, tamanho), tamanho, reinterpret_cast<const uint32_t*>(indices),
        quantos, direto<float>(env, esquerda, capacidade), direto<float>(env, direita, capacidade),
        capacidade, &inicio, &n);
    env->ReleaseIntArrayElements(instrumentos, indices, JNI_ABORT);
    if (resultado != SLT_OK) return nullptr;
    const int64_t valores[] = {inicio, n};
    return inteiros(env, valores);
}

}
