// Restaurador de áudio (diagnóstico e reparo conservador) e refino dos agudos da separação.
//
// Tudo aqui roda fora do tempo real, em fluxo: o chamador entrega blocos de qualquer
// tamanho e recebe os mesmos blocos de volta, atrasados por uma latência fixa (informada
// pela ABI). Nada exige a música inteira na memória, o que importa no Android.
//
//  - Refino dos agudos: acima de um corte, cada faixa separada é refeita por um filtro de
//    Wiener multicanal (a parte de cada faixa é a energia dela ÷ a de todas, aplicada à
//    mistura). A soma das faixas continua igual à mistura, sem perder nada; o vazamento
//    de pratos e chiado entre as faixas diminui onde uma delas domina.
//  - Diagnóstico: pico, RMS, fator de crista, DC, recorte (topos planos no pico), piso de
//    ruído, zumbido de 50/60 Hz, largura de banda estimada, estalos, correlação estéreo e
//    silêncio. São medidas, não garantias sobre a origem do arquivo.
//  - Reparo: DC, corte de grave, zumbido (filtros de rejeição nas harmônicas), estalos e
//    recorte (interpolação curta), redução de ruído estacionário (ganho espectral com piso
//    e suavização), brilho opcional e um limitador de pico. Cada etapa tem intensidade e
//    fica desligada em zero. Não há IA aqui: o que a gravação perdeu não volta.
//  - ABI 1.3, para gravações esmagadas por limitador (shows gravados no celular, rips de
//    vídeo): ganho de entrada (margem para os picos voltarem), recorte refeito também acima
//    do platô borrado pelo MP3 (com teto), devolução dos transientes (envelope rápido ÷
//    lento, até +9 dB) e equilíbrio tonal por oitava (curva suave no mesmo domínio espectral
//    da redução de ruído). Os valores saíram de uma bancada com verdade de referência
//    (laboratorio/restaurador/simular.py).
#include "nucleo.h"

namespace slt {

namespace {

constexpr double QUASE_ZERO = 1e-20;

double db(double linear) { return linear > QUASE_ZERO ? 20.0 * logaritmo10(linear) : -200.0; }
double dbDePotencia(double p) { return p > QUASE_ZERO ? 10.0 * logaritmo10(p) : -200.0; }

/**
 * STFT em fluxo com Hann na análise e na síntese e sobreposição de 75%: cada amostra que
 * entra sai `N + ...` amostras depois, já reconstruída. Sem mexer no espectro, a saída é a
 * entrada atrasada (erro de arredondamento ~1e-12).
 */
struct FluxoEspectral {
    uint32_t N = 0;
    uint32_t H = 0;
    uint32_t B = 0;
    uint32_t canais = 0;
    uint32_t preenchido = 0;
    double normal = 1;
    Fft fft;
    Vetor<double> w;
    Vetor<double> entrada;  // [canais][N]
    Vetor<double> saida;    // [canais][N]
    Vetor<double> pronto;   // [canais][H]
    Vetor<double> re;       // [canais][N]
    Vetor<double> im;       // [canais][N]

    bool criar(uint32_t quantosCanais, uint32_t janela, uint32_t passo) {
        soltar();
        canais = quantosCanais;
        N = janela;
        H = passo;
        B = janela / 2 + 1;
        const size_t total = static_cast<size_t>(canais) * N;
        if (!fft.criar(N) || !w.criar(N) || !entrada.criar(total) || !saida.criar(total) ||
            !pronto.criar(static_cast<size_t>(canais) * H) || !re.criar(total) || !im.criar(total)) {
            soltar();
            return false;
        }
        for (uint32_t i = 0; i < N; i += 1) w[i] = 0.5 - 0.5 * cosseno((2 * PI * i) / N);
        double soma = 0;
        for (uint32_t i = 0; i < H; i += 1) {
            for (uint32_t k = i; k < N; k += H) soma += w[k] * w[k];
        }
        normal = soma / H;
        preenchido = 0;
        return true;
    }

    void soltar() {
        fft.soltar();
        w.soltar();
        entrada.soltar();
        saida.soltar();
        pronto.soltar();
        re.soltar();
        im.soltar();
        canais = 0;
    }

    /** Coloca a amostra nova do canal e devolve a amostra pronta do mesmo canal. */
    double trocar(uint32_t canal, double x) {
        entrada[static_cast<size_t>(canal) * N + (N - H) + preenchido] = x;
        return pronto[static_cast<size_t>(canal) * H + preenchido];
    }

    /** Depois de trocar todos os canais: no fim do salto, analisa, chama `espectro` e sintetiza. */
    template <typename F>
    void avancar(F&& espectro) {
        preenchido += 1;
        if (preenchido < H) return;
        preenchido = 0;
        for (uint32_t c = 0; c < canais; c += 1) {
            double* r = re.dados + static_cast<size_t>(c) * N;
            double* m = im.dados + static_cast<size_t>(c) * N;
            const double* e = entrada.dados + static_cast<size_t>(c) * N;
            for (uint32_t i = 0; i < N; i += 1) {
                r[i] = e[i] * w[i];
                m[i] = 0;
            }
            fft.transformar(r, m, -1);
        }
        espectro(re.dados, im.dados);
        for (uint32_t c = 0; c < canais; c += 1) {
            double* r = re.dados + static_cast<size_t>(c) * N;
            double* m = im.dados + static_cast<size_t>(c) * N;
            // Simetria hermitiana: o sinal continua real depois de mexer só em 0..N/2.
            m[0] = 0;
            m[N / 2] = 0;
            for (uint32_t k = 1; k < N / 2; k += 1) {
                r[N - k] = r[k];
                m[N - k] = -m[k];
            }
            fft.transformar(r, m, +1);
            double* s = saida.dados + static_cast<size_t>(c) * N;
            const double escala = 1.0 / (static_cast<double>(N) * normal);
            for (uint32_t i = 0; i < N; i += 1) s[i] += r[i] * w[i] * escala;
            double* p = pronto.dados + static_cast<size_t>(c) * H;
            for (uint32_t i = 0; i < H; i += 1) p[i] = s[i];
            memmove(s, s + H, sizeof(double) * (N - H));
            memset(s + (N - H), 0, sizeof(double) * H);
            double* e = entrada.dados + static_cast<size_t>(c) * N;
            memmove(e, e + H, sizeof(double) * (N - H));
        }
    }
};

/** Mediana de poucos valores (ordena no lugar). */
double mediana(double* v, uint32_t n) {
    for (uint32_t i = 1; i < n; i += 1) {
        const double x = v[i];
        uint32_t j = i;
        while (j > 0 && v[j - 1] > x) {
            v[j] = v[j - 1];
            j -= 1;
        }
        v[j] = x;
    }
    return n == 0 ? 0 : (n % 2 ? v[n / 2] : 0.5 * (v[n / 2 - 1] + v[n / 2]));
}

/** Interpola [a, b] com uma cúbica de Hermite entre x[a-1] e x[b+1], com as inclinações vizinhas. */
void interpolar(float* x, int64_t a, int64_t b) {
    const double y0 = x[a - 1];
    const double y1 = x[b + 1];
    const double m0 = x[a - 1] - x[a - 2];
    const double m1 = x[b + 2] - x[b + 1];
    const double n = static_cast<double>(b - a + 2);
    for (int64_t i = a; i <= b; i += 1) {
        const double t = static_cast<double>(i - a + 1) / n;
        const double t2 = t * t;
        const double t3 = t2 * t;
        const double v = (2 * t3 - 3 * t2 + 1) * y0 + (t3 - 2 * t2 + t) * m0 * n + (-2 * t3 + 3 * t2) * y1 + (t3 - t2) * m1 * n;
        x[i] = static_cast<float>(v);
    }
}

}  // namespace

/* ------------------------------------------------------------------ refino dos agudos */

struct RefinadorDeAgudos {
    FluxoEspectral fluxo;
    uint32_t faixas = 0;
    Vetor<double> peso;  // [B]

    bool criar(uint32_t taxa, uint32_t quantasFaixas, double corte, double quanto) {
        faixas = quantasFaixas;
        if (faixas > 24 || !fluxo.criar(faixas * 2, 4096, 1024) || !peso.criar(fluxo.B)) return false;
        const double transicao = 2000;
        for (uint32_t b = 0; b < fluxo.B; b += 1) {
            const double f = static_cast<double>(b) * taxa / fluxo.N;
            peso[b] = limitar((f - (corte - transicao)) / transicao, 0, 1) * limitar(quanto, 0, 1);
        }
        return true;
    }

    void soltar() {
        fluxo.soltar();
        peso.soltar();
    }

    uint32_t latencia() const { return fluxo.N; }

    /**
     * Wiener com duas iterações (a segunda parte das faixas que a primeira refez): as
     * máscaras ficam mais definidas onde uma faixa domina, e a soma continua a mistura.
     */
    void espectro(double* re, double* im) {
        const uint32_t N = fluxo.N;
        double partes[24];
        for (uint32_t b = 0; b < fluxo.B; b += 1) {
            const double g = peso[b];
            if (g <= 0) continue;
            double mr[2] = {0, 0};
            double mi[2] = {0, 0};
            for (uint32_t s = 0; s < faixas; s += 1) {
                for (uint32_t c = 0; c < 2; c += 1) {
                    const size_t k = static_cast<size_t>(s * 2 + c) * N + b;
                    mr[c] += re[k];
                    mi[c] += im[k];
                }
            }
            for (uint32_t s = 0; s < faixas; s += 1) {
                const size_t l = static_cast<size_t>(s * 2) * N + b;
                const size_t r = l + N;
                partes[s] = re[l] * re[l] + im[l] * im[l] + re[r] * re[r] + im[r] * im[r];
            }
            // Duas iterações de Wiener equivalem a pesar pela energia ao quadrado.
            double total = QUASE_ZERO;
            for (uint32_t s = 0; s < faixas; s += 1) {
                partes[s] = partes[s] * partes[s];
                total += partes[s];
            }
            for (uint32_t s = 0; s < faixas; s += 1) partes[s] /= total;
            for (uint32_t s = 0; s < faixas; s += 1) {
                const size_t l = static_cast<size_t>(s * 2) * N + b;
                for (uint32_t c = 0; c < 2; c += 1) {
                    const size_t k = l + static_cast<size_t>(c) * N;
                    re[k] = (1 - g) * re[k] + g * partes[s] * mr[c];
                    im[k] = (1 - g) * im[k] + g * partes[s] * mi[c];
                }
            }
        }
    }

    void processar(float* planar, uint32_t quadros) {
        const uint32_t canais = faixas * 2;
        for (uint32_t i = 0; i < quadros; i += 1) {
            for (uint32_t c = 0; c < canais; c += 1) {
                float& x = planar[static_cast<size_t>(c) * quadros + i];
                x = static_cast<float>(fluxo.trocar(c, x));
            }
            fluxo.avancar([this](double* re, double* im) { espectro(re, im); });
        }
    }
};

/* ------------------------------------------------------------------ diagnóstico */

struct AnalisadorDeAudio {
    static constexpr uint32_t N = 16384;
    static constexpr uint32_t H = 8192;
    static constexpr uint32_t BLOCO = 2048;
    static constexpr uint32_t FAIXAS_DO_HISTOGRAMA = 292;  // −140 a +6 dB em 0,5 dB
    static constexpr uint32_t PLANOS = 240;               // −24 a 0 dB em 0,1 dB
    static constexpr uint32_t NP = SLT_NOISE_PROFILE_WINDOW;  // perfil de ruído: a mesma STFT do reparo
    static constexpr uint32_t BP = NP / 2 + 1;
    static constexpr uint32_t QUIETOS = 48;               // quadros mais calmos guardados (~4,5 s)
    static constexpr uint32_t NIVEIS = 720;               // |amostra| de −60 a +12 dB em 0,1 dB (o platô do limitador)

    uint32_t taxa = 0;
    Fft fft;
    Vetor<double> w;
    Vetor<double> mono;  // [N]
    Vetor<double> re;
    Vetor<double> im;
    Vetor<double> media;        // potência média por bin
    Vetor<double> normalizada;  // potência ÷ energia do quadro (o zumbido aparece nos trechos calmos)
    Vetor<double> normalizadaB; // o mesmo, só nos trechos ímpares de ~9 s: zumbido de rede fica nos dois
    static constexpr uint32_t BANDAS_ALTAS = 3;        // 4-8, 8-12 e 12-16 kHz
    static constexpr uint32_t NIVEIS_DAS_BANDAS = 720; // −160 a +20 dB em 0,25 dB
    Vetor<uint64_t> bandasAltas;  // [BANDAS_ALTAS][NIVEIS_DAS_BANDAS]
    Vetor<uint64_t> histograma;
    Vetor<uint64_t> planos;
    uint32_t preenchido = 0;
    uint64_t quadrosDeAnalise = 0;
    uint64_t total = 0;
    double soma[2] = {0, 0};
    double somaQuadrada[2] = {0, 0};
    double somaLR = 0;
    double pico = 0;
    double blocoEnergia = 0;
    uint32_t blocoN = 0;
    uint64_t blocos = 0;
    // Recorte: topos planos (mesmo valor repetido) por canal.
    float anterior[2] = {0, 0};
    uint32_t sequencia[2] = {0, 0};
    // Estalos: segunda diferença do mono contra o envelope dela.
    double x1 = 0;
    double x2 = 0;
    double envelope = 0;
    uint64_t estalos = 0;
    int64_t ultimoEstalo = -1000000;
    // Um estalo é isolado: o pico da segunda diferença com o entorno (±4 a ±31 amostras) calmo.
    // Ataque de bateria também tem segunda diferença alta, mas espalhada por vários milissegundos.
    static constexpr uint32_t ANEL = 64;
    double aneisD[ANEL] = {};
    double aneisE[ANEL] = {};
    // Perfil de ruído: espectro médio (L+R) dos quadros mais calmos que não são silêncio digital.
    Fft fftDoPerfil;
    Vetor<double> wp;
    Vetor<double> perfilL;
    Vetor<double> perfilR;
    Vetor<double> pre;
    Vetor<double> pim;
    Vetor<float> quietos;  // [QUIETOS][BP]
    Vetor<uint64_t> niveis;  // [NIVEIS]
    uint64_t abaixoDosNiveis = 0;
    double energiaDosQuietos[QUIETOS];
    uint32_t quantosQuietos = 0;
    uint32_t preenchidoDoPerfil = 0;

    bool criar(uint32_t taxa_) {
        taxa = taxa_;
        if (!fft.criar(N) || !w.criar(N) || !mono.criar(N) || !re.criar(N) || !im.criar(N) || !media.criar(N / 2 + 1) ||
            !normalizada.criar(N / 2 + 1) || !normalizadaB.criar(N / 2 + 1) || !bandasAltas.criar(static_cast<size_t>(BANDAS_ALTAS) * NIVEIS_DAS_BANDAS) || !histograma.criar(FAIXAS_DO_HISTOGRAMA) || !planos.criar(PLANOS) ||
            !fftDoPerfil.criar(NP) || !wp.criar(NP) || !perfilL.criar(NP) || !perfilR.criar(NP) || !pre.criar(NP) || !pim.criar(NP) ||
            !quietos.criar(static_cast<size_t>(QUIETOS) * BP) || !niveis.criar(NIVEIS)) {
            soltar();
            return false;
        }
        for (uint32_t i = 0; i < N; i += 1) w[i] = 0.5 - 0.5 * cosseno((2 * PI * i) / N);
        for (uint32_t i = 0; i < NP; i += 1) wp[i] = 0.5 - 0.5 * cosseno((2 * PI * i) / NP);
        return true;
    }

    /** Um quadro de NP amostras (sem sobreposição) concorre a entrar entre os mais calmos. */
    void quadroDoPerfil() {
        double energia = 0;
        for (uint32_t i = 0; i < NP; i += 1) energia += perfilL[i] * perfilL[i] + perfilR[i] * perfilR[i];
        energia /= 2.0 * NP;
        if (energia < 1e-10) return;  // silêncio digital (ou quase) não é ruído de fundo
        registrarBandasAltas();
        uint32_t vaga = quantosQuietos;
        if (quantosQuietos == QUIETOS) {
            vaga = 0;
            for (uint32_t k = 1; k < QUIETOS; k += 1) if (energiaDosQuietos[k] > energiaDosQuietos[vaga]) vaga = k;
            if (energia >= energiaDosQuietos[vaga]) return;
        } else {
            quantosQuietos += 1;
        }
        energiaDosQuietos[vaga] = energia;
        float* destino = quietos.dados + static_cast<size_t>(vaga) * BP;
        for (uint32_t b = 0; b < BP; b += 1) destino[b] = 0;
        const Vetor<double>* canais[2] = {&perfilL, &perfilR};
        for (uint32_t c = 0; c < 2; c += 1) {
            for (uint32_t i = 0; i < NP; i += 1) {
                pre[i] = (*canais[c])[i] * wp[i];
                pim[i] = 0;
            }
            fftDoPerfil.transformar(pre.dados, pim.dados, -1);
            for (uint32_t b = 0; b < BP; b += 1) destino[b] += static_cast<float>(pre[b] * pre[b] + pim[b] * pim[b]);
        }
    }

    /** A energia de cada banda alta neste quadro (L+R), no histograma: dá a estabilidade no fim. */
    void registrarBandasAltas() {
        static const double LIMITES[BANDAS_ALTAS + 1] = {4000, 8000, 12000, 16000};
        double somas[BANDAS_ALTAS] = {0, 0, 0};
        const Vetor<double>* canais[2] = {&perfilL, &perfilR};
        for (uint32_t c = 0; c < 2; c += 1) {
            for (uint32_t i = 0; i < NP; i += 1) {
                pre[i] = (*canais[c])[i] * wp[i];
                pim[i] = 0;
            }
            fftDoPerfil.transformar(pre.dados, pim.dados, -1);
            for (uint32_t b = 1; b < BP; b += 1) {
                const double f = static_cast<double>(b) * taxa / NP;
                for (uint32_t k = 0; k < BANDAS_ALTAS; k += 1) {
                    if (f >= LIMITES[k] && f < LIMITES[k + 1]) somas[k] += pre[b] * pre[b] + pim[b] * pim[b];
                }
            }
        }
        for (uint32_t k = 0; k < BANDAS_ALTAS; k += 1) {
            const double nivel = dbDePotencia(somas[k] / (static_cast<double>(NP) * NP));
            int64_t i = static_cast<int64_t>((nivel + 160) * 4);
            if (i < 0) i = 0;
            if (i >= NIVEIS_DAS_BANDAS) i = NIVEIS_DAS_BANDAS - 1;
            bandasAltas[static_cast<size_t>(k) * NIVEIS_DAS_BANDAS + static_cast<size_t>(i)] += 1;
        }
    }

    /** 10º ÷ 50º percentil da energia da banda alta (dB); −60 sem dados ou acima da taxa. */
    double estabilidadeDaBanda(uint32_t k) const {
        static const double TOPOS[BANDAS_ALTAS] = {8000, 12000, 16000};
        if (TOPOS[k] > taxa * 0.5) return -60;
        const uint64_t* h = bandasAltas.dados + static_cast<size_t>(k) * NIVEIS_DAS_BANDAS;
        uint64_t quadros = 0;
        for (uint32_t i = 0; i < NIVEIS_DAS_BANDAS; i += 1) quadros += h[i];
        if (quadros < 8) return -60;
        auto percentil_ = [&](double fracao) {
            uint64_t acumulado = 0;
            for (uint32_t i = 0; i < NIVEIS_DAS_BANDAS; i += 1) {
                acumulado += h[i];
                if (static_cast<double>(acumulado) >= fracao * static_cast<double>(quadros)) return -160 + i * 0.25 + 0.125;
            }
            return 20.0;
        };
        const double baixo = percentil_(0.10);
        const double meio = percentil_(0.50);
        return meio < -150 ? -60 : baixo - meio;
    }

    /** Só os calmos de verdade: até 6 dB acima do mais calmo (num arquivo curto, os 48 podem pegar música). */
    bool entraNoPerfil(uint32_t k) const {
        double menor = energiaDosQuietos[0];
        for (uint32_t j = 1; j < quantosQuietos; j += 1) if (energiaDosQuietos[j] < menor) menor = energiaDosQuietos[j];
        return energiaDosQuietos[k] <= 4 * menor;
    }

    void perfilDeRuido(double* saida) const {
        uint32_t usados = 0;
        for (uint32_t b = 0; b < BP; b += 1) saida[b] = 0;
        for (uint32_t k = 0; k < quantosQuietos; k += 1) {
            if (!entraNoPerfil(k)) continue;
            for (uint32_t b = 0; b < BP; b += 1) saida[b] += quietos[static_cast<size_t>(k) * BP + b];
            usados += 1;
        }
        for (uint32_t b = 0; b < BP && usados > 0; b += 1) saida[b] /= usados;
    }

    /** Planura espectral do perfil (média geométrica ÷ aritmética), de 200 Hz a 16 kHz: chiado perto de 1, música perto de 0. */
    double planuraDoRuido() const {
        if (quantosQuietos == 0) return 0;
        double perfil[BP];
        perfilDeRuido(perfil);
        double logs = 0;
        double linear = 0;
        uint32_t n = 0;
        for (uint32_t b = 1; b < BP; b += 1) {
            const double f = static_cast<double>(b) * taxa / NP;
            if (f < 200 || f > 16000) continue;
            const double p = perfil[b] + QUASE_ZERO;
            logs += logaritmo10(p);
            linear += p;
            n += 1;
        }
        return n ? potencia(10.0, logs / n) / (linear / n) : 0;
    }

    void soltar() {
        fft.soltar();
        w.soltar();
        mono.soltar();
        re.soltar();
        im.soltar();
        media.soltar();
        normalizada.soltar();
        normalizadaB.soltar();
        bandasAltas.soltar();
        histograma.soltar();
        planos.soltar();
        fftDoPerfil.soltar();
        wp.soltar();
        perfilL.soltar();
        perfilR.soltar();
        pre.soltar();
        pim.soltar();
        quietos.soltar();
        niveis.soltar();
    }

    void registrarNivel(double a) {
        const double nivel = db(a);
        if (nivel < -60) { abaixoDosNiveis += 1; return; }
        uint32_t k = static_cast<uint32_t>((nivel + 60) * 10);
        if (k >= NIVEIS) k = NIVEIS - 1;
        niveis[k] += 1;
    }

    /** O nível (dB) abaixo do qual fica a fração `fracao` das amostras (o platô: 0,995). */
    double nivelDasAmostras(double fracao) const {
        uint64_t todas = abaixoDosNiveis;
        for (uint32_t k = 0; k < NIVEIS; k += 1) todas += niveis[k];
        if (todas == 0) return -200;
        const double alvo = (1 - fracao) * static_cast<double>(todas);
        uint64_t acima = 0;
        for (int64_t k = NIVEIS - 1; k >= 0; k -= 1) {
            acima += niveis[static_cast<size_t>(k)];
            if (static_cast<double>(acima) >= alvo) return -60 + static_cast<double>(k) * 0.1 + 0.05;
        }
        return -60;
    }

    double fracaoAcimaDe(double nivelDb) const {
        uint64_t todas = abaixoDosNiveis;
        uint64_t acima = 0;
        for (uint32_t k = 0; k < NIVEIS; k += 1) {
            todas += niveis[k];
            if (-60 + k * 0.1 >= nivelDb) acima += niveis[k];
        }
        return todas ? static_cast<double>(acima) / static_cast<double>(todas) : 0;
    }

    /** Nível de cada oitava (31,5 Hz a 16 kHz) ÷ o da de 1 kHz, dB, do espectro médio (mono). */
    void oitavas(double* saida) const {
        static const double CENTROS[SLT_DIAG_OCTAVES] = {31.5, 63, 125, 250, 500, 1000, 2000, 4000, 8000, 16000};
        double nivel[SLT_DIAG_OCTAVES];
        const double porBin = static_cast<double>(taxa) / N;
        for (uint32_t o = 0; o < SLT_DIAG_OCTAVES; o += 1) {
            int64_t a = static_cast<int64_t>(CENTROS[o] / 1.4142135623730951 / porBin);
            int64_t b = static_cast<int64_t>(CENTROS[o] * 1.4142135623730951 / porBin);
            if (a < 1) a = 1;
            if (b > static_cast<int64_t>(N / 2)) b = N / 2;
            double s = 0;
            for (int64_t k = a; k <= b; k += 1) s += media[static_cast<size_t>(k)];
            nivel[o] = dbDePotencia(s);
        }
        for (uint32_t o = 0; o < SLT_DIAG_OCTAVES; o += 1) {
            const bool valido = quadrosDeAnalise > 0 && CENTROS[o] < taxa * 0.45 && nivel[o] > -190 && nivel[5] > -190;
            saida[o] = valido ? nivel[o] - nivel[5] : -60;
        }
    }

    void registrarPlano(float valor, uint32_t amostras) {
        const double nivel = db(absoluto(valor));
        if (nivel < -24) return;
        uint32_t k = static_cast<uint32_t>((nivel + 24) * 10);
        if (k >= PLANOS) k = PLANOS - 1;
        planos[k] += amostras;
    }

    void quadroEspectral() {
        for (uint32_t i = 0; i < N; i += 1) {
            re[i] = mono[i] * w[i];
            im[i] = 0;
        }
        fft.transformar(re.dados, im.dados, -1);
        double energia = QUASE_ZERO;
        for (uint32_t b = 0; b <= N / 2; b += 1) energia += re[b] * re[b] + im[b] * im[b];
        for (uint32_t b = 0; b <= N / 2; b += 1) {
            const double p = re[b] * re[b] + im[b] * im[b];
            media[b] += p;
            // Trechos de ~9 s alternados: a nota grave de uma música muda, o zumbido da rede não.
            if ((quadrosDeAnalise / 48) % 2 == 0) normalizada[b] += p / energia;
            else normalizadaB[b] += p / energia;
        }
        quadrosDeAnalise += 1;
        memmove(mono.dados, mono.dados + H, sizeof(double) * (N - H));
    }

    void alimentar(const float* esquerdo, const float* direito, uint32_t quadros) {
        const float* canais[2] = {esquerdo, direito != nullptr ? direito : esquerdo};
        for (uint32_t i = 0; i < quadros; i += 1) {
            const double l = canais[0][i];
            const double r = canais[1][i];
            for (uint32_t c = 0; c < 2; c += 1) {
                const float x = canais[c][i];
                soma[c] += x;
                somaQuadrada[c] += static_cast<double>(x) * x;
                const double a = absoluto(x);
                if (a > pico) pico = a;
                registrarNivel(a);
                if (x == anterior[c] && a > 0.1) sequencia[c] += 1;
                else {
                    if (sequencia[c] >= 2) registrarPlano(anterior[c], sequencia[c] + 1);
                    sequencia[c] = 0;
                }
                anterior[c] = x;
            }
            somaLR += l * r;
            const double m = 0.5 * (l + r);
            // Segunda diferença do mono, com uma amostra de atraso (precisa da vizinha seguinte).
            const double d = x1 - 0.5 * (x2 + m);
            aneisD[total % ANEL] = absoluto(d);
            aneisE[total % ANEL] = envelope;
            // O candidato fica 32 amostras atrás (precisa do que vem depois dele).
            if (total >= ANEL) {
                const uint64_t centro = total - 32;
                const double c = aneisD[centro % ANEL];
                if (c > 12 * aneisE[centro % ANEL] && c > 0.02 && static_cast<int64_t>(centro) - ultimoEstalo > static_cast<int64_t>(taxa / 100)) {
                    double entorno = 0;
                    for (uint32_t k = 4; k < 32; k += 1) {
                        const double a = aneisD[(centro - k) % ANEL];
                        const double b = aneisD[(centro + k) % ANEL];
                        if (a > entorno) entorno = a;
                        if (b > entorno) entorno = b;
                    }
                    if (entorno < 0.35 * c) {
                        estalos += 1;
                        ultimoEstalo = static_cast<int64_t>(centro);
                    }
                }
            }
            envelope = 0.999 * envelope + 0.001 * absoluto(d);
            x2 = x1;
            x1 = m;
            blocoEnergia += 0.5 * (l * l + r * r);
            if (++blocoN == BLOCO) {
                const double nivel = dbDePotencia(blocoEnergia / BLOCO);
                int64_t k = static_cast<int64_t>((nivel + 140) * 2);
                if (k < 0) k = 0;
                if (k >= FAIXAS_DO_HISTOGRAMA) k = FAIXAS_DO_HISTOGRAMA - 1;
                histograma[static_cast<size_t>(k)] += 1;
                blocos += 1;
                blocoEnergia = 0;
                blocoN = 0;
            }
            mono[(N - H) + preenchido] = m;
            total += 1;
            if (++preenchido == H) {
                preenchido = 0;
                // O primeiro quadro teria zeros antes do começo: o degrau espalharia agudos falsos.
                if (total >= N) quadroEspectral();
                else memmove(mono.dados, mono.dados + H, sizeof(double) * (N - H));
            }
            perfilL[preenchidoDoPerfil] = l;
            perfilR[preenchidoDoPerfil] = r;
            if (++preenchidoDoPerfil == NP) {
                preenchidoDoPerfil = 0;
                quadroDoPerfil();
            }
        }
    }

    /** O nível (dB) em que o histograma de blocos acumula `fracao` dos blocos acima de −100 dB. */
    double percentil(double fracao, double* silencio) const {
        uint64_t validos = 0;
        uint64_t calmos = 0;
        for (uint32_t k = 0; k < FAIXAS_DO_HISTOGRAMA; k += 1) {
            const double nivel = -140 + k * 0.5;
            if (nivel >= -100) validos += histograma[k];
            if (nivel < -70) calmos += histograma[k];
        }
        if (silencio != nullptr) *silencio = blocos ? static_cast<double>(calmos) / static_cast<double>(blocos) : 0;
        if (validos == 0) return -200;
        const double alvo = fracao * static_cast<double>(validos);
        uint64_t acumulado = 0;
        for (uint32_t k = 0; k < FAIXAS_DO_HISTOGRAMA; k += 1) {
            const double nivel = -140 + k * 0.5;
            if (nivel < -100) continue;
            acumulado += histograma[k];
            if (acumulado >= alvo) return nivel + 0.25;
        }
        return 6;
    }

    double nivelDoBin(const Vetor<double>& espectro, double frequencia, double largura) const {
        const double porBin = static_cast<double>(taxa) / N;
        int64_t de = static_cast<int64_t>((frequencia - largura) / porBin);
        int64_t ate = static_cast<int64_t>((frequencia + largura) / porBin + 1);
        if (de < 1) de = 1;
        if (ate > static_cast<int64_t>(N / 2)) ate = N / 2;
        double maior = 0;
        for (int64_t b = de; b <= ate; b += 1) if (espectro[static_cast<size_t>(b)] > maior) maior = espectro[static_cast<size_t>(b)];
        return maior;
    }

    /**
     * Quanto as harmônicas de f0 sobem acima dos vizinhos (dB): a média das três mais fortes
     * entre as cinco primeiras. Zumbido de rede às vezes é só a fundamental (aterramento bom),
     * às vezes quase só a 2ª e a 3ª (fonte chaveada); a média de todas diluía os dois casos.
     */
    double proeminenciaDoZumbido(double f0) const {
        // Nas duas metades (a nota grave da música muda, a rede não), e com duas das três primeiras
        // harmônicas acima de 6 dB ou a fundamental acima de 12 dB (aterramento bom: só ela).
        uint32_t fortesA = 0;
        uint32_t fortesB = 0;
        double fundamentalA = 0;
        double fundamentalB = 0;
        const double a = proeminenciaEm(normalizada, f0, &fortesA, &fundamentalA);
        const bool confirmadoA = fortesA >= 2 || fundamentalA > 12;
        if (quadrosDeAnalise < 96) return confirmadoA ? a : (a < 5 ? a : 5);
        const double b = proeminenciaEm(normalizadaB, f0, &fortesB, &fundamentalB);
        const double menor = a < b ? a : b;
        return confirmadoA && (fortesB >= 2 || fundamentalB > 12) ? menor : (menor < 5 ? menor : 5);
    }

    double proeminenciaEm(const Vetor<double>& espectro, double f0, uint32_t* fortes, double* fundamental) const {
        const double porBin = static_cast<double>(taxa) / N;
        double notas[5];
        uint32_t contadas = 0;
        for (uint32_t k = 1; k <= 5; k += 1) {
            const double f = f0 * k;
            if (f > taxa * 0.45) break;
            const double topo = nivelDoBin(espectro, f, 1.5 * porBin);
            double vizinhos[64];
            uint32_t n = 0;
            for (double d = 8; d <= 25 && n < 62; d += porBin) {
                const int64_t a = static_cast<int64_t>((f - d) / porBin);
                const int64_t b = static_cast<int64_t>((f + d) / porBin);
                if (a >= 1) vizinhos[n++] = espectro[static_cast<size_t>(a)];
                if (b < static_cast<int64_t>(N / 2)) vizinhos[n++] = espectro[static_cast<size_t>(b)];
            }
            if (n == 0) continue;
            const double fundo = mediana(vizinhos, n);
            notas[contadas++] = dbDePotencia(topo) - dbDePotencia(fundo);
            if (k <= 3 && notas[contadas - 1] > 6) *fortes += 1;
            if (k == 1) *fundamental = notas[contadas - 1];
        }
        if (contadas == 0) return 0;
        mediana(notas, contadas);  // ordena (crescente)
        const uint32_t usar = contadas < 3 ? contadas : 3;
        double soma_ = 0;
        for (uint32_t i = 0; i < usar; i += 1) soma_ += notas[contadas - 1 - i];
        return soma_ / usar;
    }

    /** Largura de banda: a última faixa de 1/6 de oitava que fica a menos de 60 dB dos médios. */
    double larguraDeBanda() const {
        if (quadrosDeAnalise == 0) return 0;
        const double porBin = static_cast<double>(taxa) / N;
        const double nyquist = taxa * 0.5;
        auto nivel = [&](double de, double ate) {
            int64_t a = static_cast<int64_t>(de / porBin);
            int64_t b = static_cast<int64_t>(ate / porBin);
            if (a < 1) a = 1;
            if (b > static_cast<int64_t>(N / 2)) b = N / 2;
            double s = 0;
            int64_t n = 0;
            for (int64_t k = a; k <= b; k += 1, n += 1) s += media[static_cast<size_t>(k)];
            return dbDePotencia(n ? s / static_cast<double>(n) : 0);
        };
        const double referencia = nivel(300, 3000);
        double ultima = 0;
        for (double f = 1000; f < nyquist; f *= 1.122462048309373) {
            const double topo = f * 1.122462048309373 > nyquist ? nyquist : f * 1.122462048309373;
            if (nivel(f, topo) > referencia - 60) ultima = topo;
        }
        return ultima >= nyquist * 0.97 ? nyquist : ultima;
    }

    void resultado(float* saida, uint32_t quantos) const {
        double valores[SLT_DIAGNOSIS_FIELDS];
        for (uint32_t i = 0; i < SLT_DIAGNOSIS_FIELDS; i += 1) valores[i] = 0;
        const double n = total ? static_cast<double>(total) : 1;
        const double rms = raiz((somaQuadrada[0] + somaQuadrada[1]) / (2 * n));
        double silencio = 0;
        const double piso_ = percentil(0.10, &silencio);
        const double alto = percentil(0.95, nullptr);
        uint64_t recortadas = 0;
        const double picoDb = db(pico);
        for (uint32_t k = 0; k < PLANOS; k += 1) {
            const double nivel = -24 + k * 0.1;
            if (nivel >= picoDb - 0.3) recortadas += planos[k];
        }
        const double z50 = proeminenciaDoZumbido(50);
        const double z60 = proeminenciaDoZumbido(60);
        double acima = 0;
        double tudo = QUASE_ZERO;
        for (uint32_t b = 1; b <= N / 2; b += 1) {
            tudo += media[b];
            if (static_cast<double>(b) * taxa / N >= 10000) acima += media[b];
        }
        const double correlacao = somaLR / raiz(somaQuadrada[0] * somaQuadrada[1] + QUASE_ZERO);
        valores[SLT_DIAG_DURATION_S] = static_cast<double>(total) / taxa;
        valores[SLT_DIAG_PEAK_DB] = picoDb;
        valores[SLT_DIAG_RMS_DB] = db(rms);
        valores[SLT_DIAG_CREST_DB] = picoDb - db(rms);
        valores[SLT_DIAG_DC_OFFSET] = absoluto(soma[0] / n) > absoluto(soma[1] / n) ? absoluto(soma[0] / n) : absoluto(soma[1] / n);
        valores[SLT_DIAG_CLIPPED_RATIO] = static_cast<double>(recortadas) / (2 * n);
        valores[SLT_DIAG_NOISE_FLOOR_DB] = piso_;
        valores[SLT_DIAG_HUM_HZ] = z50 >= z60 ? 50 : 60;
        valores[SLT_DIAG_HUM_DB] = z50 >= z60 ? z50 : z60;
        valores[SLT_DIAG_BANDWIDTH_HZ] = larguraDeBanda();
        valores[SLT_DIAG_CLICKS_PER_MIN] = total ? static_cast<double>(estalos) / (static_cast<double>(total) / taxa / 60.0) : 0;
        valores[SLT_DIAG_STEREO_CORRELATION] = correlacao;
        valores[SLT_DIAG_SILENCE_RATIO] = silencio;
        valores[SLT_DIAG_HIGH_BAND_DB] = dbDePotencia(acima / tudo);
        valores[SLT_DIAG_DYNAMIC_RANGE_DB] = piso_ > -200 ? alto - piso_ : 0;
        valores[SLT_DIAG_SAMPLE_RATE] = taxa;
        valores[SLT_DIAG_NOISE_FLATNESS] = planuraDoRuido();
        const double plato = nivelDasAmostras(0.995);
        valores[SLT_DIAG_LIMIT_LEVEL_DB] = plato;
        valores[SLT_DIAG_NEAR_LIMIT_RATIO] = plato > -60 ? fracaoAcimaDe(plato - 3) : 0;
        oitavas(valores + SLT_DIAG_OCTAVE_FIRST);
        valores[SLT_DIAG_HF_STABILITY_4K] = estabilidadeDaBanda(0);
        valores[SLT_DIAG_HF_STABILITY_8K] = estabilidadeDaBanda(1);
        valores[SLT_DIAG_HF_STABILITY_12K] = estabilidadeDaBanda(2);
        for (uint32_t i = 0; i < quantos && i < SLT_DIAGNOSIS_FIELDS; i += 1) saida[i] = static_cast<float>(valores[i]);
    }
};

/* ------------------------------------------------------------------ reparo */

struct ReparadorDeAudio {
    static constexpr uint32_t N = 4096;
    static constexpr uint32_t H = 1024;
    static constexpr uint32_t MAXIMO_DO_REPARO = 96;  // amostras: o que for maior não é estalo nem recorte curto

    uint32_t taxa = 0;
    float parametros[SLT_REPAIR_PARAMS];
    FluxoEspectral fluxo;
    Biquad dc;
    Biquad grave;
    Biquad zumbido[8];
    uint32_t quantosZumbidos = 0;
    Biquad ar;
    bool temDc = false;
    bool temGrave = false;
    bool temAr = false;
    // ABI 1.3.
    double ganhoDeEntrada = 1;
    Vetor<double> curva;  // ganho por bin do equilíbrio tonal
    bool temEq = false;
    double transientes = 0;
    double envRapido = 0;
    double envLento = 0;
    double ganhoDoTransiente = 0;  // dB, suavizado
    double coefRapidoA = 0, coefRapidoL = 0, coefLentoA = 0, coefLentoL = 0, coefSuave = 0;
    Vetor<float> tempo;     // [2][3H]: passado | pendente | à frente
    Vetor<float> emitido;   // [2][H]
    uint32_t posicao = 0;   // dentro do salto
    bool primeiroSalto = true;
    // Redução de ruído.
    Vetor<double> suave;
    Vetor<double> lento;  // potência bem suavizada: o mínimo dela não cai nos vales do próprio ruído
    Vetor<double> ruido;
    // Estatística de mínimos (sem perfil): o menor valor de `lento` nas últimas 4 janelas de 16 quadros (~1,5 s).
    static constexpr uint32_t JANELAS = 4;
    static constexpr uint32_t QUADROS_POR_JANELA = 16;
    Vetor<double> minimos;  // [JANELAS][B]
    Vetor<double> minimoAtual;
    uint32_t quadroNaJanela = 0;
    uint32_t janelaAtual = 0;
    Vetor<double> ganho;
    Vetor<double> ganhoSuave;
    bool espectroIniciado = false;
    Vetor<double> perfil;  // ruído de fundo por bin, vindo do diagnóstico
    bool temPerfil = false;
    double pisoDoGanho = 1;
    double excesso = 1;
    bool minimosSempre = false;  // ABI 1.4: o perfil dos trechos calmos é música (sem pausas)
    uint32_t binInicial = 0;     // ABI 1.4: a redução só age a partir daqui
    // Estalos.
    double envelopeDosEstalos[2] = {0, 0};
    // Limitador.
    double teto = 2;
    double ganhoDoLimitador = 1;
    double liberacao = 0;
    double ganhoDeSaida = 1;
    // Estatísticas.
    uint64_t estalosCorrigidos = 0;
    uint64_t recortesCorrigidos = 0;
    double somaDaReducao = 0;
    uint64_t contagemDaReducao = 0;
    double maiorLimitacao = 1;

    bool criar(uint32_t taxa_, const float* p, uint32_t quantos) {
        taxa = taxa_;
        for (uint32_t i = 0; i < SLT_REPAIR_PARAMS; i += 1) parametros[i] = i < quantos ? p[i] : 0.0f;
        if (!fluxo.criar(2, N, H) || !tempo.criar(2 * 3 * H) || !emitido.criar(2 * H) || !suave.criar(fluxo.B) || !lento.criar(fluxo.B) ||
            !ruido.criar(fluxo.B) || !minimos.criar(static_cast<size_t>(JANELAS) * fluxo.B) || !minimoAtual.criar(fluxo.B) || !ganho.criar(fluxo.B) || !ganhoSuave.criar(fluxo.B) || !perfil.criar(fluxo.B) ||
            !curva.criar(fluxo.B)) {
            soltar();
            return false;
        }
        temDc = parametros[SLT_REPAIR_DC] > 0;
        if (temDc) dc.passaAlta(taxa, 12, 0.7071);
        temGrave = parametros[SLT_REPAIR_LOW_CUT_HZ] >= 10;
        if (temGrave) grave.passaAlta(taxa, parametros[SLT_REPAIR_LOW_CUT_HZ], 0.7071);
        const double f0 = parametros[SLT_REPAIR_HUM_HZ];
        const double quantoZumbido = limitar(parametros[SLT_REPAIR_HUM_AMOUNT], 0, 1);
        if (f0 >= 40 && f0 <= 70 && quantoZumbido > 0) {
            for (uint32_t k = 1; k <= 5 && f0 * k < taxa * 0.45; k += 1) {
                // Faixa de ~2 Hz em cada harmônica (a rede varia pouco): fundo onde o zumbido mora,
                // estreito para não pegar as notas vizinhas.
                const double f = f0 * k;
                zumbido[quantosZumbidos++].pico(taxa, f, f / 2 > 20 ? f / 2 : 20, -quantoZumbido * (k <= 3 ? 30 : 20));
            }
        }
        temAr = absoluto(parametros[SLT_REPAIR_AIR_DB]) > 0.05;
        if (temAr) ar.prateleiraAlta(taxa, 9000, limitar(parametros[SLT_REPAIR_AIR_DB], -6, 6));
        const double reduzir = limitar(parametros[SLT_REPAIR_DENOISE], 0, 1);
        pisoDoGanho = dbParaLinear(-(6 + 14 * reduzir));
        excesso = 1 + 2 * reduzir;
        teto = parametros[SLT_REPAIR_CEILING_DB] < 0 ? dbParaLinear(parametros[SLT_REPAIR_CEILING_DB]) : 2;
        liberacao = exponencial(-1.0 / (0.08 * taxa));
        ganhoDeSaida = dbParaLinear(limitar(parametros[SLT_REPAIR_OUTPUT_GAIN_DB], -24, 12));
        ganhoDeEntrada = dbParaLinear(limitar(parametros[SLT_REPAIR_INPUT_GAIN_DB], -24, 12));
        minimosSempre = parametros[SLT_REPAIR_NOISE_SOURCE] >= 0.5;
        binInicial = static_cast<uint32_t>(limitar(parametros[SLT_REPAIR_DENOISE_FROM_HZ], 0, taxa * 0.45) * N / taxa);
        // Equilíbrio tonal: os ganhos por oitava viram uma curva suave em log-frequência.
        static const double CENTROS[SLT_REPAIR_EQ_BANDS] = {31.5, 63, 125, 250, 500, 1000, 2000, 4000, 8000, 16000};
        double bandas[SLT_REPAIR_EQ_BANDS];
        for (uint32_t o = 0; o < SLT_REPAIR_EQ_BANDS; o += 1) {
            bandas[o] = limitar(parametros[SLT_REPAIR_EQ_FIRST + o], -12, 12);
            if (absoluto(bandas[o]) > 0.05) temEq = true;
        }
        for (uint32_t b = 0; b < fluxo.B; b += 1) {
            const double f = static_cast<double>(b) * taxa / N;
            double g = 0;
            if (f <= CENTROS[0]) g = bandas[0];
            else if (f >= CENTROS[SLT_REPAIR_EQ_BANDS - 1]) g = bandas[SLT_REPAIR_EQ_BANDS - 1];
            else {
                uint32_t o = 0;
                while (o + 1 < SLT_REPAIR_EQ_BANDS && f > CENTROS[o + 1]) o += 1;
                const double t = (logaritmo10(f) - logaritmo10(CENTROS[o])) / (logaritmo10(CENTROS[o + 1]) - logaritmo10(CENTROS[o]));
                g = bandas[o] + (bandas[o + 1] - bandas[o]) * t;
            }
            curva[b] = dbParaLinear(g);
        }
        // Transientes: envelope rápido (0,5 ms / 30 ms) contra o lento (20 ms / 500 ms).
        transientes = limitar(parametros[SLT_REPAIR_TRANSIENTS], 0, 1);
        coefRapidoA = exponencial(-1.0 / (0.0005 * taxa));
        coefRapidoL = exponencial(-1.0 / (0.03 * taxa));
        coefLentoA = exponencial(-1.0 / (0.02 * taxa));
        coefLentoL = exponencial(-1.0 / (0.5 * taxa));
        coefSuave = exponencial(-1.0 / (0.001 * taxa));
        return true;
    }

    void soltar() {
        fluxo.soltar();
        tempo.soltar();
        emitido.soltar();
        suave.soltar();
        lento.soltar();
        ruido.soltar();
        minimos.soltar();
        minimoAtual.soltar();
        ganho.soltar();
        ganhoSuave.soltar();
        perfil.soltar();
        curva.soltar();
    }

    uint32_t latencia() const { return N + 2 * H; }

    bool definirPerfil(const float* valores, uint32_t quantos) {
        if (quantos != fluxo.B) return false;
        if (minimosSempre) return true;  // aceito e ignorado: o plano pediu a estatística de mínimos
        for (uint32_t b = 0; b < quantos; b += 1) {
            if (!(valores[b] >= 0) || valores[b] > 1e30f) return false;
            perfil[b] = valores[b];
        }
        temPerfil = true;
        return true;
    }

    void espectro(double* re, double* im) {
        if (parametros[SLT_REPAIR_DENOISE] > 0) reduzirRuido(re, im);
        if (temEq) {
            for (uint32_t c = 0; c < 2; c += 1) {
                double* r = re + static_cast<size_t>(c) * N;
                double* m = im + static_cast<size_t>(c) * N;
                for (uint32_t b = 0; b < fluxo.B; b += 1) {
                    r[b] *= curva[b];
                    m[b] *= curva[b];
                }
            }
        }
    }

    void reduzirRuido(double* re, double* im) {
        const uint32_t B = fluxo.B;
        double* l_re = re;
        double* l_im = im;
        double* r_re = re + N;
        double* r_im = im + N;
        for (uint32_t b = 0; b < B; b += 1) {
            const double p = l_re[b] * l_re[b] + l_im[b] * l_im[b] + r_re[b] * r_re[b] + r_im[b] * r_im[b];
            if (!espectroIniciado) {
                suave[b] = p;
                lento[b] = p;
                ruido[b] = 0;
                ganhoSuave[b] = 1;
                minimoAtual[b] = 1e300;
                for (uint32_t k = 0; k < JANELAS; k += 1) minimos[static_cast<size_t>(k) * B + b] = 1e300;
            } else {
                suave[b] = 0.7 * suave[b] + 0.3 * p;
                lento[b] = 0.92 * lento[b] + 0.08 * p;
            }
            if (temPerfil) {
                // O ruído medido nos trechos calmos do próprio arquivo (o diagnóstico).
                ruido[b] = perfil[b];
            } else {
                // Sem perfil: o menor nível dos últimos ~1,5 s (sobe sozinho quando o ruído aumenta).
                if (lento[b] < minimoAtual[b]) minimoAtual[b] = lento[b];
                double menor = minimoAtual[b];
                for (uint32_t k = 0; k < JANELAS; k += 1) {
                    const double m = minimos[static_cast<size_t>(k) * B + b];
                    if (m < menor) menor = m;
                }
                ruido[b] = menor;
            }
            // O mínimo de uma média ainda fica abaixo da média do ruído: compensa o viés (~1,5 dB).
            const double estimado = temPerfil ? ruido[b] : 1.4 * ruido[b];
            double g = 1 - excesso * estimado / (suave[b] + QUASE_ZERO);
            if (g < pisoDoGanho) g = pisoDoGanho;
            ganho[b] = g;
        }
        espectroIniciado = true;
        if (!temPerfil && ++quadroNaJanela == QUADROS_POR_JANELA) {
            quadroNaJanela = 0;
            for (uint32_t b = 0; b < B; b += 1) {
                minimos[static_cast<size_t>(janelaAtual) * B + b] = minimoAtual[b];
                minimoAtual[b] = 1e300;
            }
            janelaAtual = (janelaAtual + 1) % JANELAS;
        }
        for (uint32_t b = 0; b < B; b += 1) {
            // Suaviza na frequência (três vizinhos) e no tempo (abre rápido, fecha devagar): sem "ruído musical".
            const double vizinhos = (ganho[b > 0 ? b - 1 : b] + ganho[b] + ganho[b + 1 < B ? b + 1 : b]) / 3;
            ganhoSuave[b] = vizinhos > ganhoSuave[b] ? 0.3 * ganhoSuave[b] + 0.7 * vizinhos : 0.75 * ganhoSuave[b] + 0.25 * vizinhos;
            const double g = b < binInicial ? 1.0 : ganhoSuave[b];
            l_re[b] *= g;
            l_im[b] *= g;
            r_re[b] *= g;
            r_im[b] *= g;
            somaDaReducao += db(g);
            contagemDaReducao += 1;
        }
    }

    /** Estalos e recortes no trecho pendente, com o passado e o que vem à frente como contexto. */
    void repararNoTempo(float* x) {
        const double recortes = limitar(parametros[SLT_REPAIR_DECLIP], 0, 1);
        const int64_t de = H;
        const int64_t ate = 2 * H;
        if (recortes > 0) {
            const double nivel = parametros[SLT_REPAIR_CLIP_LEVEL] > 0 ? parametros[SLT_REPAIR_CLIP_LEVEL] : 0.985;
            int64_t i = de;
            while (i < ate) {
                if (absoluto(x[i]) < nivel) { i += 1; continue; }
                int64_t j = i;
                while (j + 1 < static_cast<int64_t>(3 * H) - 3 && absoluto(x[j + 1]) >= nivel && (x[j + 1] > 0) == (x[i] > 0)) j += 1;
                const int64_t comprimento = j - i + 1;
                if (comprimento >= 2 && comprimento <= MAXIMO_DO_REPARO && i >= 2) {
                    float original[MAXIMO_DO_REPARO];
                    for (int64_t k = i; k <= j; k += 1) original[k - i] = x[k];
                    const bool positivo = x[i] > 0;
                    interpolar(x, i, j);
                    for (int64_t k = i; k <= j; k += 1) {
                        // A curva tem de passar do nível do recorte, do mesmo lado; senão fica o topo.
                        double y = x[k];
                        if ((y > 0) != positivo || absoluto(y) < nivel) y = original[k - i];
                        // Teto: a curva não passa de 2× o nível do platô (seria um pico inventado).
                        if (y > 2 * nivel) y = 2 * nivel;
                        if (y < -2 * nivel) y = -2 * nivel;
                        // Pela intensidade: em 0,5, metade do caminho entre o topo plano e a curva.
                        x[k] = static_cast<float>(original[k - i] + (y - original[k - i]) * recortes);
                    }
                    recortesCorrigidos += 1;
                }
                i = j + 1;
            }
        }
    }

    void repararEstalos(float* x, uint32_t canal) {
        const double estalos = limitar(parametros[SLT_REPAIR_DECLICK], 0, 1);
        if (estalos <= 0) return;
        const double limiar = 20 - 12 * estalos;  // 20× o envelope (cauteloso) a 8×
        double& envelope = envelopeDosEstalos[canal];
        for (int64_t i = H; i < static_cast<int64_t>(2 * H); i += 1) {
            const double d = x[i] - 0.5 * (x[i - 1] + x[i + 1]);
            const double a = absoluto(d);
            // Isolado: o entorno (±4 a ±31 amostras) calmo; ataque de bateria não é estalo.
            bool isolado = a > limiar * envelope && a > 0.01 && envelope > 0 && i >= 34 && i + 34 < static_cast<int64_t>(3 * H);
            if (isolado) {
                double entorno = 0;
                for (int64_t k = 4; k < 32 && isolado; k += 1) {
                    const int64_t lados[2] = {i - k, i + k};
                    for (uint32_t s = 0; s < 2; s += 1) {
                        const int64_t p = lados[s];
                        const double v = absoluto(x[p] - 0.5 * (x[p - 1] + x[p + 1]));
                        if (v > entorno) entorno = v;
                    }
                }
                isolado = entorno < 0.35 * a;
            }
            if (isolado) {
                int64_t j = i;
                while (j + 1 < static_cast<int64_t>(3 * H) - 4 && j - i < 32) {
                    const double d2 = x[j + 1] - 0.5 * (x[j] + x[j + 2]);
                    if (absoluto(d2) < 0.5 * limiar * envelope) break;
                    j += 1;
                }
                const int64_t a0 = i - 2;
                const int64_t b0 = j + 2;
                if (a0 >= 2 && b0 + 2 < static_cast<int64_t>(3 * H)) {
                    interpolar(x, a0, b0);
                    estalosCorrigidos += 1;
                    i = b0 + 1;
                    continue;
                }
            }
            envelope = 0.995 * envelope + 0.005 * a;
        }
    }

    void salto() {
        // O trecho pendente (o do salto anterior) já tem o que vem à frente: repara e passa adiante.
        if (!primeiroSalto) {
            for (uint32_t c = 0; c < 2; c += 1) {
                float* x = tempo.dados + static_cast<size_t>(c) * 3 * H;
                repararNoTempo(x);
                repararEstalos(x, c);
            }
        }
        for (uint32_t i = 0; i < H; i += 1) {
            for (uint32_t c = 0; c < 2; c += 1) {
                const float* x = tempo.dados + static_cast<size_t>(c) * 3 * H;
                double y = fluxo.trocar(c, primeiroSalto ? 0.0 : x[H + i]);
                if (temAr) y = ar.processar(y, static_cast<int>(c));
                emitido[static_cast<size_t>(c) * H + i] = static_cast<float>(y * ganhoDeSaida);
            }
            fluxo.avancar([this](double* re, double* im) { espectro(re, im); });
        }
        // Transientes: onde o envelope rápido passa do lento, ganho (até +9 dB), o mesmo nos dois canais.
        if (transientes > 0) {
            for (uint32_t i = 0; i < H; i += 1) {
                const double l = emitido[i];
                const double r = emitido[H + i];
                const double a = absoluto(l) > absoluto(r) ? absoluto(l) : absoluto(r);
                envRapido = (a > envRapido ? coefRapidoA : coefRapidoL) * envRapido + (1 - (a > envRapido ? coefRapidoA : coefRapidoL)) * a;
                envLento = (a > envLento ? coefLentoA : coefLentoL) * envLento + (1 - (a > envLento ? coefLentoA : coefLentoL)) * a;
                double alvo = envLento > 1e-9 ? 20 * logaritmo10(envRapido / envLento + QUASE_ZERO) * 1.2 : 0;
                if (alvo < 0) alvo = 0;
                if (alvo > 9) alvo = 9;
                alvo *= transientes;
                ganhoDoTransiente = coefSuave * ganhoDoTransiente + (1 - coefSuave) * alvo;
                const double g = dbParaLinear(ganhoDoTransiente);
                emitido[i] = static_cast<float>(l * g);
                emitido[H + i] = static_cast<float>(r * g);
            }
        }
        // Limitador de pico (ataque imediato, liberação de 80 ms), com o mesmo ganho nos dois canais.
        if (teto < 1.5) {
            for (uint32_t i = 0; i < H; i += 1) {
                const double l = emitido[i];
                const double r = emitido[H + i];
                const double maior = absoluto(l) > absoluto(r) ? absoluto(l) : absoluto(r);
                double alvo = maior * ganhoDoLimitador > teto ? teto / maior : 1.0;
                if (alvo < ganhoDoLimitador) ganhoDoLimitador = alvo;
                else ganhoDoLimitador = alvo + (ganhoDoLimitador - alvo) * liberacao;
                if (ganhoDoLimitador < maiorLimitacao) maiorLimitacao = ganhoDoLimitador;
                emitido[i] = static_cast<float>(l * ganhoDoLimitador);
                emitido[H + i] = static_cast<float>(r * ganhoDoLimitador);
            }
        }
        for (uint32_t c = 0; c < 2; c += 1) {
            float* x = tempo.dados + static_cast<size_t>(c) * 3 * H;
            memmove(x, x + H, sizeof(float) * 2 * H);
            memset(x + 2 * H, 0, sizeof(float) * H);
        }
        primeiroSalto = false;
    }

    void processar(float* esquerdo, float* direito, uint32_t quadros) {
        for (uint32_t i = 0; i < quadros; i += 1) {
            float* canais[2] = {esquerdo, direito != nullptr ? direito : esquerdo};
            for (uint32_t c = 0; c < 2; c += 1) {
                double x = canais[c][i] * ganhoDeEntrada;
                if (temDc) x = dc.processar(x, static_cast<int>(c));
                if (temGrave) x = grave.processar(x, static_cast<int>(c));
                for (uint32_t k = 0; k < quantosZumbidos; k += 1) x = zumbido[k].processar(x, static_cast<int>(c));
                tempo[static_cast<size_t>(c) * 3 * H + 2 * H + posicao] = static_cast<float>(x);
            }
            const float l = emitido[posicao];
            const float r = emitido[H + posicao];
            esquerdo[i] = l;
            if (direito != nullptr) direito[i] = r;
            if (++posicao == H) {
                posicao = 0;
                salto();
            }
        }
    }

    void estatisticas(float* saida, uint32_t quantos) const {
        const double valores[SLT_REPAIR_STATS] = {
            static_cast<double>(estalosCorrigidos),
            static_cast<double>(recortesCorrigidos),
            contagemDaReducao ? somaDaReducao / static_cast<double>(contagemDaReducao) : 0,
            db(maiorLimitacao),
        };
        for (uint32_t i = 0; i < quantos && i < SLT_REPAIR_STATS; i += 1) saida[i] = static_cast<float>(valores[i]);
    }
};

}  // namespace slt

using namespace slt;

struct slt_stem_refiner_handle {
    RefinadorDeAgudos refinador;
};

struct slt_audio_analyzer_handle {
    AnalisadorDeAudio analisador;
};

struct slt_audio_repair_handle {
    ReparadorDeAudio reparador;
};

extern "C" {

SLT_API slt_stem_refiner_handle* slt_stem_refiner_create(uint32_t sample_rate, uint32_t stem_count, float cutoff_hz, float amount) {
    if (sample_rate < 8000 || sample_rate > 192000 || stem_count < 2 || stem_count > 12) return nullptr;
    if (!(cutoff_hz >= 1000) || !(cutoff_hz < sample_rate * 0.5f) || !(amount >= 0) || !(amount <= 1)) return nullptr;
    slt_stem_refiner_handle* h = criarObjeto<slt_stem_refiner_handle>();
    if (h == nullptr) return nullptr;
    if (!h->refinador.criar(sample_rate, stem_count, cutoff_hz, amount)) {
        h->refinador.soltar();
        liberar(h);
        return nullptr;
    }
    return h;
}

SLT_API uint32_t slt_stem_refiner_latency(const slt_stem_refiner_handle* refiner) { return refiner != nullptr ? refiner->refinador.latencia() : 0; }

SLT_API slt_result slt_stem_refiner_process(slt_stem_refiner_handle* refiner, float* planar, uint32_t frame_count) {
    if (refiner == nullptr || (planar == nullptr && frame_count > 0)) return SLT_ERROR_INVALID_ARGUMENT;
    refiner->refinador.processar(planar, frame_count);
    return SLT_OK;
}

SLT_API void slt_stem_refiner_destroy(slt_stem_refiner_handle* refiner) {
    if (refiner == nullptr) return;
    refiner->refinador.soltar();
    liberar(refiner);
}

SLT_API slt_audio_analyzer_handle* slt_audio_analyzer_create(uint32_t sample_rate) {
    if (sample_rate < 8000 || sample_rate > 192000) return nullptr;
    slt_audio_analyzer_handle* h = criarObjeto<slt_audio_analyzer_handle>();
    if (h == nullptr) return nullptr;
    if (!h->analisador.criar(sample_rate)) {
        liberar(h);
        return nullptr;
    }
    return h;
}

SLT_API slt_result slt_audio_analyzer_feed(slt_audio_analyzer_handle* analyzer, const float* left, const float* right, uint32_t frame_count) {
    if (analyzer == nullptr || (left == nullptr && frame_count > 0)) return SLT_ERROR_INVALID_ARGUMENT;
    analyzer->analisador.alimentar(left, right, frame_count);
    return SLT_OK;
}

SLT_API slt_result slt_audio_analyzer_result(const slt_audio_analyzer_handle* analyzer, float* values, uint32_t capacity) {
    if (analyzer == nullptr || values == nullptr) return SLT_ERROR_INVALID_ARGUMENT;
    if (capacity < SLT_DIAGNOSIS_FIELDS_1_2) return SLT_ERROR_BUFFER_TOO_SMALL;
    analyzer->analisador.resultado(values, capacity);
    return SLT_OK;
}

SLT_API slt_result slt_audio_analyzer_noise_profile(const slt_audio_analyzer_handle* analyzer, float* values, uint32_t capacity) {
    if (analyzer == nullptr || values == nullptr) return SLT_ERROR_INVALID_ARGUMENT;
    if (capacity < SLT_NOISE_PROFILE_BINS) return SLT_ERROR_BUFFER_TOO_SMALL;
    double perfil[SLT_NOISE_PROFILE_BINS];
    analyzer->analisador.perfilDeRuido(perfil);
    for (uint32_t b = 0; b < SLT_NOISE_PROFILE_BINS; b += 1) values[b] = static_cast<float>(perfil[b]);
    return SLT_OK;
}

SLT_API void slt_audio_analyzer_destroy(slt_audio_analyzer_handle* analyzer) {
    if (analyzer == nullptr) return;
    analyzer->analisador.soltar();
    liberar(analyzer);
}

SLT_API slt_audio_repair_handle* slt_audio_repair_create(uint32_t sample_rate, const float* params, uint32_t param_count) {
    if (sample_rate < 8000 || sample_rate > 192000 || (params == nullptr && param_count > 0)) return nullptr;
    for (uint32_t i = 0; i < param_count; i += 1) {
        if (!(params[i] == params[i]) || params[i] > 1e6f || params[i] < -1e6f) return nullptr;
    }
    slt_audio_repair_handle* h = criarObjeto<slt_audio_repair_handle>();
    if (h == nullptr) return nullptr;
    if (!h->reparador.criar(sample_rate, params, param_count)) {
        liberar(h);
        return nullptr;
    }
    return h;
}

SLT_API slt_result slt_audio_repair_set_noise_profile(slt_audio_repair_handle* repair, const float* profile, uint32_t count) {
    if (repair == nullptr || profile == nullptr) return SLT_ERROR_INVALID_ARGUMENT;
    return repair->reparador.definirPerfil(profile, count) ? SLT_OK : SLT_ERROR_INVALID_ARGUMENT;
}

SLT_API uint32_t slt_audio_repair_latency(const slt_audio_repair_handle* repair) { return repair != nullptr ? repair->reparador.latencia() : 0; }

SLT_API slt_result slt_audio_repair_process(slt_audio_repair_handle* repair, float* left, float* right, uint32_t frame_count) {
    if (repair == nullptr || (left == nullptr && frame_count > 0)) return SLT_ERROR_INVALID_ARGUMENT;
    repair->reparador.processar(left, right, frame_count);
    return SLT_OK;
}

SLT_API slt_result slt_audio_repair_stats(const slt_audio_repair_handle* repair, float* values, uint32_t capacity) {
    if (repair == nullptr || values == nullptr) return SLT_ERROR_INVALID_ARGUMENT;
    if (capacity < SLT_REPAIR_STATS) return SLT_ERROR_BUFFER_TOO_SMALL;
    repair->reparador.estatisticas(values, capacity);
    return SLT_OK;
}

SLT_API void slt_audio_repair_destroy(slt_audio_repair_handle* repair) {
    if (repair == nullptr) return;
    repair->reparador.soltar();
    liberar(repair);
}

}  // extern "C"
