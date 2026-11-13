// Memória do módulo WebAssembly, sem libc: memcpy/memmove/memset e um alocador de lista
// livre (primeiro que couber, com junção de vizinhos) sobre a memória linear, que cresce
// com memory.grow. Compilado com -fno-builtin para os laços não virarem chamadas a si mesmos.
#include <stddef.h>
#include <stdint.h>

extern "C" {

void* memcpy(void* destino, const void* origem, size_t n) {
    unsigned char* d = static_cast<unsigned char*>(destino);
    const unsigned char* o = static_cast<const unsigned char*>(origem);
    for (size_t i = 0; i < n; i += 1) d[i] = o[i];
    return destino;
}

void* memmove(void* destino, const void* origem, size_t n) {
    unsigned char* d = static_cast<unsigned char*>(destino);
    const unsigned char* o = static_cast<const unsigned char*>(origem);
    if (d < o) {
        for (size_t i = 0; i < n; i += 1) d[i] = o[i];
    } else {
        for (size_t i = n; i > 0; i -= 1) d[i - 1] = o[i - 1];
    }
    return destino;
}

void* memset(void* destino, int valor, size_t n) {
    unsigned char* d = static_cast<unsigned char*>(destino);
    for (size_t i = 0; i < n; i += 1) d[i] = static_cast<unsigned char>(valor);
    return destino;
}

int memcmp(const void* a, const void* b, size_t n) {
    const unsigned char* x = static_cast<const unsigned char*>(a);
    const unsigned char* y = static_cast<const unsigned char*>(b);
    for (size_t i = 0; i < n; i += 1)
        if (x[i] != y[i]) return x[i] < y[i] ? -1 : 1;
    return 0;
}

extern unsigned char __heap_base;

}  // extern "C"

namespace {

constexpr size_t PAGINA = 65536;
constexpr size_t ALINHAMENTO = 16;

struct Bloco {
    size_t tamanho;  // bytes úteis depois do cabeçalho
    Bloco* proximo;  // só vale na lista livre
};

constexpr size_t CABECALHO = (sizeof(Bloco) + ALINHAMENTO - 1) & ~(ALINHAMENTO - 1);

Bloco* livres = nullptr;
size_t topo = 0;

size_t arredondar(size_t n) { return (n + ALINHAMENTO - 1) & ~(ALINHAMENTO - 1); }

/** Pede mais memória ao navegador. Devolve nulo quando ele recusa. */
Bloco* crescer(size_t bytes) {
    if (topo == 0) topo = arredondar(reinterpret_cast<size_t>(&__heap_base));
    const size_t fim = __builtin_wasm_memory_size(0) * PAGINA;
    const size_t preciso = topo + CABECALHO + bytes;
    if (preciso < topo) return nullptr;
    if (preciso > fim) {
        const size_t paginas = (preciso - fim + PAGINA - 1) / PAGINA;
        if (__builtin_wasm_memory_grow(0, paginas) == static_cast<size_t>(-1)) return nullptr;
    }
    Bloco* bloco = reinterpret_cast<Bloco*>(topo);
    bloco->tamanho = bytes;
    bloco->proximo = nullptr;
    topo += CABECALHO + bytes;
    return bloco;
}

}  // namespace

namespace slt {

void* alocar(size_t bytes) {
    const size_t pedido = arredondar(bytes == 0 ? 1 : bytes);
    if (pedido < bytes) return nullptr;
    Bloco** anterior = &livres;
    Bloco* achado = nullptr;
    for (Bloco* b = livres; b != nullptr; anterior = &b->proximo, b = b->proximo) {
        if (b->tamanho < pedido) continue;
        // Sobra grande vira outro bloco livre; sobra pequena vai junto.
        if (b->tamanho >= pedido + CABECALHO + 64) {
            Bloco* resto = reinterpret_cast<Bloco*>(reinterpret_cast<unsigned char*>(b) + CABECALHO + pedido);
            resto->tamanho = b->tamanho - pedido - CABECALHO;
            resto->proximo = b->proximo;
            b->tamanho = pedido;
            *anterior = resto;
        } else {
            *anterior = b->proximo;
        }
        achado = b;
        break;
    }
    if (achado == nullptr) achado = crescer(pedido);
    if (achado == nullptr) return nullptr;
    void* dados = reinterpret_cast<unsigned char*>(achado) + CABECALHO;
    memset(dados, 0, achado->tamanho);
    return dados;
}

void liberar(void* ponteiro) {
    if (ponteiro == nullptr) return;
    Bloco* bloco = reinterpret_cast<Bloco*>(static_cast<unsigned char*>(ponteiro) - CABECALHO);
    // Lista em ordem de endereço, para juntar com os vizinhos.
    Bloco* antes = nullptr;
    Bloco* depois = livres;
    while (depois != nullptr && depois < bloco) {
        antes = depois;
        depois = depois->proximo;
    }
    bloco->proximo = depois;
    if (antes == nullptr) livres = bloco;
    else antes->proximo = bloco;
    if (depois != nullptr && reinterpret_cast<unsigned char*>(bloco) + CABECALHO + bloco->tamanho == reinterpret_cast<unsigned char*>(depois)) {
        bloco->tamanho += CABECALHO + depois->tamanho;
        bloco->proximo = depois->proximo;
    }
    if (antes != nullptr && reinterpret_cast<unsigned char*>(antes) + CABECALHO + antes->tamanho == reinterpret_cast<unsigned char*>(bloco)) {
        antes->tamanho += CABECALHO + bloco->tamanho;
        antes->proximo = bloco->proximo;
    }
}

}  // namespace slt
