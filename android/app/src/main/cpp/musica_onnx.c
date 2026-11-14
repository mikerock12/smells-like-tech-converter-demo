// Inferência do modelo de separação (Spleeter 4stems) pela API C do ONNX Runtime, para
// SeparacaoDaMusica no Android. Só três chamadas: abrir a sessão, rodar um trecho e fechar.
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include "onnxruntime_c_api.h"

// API 17 (ONNX Runtime 1.17): tudo que usamos existe nela e em qualquer versão posterior.
#define VERSAO_DA_API 17

static const OrtApi *api = NULL;
static OrtEnv *ambiente = NULL;

static int falhou(JNIEnv *jni, OrtStatus *status) {
    if (status == NULL) return 0;
    jclass classe = (*jni)->FindClass(jni, "java/lang/IllegalStateException");
    (*jni)->ThrowNew(jni, classe, api->GetErrorMessage(status));
    api->ReleaseStatus(status);
    return 1;
}

JNIEXPORT jlong JNICALL
Java_br_com_smellsliketech_converter_musica_InferenciaOnnx_abrir(JNIEnv *jni, jclass classe, jstring caminho, jint threads, jboolean economizar) {
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
    OrtStatus *status = api->CreateSession(ambiente, arquivo, opcoes, &sessao);
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

JNIEXPORT void JNICALL
Java_br_com_smellsliketech_converter_musica_InferenciaOnnx_fechar(JNIEnv *jni, jclass classe, jlong ponteiro) {
    (void) jni;
    (void) classe;
    if (ponteiro != 0 && api != NULL) api->ReleaseSession((OrtSession *) (intptr_t) ponteiro);
}
