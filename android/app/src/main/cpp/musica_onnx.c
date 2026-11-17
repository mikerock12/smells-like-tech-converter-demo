// Inferência do modelo de separação (Spleeter 4stems) pela API C do ONNX Runtime, para
// SeparacaoDaMusica no Android. Só três chamadas: abrir a sessão, rodar um trecho e fechar.
#include <dlfcn.h>
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include "onnxruntime_c_api.h"

// API 17 (ONNX Runtime 1.17): tudo que usamos existe nela e em qualquer versão posterior.
#define VERSAO_DA_API 17

static const OrtApi *api = NULL;
static OrtEnv *ambiente = NULL;
/** Se a última sessão aberta ficou com o NNAPI (acelerador do aparelho) ligado. */
static int ultimoComAcelerador = 0;

typedef OrtStatus *(*AnexarNnapi)(OrtSessionOptions *, uint32_t);

/**
 * Liga o NNAPI, que entrega ao Android as partes do modelo que o acelerador do aparelho
 * (Qualcomm Hexagon/Adreno, MediaTek APU, Samsung NPU...) sabe rodar; o resto continua na CPU.
 * Sem relaxar para FP16 (flag 0): a precisão continua a de float32. A função existe no
 * libonnxruntime.so do sherpa, mas não está no cabeçalho: procura pelo nome.
 */
static int anexarNnapi(OrtSessionOptions *opcoes) {
    void *biblioteca = dlopen("libonnxruntime.so", RTLD_NOW | RTLD_NOLOAD);
    if (biblioteca == NULL) return 0;
    AnexarNnapi anexar = (AnexarNnapi) dlsym(biblioteca, "OrtSessionOptionsAppendExecutionProvider_Nnapi");
    int ok = 0;
    if (anexar != NULL) {
        OrtStatus *status = anexar(opcoes, 0);
        if (status == NULL) ok = 1;
        else api->ReleaseStatus(status);
    }
    dlclose(biblioteca);
    return ok;
}

static int falhou(JNIEnv *jni, OrtStatus *status) {
    if (status == NULL) return 0;
    jclass classe = (*jni)->FindClass(jni, "java/lang/IllegalStateException");
    (*jni)->ThrowNew(jni, classe, api->GetErrorMessage(status));
    api->ReleaseStatus(status);
    return 1;
}

JNIEXPORT jlong JNICALL
Java_br_com_smellsliketech_converter_musica_InferenciaOnnx_abrir(JNIEnv *jni, jclass classe, jstring caminho, jint threads, jboolean economizar, jboolean acelerar) {
    (void) classe;
    if (api == NULL) api = OrtGetApiBase()->GetApi(VERSAO_DA_API);
    if (api == NULL) {
        (*jni)->ThrowNew(jni, (*jni)->FindClass(jni, "java/lang/IllegalStateException"), "ONNX Runtime incompatível.");
        return 0;
    }
    if (ambiente == NULL && falhou(jni, api->CreateEnv(ORT_LOGGING_LEVEL_WARNING, "criacao-musical", &ambiente))) return 0;
    OrtSessionOptions *opcoes = NULL;
    if (falhou(jni, api->CreateSessionOptions(&opcoes))) return 0;
    if (falhou(jni, api->SetIntraOpNumThreads(opcoes, threads)) ||
        falhou(jni, api->SetSessionGraphOptimizationLevel(opcoes, ORT_ENABLE_ALL)) ||
        (economizar && (falhou(jni, api->DisableCpuMemArena(opcoes)) || falhou(jni, api->DisableMemPattern(opcoes))))) {
        api->ReleaseSessionOptions(opcoes);
        return 0;
    }
    const char *arquivo = (*jni)->GetStringUTFChars(jni, caminho, NULL);
    OrtSession *sessao = NULL;
    ultimoComAcelerador = acelerar ? anexarNnapi(opcoes) : 0;
    OrtStatus *status = api->CreateSession(ambiente, arquivo, opcoes, &sessao);
    if (status != NULL && ultimoComAcelerador) {
        // O driver do aparelho recusou o modelo: abre de novo só na CPU, como antes.
        api->ReleaseStatus(status);
        api->ReleaseSessionOptions(opcoes);
        opcoes = NULL;
        ultimoComAcelerador = 0;
        if (falhou(jni, api->CreateSessionOptions(&opcoes))) { (*jni)->ReleaseStringUTFChars(jni, caminho, arquivo); return 0; }
        api->SetIntraOpNumThreads(opcoes, threads);
        api->SetSessionGraphOptimizationLevel(opcoes, ORT_ENABLE_ALL);
        if (economizar) { api->DisableCpuMemArena(opcoes); api->DisableMemPattern(opcoes); }
        status = api->CreateSession(ambiente, arquivo, opcoes, &sessao);
    }
    (*jni)->ReleaseStringUTFChars(jni, caminho, arquivo);
    api->ReleaseSessionOptions(opcoes);
    if (falhou(jni, status)) return 0;
    return (jlong) (intptr_t) sessao;
}

JNIEXPORT jfloatArray JNICALL
Java_br_com_smellsliketech_converter_musica_InferenciaOnnx_executar(JNIEnv *jni, jclass classe, jlong ponteiro, jfloatArray entrada, jlongArray forma, jstring nomeDaEntrada, jstring nomeDaSaida) {
    (void) classe;
    OrtSession *sessao = (OrtSession *) (intptr_t) ponteiro;
    OrtMemoryInfo *memoria = NULL;
    if (falhou(jni, api->CreateCpuMemoryInfo(OrtArenaAllocator, OrtMemTypeDefault, &memoria))) return NULL;
    jsize n = (*jni)->GetArrayLength(jni, entrada);
    jsize dimensoes = (*jni)->GetArrayLength(jni, forma);
    jfloat *dados = (*jni)->GetFloatArrayElements(jni, entrada, NULL);
    jlong *tamanhos = (*jni)->GetLongArrayElements(jni, forma, NULL);
    int64_t formato[8];
    for (jsize i = 0; i < dimensoes && i < 8; i++) formato[i] = tamanhos[i];
    OrtValue *tensor = NULL;
    OrtValue *resultado = NULL;
    jfloatArray saida = NULL;
    const char *entradaNome = (*jni)->GetStringUTFChars(jni, nomeDaEntrada, NULL);
    const char *saidaNome = (*jni)->GetStringUTFChars(jni, nomeDaSaida, NULL);
    if (!falhou(jni, api->CreateTensorWithDataAsOrtValue(memoria, dados, (size_t) n * sizeof(float), formato, (size_t) dimensoes, ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &tensor))
        && !falhou(jni, api->Run(sessao, NULL, &entradaNome, (const OrtValue *const *) &tensor, 1, &saidaNome, 1, &resultado))) {
        float *valores = NULL;
        OrtTensorTypeAndShapeInfo *info = NULL;
        size_t quantidade = 0;
        if (!falhou(jni, api->GetTensorMutableData(resultado, (void **) &valores))
            && !falhou(jni, api->GetTensorTypeAndShape(resultado, &info))) {
            api->GetTensorShapeElementCount(info, &quantidade);
            api->ReleaseTensorTypeAndShapeInfo(info);
            saida = (*jni)->NewFloatArray(jni, (jsize) quantidade);
            if (saida != NULL) (*jni)->SetFloatArrayRegion(jni, saida, 0, (jsize) quantidade, valores);
        }
    }
    if (resultado != NULL) api->ReleaseValue(resultado);
    if (tensor != NULL) api->ReleaseValue(tensor);
    (*jni)->ReleaseStringUTFChars(jni, nomeDaEntrada, entradaNome);
    (*jni)->ReleaseStringUTFChars(jni, nomeDaSaida, saidaNome);
    (*jni)->ReleaseLongArrayElements(jni, forma, tamanhos, JNI_ABORT);
    (*jni)->ReleaseFloatArrayElements(jni, entrada, dados, JNI_ABORT);
    api->ReleaseMemoryInfo(memoria);
    return saida;
}

/* Uma entrada principal e até 4 escalares de forma [1] (o Apollo recebe o corte e a mistura). */
JNIEXPORT jfloatArray JNICALL
Java_br_com_smellsliketech_converter_musica_InferenciaOnnx_executarComEscalares(JNIEnv *jni, jclass classe, jlong ponteiro, jfloatArray entrada, jlongArray forma,
                                                                               jstring nomeDaEntrada, jfloatArray escalares, jobjectArray nomesDosEscalares, jstring nomeDaSaida) {
    (void) classe;
    OrtSession *sessao = (OrtSession *) (intptr_t) ponteiro;
    jsize quantosEscalares = (*jni)->GetArrayLength(jni, escalares);
    if (quantosEscalares > 4 || quantosEscalares != (*jni)->GetArrayLength(jni, nomesDosEscalares)) return NULL;
    OrtMemoryInfo *memoria = NULL;
    if (falhou(jni, api->CreateCpuMemoryInfo(OrtArenaAllocator, OrtMemTypeDefault, &memoria))) return NULL;
    jsize n = (*jni)->GetArrayLength(jni, entrada);
    jsize dimensoes = (*jni)->GetArrayLength(jni, forma);
    jfloat *dados = (*jni)->GetFloatArrayElements(jni, entrada, NULL);
    jlong *tamanhos = (*jni)->GetLongArrayElements(jni, forma, NULL);
    jfloat valores[4] = {0};
    (*jni)->GetFloatArrayRegion(jni, escalares, 0, quantosEscalares, valores);
    int64_t formato[8];
    int64_t um = 1;
    for (jsize i = 0; i < dimensoes && i < 8; i++) formato[i] = tamanhos[i];
    OrtValue *tensores[5] = {NULL, NULL, NULL, NULL, NULL};
    const char *nomes[5] = {NULL, NULL, NULL, NULL, NULL};
    jstring textos[4] = {NULL, NULL, NULL, NULL};
    OrtValue *resultado = NULL;
    jfloatArray saida = NULL;
    nomes[0] = (*jni)->GetStringUTFChars(jni, nomeDaEntrada, NULL);
    for (jsize i = 0; i < quantosEscalares; i++) {
        textos[i] = (jstring) (*jni)->GetObjectArrayElement(jni, nomesDosEscalares, i);
        nomes[i + 1] = (*jni)->GetStringUTFChars(jni, textos[i], NULL);
    }
    const char *saidaNome = (*jni)->GetStringUTFChars(jni, nomeDaSaida, NULL);
    int ok = !falhou(jni, api->CreateTensorWithDataAsOrtValue(memoria, dados, (size_t) n * sizeof(float), formato, (size_t) dimensoes, ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &tensores[0]));
    for (jsize i = 0; ok && i < quantosEscalares; i++)
        ok = !falhou(jni, api->CreateTensorWithDataAsOrtValue(memoria, &valores[i], sizeof(float), &um, 1, ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &tensores[i + 1]));
    if (ok && !falhou(jni, api->Run(sessao, NULL, nomes, (const OrtValue *const *) tensores, (size_t) quantosEscalares + 1, &saidaNome, 1, &resultado))) {
        float *pronto = NULL;
        OrtTensorTypeAndShapeInfo *info = NULL;
        size_t quantidade = 0;
        if (!falhou(jni, api->GetTensorMutableData(resultado, (void **) &pronto))
            && !falhou(jni, api->GetTensorTypeAndShape(resultado, &info))) {
            api->GetTensorShapeElementCount(info, &quantidade);
            api->ReleaseTensorTypeAndShapeInfo(info);
            saida = (*jni)->NewFloatArray(jni, (jsize) quantidade);
            if (saida != NULL) (*jni)->SetFloatArrayRegion(jni, saida, 0, (jsize) quantidade, pronto);
        }
    }
    if (resultado != NULL) api->ReleaseValue(resultado);
    for (int i = 0; i < 5; i++) if (tensores[i] != NULL) api->ReleaseValue(tensores[i]);
    (*jni)->ReleaseStringUTFChars(jni, nomeDaEntrada, nomes[0]);
    for (jsize i = 0; i < quantosEscalares; i++) {
        (*jni)->ReleaseStringUTFChars(jni, textos[i], nomes[i + 1]);
        (*jni)->DeleteLocalRef(jni, textos[i]);
    }
    (*jni)->ReleaseStringUTFChars(jni, nomeDaSaida, saidaNome);
    (*jni)->ReleaseLongArrayElements(jni, forma, tamanhos, JNI_ABORT);
    (*jni)->ReleaseFloatArrayElements(jni, entrada, dados, JNI_ABORT);
    api->ReleaseMemoryInfo(memoria);
    return saida;
}

JNIEXPORT void JNICALL
Java_br_com_smellsliketech_converter_musica_InferenciaOnnx_fechar(JNIEnv *jni, jclass classe, jlong ponteiro) {
    (void) jni;
    (void) classe;
    if (ponteiro != 0 && api != NULL) api->ReleaseSession((OrtSession *) (intptr_t) ponteiro);
}

JNIEXPORT jboolean JNICALL
Java_br_com_smellsliketech_converter_musica_InferenciaOnnx_comAcelerador(JNIEnv *jni, jclass classe) {
    (void) jni;
    (void) classe;
    return ultimoComAcelerador ? JNI_TRUE : JNI_FALSE;
}
