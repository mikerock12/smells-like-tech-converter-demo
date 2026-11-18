// Restaurador e refino dos agudos, pela ABI pública: o que cada etapa promete, medido em
// sinais sintéticos com o defeito conhecido (zumbido, ruído, estalos, recorte, vazamento).
#include <stdio.h>
#include <cmath>
#include <vector>

#include "slt_media_api.h"

namespace {

constexpr uint32_t TAXA = 44100;
constexpr double PI = 3.14159265358979323846;
int falhas = 0;

void conferir(bool ok, const char* oque) {
    if (!ok) {
        printf("FALHOU: %s\n", oque);
        falhas += 1;
    }
}

double rms(const std::vector<float>& x, size_t de, size_t ate) {
    double s = 0;
    for (size_t i = de; i < ate; ++i) s += static_cast<double>(x[i]) * x[i];
    return std::sqrt(s / static_cast<double>(ate - de));
}

/** Energia de um seno de frequência f (projeção), no trecho. */
double nivelDoSeno(const std::vector<float>& x, size_t de, size_t ate, double f) {
    double c = 0, s = 0;
    for (size_t i = de; i < ate; ++i) {
        c += x[i] * std::cos(2 * PI * f * static_cast<double>(i) / TAXA);
        s += x[i] * std::sin(2 * PI * f * static_cast<double>(i) / TAXA);
    }
    return 2 * std::sqrt(c * c + s * s) / static_cast<double>(ate - de);
}

uint32_t ruido = 12345;
float aleatorio() {
    ruido = ruido * 1664525u + 1013904223u;
    return static_cast<float>(ruido >> 8) / 8388608.0f - 1.0f;
}

/** Passa o sinal pelo reparo e devolve a saída já alinhada (latência descontada). */
std::vector<float> reparar(const std::vector<float>& entrada, const float* p, float* estatisticas = nullptr, const float* perfil = nullptr) {
    slt_audio_repair_handle* h = slt_audio_repair_create(TAXA, p, SLT_REPAIR_PARAMS);
    conferir(h != nullptr, "criar o reparo");
    if (h == nullptr) return entrada;
    if (perfil != nullptr) conferir(slt_audio_repair_set_noise_profile(h, perfil, SLT_NOISE_PROFILE_BINS) == SLT_OK, "perfil de ruído aceito");
    const uint32_t atraso = slt_audio_repair_latency(h);
    std::vector<float> l(entrada.size() + atraso, 0.0f), r(entrada.size() + atraso, 0.0f);
    for (size_t i = 0; i < entrada.size(); ++i) l[i] = r[i] = entrada[i];
    // Em blocos de tamanhos diferentes, como os clientes mandam.
    size_t feito = 0;
    uint32_t tamanhos[] = {1000, 4096, 333, 8192};
    for (int k = 0; feito < l.size(); ++k) {
        const uint32_t n = static_cast<uint32_t>(std::min<size_t>(tamanhos[k % 4], l.size() - feito));
        conferir(slt_audio_repair_process(h, l.data() + feito, r.data() + feito, n) == SLT_OK, "processar o reparo");
        feito += n;
    }
    if (estatisticas != nullptr) slt_audio_repair_stats(h, estatisticas, SLT_REPAIR_STATS);
    slt_audio_repair_destroy(h);
    std::vector<float> saida(entrada.size());
    for (size_t i = 0; i < saida.size(); ++i) {
        saida[i] = l[i + atraso];
        if (std::fabs(l[i + atraso] - r[i + atraso]) > 1e-6f) {
            conferir(false, "os dois canais iguais na entrada saem iguais");
            break;
        }
    }
    return saida;
}

std::vector<float> diagnosticar(const std::vector<float>& x, std::vector<float>* perfil = nullptr) {
    std::vector<float> valores(SLT_DIAGNOSIS_FIELDS, 0.0f);
    slt_audio_analyzer_handle* a = slt_audio_analyzer_create(TAXA);
    conferir(a != nullptr, "criar o analisador");
    if (a == nullptr) return valores;
    for (size_t i = 0; i < x.size(); i += 5000) slt_audio_analyzer_feed(a, x.data() + i, x.data() + i, static_cast<uint32_t>(std::min<size_t>(5000, x.size() - i)));
    conferir(slt_audio_analyzer_result(a, valores.data(), 3) == SLT_ERROR_BUFFER_TOO_SMALL, "resultado pede espaço");
    conferir(slt_audio_analyzer_result(a, valores.data(), SLT_DIAGNOSIS_FIELDS) == SLT_OK, "resultado do diagnóstico");
    if (perfil != nullptr) {
        perfil->assign(SLT_NOISE_PROFILE_BINS, 0.0f);
        conferir(slt_audio_analyzer_noise_profile(a, perfil->data(), SLT_NOISE_PROFILE_BINS) == SLT_OK, "perfil de ruído");
    }
    slt_audio_analyzer_destroy(a);
    return valores;
}

void testarReparoNeutro() {
    std::vector<float> x(TAXA * 2);
    for (size_t i = 0; i < x.size(); ++i) x[i] = 0.3f * static_cast<float>(std::sin(2 * PI * 440 * static_cast<double>(i) / TAXA)) + 0.05f * aleatorio();
    float p[SLT_REPAIR_PARAMS] = {};
    p[SLT_REPAIR_CEILING_DB] = 0;  // desligado
    const std::vector<float> y = reparar(x, p);
    double erro = 0;
    for (size_t i = 0; i < x.size(); ++i) erro = std::max(erro, static_cast<double>(std::fabs(y[i] - x[i])));
    printf("reparo neutro: erro máximo %.3g\n", erro);
    conferir(erro < 1e-5, "com tudo desligado o áudio sai igual (só atrasado)");
}

void testarZumbido() {
    std::vector<float> x(TAXA * 4);
    for (size_t i = 0; i < x.size(); ++i) {
        const double t = static_cast<double>(i) / TAXA;
        x[i] = static_cast<float>(0.2 * std::sin(2 * PI * 440 * t) + 0.03 * std::sin(2 * PI * 60 * t) + 0.015 * std::sin(2 * PI * 180 * t));
    }
    const std::vector<float> d = diagnosticar(x);
    printf("zumbido: %.0f Hz, %.1f dB acima dos vizinhos\n", d[SLT_DIAG_HUM_HZ], d[SLT_DIAG_HUM_DB]);
    conferir(d[SLT_DIAG_HUM_HZ] == 60 && d[SLT_DIAG_HUM_DB] > 10, "diagnóstico acha o zumbido de 60 Hz");
    float p[SLT_REPAIR_PARAMS] = {};
    p[SLT_REPAIR_HUM_HZ] = 60;
    p[SLT_REPAIR_HUM_AMOUNT] = 1;
    const std::vector<float> y = reparar(x, p);
    const size_t de = TAXA, ate = TAXA * 3;
    const double antes = nivelDoSeno(x, de, ate, 60), depois = nivelDoSeno(y, de, ate, 60);
    const double tom = 20 * std::log10(nivelDoSeno(y, de, ate, 440) / nivelDoSeno(x, de, ate, 440));
    printf("zumbido: 60 Hz %.1f dB, 440 Hz %+.2f dB\n", 20 * std::log10(depois / antes), tom);
    conferir(20 * std::log10(depois / antes) < -20, "o zumbido cai mais de 20 dB");
    conferir(std::fabs(tom) < 0.5, "a música (440 Hz) fica");
}

void testarRuido() {
    std::vector<float> x(TAXA * 6);
    for (size_t i = 0; i < x.size(); ++i) {
        const double t = static_cast<double>(i) / TAXA;
        const double tom = (t > 2 && t < 4) ? 0.3 * std::sin(2 * PI * 880 * t) : 0;
        x[i] = static_cast<float>(tom + 0.01 * aleatorio());
    }
    std::vector<float> perfil;
    const std::vector<float> d = diagnosticar(x, &perfil);
    printf("ruído: piso %.1f dB, silêncio %.2f, planura %.2f\n", d[SLT_DIAG_NOISE_FLOOR_DB], d[SLT_DIAG_SILENCE_RATIO], d[SLT_DIAG_NOISE_FLATNESS]);
    conferir(d[SLT_DIAG_NOISE_FLOOR_DB] > -50 && d[SLT_DIAG_NOISE_FLOOR_DB] < -40, "piso de ruído perto de −45 dB");
    conferir(d[SLT_DIAG_NOISE_FLATNESS] > 0.5, "o ruído branco tem espectro plano");
    float p[SLT_REPAIR_PARAMS] = {};
    p[SLT_REPAIR_DENOISE] = 1;
    float estatisticas[SLT_REPAIR_STATS];
    // Sem perfil, o próprio reparo acompanha o mínimo; com o perfil do diagnóstico, já começa certo.
    const std::vector<float> semPerfil = reparar(x, p, estatisticas);
    printf("ruído sem perfil: %.1f dB no trecho só de ruído\n", 20 * std::log10(rms(semPerfil, TAXA * 5, TAXA * 6) / rms(x, TAXA * 5, TAXA * 6)));
    conferir(rms(semPerfil, TAXA * 5, TAXA * 6) < rms(x, TAXA * 5, TAXA * 6) * 0.5, "sem perfil o ruído também cai");
    const std::vector<float> y = reparar(x, p, estatisticas, perfil.data());
    const double ruidoAntes = rms(x, TAXA * 5, TAXA * 6), ruidoDepois = rms(y, TAXA * 5, TAXA * 6);
    const double tom = 20 * std::log10(nivelDoSeno(y, TAXA * 5 / 2, TAXA * 7 / 2, 880) / nivelDoSeno(x, TAXA * 5 / 2, TAXA * 7 / 2, 880));
    printf("ruído: %.1f dB no trecho só de ruído, tom %+.2f dB, média %.1f dB\n", 20 * std::log10(ruidoDepois / ruidoAntes), tom, estatisticas[SLT_REPAIR_STAT_DENOISE_DB]);
    conferir(20 * std::log10(ruidoDepois / ruidoAntes) < -10, "o ruído estacionário cai mais de 10 dB");
    conferir(std::fabs(tom) < 1.0, "o tom fica (menos de 1 dB)");
}

void testarEstalos() {
    std::vector<float> x(TAXA * 4);
    for (size_t i = 0; i < x.size(); ++i) x[i] = 0.2f * static_cast<float>(std::sin(2 * PI * 330 * static_cast<double>(i) / TAXA)) + 0.002f * aleatorio();
    for (size_t i = TAXA / 4; i < x.size(); i += TAXA / 2) x[i] += 0.6f, x[i + 1] -= 0.3f;
    const std::vector<float> d = diagnosticar(x);
    printf("estalos: %.0f por minuto\n", d[SLT_DIAG_CLICKS_PER_MIN]);
    conferir(d[SLT_DIAG_CLICKS_PER_MIN] > 100 && d[SLT_DIAG_CLICKS_PER_MIN] < 140, "diagnóstico conta 120 estalos por minuto");
    float p[SLT_REPAIR_PARAMS] = {};
    p[SLT_REPAIR_DECLICK] = 1;
    float estatisticas[SLT_REPAIR_STATS];
    const std::vector<float> y = reparar(x, p, estatisticas);
    double pior = 0;
    for (size_t i = TAXA / 4; i < x.size(); i += TAXA / 2) {
        const double limpo = 0.2 * std::sin(2 * PI * 330 * static_cast<double>(i) / TAXA);
        pior = std::max(pior, std::fabs(y[i] - limpo));
    }
    printf("estalos: %.0f corrigidos, maior sobra %.3f\n", estatisticas[SLT_REPAIR_STAT_CLICKS], pior);
    conferir(estatisticas[SLT_REPAIR_STAT_CLICKS] >= 7, "os estalos são corrigidos");
    conferir(pior < 0.05, "no lugar do estalo fica o seno");
    // Sem estalo nenhum, nada é tocado.
    std::vector<float> limpo(TAXA * 2);
    for (size_t i = 0; i < limpo.size(); ++i) {
        const double fim = std::min(1.0, static_cast<double>(limpo.size() - i) / 2000.0);  // termina sem degrau
        limpo[i] = static_cast<float>(0.4 * fim * std::sin(2 * PI * 1000 * static_cast<double>(i) / TAXA));
    }
    reparar(limpo, p, estatisticas);
    conferir(estatisticas[SLT_REPAIR_STAT_CLICKS] == 0, "seno limpo não tem estalo");
}

void testarRecorte() {
    std::vector<float> x(TAXA * 2);
    for (size_t i = 0; i < x.size(); ++i) x[i] = static_cast<float>(std::max(-0.6, std::min(0.6, 0.8 * std::sin(2 * PI * 220 * static_cast<double>(i) / TAXA))));
    const std::vector<float> d = diagnosticar(x);
    printf("recorte: %.3f das amostras, pico %.1f dB\n", d[SLT_DIAG_CLIPPED_RATIO], d[SLT_DIAG_PEAK_DB]);
    conferir(d[SLT_DIAG_CLIPPED_RATIO] > 0.05, "diagnóstico vê os topos planos");
    float p[SLT_REPAIR_PARAMS] = {};
    p[SLT_REPAIR_DECLIP] = 1;
    p[SLT_REPAIR_CLIP_LEVEL] = 0.6f * 0.999f;
    float estatisticas[SLT_REPAIR_STATS];
    const std::vector<float> y = reparar(x, p, estatisticas);
    double pico = 0;
    for (size_t i = TAXA / 2; i < TAXA; ++i) pico = std::max(pico, static_cast<double>(std::fabs(y[i])));
    printf("recorte: %.0f trechos refeitos, pico %.3f (era 0,600)\n", estatisticas[SLT_REPAIR_STAT_CLIPS], pico);
    conferir(estatisticas[SLT_REPAIR_STAT_CLIPS] > 100 && pico > 0.65, "o topo do seno volta a subir");
    std::vector<float> sem(TAXA);
    for (size_t i = 0; i < sem.size(); ++i) sem[i] = 0.5f * static_cast<float>(std::sin(2 * PI * 220 * static_cast<double>(i) / TAXA));
    conferir(diagnosticar(sem)[SLT_DIAG_CLIPPED_RATIO] < 0.001f, "seno inteiro não tem recorte");
}

void testarLimitadorEBanda() {
    std::vector<float> x(TAXA * 2);
    for (size_t i = 0; i < x.size(); ++i) x[i] = 0.95f * static_cast<float>(std::sin(2 * PI * 100 * static_cast<double>(i) / TAXA));
    float p[SLT_REPAIR_PARAMS] = {};
    p[SLT_REPAIR_CEILING_DB] = -6;
    const std::vector<float> y = reparar(x, p);
    double pico = 0;
    for (size_t i = TAXA / 2; i < y.size(); ++i) pico = std::max(pico, static_cast<double>(std::fabs(y[i])));
    printf("limitador: pico %.3f com teto −6 dB (0,501)\n", pico);
    conferir(pico <= 0.502, "o limitador segura o teto");
    // Largura de banda: senos até 8 kHz contra senos até 20 kHz.
    std::vector<float> estreito(TAXA * 3), largo(TAXA * 3);
    for (size_t i = 0; i < estreito.size(); ++i) {
        double a = 0, b = 0;
        for (double f = 200; f < 20000; f *= 1.07) {
            const double v = 0.01 * std::sin(2 * PI * f * static_cast<double>(i) / TAXA + f);
            b += v;
            if (f < 8000) a += v;
        }
        estreito[i] = static_cast<float>(a);
        largo[i] = static_cast<float>(b);
    }
    const double be = diagnosticar(estreito)[SLT_DIAG_BANDWIDTH_HZ], bl = diagnosticar(largo)[SLT_DIAG_BANDWIDTH_HZ];
    printf("banda: %.0f Hz (até 8 kHz) e %.0f Hz (até 20 kHz)\n", be, bl);
    conferir(be > 7000 && be < 9500, "a banda estreita para perto de 8 kHz");
    conferir(bl > 19000, "a banda larga vai até perto de 20 kHz");
}

void testarRefinoDosAgudos() {
    // Duas fontes em bandas separadas, acima de 10 kHz; as estimativas vazaram 30% uma na outra.
    const size_t n = TAXA * 2;
    std::vector<float> a(n), b(n);
    for (size_t i = 0; i < n; ++i) {
        double va = 0, vb = 0;
        for (double f = 11000; f < 13000; f += 97) va += 0.02 * std::sin(2 * PI * f * static_cast<double>(i) / TAXA + f);
        for (double f = 16000; f < 18000; f += 89) vb += 0.02 * std::sin(2 * PI * f * static_cast<double>(i) / TAXA + 2 * f);
        a[i] = static_cast<float>(va);
        b[i] = static_cast<float>(vb);
    }
    slt_stem_refiner_handle* h = slt_stem_refiner_create(TAXA, 2, 8000, 1);
    conferir(h != nullptr, "criar o refino");
    conferir(slt_stem_refiner_create(TAXA, 1, 8000, 1) == nullptr, "uma faixa só é recusada");
    if (h == nullptr) return;
    const uint32_t atraso = slt_stem_refiner_latency(h);
    const size_t total = n + atraso;
    std::vector<float> planar(4 * total, 0.0f);
    for (size_t i = 0; i < n; ++i) {
        const float ea = 0.7f * a[i] + 0.3f * b[i];
        const float eb = 0.3f * a[i] + 0.7f * b[i];
        planar[0 * total + i] = planar[1 * total + i] = ea;
        planar[2 * total + i] = planar[3 * total + i] = eb;
    }
    // Em blocos: o planar de cada bloco é contíguo; copiamos para um bloco temporário.
    const uint32_t bloco = 3000;
    std::vector<float> temp(4 * bloco);
    for (size_t de = 0; de < total; de += bloco) {
        const uint32_t k = static_cast<uint32_t>(std::min<size_t>(bloco, total - de));
        for (int c = 0; c < 4; ++c) for (uint32_t i = 0; i < k; ++i) temp[c * k + i] = planar[c * total + de + i];
        conferir(slt_stem_refiner_process(h, temp.data(), k) == SLT_OK, "processar o refino");
        for (int c = 0; c < 4; ++c) for (uint32_t i = 0; i < k; ++i) planar[c * total + de + i] = temp[c * k + i];
    }
    slt_stem_refiner_destroy(h);
    double erroA = 0, erroB = 0, base = 0, soma = 0;
    for (size_t i = TAXA / 2; i < n - TAXA / 2; ++i) {
        const float ya = planar[0 * total + i + atraso], yb = planar[2 * total + i + atraso];
        erroA += (ya - a[i]) * (ya - a[i]);
        erroB += (yb - b[i]) * (yb - b[i]);
        const double antes = 0.3 * (b[i] - a[i]);
        base += antes * antes;
        const double s = ya + yb - (a[i] + b[i]);
        soma += s * s;
    }
    const double ganho = 10 * std::log10(base / (erroA + 1e-30));
    printf("refino dos agudos: vazamento %.1f dB menor; erro da soma %.2g\n", ganho, std::sqrt(soma));
    conferir(ganho > 12, "o vazamento entre as faixas cai mais de 12 dB");
    conferir(std::sqrt(soma) < 1e-3, "a soma das faixas continua a mistura");
    (void)erroB;
}

/** Bateria (ataques que decaem) e um tom grave, esmagados: ganho alto e recorte suave (tanh). */
std::vector<float> musicaEsmagada(double* cristaOriginal) {
    std::vector<float> limpo(TAXA * 6);
    for (size_t i = 0; i < limpo.size(); ++i) {
        const double t = static_cast<double>(i) / TAXA;
        const double fase = std::fmod(t, 0.25);
        const double bumbo = std::exp(-fase * 30) * std::sin(2 * PI * 60 * fase);
        const double caixa = (std::fmod(t, 0.5) < 0.25 ? 0 : 1) * std::exp(-fase * 45) * aleatorio();
        limpo[i] = static_cast<float>(0.45 * bumbo + 0.3 * caixa + 0.12 * std::sin(2 * PI * 220 * t));
    }
    double pico = 0;
    for (float v : limpo) pico = std::max(pico, static_cast<double>(std::fabs(v)));
    *cristaOriginal = 20 * std::log10(pico / rms(limpo, 0, limpo.size()));
    std::vector<float> x(limpo.size());
    for (size_t i = 0; i < x.size(); ++i) x[i] = static_cast<float>(std::tanh(limpo[i] * 5.0) * 0.98);
    return x;
}

double crista(const std::vector<float>& x, size_t de, size_t ate) {
    double pico = 0;
    for (size_t i = de; i < ate; ++i) pico = std::max(pico, static_cast<double>(std::fabs(x[i])));
    return 20 * std::log10(pico / rms(x, de, ate));
}

void testarGravacaoEsmagada() {
    double cristaOriginal = 0;
    const std::vector<float> x = musicaEsmagada(&cristaOriginal);
    const std::vector<float> d = diagnosticar(x);
    printf("esmagada: platô %.1f dBFS, %.0f%% perto dele, crista %.1f dB (original %.1f)\n",
           d[SLT_DIAG_LIMIT_LEVEL_DB], 100 * d[SLT_DIAG_NEAR_LIMIT_RATIO], d[SLT_DIAG_CREST_DB], cristaOriginal);
    conferir(d[SLT_DIAG_LIMIT_LEVEL_DB] > -1 && d[SLT_DIAG_LIMIT_LEVEL_DB] < 0.1, "diagnóstico acha o platô do limitador");
    conferir(d[SLT_DIAG_NEAR_LIMIT_RATIO] > 0.05, "muitas amostras perto do platô");
    float p[SLT_REPAIR_PARAMS] = {};
    p[SLT_REPAIR_INPUT_GAIN_DB] = -9;
    p[SLT_REPAIR_TRANSIENTS] = 1;
    p[SLT_REPAIR_CEILING_DB] = -1;
    float est[SLT_REPAIR_STATS] = {};
    const std::vector<float> y = reparar(x, p, est);
    const double antes = crista(x, TAXA, x.size() - TAXA);
    const double depois = crista(y, TAXA, y.size() - TAXA);
    double pico = 0;
    for (float v : y) pico = std::max(pico, static_cast<double>(std::fabs(v)));
    printf("transientes: crista %.1f -> %.1f dB, pico %.2f dBFS, limitador %.1f dB\n", antes, depois, 20 * std::log10(pico), est[SLT_REPAIR_STAT_LIMITER_DB]);
    conferir(depois > antes + 2, "os transientes voltam (a crista sobe mais de 2 dB)");
    conferir(pico <= std::pow(10.0, -1.0 / 20) + 1e-4, "o teto continua valendo");
    // Ganho de entrada sozinho: só o nível muda.
    float q[SLT_REPAIR_PARAMS] = {};
    q[SLT_REPAIR_INPUT_GAIN_DB] = -6;
    const std::vector<float> z = reparar(x, q);
    const double razao = rms(z, TAXA, z.size() - TAXA) / rms(x, TAXA, x.size() - TAXA);
    conferir(std::fabs(20 * std::log10(razao) + 6) < 0.05, "o ganho de entrada baixa 6 dB");
}

void testarEquilibrioTonal() {
    std::vector<float> x(TAXA * 3);
    for (size_t i = 0; i < x.size(); ++i) {
        const double t = static_cast<double>(i) / TAXA;
        x[i] = static_cast<float>(0.1 * std::sin(2 * PI * 125 * t) + 0.1 * std::sin(2 * PI * 1000 * t) + 0.1 * std::sin(2 * PI * 8000 * t));
    }
    const std::vector<float> d = diagnosticar(x);
    printf("oitavas: 125 Hz %.1f, 8 kHz %.1f dB (÷ 1 kHz)\n", d[SLT_DIAG_OCTAVE_FIRST + 2], d[SLT_DIAG_OCTAVE_FIRST + 8]);
    conferir(std::fabs(d[SLT_DIAG_OCTAVE_FIRST + 2]) < 1.5 && std::fabs(d[SLT_DIAG_OCTAVE_FIRST + 8]) < 1.5, "três senos iguais dão oitavas iguais");
    conferir(d[SLT_DIAG_OCTAVE_FIRST + 4] < -20, "oitava sem sinal fica bem abaixo");
    float p[SLT_REPAIR_PARAMS] = {};
    p[SLT_REPAIR_EQ_FIRST + 2] = 6;   // 125 Hz
    p[SLT_REPAIR_EQ_FIRST + 8] = -6;  // 8 kHz
    const std::vector<float> y = reparar(x, p);
    const size_t de = TAXA, ate = x.size() - TAXA;
    const double g125 = 20 * std::log10(nivelDoSeno(y, de, ate, 125) / nivelDoSeno(x, de, ate, 125));
    const double g1k = 20 * std::log10(nivelDoSeno(y, de, ate, 1000) / nivelDoSeno(x, de, ate, 1000));
    const double g8k = 20 * std::log10(nivelDoSeno(y, de, ate, 8000) / nivelDoSeno(x, de, ate, 8000));
    printf("equilíbrio tonal: 125 Hz %+.2f, 1 kHz %+.2f, 8 kHz %+.2f dB\n", g125, g1k, g8k);
    conferir(std::fabs(g125 - 6) < 0.6 && std::fabs(g8k + 6) < 0.6 && std::fabs(g1k) < 0.3, "cada oitava recebe o ganho pedido");
}

}  // namespace

int main() {
    const uint32_t capacidades = SLT_CAP_STEM_REFINE | SLT_CAP_AUDIO_DIAGNOSTICS | SLT_CAP_AUDIO_REPAIR;
    conferir((slt_get_capabilities() & capacidades) == capacidades, "capacidades do restaurador");
    conferir((slt_get_api_version() & 0xFFFF) >= 2, "ABI 1.2");
    conferir(slt_audio_repair_create(1000, nullptr, 0) == nullptr, "taxa inválida recusada");
    conferir(slt_audio_repair_process(nullptr, nullptr, nullptr, 0) == SLT_ERROR_INVALID_ARGUMENT, "nulo recusado");
    testarReparoNeutro();
    testarZumbido();
    testarRuido();
    testarEstalos();
    testarRecorte();
    testarLimitadorEBanda();
    testarRefinoDosAgudos();
    conferir((slt_get_capabilities() & SLT_CAP_AUDIO_DELIMIT) != 0 && (slt_get_api_version() & 0xFFFF) >= 3, "ABI 1.3");
    testarGravacaoEsmagada();
    testarEquilibrioTonal();
    printf("reparo: %d falhas\n", falhas);
    return falhas == 0 ? 0 : 1;
}
