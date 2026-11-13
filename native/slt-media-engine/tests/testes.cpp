// Testes do núcleo: paridade da separação com a referência em JS (casos_gerados.h),
// invariantes da máscara refinada, da restauração, da consistência e do motor.
// Sem framework: cada CONFERIR que falha é listado e o processo sai com código 1.
#include <math.h>
#include <stdio.h>
#include <string.h>
#include <vector>

#include "../src/nucleo.h"
#include "casos_gerados.h"

static int falhas = 0;
static int conferencias = 0;

#define CONFERIR(condicao, ...)                      \
    do {                                             \
        conferencias += 1;                           \
        if (!(condicao)) {                           \
            falhas += 1;                             \
            printf("FALHOU %s:%d: ", __FILE__, __LINE__); \
            printf(__VA_ARGS__);                     \
            printf("\n");                            \
        }                                            \
    } while (0)

static bool perto(double a, double b, double absoluta, double relativa = 0) { return fabs(a - b) <= absoluta + relativa * fabs(b); }

using Canal = std::vector<float>;

/* ------------------------------------------------------------------ geradores (os de musica-casos.mjs) */

static Canal ruido(uint32_t semente, uint32_t quadros, double amplitude) {
    Canal x(quadros);
    uint32_t estado = semente != 0 ? semente : 1;
    for (uint32_t i = 0; i < quadros; i += 1) {
        estado ^= estado << 13;
        estado ^= estado >> 17;
        estado ^= estado << 5;
        x[i] = static_cast<float>(amplitude * ((estado / 4294967296.0) * 2 - 1));
    }
    return x;
}

static Canal seno(double frequencia, uint32_t quadros, double amplitude, double taxa) {
    Canal x(quadros);
    for (uint32_t i = 0; i < quadros; i += 1) x[i] = static_cast<float>(amplitude * sin((2 * slt::PI * frequencia * i) / taxa));
    return x;
}

static double energiaDe(const Canal& x) {
    double e = 0;
    for (float v : x) e += static_cast<double>(v) * v;
    return e;
}

static double db(double razao) { return razao > 0 ? 10 * log10(razao) : -300; }

/* ------------------------------------------------------------------ separação pela ABI */

typedef void (*ModeloFalso)(const float* entrada, uint32_t T, uint32_t bandas, uint32_t instrumentos, float* saida);

/** O modelo falso dos casos compartilhados (estimativasFalsas em musica-casos.mjs). */
static void estimativasFalsas(const float* entrada, uint32_t T, uint32_t bandas, uint32_t instrumentos, float* saida) {
    const size_t plano = static_cast<size_t>(2) * T * bandas;
    for (uint32_t i = 0; i < instrumentos; i += 1)
        for (uint32_t c = 0; c < 2; c += 1)
            for (uint32_t f = 0; f < T; f += 1)
                for (uint32_t k = 0; k < bandas; k += 1) {
                    const size_t j = (static_cast<size_t>(c) * T + f) * bandas + k;
                    const double peso = 1 + ((i + 1) * ((k * 7 + f * 3 + c) % 11)) / 10.0;
                    saida[i * plano + j] = static_cast<float>(entrada[j] * peso);
                }
}

/** Instrumento 0 é dono dos graves e o 1 dos agudos (da metade das bandas para cima). */
static void estimativasPorRegiao(const float* entrada, uint32_t T, uint32_t bandas, uint32_t instrumentos, float* saida) {
    const size_t plano = static_cast<size_t>(2) * T * bandas;
    memset(saida, 0, instrumentos * plano * sizeof(float));
    for (uint32_t c = 0; c < 2; c += 1)
        for (uint32_t f = 0; f < T; f += 1)
            for (uint32_t k = 0; k < bandas; k += 1) {
                const size_t j = (static_cast<size_t>(c) * T + f) * bandas + k;
                saida[(k >= bandas / 2 ? 1 : 0) * plano + j] = entrada[j];
            }
}

struct Separada {
    std::vector<Canal> canais;  // [saida * 2 + canal]
    uint32_t trechos = 0;
};

static Separada separar(const Canal& esquerdo, const Canal& direito, const std::vector<std::vector<uint32_t>>& faixas, uint32_t perfil, ModeloFalso modelo) {
    Separada r;
    const uint32_t comprimento = static_cast<uint32_t>(esquerdo.size());
    slt_separator_config config = {};
    config.struct_size = sizeof(config);
    config.length = comprimento;
    config.window = 4096;
    config.hop = 1024;
    config.frames_per_chunk = 512;
    config.bands = 1024;
    config.instruments = 4;
    config.outputs = static_cast<uint32_t>(faixas.size());
    config.mask_profile = perfil;
    slt_separator_handle* s = slt_separator_create(&config);
    CONFERIR(s != nullptr, "separador não foi criado");
    if (s == nullptr) return r;
    r.trechos = slt_separator_chunk_count(s);
    r.canais.assign(faixas.size() * 2, Canal(comprimento, 0.0f));
    const uint32_t planoDeEntrada = 2 * 512 * 1024;
    std::vector<float> magnitude(planoDeEntrada);
    std::vector<float> estimativas(static_cast<size_t>(4) * planoDeEntrada);
    const uint32_t capacidade = 512 * 1024 + 4096;
    Canal saidaE(capacidade);
    Canal saidaD(capacidade);
    Canal janelaE;
    Canal janelaD;
    for (uint32_t t = 0; t < r.trechos; t += 1) {
        int64_t inicio = 0;
        uint32_t n = 0;
        uint32_t quadros = 0;
        CONFERIR(slt_separator_chunk_window(s, t, &inicio, &n, &quadros) == SLT_OK, "janela do trecho %u", t);
        janelaE.assign(n, 0.0f);
        janelaD.assign(n, 0.0f);
        for (uint32_t i = 0; i < n; i += 1) {
            const int64_t p = inicio + i;
            if (p < 0 || p >= comprimento) continue;
            janelaE[i] = esquerdo[static_cast<size_t>(p)];
            janelaD[i] = direito[static_cast<size_t>(p)];
        }
        CONFERIR(slt_separator_analyze(s, t, janelaE.data(), janelaD.data(), n, magnitude.data(), planoDeEntrada) == SLT_OK, "análise do trecho %u", t);
        modelo(magnitude.data(), 512, 1024, 4, estimativas.data());
        for (uint32_t f = 0; f < faixas.size(); f += 1) {
            int64_t de = 0;
            uint32_t prontas = 0;
            const slt_result ok = slt_separator_synthesize(s, t, f, estimativas.data(), static_cast<uint32_t>(estimativas.size()), faixas[f].data(), static_cast<uint32_t>(faixas[f].size()), saidaE.data(), saidaD.data(), capacidade, &de, &prontas);
            CONFERIR(ok == SLT_OK, "síntese do trecho %u, faixa %u: %s", t, f, slt_result_message(ok));
            CONFERIR(de >= 0 && static_cast<uint64_t>(de) + prontas <= comprimento, "amostras fora do sinal");
            if (ok != SLT_OK || prontas == 0) continue;
            memcpy(r.canais[f * 2].data() + de, saidaE.data(), prontas * sizeof(float));
            memcpy(r.canais[f * 2 + 1].data() + de, saidaD.data(), prontas * sizeof(float));
        }
    }
    slt_separator_destroy(s);
    return r;
}

static void testarParidadeComOJs() {
    for (const CasoEsperado& caso : CASOS) {
        Canal esquerdo = caso.tipo == 1 ? ruido(caso.semente, caso.comprimento, caso.amplitude) : seno(caso.frequencia, caso.comprimento, caso.amplitude, 44100);
        Canal direito = caso.canais == 2 ? (caso.tipo == 1 ? ruido(caso.semente + 1, caso.comprimento, caso.amplitude) : esquerdo) : esquerdo;
        std::vector<std::vector<uint32_t>> faixas;
        for (uint32_t f = 0; f < caso.quantasFaixas; f += 1) faixas.emplace_back(caso.faixas[f].indices, caso.faixas[f].indices + caso.faixas[f].quantosIndices);
        const Separada r = separar(esquerdo, direito, faixas, caso.refinada ? SLT_MASK_REFINED : SLT_MASK_LEGACY, estimativasFalsas);
        if (r.canais.empty()) continue;
        for (uint32_t f = 0; f < caso.quantasFaixas; f += 1) {
            const Canal& e = r.canais[f * 2];
            const Canal& d = r.canais[f * 2 + 1];
            double soma = 0;
            double energia = 0;
            double pico = 0;
            for (uint32_t i = 0; i < caso.comprimento; i += 1) {
                soma += static_cast<double>(e[i]) + d[i];
                energia += static_cast<double>(e[i]) * e[i] + static_cast<double>(d[i]) * d[i];
                pico = fmax(pico, fmax(fabs(e[i]), fabs(d[i])));
            }
            const FaixaEsperada& esperado = caso.faixas[f];
            CONFERIR(perto(soma, esperado.soma, 2e-3, 1e-4), "%s faixa %u: soma %.6f, esperada %.6f", caso.nome, f, soma, esperado.soma);
            CONFERIR(perto(energia, esperado.energia, 2e-3, 1e-4), "%s faixa %u: energia %.6f, esperada %.6f", caso.nome, f, energia, esperado.energia);
            CONFERIR(perto(pico, esperado.pico, 2e-5), "%s faixa %u: pico %.6f, esperado %.6f", caso.nome, f, pico, esperado.pico);
            for (const AmostraEsperada& a : esperado.amostras) {
                if (a.indice >= caso.comprimento) continue;
                CONFERIR(perto(e[a.indice], a.esquerdo, 2e-5) && perto(d[a.indice], a.direito, 2e-5), "%s faixa %u amostra %u: %.6f/%.6f, esperadas %.6f/%.6f", caso.nome, f, a.indice, e[a.indice], d[a.indice], a.esquerdo, a.direito);
            }
        }
    }
}

/** A soma das faixas tem de ser a mistura, nos dois perfis de máscara. */
static void testarSomaDasFaixas() {
    const uint32_t n = 70000;
    const Canal e = ruido(3, n, 0.5);
    const Canal d = ruido(4, n, 0.5);
    const std::vector<std::vector<uint32_t>> faixas = {{0}, {1}, {2}, {3}};
    for (uint32_t perfil : {static_cast<uint32_t>(SLT_MASK_LEGACY), static_cast<uint32_t>(SLT_MASK_REFINED)}) {
        const Separada r = separar(e, d, faixas, perfil, estimativasFalsas);
        if (r.canais.empty()) continue;
        double erro = 0;
        for (uint32_t i = 0; i < n; i += 1) {
            double somaE = 0;
            double somaD = 0;
            for (uint32_t f = 0; f < 4; f += 1) {
                somaE += r.canais[f * 2][i];
                somaD += r.canais[f * 2 + 1][i];
            }
            erro += (somaE - e[i]) * (somaE - e[i]) + (somaD - d[i]) * (somaD - d[i]);
        }
        const double relacao = db((energiaDe(e) + energiaDe(d)) / (erro + 1e-30));
        CONFERIR(relacao > 90, "perfil %u: a soma das faixas fica a %.1f dB da mistura (mínimo 90)", perfil, relacao);
    }
}

/** Energia de uma frequência num sinal (Goertzel simples). */
static double energiaEm(const Canal& x, double frequencia, double taxa) {
    double re = 0;
    double im = 0;
    for (size_t i = 0; i < x.size(); i += 1) {
        const double fase = 2 * slt::PI * frequencia * i / taxa;
        re += x[i] * cos(fase);
        im += x[i] * sin(fase);
    }
    return (re * re + im * im) / (static_cast<double>(x.size()) * x.size());
}

/**
 * O que a máscara refinada existe para fazer: um som de 15 kHz (acima do que o modelo vê)
 * acompanha quem domina a oitava de 5,5 a 11 kHz, em vez de ser rateado pela média.
 */
static void testarAgudosNaFaixaCerta() {
    const uint32_t n = 60000;
    Canal x(n);
    for (uint32_t i = 0; i < n; i += 1) x[i] = static_cast<float>(0.4 * sin(2 * slt::PI * 200.0 * i / 44100) + 0.1 * sin(2 * slt::PI * 8000.0 * i / 44100) + 0.1 * sin(2 * slt::PI * 15000.0 * i / 44100));
    const std::vector<std::vector<uint32_t>> faixas = {{0}, {1}};
    const Separada classica = separar(x, x, faixas, SLT_MASK_LEGACY, estimativasPorRegiao);
    const Separada refinada = separar(x, x, faixas, SLT_MASK_REFINED, estimativasPorRegiao);
    if (classica.canais.empty() || refinada.canais.empty()) return;
    const double total = energiaEm(x, 15000, 44100);
    const double naClassica = energiaEm(classica.canais[2], 15000, 44100) / total;
    const double naRefinada = energiaEm(refinada.canais[2], 15000, 44100) / total;
    CONFERIR(naRefinada > 0.9, "refinada: só %.1f%% dos 15 kHz foram para o dono dos agudos", naRefinada * 100);
    CONFERIR(naClassica < 0.5, "clássica: %.1f%% dos 15 kHz (esperado o rateio pela média)", naClassica * 100);
    // Abaixo do limite do modelo os dois perfis são iguais.
    const double graves = energiaEm(classica.canais[0], 200, 44100);
    CONFERIR(perto(energiaEm(refinada.canais[0], 200, 44100), graves, 0, 1e-3), "os graves mudaram com a máscara refinada");
}

static void testarArgumentosDoSeparador() {
    slt_separator_config config = {};
    CONFERIR(slt_separator_create(nullptr) == nullptr, "config nula aceita");
    CONFERIR(slt_separator_create(&config) == nullptr, "struct_size zero aceito");
    config.struct_size = sizeof(config);
    config.length = 1000;
    config.window = 1000;  // não é potência de 2
    config.hop = 250;
    config.frames_per_chunk = 8;
    config.bands = 100;
    config.instruments = 4;
    config.outputs = 2;
    CONFERIR(slt_separator_create(&config) == nullptr, "janela que não é potência de 2 aceita");
    config.window = 1024;
    config.hop = 256;
    slt_separator_handle* s = slt_separator_create(&config);
    CONFERIR(s != nullptr, "separador pequeno não foi criado");
    if (s == nullptr) return;
    std::vector<float> a(4096);
    std::vector<float> b(4096);
    int64_t de = 0;
    uint32_t n = 0;
    const uint32_t indice = 0;
    CONFERIR(slt_separator_synthesize(s, 0, 0, a.data(), 4096, &indice, 1, a.data(), b.data(), 4096, &de, &n) == SLT_ERROR_INVALID_STATE, "síntese sem análise aceita");
    CONFERIR(slt_separator_analyze(s, 99, a.data(), a.data(), 4096, b.data(), 4096) == SLT_ERROR_INVALID_ARGUMENT, "trecho inexistente aceito");
    CONFERIR(slt_separator_analyze(s, 0, a.data(), a.data(), 4, b.data(), 4096) == SLT_ERROR_BUFFER_TOO_SMALL, "buffer curto aceito");
    slt_separator_destroy(s);
    slt_separator_destroy(nullptr);
}

/* ------------------------------------------------------------------ restauração */

static void testarRestauracao() {
    const uint32_t taxa = 48000;
    const uint32_t n = taxa * 2;
    Canal base = ruido(11, n, 0.25);
    for (uint32_t i = 0; i < n; i += 1) base[i] += static_cast<float>(0.3 * sin(2 * slt::PI * 110.0 * i / taxa) * (i % 12000 < 2400 ? 1.0 : 0.3));
    for (uint32_t perfil = 0; perfil <= SLT_STEM_OTHER; perfil += 1) {
        // Intensidade 0: o áudio sai igual e o nivelamento é 1.
        {
            slt_restorer_config config = {sizeof(slt_restorer_config), taxa, perfil, 0};
            slt_restorer_handle* r = slt_restorer_create(&config);
            CONFERIR(r != nullptr, "perfil %u: restaurador não criado", perfil);
            if (r == nullptr) continue;
            Canal e = base;
            Canal d = base;
            CONFERIR(slt_restorer_process(r, e.data(), d.data(), n) == SLT_OK, "processar");
            CONFERIR(memcmp(e.data(), base.data(), n * sizeof(float)) == 0, "perfil %u: intensidade 0 mudou o áudio", perfil);
            float ganho = 0;
            slt_restorer_matching_gain(r, &ganho);
            CONFERIR(perto(ganho, 1, 1e-6), "perfil %u: nivelamento %.4f com intensidade 0", perfil, ganho);
            slt_restorer_destroy(r);
        }
        // Intensidade 100: muda o som, sem valores inválidos, e o nivelamento iguala o volume.
        slt_restorer_config config = {sizeof(slt_restorer_config), taxa, perfil, 100};
        slt_restorer_handle* inteiro = slt_restorer_create(&config);
        slt_restorer_handle* emBlocos = slt_restorer_create(&config);
        CONFERIR(inteiro != nullptr && emBlocos != nullptr, "perfil %u: restaurador não criado", perfil);
        if (inteiro == nullptr || emBlocos == nullptr) continue;
        Canal e = base;
        Canal d = base;
        slt_restorer_process(inteiro, e.data(), d.data(), n);
        Canal e2 = base;
        Canal d2 = base;
        for (uint32_t p = 0; p < n; p += 777) slt_restorer_process(emBlocos, e2.data() + p, d2.data() + p, n - p < 777 ? n - p : 777);
        CONFERIR(memcmp(e.data(), e2.data(), n * sizeof(float)) == 0, "perfil %u: o resultado depende do tamanho do bloco", perfil);
        bool finito = true;
        double pico = 0;
        double diferenca = 0;
        for (uint32_t i = 0; i < n; i += 1) {
            finito = finito && isfinite(e[i]) && isfinite(d[i]);
            pico = fmax(pico, fabs(e[i]));
            diferenca += (static_cast<double>(e[i]) - base[i]) * (static_cast<double>(e[i]) - base[i]);
        }
        CONFERIR(finito, "perfil %u: valor não finito", perfil);
        CONFERIR(diferenca > 1e-3, "perfil %u: intensidade 100 não mudou nada", perfil);
        float ganho = 0;
        CONFERIR(slt_restorer_matching_gain(inteiro, &ganho) == SLT_OK, "nivelamento");
        CONFERIR(ganho >= 0.25f && ganho <= 4.0f, "perfil %u: nivelamento %.3f fora de ±12 dB", perfil, ganho);
        CONFERIR(pico * ganho <= 1.0 + 1e-4, "perfil %u: pico %.3f depois de nivelar", perfil, pico * ganho);
        const double volume = db(energiaDe(e) * ganho * ganho / energiaDe(base));
        CONFERIR(fabs(volume) < 0.5 || pico * ganho > 0.999, "perfil %u: volume %.2f dB fora do bruto", perfil, volume);
        // Mono: o canal direito nulo vale.
        slt_restorer_handle* mono = slt_restorer_create(&config);
        Canal m = base;
        CONFERIR(slt_restorer_process(mono, m.data(), nullptr, n) == SLT_OK, "mono");
        slt_restorer_destroy(mono);
        slt_restorer_destroy(inteiro);
        slt_restorer_destroy(emBlocos);
    }
    slt_restorer_config ruim = {sizeof(slt_restorer_config), taxa, 9, 50};
    CONFERIR(slt_restorer_create(&ruim) == nullptr, "perfil inválido aceito");
    ruim.profile = 0;
    ruim.intensity = 101;
    CONFERIR(slt_restorer_create(&ruim) == nullptr, "intensidade 101 aceita");
    ruim.intensity = 50;
    ruim.sample_rate = 100;
    CONFERIR(slt_restorer_create(&ruim) == nullptr, "taxa 100 aceita");
}

/** O perfil da voz tira grave: a energia em 50 Hz cai; o do baixo reforça 80 Hz. */
static void testarDirecaoDaRestauracao() {
    const uint32_t taxa = 44100;
    const uint32_t n = taxa;
    auto medir = [&](uint32_t perfil, double frequencia) {
        Canal x = seno(frequencia, n, 0.2, taxa);
        slt_restorer_config config = {sizeof(slt_restorer_config), taxa, perfil, 100};
        slt_restorer_handle* r = slt_restorer_create(&config);
        slt_restorer_process(r, x.data(), nullptr, n);
        slt_restorer_destroy(r);
        Canal fim(x.begin() + n / 2, x.end());
        return db(energiaDe(fim) / (0.02 * (n / 2)));
    };
    CONFERIR(medir(SLT_STEM_VOCALS, 50) < -3, "voz: 50 Hz devia cair (%.2f dB)", medir(SLT_STEM_VOCALS, 50));
    CONFERIR(medir(SLT_STEM_BASS, 80) > 1.5, "baixo: 80 Hz devia subir (%.2f dB)", medir(SLT_STEM_BASS, 80));
    CONFERIR(medir(SLT_STEM_BASS, 9000) < -2, "baixo: 9 kHz devia cair (%.2f dB)", medir(SLT_STEM_BASS, 9000));
    CONFERIR(medir(SLT_STEM_OTHER, 450) > 1.5, "outros: 450 Hz devia subir (%.2f dB)", medir(SLT_STEM_OTHER, 450));
    CONFERIR(medir(SLT_STEM_DRUMS, 90) > 1, "bateria: 90 Hz devia subir (%.2f dB)", medir(SLT_STEM_DRUMS, 90));
}

/* ------------------------------------------------------------------ consistência */

static void testarConsistencia() {
    const uint32_t n = 50000;
    const Canal a = ruido(31, n, 0.3);
    const Canal b = seno(330, n, 0.4, 44100);
    Canal misturaE(n);
    Canal misturaD(n);
    for (uint32_t i = 0; i < n; i += 1) {
        misturaE[i] = a[i] + b[i];
        misturaD[i] = a[i] - 0.5f * b[i];
    }
    // Faixas "estimadas" com erro: falta um pedaço de cada uma.
    Canal f0E(n), f0D(n), f1E(n), f1D(n);
    for (uint32_t i = 0; i < n; i += 1) {
        f0E[i] = 0.8f * a[i];
        f0D[i] = 0.8f * a[i];
        f1E[i] = 0.9f * b[i];
        f1D[i] = -0.45f * b[i];
    }
    Canal residuoE(n), residuoD(n);
    {
        Canal c0E = f0E, c0D = f0D, c1E = f1E, c1D = f1D;
        float* faixas[4] = {c0E.data(), c0D.data(), c1E.data(), c1D.data()};
        slt_consistency_config config = {sizeof(slt_consistency_config), 0, 0, 1};
        CONFERIR(slt_mixture_consistency(&config, misturaE.data(), misturaD.data(), faixas, 2, n, residuoE.data(), residuoD.data()) == SLT_OK, "resíduo explícito");
        CONFERIR(memcmp(c0E.data(), f0E.data(), n * sizeof(float)) == 0 && memcmp(c1D.data(), f1D.data(), n * sizeof(float)) == 0, "keep_residual mudou as faixas");
        double erro = 0;
        for (uint32_t i = 0; i < n; i += 1) erro += pow(c0E[i] + c1E[i] + residuoE[i] - misturaE[i], 2) + pow(c0D[i] + c1D[i] + residuoD[i] - misturaD[i], 2);
        CONFERIR(erro < 1e-8, "faixas + resíduo não dão a mistura (erro %.3g)", erro);
        CONFERIR(energiaDe(residuoE) > 1, "o resíduo devia ter energia");
        CONFERIR(slt_mixture_consistency(&config, misturaE.data(), misturaD.data(), faixas, 2, n, nullptr, nullptr) == SLT_ERROR_INVALID_ARGUMENT, "keep_residual sem destino aceito");
    }
    {
        Canal c0E = f0E, c0D = f0D, c1E = f1E, c1D = f1D;
        float* faixas[4] = {c0E.data(), c0D.data(), c1E.data(), c1D.data()};
        slt_consistency_config config = {sizeof(slt_consistency_config), 0, 0, 0};
        CONFERIR(slt_mixture_consistency(&config, misturaE.data(), misturaD.data(), faixas, 2, n, residuoE.data(), residuoD.data()) == SLT_OK, "redistribuição");
        const double relacao = db((energiaDe(misturaE) + energiaDe(misturaD)) / (energiaDe(residuoE) + energiaDe(residuoD) + 1e-30));
        CONFERIR(relacao > 80, "depois de redistribuir a soma fica a %.1f dB da mistura (mínimo 80)", relacao);
        // Cada faixa recebeu de volta, principalmente, o que era dela.
        double erro0 = 0;
        double antes0 = 0;
        for (uint32_t i = 0; i < n; i += 1) {
            erro0 += pow(c0E[i] - a[i], 2);
            antes0 += pow(f0E[i] - a[i], 2);
        }
        CONFERIR(erro0 < antes0 * 0.5, "a faixa 0 não ficou mais perto do original (%.4f contra %.4f)", erro0, antes0);
        config.window = 1000;
        CONFERIR(slt_mixture_consistency(&config, misturaE.data(), misturaD.data(), faixas, 2, n, nullptr, nullptr) == SLT_ERROR_INVALID_ARGUMENT, "janela inválida aceita");
    }
}

/* ------------------------------------------------------------------ motor */

static void testarMotor() {
    CONFERIR(slt_engine_create(100, 2) == nullptr, "taxa 100 aceita");
    CONFERIR(slt_engine_create(48000, 3) == nullptr, "3 canais aceitos");
    slt_engine_handle* motor = slt_engine_create(48000, 2);
    CONFERIR(motor != nullptr, "motor não criado");
    if (motor == nullptr) return;
    const uint32_t n = 4800;
    std::vector<float> entrada(n * 2, 0.5f);
    std::vector<float> saida(n * 2);
    CONFERIR(slt_engine_process(motor, entrada.data(), saida.data(), n) == SLT_OK, "processar");
    CONFERIR(saida[0] == 0.5f && saida[2 * n - 1] == 0.5f, "ganho inicial devia ser 1");
    // −6,0206 dB no centro: os dois lados a 0,5 depois da rampa de 10 ms, sem salto.
    CONFERIR(slt_engine_set_gain(motor, -6.0206f, 0) == SLT_OK, "definir ganho");
    slt_engine_process(motor, entrada.data(), saida.data(), n);
    float maiorSalto = 0;
    for (uint32_t i = 1; i < n; i += 1) maiorSalto = fmaxf(maiorSalto, fabsf(saida[2 * i] - saida[2 * (i - 1)]));
    CONFERIR(maiorSalto < 0.002f, "salto de %.5f na rampa", maiorSalto);
    CONFERIR(perto(saida[2 * n - 2], 0.25, 1e-4) && perto(saida[2 * n - 1], 0.25, 1e-4), "ganho final %.5f/%.5f", saida[2 * n - 2], saida[2 * n - 1]);
    CONFERIR(saida[2 * 100] > 0.3f && saida[2 * 100] < 0.45f, "a rampa não é gradual: %.4f aos 100 quadros", saida[2 * 100]);
    // Pan todo à esquerda: a direita some e a esquerda não ganha reforço.
    slt_engine_set_gain(motor, 0, -100);
    slt_engine_process(motor, entrada.data(), saida.data(), n);
    CONFERIR(perto(saida[2 * n - 2], 0.5, 1e-4) && fabsf(saida[2 * n - 1]) < 1e-6f, "pan esquerdo %.5f/%.5f", saida[2 * n - 2], saida[2 * n - 1]);
    // Pan 50 à direita: a mesma lei de ganhosEstereo (cos/sen de 67,5° vezes raiz de 2, até 1).
    slt_engine_set_gain(motor, 0, 50);
    slt_engine_process(motor, entrada.data(), saida.data(), n);
    CONFERIR(perto(saida[2 * n - 2], 0.5 * sqrt(2.0) * cos(slt::PI * 0.375), 1e-4) && perto(saida[2 * n - 1], 0.5, 1e-4), "pan 50: %.5f/%.5f", saida[2 * n - 2], saida[2 * n - 1]);
    slt_engine_set_gain(motor, -60, 0);
    slt_engine_process(motor, entrada.data(), saida.data(), n);
    CONFERIR(saida[2 * n - 1] == 0.0f, "−60 dB devia ser silêncio");
    CONFERIR(slt_engine_set_gain(motor, 30, 0) == SLT_ERROR_INVALID_ARGUMENT, "ganho de +30 dB aceito");
    CONFERIR(slt_engine_set_gain(motor, 0, 150) == SLT_ERROR_INVALID_ARGUMENT, "pan 150 aceito");
    CONFERIR(slt_engine_set_gain(motor, NAN, 0) == SLT_ERROR_INVALID_ARGUMENT, "ganho NaN aceito");
    CONFERIR(slt_engine_process(motor, nullptr, saida.data(), n) == SLT_ERROR_INVALID_ARGUMENT, "entrada nula aceita");
    slt_engine_destroy(motor);
    slt_engine_destroy(nullptr);

    slt_measures medidas = {};
    const float bloco[6] = {0.5f, -0.5f, 1.5f, 0.0f, -2.0f, 0.25f};
    CONFERIR(slt_measure(bloco, 3, 2, &medidas) == SLT_OK, "medir");
    CONFERIR(medidas.peak == 2.0f && medidas.clipped == 2, "pico %.2f, cortadas %u", medidas.peak, medidas.clipped);
    CONFERIR(perto(medidas.rms, sqrt((0.25 + 0.25 + 2.25 + 4 + 0.0625) / 6), 1e-6), "rms %.6f", medidas.rms);
}

static void testarFft() {
    slt::Fft fft;
    CONFERIR(!fft.criar(1000), "FFT de 1000 aceita");
    CONFERIR(fft.criar(1024), "FFT de 1024 recusada");
    std::vector<double> re(1024), im(1024, 0.0), original(1024);
    for (int i = 0; i < 1024; i += 1) original[i] = re[i] = sin(0.37 * i) + 0.25 * cos(1.9 * i);
    fft.transformar(re.data(), im.data(), -1);
    fft.transformar(re.data(), im.data(), 1);
    double erro = 0;
    for (int i = 0; i < 1024; i += 1) erro = fmax(erro, fabs(re[i] / 1024 - original[i]));
    CONFERIR(erro < 1e-12, "ida e volta da FFT com erro %.3g", erro);
    fft.soltar();
}

int testarMixer();
#if defined(_WIN32)
int testarWindows();
#endif

int main() {
    CONFERIR(slt_get_api_version() == SLT_API_VERSION, "versão da ABI");
    CONFERIR((slt_get_capabilities() & SLT_CAP_REFINED_MASK) != 0, "capacidades");
    CONFERIR(strcmp(slt_result_message(SLT_OK), "ok") == 0, "mensagem");
    testarFft();
    testarArgumentosDoSeparador();
    testarParidadeComOJs();
    testarSomaDasFaixas();
    testarAgudosNaFaixaCerta();
    testarRestauracao();
    testarDirecaoDaRestauracao();
    testarConsistencia();
    testarMotor();
    falhas += testarMixer();
#if defined(_WIN32)
    falhas += testarWindows();
#endif
    printf("%d conferencias, %d falhas\n", conferencias, falhas);
    return falhas == 0 ? 0 : 1;
}
