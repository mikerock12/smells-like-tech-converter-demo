#include "json.h"

namespace slt {

namespace {

constexpr int PROFUNDIDADE_MAXIMA = 64;

struct Leitor {
    char* p;
    char* fim;
    No* nos;       // nulo na primeira passada (só conta)
    size_t usados;
    bool erro;

    void espacos() {
        while (p < fim && (*p == ' ' || *p == '\n' || *p == '\r' || *p == '\t')) p += 1;
    }

    No* novo() {
        No* no = nos != nullptr ? &nos[usados] : nullptr;
        usados += 1;
        return no;
    }

    bool literal(const char* palavra) {
        size_t n = comprimentoDoTexto(palavra);
        if (static_cast<size_t>(fim - p) < n || memcmp(p, palavra, n) != 0) return false;
        p += n;
        return true;
    }

    static int hexa(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }

    /** Lê um texto entre aspas e tira os escapes no lugar. `p` fica depois da aspa final. */
    bool lerTexto(const char** inicio, uint32_t* tamanho) {
        if (p >= fim || *p != '"') return false;
        p += 1;
        char* escrita = p;
        *inicio = p;
        while (p < fim && *p != '"') {
            if (*p != '\\') {
                if (nos != nullptr) *escrita = *p;
                escrita += 1;
                p += 1;
                continue;
            }
            p += 1;
            if (p >= fim) return false;
            char c = *p;
            p += 1;
            uint32_t ponto = 0;
            switch (c) {
                case 'n': ponto = '\n'; break;
                case 't': ponto = '\t'; break;
                case 'r': ponto = '\r'; break;
                case 'b': ponto = '\b'; break;
                case 'f': ponto = '\f'; break;
                case '"': case '\\': case '/': ponto = static_cast<unsigned char>(c); break;
                case 'u': {
                    if (fim - p < 4) return false;
                    for (int i = 0; i < 4; i += 1) {
                        const int h = hexa(p[i]);
                        if (h < 0) return false;
                        ponto = ponto * 16 + static_cast<uint32_t>(h);
                    }
                    p += 4;
                    break;
                }
                default: return false;
            }
            // UTF-8 do ponto de código (pares substitutos viram dois pontos de 3 bytes: basta para ids).
            char bytes[3];
            int n = 0;
            if (ponto < 0x80) bytes[n++] = static_cast<char>(ponto);
            else if (ponto < 0x800) {
                bytes[n++] = static_cast<char>(0xC0 | (ponto >> 6));
                bytes[n++] = static_cast<char>(0x80 | (ponto & 0x3F));
            } else {
                bytes[n++] = static_cast<char>(0xE0 | (ponto >> 12));
                bytes[n++] = static_cast<char>(0x80 | ((ponto >> 6) & 0x3F));
                bytes[n++] = static_cast<char>(0x80 | (ponto & 0x3F));
            }
            // O escape ocupa pelo menos tantos bytes quanto o que ele vira: nunca passa de `p`.
            for (int i = 0; i < n; i += 1) {
                if (nos != nullptr) escrita[i] = bytes[i];
            }
            escrita += n;
        }
        if (p >= fim) return false;
        *tamanho = static_cast<uint32_t>(escrita - *inicio);
        p += 1;
        return true;
    }

    bool lerNumero(double* valor) {
        const char* inicio = p;
        bool negativo = false;
        if (p < fim && *p == '-') {
            negativo = true;
            p += 1;
        }
        double mantissa = 0;
        int digitos = 0;
        int casas = 0;
        while (p < fim && *p >= '0' && *p <= '9') {
            mantissa = mantissa * 10 + (*p - '0');
            digitos += 1;
            p += 1;
        }
        if (p < fim && *p == '.') {
            p += 1;
            while (p < fim && *p >= '0' && *p <= '9') {
                mantissa = mantissa * 10 + (*p - '0');
                digitos += 1;
                casas += 1;
                p += 1;
            }
        }
        if (digitos == 0) {
            p = const_cast<char*>(inicio);
            return false;
        }
        int expoente = 0;
        if (p < fim && (*p == 'e' || *p == 'E')) {
            p += 1;
            bool expoenteNegativo = false;
            if (p < fim && (*p == '+' || *p == '-')) {
                expoenteNegativo = *p == '-';
                p += 1;
            }
            int lidos = 0;
            while (p < fim && *p >= '0' && *p <= '9') {
                if (expoente < 10000) expoente = expoente * 10 + (*p - '0');
                lidos += 1;
                p += 1;
            }
            if (lidos == 0) return false;
            if (expoenteNegativo) expoente = -expoente;
        }
        const int total = expoente - casas;
        // Divisão por potência exata de 10 (até 10^22) dá o double mais próximo nos casos comuns.
        double resultado = mantissa;
        if (total < 0 && total >= -22) resultado = mantissa / potencia(10.0, -total);
        else if (total != 0) resultado = mantissa * potencia(10.0, total);
        *valor = negativo ? -resultado : resultado;
        return true;
    }

    No* valor(int profundidade) {
        espacos();
        if (p >= fim || profundidade > PROFUNDIDADE_MAXIMA) {
            erro = true;
            return nullptr;
        }
        No* no = novo();
        No vazio;
        No& alvo = no != nullptr ? *no : vazio;
        if (no != nullptr) *no = No();
        if (*p == '{' || *p == '[') {
            const bool objeto = *p == '{';
            const char fecha = objeto ? '}' : ']';
            alvo.tipo = objeto ? TipoDeNo::Objeto : TipoDeNo::Lista;
            p += 1;
            espacos();
            No* ultimo = nullptr;
            if (p < fim && *p == fecha) {
                p += 1;
                return no;
            }
            for (;;) {
                const char* chave = nullptr;
                uint32_t tamanhoDaChave = 0;
                if (objeto) {
                    espacos();
                    if (!lerTexto(&chave, &tamanhoDaChave)) {
                        erro = true;
                        return nullptr;
                    }
                    espacos();
                    if (p >= fim || *p != ':') {
                        erro = true;
                        return nullptr;
                    }
                    p += 1;
                }
                No* filho = valor(profundidade + 1);
                if (erro) return nullptr;
                if (filho != nullptr) {
                    filho->chave = chave;
                    filho->tamanhoDaChave = tamanhoDaChave;
                    if (ultimo != nullptr) ultimo->proximo = filho;
                    else alvo.filho = filho;
                    ultimo = filho;
                }
                espacos();
                if (p < fim && *p == ',') {
                    p += 1;
                    continue;
                }
                if (p < fim && *p == fecha) {
                    p += 1;
                    return no;
                }
                erro = true;
                return nullptr;
            }
        }
        if (*p == '"') {
            alvo.tipo = TipoDeNo::Texto;
            if (!lerTexto(&alvo.texto, &alvo.tamanho)) erro = true;
            return no;
        }
        if (literal("true")) {
            alvo.tipo = TipoDeNo::Booleano;
            alvo.verdadeiro = true;
            return no;
        }
        if (literal("false")) {
            alvo.tipo = TipoDeNo::Booleano;
            return no;
        }
        if (literal("null")) return no;
        alvo.tipo = TipoDeNo::Numero;
        if (!lerNumero(&alvo.numero)) erro = true;
        return no;
    }
};

}  // namespace

const No* No::campo(const char* nome) const {
    if (tipo != TipoDeNo::Objeto) return nullptr;
    const size_t n = comprimentoDoTexto(nome);
    for (const No* f = filho; f != nullptr; f = f->proximo)
        if (textosIguais(f->chave, f->tamanhoDaChave, nome, n)) return f;
    return nullptr;
}

double No::numeroDe(const char* nome, double padrao) const {
    const No* no = campo(nome);
    return no != nullptr && no->tipo == TipoDeNo::Numero ? no->numero : padrao;
}

bool No::booleanoDe(const char* nome, bool padrao) const {
    const No* no = campo(nome);
    return no != nullptr && no->tipo == TipoDeNo::Booleano ? no->verdadeiro : padrao;
}

uint32_t No::quantos() const {
    uint32_t n = 0;
    for (const No* f = filho; f != nullptr; f = f->proximo) n += 1;
    return n;
}

bool No::textoIgual(const char* literal) const { return tipo == TipoDeNo::Texto && textosIguais(texto, tamanho, literal, comprimentoDoTexto(literal)); }

bool Documento::analisar(const char* utf8, size_t tamanho) {
    soltar();
    if (utf8 == nullptr || tamanho == 0 || tamanho > (static_cast<size_t>(1) << 28)) return false;
    if (!texto.criar(tamanho + 1)) return false;
    memcpy(texto.dados, utf8, tamanho);
    // Primeira passada: só conta os nós (sem escrever no texto). Segunda: monta a árvore.
    Leitor contagem = {texto.dados, texto.dados + tamanho, nullptr, 0, false};
    contagem.valor(0);
    contagem.espacos();
    if (contagem.erro || contagem.p != contagem.fim || !nos.criar(contagem.usados)) {
        soltar();
        return false;
    }
    Leitor leitor = {texto.dados, texto.dados + tamanho, nos.dados, 0, false};
    raiz = leitor.valor(0);
    if (leitor.erro || raiz == nullptr) {
        soltar();
        return false;
    }
    return true;
}

void Documento::soltar() {
    texto.soltar();
    nos.soltar();
    raiz = nullptr;
}

}  // namespace slt
