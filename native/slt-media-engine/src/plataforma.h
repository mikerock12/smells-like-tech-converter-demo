// O pouco que o núcleo precisa do sistema: matemática, memória e um valor atômico.
// No Windows/Android/Linux vem da libc; no WebAssembly a matemática é importada do
// JavaScript (os mesmos Math.sin/Math.cos do núcleo em JS) e a memória é a de wasm/memoria.cpp.
// O resto do núcleo só inclui este arquivo, e por isso compila sem biblioteca padrão.
#pragma once

#include <stddef.h>
#include <stdint.h>

#if defined(__wasm__)

extern "C" {
double slt_env_sin(double) __attribute__((import_module("env"), import_name("sin")));
double slt_env_cos(double) __attribute__((import_module("env"), import_name("cos")));
double slt_env_exp(double) __attribute__((import_module("env"), import_name("exp")));
double slt_env_log10(double) __attribute__((import_module("env"), import_name("log10")));
double slt_env_pow(double, double) __attribute__((import_module("env"), import_name("pow")));
double slt_env_tanh(double) __attribute__((import_module("env"), import_name("tanh")));
void* memcpy(void* destino, const void* origem, size_t n);
void* memmove(void* destino, const void* origem, size_t n);
void* memset(void* destino, int valor, size_t n);
int memcmp(const void* a, const void* b, size_t n);
}

namespace slt {
inline double seno(double x) { return slt_env_sin(x); }
inline double cosseno(double x) { return slt_env_cos(x); }
inline double exponencial(double x) { return slt_env_exp(x); }
inline double logaritmo10(double x) { return slt_env_log10(x); }
inline double potencia(double x, double y) { return slt_env_pow(x, y); }
inline double tangenteH(double x) { return slt_env_tanh(x); }
inline double raiz(double x) { return __builtin_sqrt(x); }
inline double absoluto(double x) { return __builtin_fabs(x); }
inline double piso(double x) { return __builtin_floor(x); }
void* alocar(size_t bytes);
void liberar(void* ponteiro);
}

inline void* operator new(size_t, void* onde) noexcept { return onde; }

#else

#include <new>

#include <math.h>
#include <stdlib.h>
#include <string.h>

namespace slt {
inline double seno(double x) { return sin(x); }
inline double cosseno(double x) { return cos(x); }
inline double exponencial(double x) { return exp(x); }
inline double logaritmo10(double x) { return log10(x); }
inline double potencia(double x, double y) { return pow(x, y); }
inline double tangenteH(double x) { return tanh(x); }
inline double raiz(double x) { return sqrt(x); }
inline double absoluto(double x) { return fabs(x); }
inline double piso(double x) { return floor(x); }
inline void* alocar(size_t bytes) { return calloc(bytes == 0 ? 1 : bytes, 1); }
inline void liberar(void* ponteiro) { free(ponteiro); }
}

#endif

#if defined(_MSC_VER)
#include <intrin.h>
#endif

namespace slt {

constexpr double PI = 3.14159265358979323846;

inline double hipotenusa(double a, double b) { return raiz(a * a + b * b); }
inline double dbParaLinear(double db) { return potencia(10.0, db / 20.0); }
inline double limitar(double v, double minimo, double maximo) { return v < minimo ? minimo : v > maximo ? maximo : v; }

inline size_t comprimentoDoTexto(const char* texto) {
    size_t n = 0;
    while (texto[n] != 0) n += 1;
    return n;
}

inline bool textosIguais(const char* a, size_t tamanhoDeA, const char* b, size_t tamanhoDeB) { return tamanhoDeA == tamanhoDeB && (tamanhoDeA == 0 || memcmp(a, b, tamanhoDeA) == 0); }

/** Vetor zerado, de tamanho fixo, com dono explícito: quem chama `criar` chama `soltar`. */
template <typename T>
struct Vetor {
    T* dados = nullptr;
    size_t n = 0;

    bool criar(size_t quantos) {
        soltar();
        if (quantos == 0) return true;
        if (quantos > SIZE_MAX / sizeof(T)) return false;
        dados = static_cast<T*>(alocar(quantos * sizeof(T)));
        if (dados == nullptr) return false;
        n = quantos;
        return true;
    }

    void soltar() {
        if (dados != nullptr) liberar(dados);
        dados = nullptr;
        n = 0;
    }

    void zerar() {
        if (dados != nullptr) memset(dados, 0, n * sizeof(T));
    }

    T& operator[](size_t i) { return dados[i]; }
    const T& operator[](size_t i) const { return dados[i]; }
};

/** Objeto novo em memória zerada (sem exceção: nulo quando falta memória). */
template <typename T>
T* criarObjeto() {
    void* memoria = alocar(sizeof(T));
    return memoria != nullptr ? new (memoria) T() : nullptr;
}

/** Um valor de 64 bits trocado entre a thread de controle e a de áudio, sem trava. */
struct Atomico64 {
    volatile int64_t valor = 0;

    void guardar(int64_t novo) {
#if defined(_MSC_VER)
        _InterlockedExchange64(&valor, novo);
#else
        __atomic_store_n(&valor, novo, __ATOMIC_RELEASE);
#endif
    }

    /** Troca o valor e devolve o que estava lá (uma operação só). */
    int64_t trocar(int64_t novo) {
#if defined(_MSC_VER)
        return _InterlockedExchange64(&valor, novo);
#else
        return __atomic_exchange_n(&valor, novo, __ATOMIC_ACQ_REL);
#endif
    }

    int64_t ler() const {
#if defined(_MSC_VER)
        return _InterlockedOr64(const_cast<volatile int64_t*>(&valor), 0);
#else
        return __atomic_load_n(&valor, __ATOMIC_ACQUIRE);
#endif
    }

    /** Lê a revisão depois de completar as leituras de um conjunto de valores atômicos. */
    int64_t lerAposLeituras() const {
#if !defined(_MSC_VER)
        __atomic_thread_fence(__ATOMIC_ACQUIRE);
#endif
        return ler();
    }
};

}  // namespace slt
