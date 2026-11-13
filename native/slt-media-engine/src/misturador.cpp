#include "misturador.h"

#if defined(SLT_COM_VST3)
bool sltVst3FoiListado(const char* caminho);
#endif

namespace slt {

namespace {

constexpr double DURACAO_DO_CLIQUE = 0.03;
constexpr double RAIZ_DE_DOIS = 1.4142135623730951;

inline double arredondar(double x) { return piso(x + 0.5); }

/** dB → ganho linear; o mínimo do fader (−60 dB ou menos) é silêncio (musica.mjs). */
inline double dbDoFader(double db) { return db <= -60 ? 0 : potencia(10.0, db / 20); }

void ganhosEstereo(double ganhoDb, double pan, double ajusteEsquerdoDb, double ajusteDireitoDb, double* esquerdo, double* direito) {
    const double ganho = dbDoFader(ganhoDb);
    const double angulo = ((limitar(pan, -100, 100) + 100) / 200) * (PI / 2);
    double e = RAIZ_DE_DOIS * cosseno(angulo);
    double d = RAIZ_DE_DOIS * seno(angulo);
    if (e > 1) e = 1;
    if (d > 1) d = 1;
    *esquerdo = ganho * e * (ajusteEsquerdoDb == 0 ? 1 : potencia(10.0, ajusteEsquerdoDb / 20));
    *direito = ganho * d * (ajusteDireitoDb == 0 ? 1 : potencia(10.0, ajusteDireitoDb / 20));
}

double valorDaAutomacao(const Vetor<PontoDeAutomacao>& pontos, double quadro, double padrao) {
    if (pontos.n == 0) return padrao;
    if (quadro <= pontos[0].quadro) return pontos[0].valor;
    const PontoDeAutomacao& ultimo = pontos[pontos.n - 1];
    if (quadro >= ultimo.quadro) return ultimo.valor;
    for (size_t i = 1; i < pontos.n; i += 1) {
        const PontoDeAutomacao& b = pontos[i];
        if (quadro <= b.quadro) {
            const PontoDeAutomacao& a = pontos[i - 1];
            if (b.quadro == a.quadro) return b.valor;
            return a.valor + ((b.valor - a.valor) * (quadro - a.quadro)) / (b.quadro - a.quadro);
        }
    }
    return ultimo.valor;
}

inline double envelopeDoClipe(const ClipeDoPlano& clipe, double deslocamento) {
    double fator = clipe.ganhoDb == 0 ? 1 : dbDoFader(clipe.ganhoDb);
    if (clipe.entradaSuave > 0 && deslocamento < clipe.entradaSuave) fator *= deslocamento / clipe.entradaSuave;
    const double restante = clipe.duracao - deslocamento;
    if (clipe.saidaSuave > 0 && restante < clipe.saidaSuave) fator *= restante / clipe.saidaSuave;
    return fator;
}

/** Copia `n` quadros de um canal da fonte a partir de `inicio`, com zeros depois do fim. */
void copiarDaFonte(const FonteDeAudio& fonte, int canal, int64_t inicio, uint32_t n, float* destino) {
    const float* origem = canal == 1 && fonte.dir != nullptr ? fonte.dir : fonte.esq;
    uint32_t i = 0;
    if (inicio >= 0 && static_cast<uint64_t>(inicio) < fonte.quadros) {
        const uint64_t disponivel = fonte.quadros - static_cast<uint64_t>(inicio);
        const uint32_t copiar = disponivel < n ? static_cast<uint32_t>(disponivel) : n;
        memcpy(destino, origem + inicio, static_cast<size_t>(copiar) * sizeof(float));
        i = copiar;
    }
    for (; i < n; i += 1) destino[i] = 0;
}

void medir(float* m, const float* esq, const float* dir, uint32_t n) {
    for (uint32_t i = 0; i < n; i += 1) {
        const float a = esq[i] < 0 ? -esq[i] : esq[i];
        const float b = dir[i] < 0 ? -dir[i] : dir[i];
        if (a > m[0]) m[0] = a;
        if (b > m[1]) m[1] = b;
        m[2] = static_cast<float>(m[2] + static_cast<double>(esq[i]) * esq[i]);
        m[3] = static_cast<float>(m[3] + static_cast<double>(dir[i]) * dir[i]);
    }
}

bool copiarTexto(Vetor<char>& destino, const char* a, size_t tamanhoDeA, const char* b, size_t tamanhoDeB) {
    if (!destino.criar(tamanhoDeA + tamanhoDeB + 2)) return false;
    memcpy(destino.dados, a, tamanhoDeA);
    destino[tamanhoDeA] = '\n';
    if (tamanhoDeB > 0) memcpy(destino.dados + tamanhoDeA + 1, b, tamanhoDeB);
    destino.n = tamanhoDeA + 1 + tamanhoDeB;  // o zero final fica fora da conta
    return true;
}

bool lerPontos(const No* lista, Vetor<PontoDeAutomacao>& destino) {
    const uint32_t n = lista != nullptr && lista->tipo == TipoDeNo::Lista ? lista->quantos() : 0;
    if (!destino.criar(n)) return false;
    uint32_t i = 0;
    for (const No* p = n > 0 ? lista->filho : nullptr; p != nullptr; p = p->proximo, i += 1) {
        destino[i].quadro = p->numeroDe("quadro", 0);
        destino[i].valor = p->numeroDe("valor", 0);
    }
    return true;
}

Vetor<float> somDoClique(uint32_t taxa, bool forte) {
    Vetor<float> som;
    const uint32_t n = static_cast<uint32_t>(arredondar(taxa * DURACAO_DO_CLIQUE));
    if (!som.criar(n)) return som;
    const double f = forte ? 2000 : 1500;
    for (uint32_t i = 0; i < n; i += 1) som[i] = static_cast<float>(seno((2 * PI * f * i) / taxa) * exponencial(-static_cast<double>(i) / (taxa * 0.006)) * (forte ? 1 : 0.7));
    return som;
}

}  // namespace

bool Misturador::criar(uint32_t taxa_) {
    if (taxa_ < 8000 || taxa_ > 192000) return false;
    taxa = taxa_;
    bool ok = te.criar(BLOCO_DO_MIXER) && td.criar(BLOCO_DO_MIXER) && le.criar(BLOCO_DO_MIXER) && ld.criar(BLOCO_DO_MIXER) && me.criar(BLOCO_DO_MIXER) && md.criar(BLOCO_DO_MIXER);
    cliqueForte = somDoClique(taxa, true);
    cliqueFraco = somDoClique(taxa, false);
    return ok && cliqueForte.n > 0 && cliqueFraco.n > 0;
}

void Misturador::soltar() {
    Plano* esperando = reinterpret_cast<Plano*>(pendente.trocar(0));
    Plano* velho = reinterpret_cast<Plano*>(descartado.trocar(0));
    if (esperando != nullptr) liberarPlano(esperando);
    if (velho != nullptr) liberarPlano(velho);
    if (atual != nullptr && atual != esperando) liberarPlano(atual);
    atual = nullptr;
    publicado = nullptr;
    while (fontes != nullptr) {
        FonteRegistrada* proxima = fontes->proximo;
        fontes->chave.soltar();
        liberar(fontes);
        fontes = proxima;
    }
    // Sobrou registro só se um plano vazou; libera para não deixar memória para trás.
    while (registros != nullptr) {
        Registro* proximo = registros->proximo;
        liberarRegistro(registros);
        registros = proximo;
    }
    projeto.soltar();
    contagem.soltar();
    te.soltar();
    td.soltar();
    le.soltar();
    ld.soltar();
    me.soltar();
    md.soltar();
    cliqueForte.soltar();
    cliqueFraco.soltar();
}

/* ------------------------------------------------------------------ controle */

slt_result Misturador::definirFonte(const char* chave, const float* esq, const float* dir, uint64_t quadros) {
    if (chave == nullptr) return SLT_ERROR_INVALID_ARGUMENT;
    const size_t n = comprimentoDoTexto(chave);
    if (n == 0 || n > 4096) return SLT_ERROR_INVALID_ARGUMENT;
    FonteRegistrada** anterior = &fontes;
    for (FonteRegistrada* f = fontes; f != nullptr; anterior = &f->proximo, f = f->proximo) {
        if (!textosIguais(f->chave.dados, f->chave.n, chave, n)) continue;
        if (esq == nullptr) {
            *anterior = f->proximo;
            f->chave.soltar();
            liberar(f);
        } else {
            f->fonte = {esq, dir, quadros};
        }
        return SLT_OK;
    }
    if (esq == nullptr) return SLT_OK;
    FonteRegistrada* nova = criarObjeto<FonteRegistrada>();
    if (nova == nullptr || !nova->chave.criar(n)) {
        if (nova != nullptr) liberar(nova);
        return SLT_ERROR_OUT_OF_MEMORY;
    }
    memcpy(nova->chave.dados, chave, n);
    nova->fonte = {esq, dir, quadros};
    nova->proximo = fontes;
    fontes = nova;
    return SLT_OK;
}

slt_result Misturador::definirContagem(const Clique* cliques, uint32_t quantos, int64_t fim) {
    if (quantos > 0 && cliques == nullptr) return SLT_ERROR_INVALID_ARGUMENT;
    if (!contagem.criar(quantos)) return SLT_ERROR_OUT_OF_MEMORY;
    for (uint32_t i = 0; i < quantos; i += 1) contagem[i] = cliques[i];
    fimDaContagem = fim;
    return SLT_OK;
}

slt_result Misturador::definirProjeto(const char* json, size_t tamanho) {
    Documento novo;
    if (!novo.analisar(json, tamanho)) return SLT_ERROR_INVALID_ARGUMENT;
    slt_result erro = SLT_OK;
    Plano* plano = compilar(*novo.raiz, &erro);
    if (plano == nullptr) {
        novo.soltar();
        return erro;
    }
    projeto.soltar();
    projeto = novo;  // os vetores passam de dono: `novo` não é mais solto
    publicar(plano);
    return SLT_OK;
}

slt_result Misturador::definirProjeto(const No& raiz) {
    slt_result erro = SLT_OK;
    Plano* plano = compilar(raiz, &erro);
    if (plano == nullptr) return erro;
    publicar(plano);
    return SLT_OK;
}

slt_result Misturador::atualizar() {
    if (projeto.raiz == nullptr) return SLT_ERROR_INVALID_STATE;
    return definirProjeto(*projeto.raiz);
}

void Misturador::coletar() {
    Plano* velho = reinterpret_cast<Plano*>(descartado.trocar(0));
    if (velho != nullptr) liberarPlano(velho);
}

void Misturador::publicar(Plano* plano) {
    coletar();
    Plano* naoUsado = reinterpret_cast<Plano*>(pendente.trocar(reinterpret_cast<int64_t>(plano)));
    if (naoUsado != nullptr) liberarPlano(naoUsado);
    publicado = plano;
}

void Misturador::liberarPlano(Plano* plano) {
    auto soltarCadeia = [this](Vetor<EfeitoDoPlano>& efeitos) {
        for (size_t i = 0; i < efeitos.n; i += 1) {
#if defined(SLT_COM_VST3)
            efeitos[i].parametrosVst3.soltar();
#endif
            Registro* registro = efeitos[i].registro;
            if (registro == nullptr) continue;
            registro->referencias -= 1;
            if (registro->referencias > 0) continue;
            Registro** anterior = &registros;
            for (Registro* r = registros; r != nullptr; anterior = &r->proximo, r = r->proximo) {
                if (r != registro) continue;
                *anterior = r->proximo;
                break;
            }
            liberarRegistro(registro);
        }
        efeitos.soltar();
    };
    for (size_t f = 0; f < plano->faixas.n; f += 1) {
        FaixaDoPlano& faixa = plano->faixas[f];
        faixa.clipes.soltar();
        faixa.envios.soltar();
        faixa.volume.soltar();
        faixa.panorama.soltar();
        soltarCadeia(faixa.efeitos);
    }
    for (size_t b = 0; b < plano->barramentos.n; b += 1) {
        BarramentoDoPlano& barramento = plano->barramentos[b];
        soltarCadeia(barramento.efeitos);
        barramento.esq.soltar();
        barramento.dir.soltar();
    }
    soltarCadeia(plano->master);
    plano->faixas.soltar();
    plano->barramentos.soltar();
    plano->contagem.soltar();
    plano->textos.soltar();
    liberar(plano);
}

void Misturador::liberarRegistro(Registro* registro) {
#if defined(SLT_COM_VST3)
    slt_plugin_close(registro->plugin);
    registro->caminhoDoPlugin.soltar();
#endif
    if (registro->processador != nullptr) {
        registro->processador->soltar();
        liberar(registro->processador);
    }
    registro->chave.soltar();
    liberar(registro);
}

#if defined(SLT_COM_VST3)
static bool lerParametrosVst3(const No& efeito, EfeitoDoPlano& item) {
    const No* parametros = efeito.campo("parametros");
    if (parametros == nullptr) return true;
    const uint32_t n = parametros->quantos();
    if (parametros->tipo != TipoDeNo::Lista || n > 4096 || !item.parametrosVst3.criar(n)) return false;
    size_t i = 0;
    for (const No* p = parametros->filho; p != nullptr; p = p->proximo) {
        const double id = p->numeroDe("id", -1);
        const double valor = p->numeroDe("valor", -1);
        if (!(id >= 0 && id <= UINT32_MAX && id == piso(id) && valor >= 0 && valor <= 1)) return false;
        item.parametrosVst3[i++] = {static_cast<uint32_t>(id), valor};
    }
    return true;
}
#endif

bool Misturador::compilarCadeia(const No* efeitos, const char* dono, uint32_t tamanhoDoDono, Vetor<EfeitoDoPlano>& destino, slt_result* erro) {
    const uint32_t n = efeitos != nullptr && efeitos->tipo == TipoDeNo::Lista ? efeitos->quantos() : 0;
    if (!destino.criar(n)) {
        *erro = SLT_ERROR_OUT_OF_MEMORY;
        return false;
    }
    uint32_t i = 0;
    for (const No* e = n > 0 ? efeitos->filho : nullptr; e != nullptr; e = e->proximo, i += 1) {
        EfeitoDoPlano& item = destino[i];
        item.registro = nullptr;
        const No* tipo = e->campo("tipo");
        const bool vst3 = tipo != nullptr && tipo->textoIgual("vst3");
        if (vst3) {
            item.parametros.tipo = TipoDeEfeito::Desconhecido;
            item.parametros.ligado = false;
        } else if (!item.parametros.ler(*e)) {
            *erro = SLT_ERROR_INVALID_ARGUMENT;
            return false;
        }
#if defined(SLT_COM_VST3)
        const No* caminho = vst3 ? e->campo("caminho") : nullptr;
        const No* classe = vst3 ? e->campo("classe") : nullptr;
        if (vst3) {
            if (caminho == nullptr || caminho->tipo != TipoDeNo::Texto || caminho->tamanho == 0 ||
                caminho->tamanho >= 1024 || memchr(caminho->texto, 0, caminho->tamanho) != nullptr ||
                classe == nullptr || classe->tipo != TipoDeNo::Texto || classe->tamanho != 32 ||
                memchr(classe->texto, 0, classe->tamanho) != nullptr || !lerParametrosVst3(*e, item)) {
                *erro = SLT_ERROR_INVALID_ARGUMENT;
                return false;
            }
            item.parametros.ligado = e->booleanoDe("ligado", true);
        }
#else
        if (vst3) continue;
#endif
        const No* id = e->campo("id");
        const char* textoDoId = id != nullptr && id->tipo == TipoDeNo::Texto ? id->texto : "";
        const uint32_t tamanhoDoId = id != nullptr && id->tipo == TipoDeNo::Texto ? id->tamanho : 0;
        Vetor<char> chave;
        if (!copiarTexto(chave, dono, tamanhoDoDono, textoDoId, tamanhoDoId)) {
            *erro = SLT_ERROR_OUT_OF_MEMORY;
            return false;
        }
        Registro* achado = nullptr;
        for (Registro* r = registros; r != nullptr; r = r->proximo) {
            if (r->tipo == item.parametros.tipo && textosIguais(r->chave.dados, r->chave.n, chave.dados, chave.n)) {
#if defined(SLT_COM_VST3)
                if (vst3 && (r->caminhoDoPlugin.n != caminho->tamanho + 1 ||
                    memcmp(r->caminhoDoPlugin.dados, caminho->texto, caminho->tamanho) != 0 ||
                    memcmp(r->classeDoPlugin, classe->texto, 32) != 0)) continue;
#endif
                achado = r;
                break;
            }
        }
        if (achado != nullptr) {
            chave.soltar();
        } else {
            achado = criarObjeto<Registro>();
            if (achado == nullptr) {
                chave.soltar();
                *erro = SLT_ERROR_OUT_OF_MEMORY;
                return false;
            }
            achado->chave = chave;
            achado->tipo = item.parametros.tipo;
#if defined(SLT_COM_VST3)
            if (vst3) {
                if (!achado->caminhoDoPlugin.criar(caminho->tamanho + 1)) {
                    liberarRegistro(achado);
                    *erro = SLT_ERROR_OUT_OF_MEMORY;
                    return false;
                }
                memcpy(achado->caminhoDoPlugin.dados, caminho->texto, caminho->tamanho);
                memcpy(achado->classeDoPlugin, classe->texto, 32);
                if (!sltVst3FoiListado(achado->caminhoDoPlugin.dados) ||
                    slt_plugin_open(achado->caminhoDoPlugin.dados, achado->classeDoPlugin, taxa,
                        BLOCO_DO_MIXER, &achado->plugin) != SLT_OK) {
                    liberarRegistro(achado);
                    item.parametros.ligado = false;
                    continue;
                }
            } else
#endif
            {
                achado->processador = criarObjeto<Processador>();
                if (achado->processador == nullptr || !achado->processador->criar(item.parametros, taxa)) {
                    liberarRegistro(achado);
                    *erro = SLT_ERROR_OUT_OF_MEMORY;
                    return false;
                }
            }
            achado->proximo = registros;
            registros = achado;
        }
        achado->referencias += 1;
        item.registro = achado;
    }
    return true;
}

Plano* Misturador::compilar(const No& raiz, slt_result* erro) {
    *erro = SLT_ERROR_INVALID_ARGUMENT;
    if (raiz.tipo != TipoDeNo::Objeto) return nullptr;
    const No* faixas = raiz.campo("faixas");
    const No* barramentos = raiz.campo("barramentos");
    const No* master = raiz.campo("master");
    if (faixas == nullptr || faixas->tipo != TipoDeNo::Lista) return nullptr;
    const uint32_t quantasFaixas = faixas->quantos();
    const uint32_t quantosBarramentos = barramentos != nullptr && barramentos->tipo == TipoDeNo::Lista ? barramentos->quantos() : 0;
    if (quantasFaixas > 4096 || quantosBarramentos > 256) return nullptr;

    *erro = SLT_ERROR_OUT_OF_MEMORY;
    Plano* plano = criarObjeto<Plano>();
    if (plano == nullptr) return nullptr;
    // Os ids vão para um texto do próprio plano (o documento pode ser trocado antes).
    size_t bytesDeTexto = 1;
    for (const No* f = faixas->filho; f != nullptr; f = f->proximo) {
        const No* id = f->campo("id");
        bytesDeTexto += id != nullptr && id->tipo == TipoDeNo::Texto ? id->tamanho : 0;
    }
    for (const No* b = quantosBarramentos > 0 ? barramentos->filho : nullptr; b != nullptr; b = b->proximo) {
        const No* id = b->campo("id");
        bytesDeTexto += id != nullptr && id->tipo == TipoDeNo::Texto ? id->tamanho : 0;
    }
    bool ok = plano->textos.criar(bytesDeTexto) && plano->faixas.criar(quantasFaixas) && plano->barramentos.criar(quantosBarramentos) && plano->contagem.criar(contagem.n);
    size_t usado = 0;
    auto guardarId = [&](const No& dono, const char** texto, uint32_t* tamanho) {
        const No* id = dono.campo("id");
        const uint32_t n = id != nullptr && id->tipo == TipoDeNo::Texto ? id->tamanho : 0;
        if (n > 0) memcpy(plano->textos.dados + usado, id->texto, n);
        *texto = plano->textos.dados + usado;
        *tamanho = n;
        usado += n;
    };

    if (ok) {
        uint32_t b = 0;
        for (const No* no = quantosBarramentos > 0 ? barramentos->filho : nullptr; no != nullptr && ok; no = no->proximo, b += 1) {
            BarramentoDoPlano& barramento = plano->barramentos[b];
            guardarId(*no, &barramento.id, &barramento.tamanhoDoId);
            ganhosEstereo(no->numeroDe("ganhoDb", 0), no->numeroDe("pan", 0), 0, 0, &barramento.ganhoEsquerdo, &barramento.ganhoDireito);
            barramento.mudo = no->booleanoDe("mudo", false);
            ok = barramento.esq.criar(BLOCO_DO_MIXER) && barramento.dir.criar(BLOCO_DO_MIXER) && compilarCadeia(no->campo("efeitos"), barramento.id, barramento.tamanhoDoId, barramento.efeitos, erro);
        }
    }
    if (ok) {
        bool algumSolo = false;
        for (const No* no = faixas->filho; no != nullptr; no = no->proximo) algumSolo = algumSolo || no->booleanoDe("solo", false);
        uint32_t f = 0;
        for (const No* no = faixas->filho; no != nullptr && ok; no = no->proximo, f += 1) {
            FaixaDoPlano& faixa = plano->faixas[f];
            guardarId(*no, &faixa.id, &faixa.tamanhoDoId);
            faixa.ganhoDb = no->numeroDe("ganhoDb", 0);
            faixa.pan = no->numeroDe("pan", 0);
            faixa.ajusteEsquerdoDb = no->numeroDe("ajusteEsquerdoDb", 0);
            faixa.ajusteDireitoDb = no->numeroDe("ajusteDireitoDb", 0);
            faixa.audivel = !no->booleanoDe("mudo", false) && (!algumSolo || no->booleanoDe("solo", false));
            faixa.gravar = no->booleanoDe("gravar", false);
            faixa.monitorar = no->booleanoDe("monitorar", false);
            faixa.saida = -1;
            const No* saida = no->campo("saida");
            if (saida != nullptr && saida->tipo == TipoDeNo::Texto && !saida->textoIgual("master")) {
                for (uint32_t b = 0; b < quantosBarramentos; b += 1)
                    if (textosIguais(plano->barramentos[b].id, plano->barramentos[b].tamanhoDoId, saida->texto, saida->tamanho)) faixa.saida = static_cast<int32_t>(b);
            }
            const No* automacao = no->campo("automacao");
            ok = lerPontos(automacao != nullptr ? automacao->campo("volume") : nullptr, faixa.volume) && lerPontos(automacao != nullptr ? automacao->campo("pan") : nullptr, faixa.panorama);
            const No* envios = no->campo("envios");
            const uint32_t quantosEnvios = envios != nullptr && envios->tipo == TipoDeNo::Lista ? envios->quantos() : 0;
            ok = ok && faixa.envios.criar(quantosEnvios);
            uint32_t e = 0;
            for (const No* envio = ok && quantosEnvios > 0 ? envios->filho : nullptr; envio != nullptr; envio = envio->proximo, e += 1) {
                faixa.envios[e].barramento = -1;
                const No* destino = envio->campo("barramento");
                for (uint32_t b = 0; destino != nullptr && destino->tipo == TipoDeNo::Texto && b < quantosBarramentos; b += 1)
                    if (textosIguais(plano->barramentos[b].id, plano->barramentos[b].tamanhoDoId, destino->texto, destino->tamanho)) faixa.envios[e].barramento = static_cast<int32_t>(b);
                faixa.envios[e].ganho = dbDoFader(envio->numeroDe("nivelDb", 0));
                faixa.envios[e].preFader = envio->booleanoDe("preFader", false);
            }
            const No* clipes = no->campo("clipes");
            const uint32_t quantosClipes = clipes != nullptr && clipes->tipo == TipoDeNo::Lista ? clipes->quantos() : 0;
            ok = ok && faixa.clipes.criar(quantosClipes);
            uint32_t c = 0;
            for (const No* clipe = ok && quantosClipes > 0 ? clipes->filho : nullptr; clipe != nullptr; clipe = clipe->proximo) {
                if (clipe->booleanoDe("mudo", false)) continue;
                // `_fonte` é a chave da fonte já transformada (tempo/tom), posta pelo cliente
                // quando ela está pronta; sem ela, vale a mídia original.
                const No* transformada = clipe->campo("_fonte");
                const No* midia = clipe->campo("midia");
                const FonteRegistrada* achada = nullptr;
                bool usaTransformada = false;
                for (int tentativa = 0; tentativa < 2 && achada == nullptr; tentativa += 1) {
                    const No* chave = tentativa == 0 ? transformada : midia;
                    if (chave == nullptr || chave->tipo != TipoDeNo::Texto) continue;
                    for (const FonteRegistrada* r = fontes; r != nullptr; r = r->proximo) {
                        if (!textosIguais(r->chave.dados, r->chave.n, chave->texto, chave->tamanho)) continue;
                        achada = r;
                        usaTransformada = tentativa == 0;
                        break;
                    }
                }
                if (achada == nullptr) continue;
                ClipeDoPlano& item = faixa.clipes[c];
                item.inicio = static_cast<int64_t>(clipe->numeroDe("inicio", 0));
                item.inicioNaMidia = static_cast<int64_t>(clipe->numeroDe("inicioNaMidia", 0));
                item.duracao = static_cast<int64_t>(clipe->numeroDe("duracao", 0));
                item.ganhoDb = clipe->numeroDe("ganhoDb", 0);
                item.entradaSuave = clipe->numeroDe("entradaSuave", 0);
                item.saidaSuave = clipe->numeroDe("saidaSuave", 0);
                item.velocidade = clipe->numeroDe("velocidade", 1);
                if (!(item.velocidade > 0)) item.velocidade = 1;
                item.transformada = usaTransformada;
                item.fonte = achada->fonte;
                c += 1;
            }
            faixa.clipes.n = c;  // os mudos e os sem fonte ficam fora
            ok = ok && compilarCadeia(no->campo("efeitos"), faixa.id, faixa.tamanhoDoId, faixa.efeitos, erro);
        }
    }
    if (ok) {
        ok = compilarCadeia(master != nullptr ? master->campo("efeitos") : nullptr, "master", 6, plano->master, erro);
        plano->ganhoDoMaster = dbDoFader(master != nullptr ? master->numeroDe("ganhoDb", 0) : 0);
        const No* metronomoDoProjeto = raiz.campo("metronomo");
        plano->volumeDoMetronomo = dbDoFader(metronomoDoProjeto != nullptr ? metronomoDoProjeto->numeroDe("volumeDb", -6) : -6);
        const No* compasso = raiz.campo("compasso");
        double batidas = 4;
        double denominador = 4;
        if (compasso != nullptr && compasso->tipo == TipoDeNo::Lista && compasso->filho != nullptr && compasso->filho->proximo != nullptr) {
            batidas = compasso->filho->numero;
            denominador = compasso->filho->proximo->numero;
        }
        const double bpm = raiz.numeroDe("bpm", 120);
        const double taxaDoProjeto = raiz.numeroDe("taxa", taxa);
        plano->quadrosPorBatida = (taxaDoProjeto * 60) / (bpm > 0 ? bpm : 120) * (4 / (denominador > 0 ? denominador : 4));
        plano->batidasPorCompasso = batidas >= 1 ? static_cast<uint32_t>(batidas) : 4;
        for (size_t i = 0; i < contagem.n; i += 1) plano->contagem[i] = contagem[i];
        plano->fimDaContagem = fimDaContagem;
    }
    if (!ok) {
        liberarPlano(plano);
        return nullptr;
    }
    *erro = SLT_OK;
    return plano;
}

int32_t Misturador::indiceDaFaixa(const char* id) const {
    if (publicado == nullptr || id == nullptr) return -1;
    const size_t n = comprimentoDoTexto(id);
    for (size_t f = 0; f < publicado->faixas.n; f += 1)
        if (textosIguais(publicado->faixas[f].id, publicado->faixas[f].tamanhoDoId, id, n)) return static_cast<int32_t>(f);
    return -1;
}

void MedidorDoMixer::publicar(uint32_t quadros) {
    const double divisor = quadros > 0 ? quadros : 1;
    acumulado[2] = static_cast<float>(raiz(acumulado[2] / divisor));
    acumulado[3] = static_cast<float>(raiz(acumulado[3] / divisor));
    int64_t bitsDosPicos;
    int64_t bitsDoRms;
    memcpy(&bitsDosPicos, acumulado, sizeof(bitsDosPicos));
    memcpy(&bitsDoRms, acumulado + 2, sizeof(bitsDoRms));

    // A revisão ímpar indica que os dois pares ainda estão sendo trocados.
    const int64_t anterior = revisao.ler();
    revisao.trocar(anterior + 1);
    picos.guardar(bitsDosPicos);
    rms.guardar(bitsDoRms);
    revisao.guardar(anterior == INT64_MAX - 1 ? 0 : anterior + 2);
}

void MedidorDoMixer::ler(float* quatro) const {
    for (;;) {
        const int64_t anterior = revisao.ler();
        if ((anterior & 1) != 0) continue;
        const int64_t bitsDosPicos = picos.ler();
        const int64_t bitsDoRms = rms.ler();
        if (anterior != revisao.lerAposLeituras()) continue;
        memcpy(quatro, &bitsDosPicos, sizeof(bitsDosPicos));
        memcpy(quatro + 2, &bitsDoRms, sizeof(bitsDoRms));
        return;
    }
}

bool Misturador::medidores(const char* id, float* quatro) const {
    if (publicado == nullptr || id == nullptr || quatro == nullptr) return false;
    const size_t n = comprimentoDoTexto(id);
    const MedidorDoMixer* m = nullptr;
    if (textosIguais(id, n, "master", 6)) m = &publicado->medidorDoMaster;
    for (size_t f = 0; m == nullptr && f < publicado->faixas.n; f += 1)
        if (textosIguais(publicado->faixas[f].id, publicado->faixas[f].tamanhoDoId, id, n)) m = &publicado->faixas[f].medidor;
    for (size_t b = 0; m == nullptr && b < publicado->barramentos.n; b += 1)
        if (textosIguais(publicado->barramentos[b].id, publicado->barramentos[b].tamanhoDoId, id, n)) m = &publicado->barramentos[b].medidor;
    if (m == nullptr) return false;
    m->ler(quatro);
    return true;
}

/* ------------------------------------------------------------------ áudio */

void Misturador::pegarPlano() {
    // Só troca se o controle já recolheu o plano anterior: a thread de áudio nunca libera.
    if (descartado.ler() != 0 || pendente.ler() == 0) return;
    Plano* novo = reinterpret_cast<Plano*>(pendente.trocar(0));
    if (novo == nullptr) return;
    auto aplicar = [](Vetor<EfeitoDoPlano>& efeitos) {
        for (size_t i = 0; i < efeitos.n; i += 1) {
            auto& efeito = efeitos[i];
            if (efeito.registro == nullptr) continue;
#if defined(SLT_COM_VST3)
            if (efeito.registro->plugin != nullptr) {
                for (size_t j = 0; j < efeito.parametrosVst3.n; ++j) {
                    const auto& parametro = efeito.parametrosVst3[j];
                    slt_plugin_set_parameter(efeito.registro->plugin, parametro.id, parametro.valor);
                }
                continue;
            }
#endif
            efeito.registro->processador->atualizar(efeito.parametros);
        }
    };
    for (size_t f = 0; f < novo->faixas.n; f += 1) aplicar(novo->faixas[f].efeitos);
    for (size_t b = 0; b < novo->barramentos.n; b += 1) aplicar(novo->barramentos[b].efeitos);
    aplicar(novo->master);
    Plano* velho = atual;
    atual = novo;
    if (velho != nullptr) descartado.guardar(reinterpret_cast<int64_t>(velho));
}

void Misturador::misturar(int64_t inicio, uint32_t n, float* saidaE, float* saidaD, const OpcoesDeMixagem& opcoes) {
    pegarPlano();
    if (atual == nullptr) {
        memset(saidaE, 0, static_cast<size_t>(n) * sizeof(float));
        memset(saidaD, 0, static_cast<size_t>(n) * sizeof(float));
        return;
    }
    Plano& plano = *atual;
    for (size_t f = 0; f < plano.faixas.n; f += 1) memset(plano.faixas[f].medidor.acumulado, 0, sizeof(float) * 4);
    for (size_t b = 0; b < plano.barramentos.n; b += 1) memset(plano.barramentos[b].medidor.acumulado, 0, sizeof(float) * 4);
    memset(plano.medidorDoMaster.acumulado, 0, sizeof(float) * 4);
    uint32_t feito = 0;
    while (feito < n) {
        const uint32_t k = n - feito < BLOCO_DO_MIXER ? n - feito : BLOCO_DO_MIXER;
        bloco(inicio + feito, k, opcoes, feito);
        memcpy(saidaE + feito, me.dados, static_cast<size_t>(k) * sizeof(float));
        memcpy(saidaD + feito, md.dados, static_cast<size_t>(k) * sizeof(float));
        feito += k;
    }
    for (size_t f = 0; f < plano.faixas.n; f += 1) plano.faixas[f].medidor.publicar(n);
    for (size_t b = 0; b < plano.barramentos.n; b += 1) plano.barramentos[b].medidor.publicar(n);
    plano.medidorDoMaster.publicar(n);
}

void Misturador::tempoReal(const float* entradaE, const float* entradaD, float* saidaE, float* saidaD, uint32_t n) {
    if (tocando.ler() == 0) {
        pegarPlano();
        memset(saidaE, 0, static_cast<size_t>(n) * sizeof(float));
        memset(saidaD, 0, static_cast<size_t>(n) * sizeof(float));
        return;
    }
    OpcoesDeMixagem opcoes;
    opcoes.metronomo = metronomoLigado.ler() != 0;
    uint32_t feito = 0;
    int64_t agora = posicao.ler();
    while (feito < n) {
        uint32_t k = n - feito;
        const int64_t inicioDaVolta = inicioDoLoop.ler();
        const int64_t fimDaVolta = fimDoLoop.ler();
        const bool emLoop = fimDaVolta > inicioDaVolta && agora < fimDaVolta;
        if (emLoop && agora + k > fimDaVolta) k = static_cast<uint32_t>(fimDaVolta - agora);
        opcoes.entradaEsquerda = entradaE != nullptr ? entradaE + feito : nullptr;
        opcoes.entradaDireita = entradaD != nullptr ? entradaD + feito : opcoes.entradaEsquerda;
        misturar(agora, k, saidaE + feito, saidaD + feito, opcoes);
        feito += k;
        agora += k;
        if (emLoop && agora >= fimDaVolta) agora = inicioDaVolta;
    }
    posicao.guardar(agora);
}

static void processarCadeia(const Vetor<EfeitoDoPlano>& efeitos, float* esq, float* dir, uint32_t n) {
    for (size_t i = 0; i < efeitos.n; i += 1) {
        const auto& efeito = efeitos[i];
        if (!efeito.parametros.ligado || efeito.registro == nullptr) continue;
#if defined(SLT_COM_VST3)
        if (efeito.registro->plugin != nullptr) {
            slt_plugin_process(efeito.registro->plugin, esq, dir, n);
            continue;
        }
#endif
        efeito.registro->processador->processar(esq, dir, n);
    }
}

void Misturador::bloco(int64_t t0, uint32_t n, const OpcoesDeMixagem& opcoes, uint32_t deslocamentoDaEntrada) {
    Plano& plano = *atual;
    float* te_ = te.dados;
    float* td_ = td.dados;
    float* le_ = le.dados;
    float* ld_ = ld.dados;
    float* me_ = me.dados;
    float* md_ = md.dados;
    const size_t bytes = static_cast<size_t>(n) * sizeof(float);
    memset(me_, 0, bytes);
    memset(md_, 0, bytes);
    for (size_t b = 0; b < plano.barramentos.n; b += 1) {
        memset(plano.barramentos[b].esq.dados, 0, bytes);
        memset(plano.barramentos[b].dir.dados, 0, bytes);
    }
    for (size_t f = 0; f < plano.faixas.n; f += 1) {
        FaixaDoPlano& faixa = plano.faixas[f];
        const bool audivel = opcoes.somente >= 0 ? static_cast<int32_t>(f) == opcoes.somente : faixa.audivel;
        if (!audivel) continue;
        memset(te_, 0, bytes);
        memset(td_, 0, bytes);
        for (size_t c = 0; c < faixa.clipes.n; c += 1) {
            const ClipeDoPlano& clipe = faixa.clipes[c];
            const int64_t a = t0 > clipe.inicio ? t0 : clipe.inicio;
            const int64_t fimDoBloco = t0 + n;
            const int64_t fimDoClipe = clipe.inicio + clipe.duracao;
            const int64_t b = fimDoBloco < fimDoClipe ? fimDoBloco : fimDoClipe;
            if (a >= b) continue;
            const int64_t deslocamento = a - clipe.inicio;
            const uint32_t k = static_cast<uint32_t>(b - a);
            // A fonte transformada já está na duração da linha do tempo; a original é o recurso.
            const int64_t origem = clipe.transformada ? static_cast<int64_t>(piso(clipe.inicioNaMidia / clipe.velocidade)) + deslocamento : clipe.inicioNaMidia + deslocamento;
            copiarDaFonte(clipe.fonte, 0, origem, k, le_);
            if (clipe.fonte.dir != nullptr) copiarDaFonte(clipe.fonte, 1, origem, k, ld_);
            else memcpy(ld_, le_, static_cast<size_t>(k) * sizeof(float));
            const uint32_t destino = static_cast<uint32_t>(a - t0);
            for (uint32_t i = 0; i < k; i += 1) {
                const double fator = envelopeDoClipe(clipe, static_cast<double>(deslocamento + i));
                te_[destino + i] = static_cast<float>(te_[destino + i] + le_[i] * fator);
                td_[destino + i] = static_cast<float>(td_[destino + i] + ld_[i] * fator);
            }
        }
        if (opcoes.entradaEsquerda != nullptr && faixa.gravar && faixa.monitorar) {
            const float* entradaD = opcoes.entradaDireita != nullptr ? opcoes.entradaDireita : opcoes.entradaEsquerda;
            for (uint32_t i = 0; i < n; i += 1) {
                te_[i] += opcoes.entradaEsquerda[deslocamentoDaEntrada + i];
                td_[i] += entradaD[deslocamentoDaEntrada + i];
            }
        }
        processarCadeia(faixa.efeitos, te_, td_, n);
        for (int passada = 0; passada < 2; passada += 1) {
            const bool pre = passada == 0;
            for (size_t e = 0; e < faixa.envios.n; e += 1) {
                const EnvioDoPlano& envio = faixa.envios[e];
                if (envio.preFader != pre || envio.barramento < 0) continue;
                float* be = plano.barramentos[envio.barramento].esq.dados;
                float* bd = plano.barramentos[envio.barramento].dir.dados;
                for (uint32_t i = 0; i < n; i += 1) {
                    be[i] = static_cast<float>(be[i] + te_[i] * envio.ganho);
                    bd[i] = static_cast<float>(bd[i] + td_[i] * envio.ganho);
                }
            }
            if (!pre) break;
            double e0, d0, e1, d1;
            ganhosEstereo(valorDaAutomacao(faixa.volume, static_cast<double>(t0), faixa.ganhoDb), valorDaAutomacao(faixa.panorama, static_cast<double>(t0), faixa.pan), faixa.ajusteEsquerdoDb, faixa.ajusteDireitoDb, &e0, &d0);
            ganhosEstereo(valorDaAutomacao(faixa.volume, static_cast<double>(t0 + n), faixa.ganhoDb), valorDaAutomacao(faixa.panorama, static_cast<double>(t0 + n), faixa.pan), faixa.ajusteEsquerdoDb, faixa.ajusteDireitoDb, &e1, &d1);
            for (uint32_t i = 0; i < n; i += 1) {
                const double w = static_cast<double>(i) / n;
                te_[i] = static_cast<float>(te_[i] * (e0 + (e1 - e0) * w));
                td_[i] = static_cast<float>(td_[i] * (d0 + (d1 - d0) * w));
            }
        }
        float* de = faixa.saida >= 0 ? plano.barramentos[faixa.saida].esq.dados : me_;
        float* dd = faixa.saida >= 0 ? plano.barramentos[faixa.saida].dir.dados : md_;
        for (uint32_t i = 0; i < n; i += 1) {
            de[i] += te_[i];
            dd[i] += td_[i];
        }
        medir(faixa.medidor.acumulado, te_, td_, n);
    }
    for (size_t b = 0; b < plano.barramentos.n; b += 1) {
        BarramentoDoPlano& barramento = plano.barramentos[b];
        if (barramento.mudo) continue;
        float* e = barramento.esq.dados;
        float* d = barramento.dir.dados;
        processarCadeia(barramento.efeitos, e, d, n);
        for (uint32_t i = 0; i < n; i += 1) {
            e[i] = static_cast<float>(e[i] * barramento.ganhoEsquerdo);
            d[i] = static_cast<float>(d[i] * barramento.ganhoDireito);
            me_[i] += e[i];
            md_[i] += d[i];
        }
        medir(barramento.medidor.acumulado, e, d, n);
    }
    if (!opcoes.semMaster)
        processarCadeia(plano.master, me_, md_, n);
    for (uint32_t i = 0; i < n; i += 1) {
        me_[i] = static_cast<float>(me_[i] * plano.ganhoDoMaster);
        md_[i] = static_cast<float>(md_[i] * plano.ganhoDoMaster);
    }
    medir(plano.medidorDoMaster.acumulado, me_, md_, n);
    if (opcoes.metronomo || plano.fimDaContagem > t0) metronomo(t0, n, opcoes.metronomo);
}

void Misturador::metronomo(int64_t t0, uint32_t n, bool grade) {
    Plano& plano = *atual;
    const double volume = plano.volumeDoMetronomo;
    const int64_t comprimento = static_cast<int64_t>(cliqueForte.n);
    auto somar = [&](const Vetor<float>& som, int64_t quadroDoClique) {
        const int64_t a = t0 > quadroDoClique ? t0 : quadroDoClique;
        const int64_t fim = quadroDoClique + static_cast<int64_t>(som.n);
        const int64_t b = t0 + n < fim ? t0 + n : fim;
        for (int64_t t = a; t < b; t += 1) {
            const double v = som[static_cast<size_t>(t - quadroDoClique)] * volume;
            me[static_cast<size_t>(t - t0)] = static_cast<float>(me[static_cast<size_t>(t - t0)] + v);
            md[static_cast<size_t>(t - t0)] = static_cast<float>(md[static_cast<size_t>(t - t0)] + v);
        }
    };
    for (size_t i = 0; i < plano.contagem.n; i += 1) {
        const Clique& clique = plano.contagem[i];
        if (clique.quadro + comprimento > t0 && clique.quadro < t0 + n) somar(clique.forte != 0 ? cliqueForte : cliqueFraco, clique.quadro);
    }
    if (!grade) return;
    const double porBatida = plano.quadrosPorBatida;
    double k = piso(static_cast<double>(t0 - comprimento) / porBatida);
    if (k < 0) k = 0;
    for (;;) {
        const int64_t quadro = static_cast<int64_t>(arredondar(k * porBatida));
        if (quadro >= t0 + n) break;
        if (quadro >= plano.fimDaContagem && quadro + comprimento > t0) somar(static_cast<uint64_t>(k) % plano.batidasPorCompasso == 0 ? cliqueForte : cliqueFraco, quadro);
        k += 1;
    }
}

}  // namespace slt
