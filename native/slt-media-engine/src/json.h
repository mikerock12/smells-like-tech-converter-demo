// Leitor de JSON mínimo, sem biblioteca padrão: o projeto musical chega ao mixer nativo no
// mesmo JSON que os três clientes já gravam (.sltmusic). Só leitura; os nós apontam para uma
// cópia do texto, e tudo é liberado de uma vez em `soltar`.
#pragma once

#include "plataforma.h"

namespace slt {

enum class TipoDeNo : uint8_t { Nulo, Booleano, Numero, Texto, Lista, Objeto };

struct No {
    TipoDeNo tipo = TipoDeNo::Nulo;
    bool verdadeiro = false;
    double numero = 0;
    const char* texto = nullptr;
    uint32_t tamanho = 0;
    const char* chave = nullptr;
    uint32_t tamanhoDaChave = 0;
    No* filho = nullptr;
    No* proximo = nullptr;

    const No* campo(const char* nome) const;
    double numeroDe(const char* nome, double padrao) const;
    bool booleanoDe(const char* nome, bool padrao) const;
    /** Quantos filhos (itens da lista ou campos do objeto). */
    uint32_t quantos() const;
    bool textoIgual(const char* literal) const;
};

struct Documento {
    Vetor<char> texto;
    Vetor<No> nos;
    No* raiz = nullptr;

    /** Falso quando o texto não é JSON válido ou falta memória. */
    bool analisar(const char* utf8, size_t tamanho);
    void soltar();
};

}  // namespace slt
