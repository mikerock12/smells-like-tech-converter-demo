// O mixer nativo contra os casos compartilhados da criação musical
// (packages/converter-core/musica-casos/*.json): o "esperado" de cada render foi gravado
// pela referência em JS, e o C# e o Kotlin conferem os mesmos arquivos.
// Também: o leitor de JSON, a troca de projeto com o áudio rodando e o transporte.
#define _CRT_SECURE_NO_WARNINGS
#include <math.h>
#include <stdio.h>
#include <string.h>
#include <atomic>
#include <string>
#include <thread>
#include <vector>

#include "../src/misturador.h"

using namespace slt;

static int falhasDoMixer = 0;
static int conferenciasDoMixer = 0;

#define CONFERIR(condicao, ...)                           \
    do {                                                  \
        conferenciasDoMixer += 1;                         \
        if (!(condicao)) {                                \
            falhasDoMixer += 1;                           \
            printf("FALHOU %s:%d: ", __FILE__, __LINE__); \
            printf(__VA_ARGS__);                          \
            printf("\n");                                 \
        }                                                 \
    } while (0)

static bool perto(double a, double b, double absoluta, double relativa = 0) { return fabs(a - b) <= absoluta + relativa * fabs(b); }

static std::string lerArquivo(const std::string& caminho) {
    std::string texto;
    FILE* arquivo = fopen(caminho.c_str(), "rb");
    if (arquivo == nullptr) return texto;
    char bloco[65536];
    size_t n;
    while ((n = fread(bloco, 1, sizeof(bloco), arquivo)) > 0) texto.append(bloco, n);
    fclose(arquivo);
    return texto;
}

static std::string textoDe(const No* no) { return no != nullptr && no->tipo == TipoDeNo::Texto ? std::string(no->texto, no->tamanho) : std::string(); }

/** gerarMidia de musica-casos.mjs: seno, ruído (xorshift32) ou impulsos. */
static std::vector<std::vector<float>> gerarMidia(const No& gerador, uint32_t canais, uint32_t quadros, double taxa) {
    std::vector<std::vector<float>> saida;
    const double amplitude = gerador.numeroDe("amplitude", 0.5);
    const No* tipo = gerador.campo("tipo");
    for (uint32_t c = 0; c < canais; c += 1) {
        std::vector<float> x(quadros, 0.0f);
        if (tipo != nullptr && tipo->textoIgual("seno")) {
            const double f2 = gerador.numeroDe("frequencia2", 0);
            const double f = c == 1 && f2 != 0 ? f2 : gerador.numeroDe("frequencia", 0);
            for (uint32_t i = 0; i < quadros; i += 1) x[i] = static_cast<float>(amplitude * sin((2 * PI * f * i) / taxa));
        } else if (tipo != nullptr && tipo->textoIgual("ruido")) {
            uint32_t estado = static_cast<uint32_t>(gerador.numeroDe("semente", 1)) + c;
            if (estado == 0) estado = 1;
            for (uint32_t i = 0; i < quadros; i += 1) {
                estado ^= estado << 13;
                estado ^= estado >> 17;
                estado ^= estado << 5;
                x[i] = static_cast<float>(amplitude * ((estado / 4294967296.0) * 2 - 1));
            }
        } else if (tipo != nullptr && tipo->textoIgual("impulsos")) {
            const uint32_t periodo = static_cast<uint32_t>(gerador.numeroDe("periodo", 1));
            for (uint32_t i = static_cast<uint32_t>(gerador.numeroDe("inicio", 0)); i < quadros; i += periodo > 0 ? periodo : 1) x[i] = static_cast<float>(amplitude);
        }
        saida.push_back(std::move(x));
    }
    return saida;
}

static void conferirCaso(const char* arquivo) {
    const std::string texto = lerArquivo(std::string(SLT_CASOS) + "/" + arquivo);
    CONFERIR(!texto.empty(), "%s: não abriu (SLT_CASOS=%s)", arquivo, SLT_CASOS);
    if (texto.empty()) return;
    Documento caso;
    CONFERIR(caso.analisar(texto.data(), texto.size()), "%s: JSON inválido", arquivo);
    if (caso.raiz == nullptr) return;
    const No* projeto = caso.raiz->campo("projeto");
    const No* renders = caso.raiz->campo("renders");
    const No* esperado = caso.raiz->campo("esperado");
    const No* esperados = esperado != nullptr ? esperado->campo("renders") : nullptr;
    CONFERIR(projeto != nullptr && renders != nullptr && esperados != nullptr, "%s: caso sem renders", arquivo);
    if (projeto == nullptr || renders == nullptr || esperados == nullptr) {
        caso.soltar();
        return;
    }
    const double taxa = projeto->numeroDe("taxa", 48000);
    // As mídias do caso, geradas como no JS.
    std::vector<std::string> chaves;
    std::vector<std::vector<std::vector<float>>> midias;
    const No* geradores = caso.raiz->campo("midias");
    const No* listaDeMidias = projeto->campo("midias");
    for (const No* midia = listaDeMidias != nullptr ? listaDeMidias->filho : nullptr; midia != nullptr; midia = midia->proximo) {
        const std::string id = textoDe(midia->campo("id"));
        const No* gerador = geradores != nullptr ? geradores->campo(id.c_str()) : nullptr;
        if (gerador == nullptr) continue;
        chaves.push_back(id);
        midias.push_back(gerarMidia(*gerador, static_cast<uint32_t>(midia->numeroDe("canais", 1)), static_cast<uint32_t>(midia->numeroDe("quadros", 0)), taxa));
    }
    const No* resultado = esperados->filho;
    int indice = 0;
    for (const No* pedido = renders->filho; pedido != nullptr && resultado != nullptr; pedido = pedido->proximo, resultado = resultado->proximo, indice += 1) {
        // Cada render do JS começa com um misturador novo (efeitos sem cauda anterior).
        Misturador* mixer = criarObjeto<Misturador>();
        CONFERIR(mixer != nullptr && mixer->criar(static_cast<uint32_t>(taxa)), "%s: misturador não criado", arquivo);
        for (size_t i = 0; i < chaves.size(); i += 1) mixer->definirFonte(chaves[i].c_str(), midias[i][0].data(), midias[i].size() > 1 ? midias[i][1].data() : nullptr, midias[i][0].size());
        CONFERIR(mixer->definirProjeto(*projeto) == SLT_OK, "%s: projeto recusado", arquivo);
        const uint32_t quadros = static_cast<uint32_t>(pedido->numeroDe("quadros", 0));
        const int64_t inicio = static_cast<int64_t>(pedido->numeroDe("inicio", 0));
        OpcoesDeMixagem opcoes;
        opcoes.metronomo = pedido->booleanoDe("metronomo", false);
        opcoes.semMaster = pedido->booleanoDe("semMaster", false);
        const std::string somente = textoDe(pedido->campo("somente"));
        if (!somente.empty()) {
            opcoes.somente = mixer->indiceDaFaixa(somente.c_str());
            CONFERIR(opcoes.somente >= 0, "%s render %d: faixa %s não existe", arquivo, indice, somente.c_str());
        }
        std::vector<float> esq(quadros), dir(quadros);
        // Em pedaços de 4096, como `renderizar` do JS (múltiplo do bloco de 128).
        for (uint32_t feito = 0; feito < quadros; feito += 4096) {
            const uint32_t k = quadros - feito < 4096 ? quadros - feito : 4096;
            mixer->misturar(inicio + feito, k, esq.data() + feito, dir.data() + feito, opcoes);
        }
        double soma = 0, energia = 0, pico = 0;
        for (uint32_t i = 0; i < quadros; i += 1) {
            soma += static_cast<double>(esq[i]) + dir[i];
            energia += static_cast<double>(esq[i]) * esq[i] + static_cast<double>(dir[i]) * dir[i];
            pico = fmax(pico, fmax(fabs(esq[i]), fabs(dir[i])));
        }
        CONFERIR(perto(soma, resultado->numeroDe("soma", NAN), 5e-3, 1e-4), "%s render %d (%s): soma %.6f, esperada %.6f", arquivo, indice, somente.c_str(), soma, resultado->numeroDe("soma", NAN));
        CONFERIR(perto(energia, resultado->numeroDe("energia", NAN), 5e-3, 1e-4), "%s render %d (%s): energia %.6f, esperada %.6f", arquivo, indice, somente.c_str(), energia, resultado->numeroDe("energia", NAN));
        CONFERIR(perto(pico, resultado->numeroDe("pico", NAN), 2e-5), "%s render %d (%s): pico %.6f, esperado %.6f", arquivo, indice, somente.c_str(), pico, resultado->numeroDe("pico", NAN));
        const No* amostras = resultado->campo("amostras");
        int erradas = 0;
        for (const No* amostra = amostras != nullptr ? amostras->filho : nullptr; amostra != nullptr; amostra = amostra->proximo) {
            const No* i = amostra->filho;
            if (i == nullptr || i->proximo == nullptr || i->proximo->proximo == nullptr) continue;
            const uint32_t posicao = static_cast<uint32_t>(i->numero);
            if (posicao >= quadros) continue;
            if (!perto(esq[posicao], i->proximo->numero, 2e-5) || !perto(dir[posicao], i->proximo->proximo->numero, 2e-5)) {
                if (erradas == 0) printf("  %s render %d (%s) amostra %u: %.6f/%.6f, esperadas %.6f/%.6f\n", arquivo, indice, somente.c_str(), posicao, esq[posicao], dir[posicao], i->proximo->numero, i->proximo->proximo->numero);
                erradas += 1;
            }
        }
        CONFERIR(erradas == 0, "%s render %d (%s): %d amostras diferentes", arquivo, indice, somente.c_str(), erradas);
        mixer->soltar();
        liberar(mixer);
    }
    caso.soltar();
}

static void testarJson() {
    Documento d;
    const char* texto = "{\"a\": [1, -2.5, 3e2, 0.1], \"t\": \"l\\u00e1 \\\"x\\\"\\n\", \"v\": true, \"n\": null, \"o\": {\"k\": 7}}";
    CONFERIR(d.analisar(texto, strlen(texto)), "JSON válido recusado");
    if (d.raiz != nullptr) {
        const No* a = d.raiz->campo("a");
        CONFERIR(a != nullptr && a->quantos() == 4, "lista");
        CONFERIR(a->filho->numero == 1 && a->filho->proximo->numero == -2.5 && a->filho->proximo->proximo->numero == 300 && a->filho->proximo->proximo->proximo->numero == 0.1, "números");
        const No* t = d.raiz->campo("t");
        CONFERIR(t != nullptr && std::string(t->texto, t->tamanho) == "l\xC3\xA1 \"x\"\n", "escapes do texto");
        CONFERIR(d.raiz->booleanoDe("v", false) && d.raiz->campo("n")->tipo == TipoDeNo::Nulo, "booleano e nulo");
        CONFERIR(d.raiz->campo("o")->numeroDe("k", 0) == 7 && d.raiz->numeroDe("falta", 42) == 42, "objeto e padrão");
    }
    d.soltar();
    for (const char* ruim : {"", "{", "[1,]", "{\"a\" 1}", "[1] x", "\"sem fim", "{\"a\": tru}", "[1e]"}) CONFERIR(!d.analisar(ruim, strlen(ruim)), "JSON inválido aceito: %s", ruim);
    std::string fundo(200, '[');
    CONFERIR(!d.analisar(fundo.data(), fundo.size()), "aninhamento sem limite aceito");
    d.soltar();
}

static std::string projetoDeTeste(double ganhoDb, bool comAtraso, double tamanhoDoReverb) {
    char texto[2048];
    snprintf(texto, sizeof(texto),
             "{\"taxa\":48000,\"bpm\":120,\"compasso\":[4,4],\"metronomo\":{\"volumeDb\":-6},"
             "\"barramentos\":[{\"id\":\"bus\",\"ganhoDb\":0,\"pan\":0,\"mudo\":false,\"efeitos\":[{\"id\":\"rev\",\"tipo\":\"reverberacao\",\"ligado\":true,\"tamanho\":%.3f,\"mistura\":1}]}],"
             "\"master\":{\"ganhoDb\":0,\"efeitos\":[{\"id\":\"lim\",\"tipo\":\"limitador\",\"ligado\":true,\"tetoDb\":-1}]},"
             "\"faixas\":[{\"id\":\"f1\",\"ganhoDb\":%.3f,\"pan\":-20,\"mudo\":false,\"solo\":false,\"saida\":\"master\","
             "\"envios\":[{\"barramento\":\"bus\",\"nivelDb\":-6,\"preFader\":false}],"
             "\"efeitos\":[%s{\"id\":\"eq\",\"tipo\":\"equalizador\",\"ligado\":true,\"gravesDb\":3}],"
             "\"clipes\":[{\"midia\":\"m\",\"inicio\":0,\"inicioNaMidia\":0,\"duracao\":48000,\"ganhoDb\":0,\"entradaSuave\":0,\"saidaSuave\":0,\"mudo\":false}]}]}",
             tamanhoDoReverb, ganhoDb, comAtraso ? "{\"id\":\"dl\",\"tipo\":\"atraso\",\"ligado\":true,\"tempoMs\":120,\"retorno\":0.4,\"mistura\":0.3}," : "");
    return texto;
}

/** Trocar o projeto não corta a cauda: o processador que continua (pelo id) é o mesmo. */
static void testarTrocaDeProjeto() {
    std::vector<float> midia(48000);
    for (size_t i = 0; i < midia.size(); i += 1) midia[i] = static_cast<float>(0.3 * sin(2 * PI * 440.0 * i / 48000));
    slt_mixer_handle* mixer = slt_mixer_create(48000);
    CONFERIR(mixer != nullptr, "mixer não criado");
    if (mixer == nullptr) return;
    CONFERIR(slt_mixer_set_source(mixer, "m", midia.data(), nullptr, midia.size()) == SLT_OK, "fonte");
    std::string a = projetoDeTeste(0, false, 0.8);
    CONFERIR(slt_mixer_set_project(mixer, a.data(), static_cast<uint32_t>(a.size())) == SLT_OK, "projeto A");
    std::vector<float> e(48000), d(48000);
    CONFERIR(slt_mixer_render(mixer, 0, 48000, e.data(), d.data(), nullptr) == SLT_OK, "render A");
    float medidor[4] = {0, 0, 0, 0};
    CONFERIR(slt_mixer_meters(mixer, "master", medidor) == SLT_OK && medidor[0] > 0.05f && medidor[2] > 0.01f, "medidor do master %.4f/%.4f", medidor[0], medidor[2]);
    CONFERIR(slt_mixer_meters(mixer, "f1", medidor) == SLT_OK && slt_mixer_meters(mixer, "bus", medidor) == SLT_OK, "medidores de faixa e barramento");
    CONFERIR(slt_mixer_meters(mixer, "nada", medidor) == SLT_ERROR_INVALID_ARGUMENT, "medidor de id inexistente");
    // Depois do fim do clipe só sobra a cauda do reverb. Troca o projeto (fader, efeito novo,
    // parâmetro do reverb) no meio da cauda: ela continua, sem salto nem silêncio.
    std::vector<float> c1(4800), c2(4800), lixo(4800);
    slt_mixer_render(mixer, 48000, 4800, c1.data(), lixo.data(), nullptr);
    std::string b = projetoDeTeste(-3, true, 0.82);
    CONFERIR(slt_mixer_set_project(mixer, b.data(), static_cast<uint32_t>(b.size())) == SLT_OK, "projeto B");
    slt_mixer_render(mixer, 52800, 4800, c2.data(), lixo.data(), nullptr);
    double antes = 0, depois = 0;
    for (int i = 0; i < 480; i += 1) {
        antes += static_cast<double>(c1[4320 + i]) * c1[4320 + i];
        depois += static_cast<double>(c2[i]) * c2[i];
    }
    CONFERIR(antes > 1e-8, "não havia cauda de reverb para conferir (%.3g)", antes);
    CONFERIR(depois > antes * 0.3 && depois < antes * 3, "a cauda mudou de %.3g para %.3g na troca do projeto", antes, depois);
    CONFERIR(fabs(c2[0] - c1[4799]) < 0.02, "salto de %.4f na troca do projeto", fabs(c2[0] - c1[4799]));
    // Projeto inválido não troca o que toca.
    const char* ruim = "{\"faixas\":[{\"id\":\"x\",\"efeitos\":[{\"id\":\"e\",\"tipo\":\"inexistente\"}],\"clipes\":[]}]}";
    CONFERIR(slt_mixer_set_project(mixer, ruim, static_cast<uint32_t>(strlen(ruim))) == SLT_ERROR_INVALID_ARGUMENT, "efeito desconhecido aceito");
    CONFERIR(slt_mixer_set_project(mixer, "{", 1) == SLT_ERROR_INVALID_ARGUMENT, "JSON quebrado aceito");
    CONFERIR(slt_mixer_meters(mixer, "f1", medidor) == SLT_OK, "o projeto anterior devia continuar valendo");
    slt_mix_options opcoes = {};
    opcoes.struct_size = sizeof(opcoes);
    opcoes.only_track = "nada";
    CONFERIR(slt_mixer_render(mixer, 0, 128, e.data(), d.data(), &opcoes) == SLT_ERROR_INVALID_ARGUMENT, "faixa inexistente aceita");
    // Sem a fonte, o clipe fica mudo (e nada quebra).
    slt_mixer_set_source(mixer, "m", nullptr, nullptr, 0);
    CONFERIR(slt_mixer_refresh(mixer) == SLT_OK, "refresh");
    slt_mixer_destroy(mixer);
    slt_mixer_destroy(nullptr);
}

static void testarTransporte() {
    std::vector<float> midia(48000);
    for (size_t i = 0; i < midia.size(); i += 1) midia[i] = static_cast<float>(i) / 48000.0f;  // rampa: a amostra diz a posição
    slt_mixer_handle* mixer = slt_mixer_create(48000);
    if (mixer == nullptr) return;
    slt_mixer_set_source(mixer, "m", midia.data(), nullptr, midia.size());
    const char* projeto = "{\"taxa\":48000,\"bpm\":120,\"compasso\":[4,4],\"barramentos\":[],\"master\":{\"ganhoDb\":0,\"efeitos\":[]},\"faixas\":[{\"id\":\"f\",\"ganhoDb\":0,\"pan\":-100,\"saida\":\"master\",\"gravar\":true,\"monitorar\":true,\"efeitos\":[],\"envios\":[],\"clipes\":[{\"midia\":\"m\",\"inicio\":0,\"inicioNaMidia\":0,\"duracao\":48000}]}]}";
    CONFERIR(slt_mixer_set_project(mixer, projeto, static_cast<uint32_t>(strlen(projeto))) == SLT_OK, "projeto do transporte");
    std::vector<float> e(480), d(480);
    CONFERIR(slt_mixer_process(mixer, nullptr, nullptr, e.data(), d.data(), 480) == SLT_OK, "parado");
    CONFERIR(e[0] == 0 && e[479] == 0 && slt_mixer_position(mixer) == 0, "parado devia ser silêncio sem avançar");
    slt_mixer_play(mixer, 1000);
    slt_mixer_process(mixer, nullptr, nullptr, e.data(), d.data(), 480);
    CONFERIR(perto(e[0], 1000 / 48000.0, 1e-6) && perto(e[479], 1479 / 48000.0, 1e-6) && d[0] == 0, "tocando de 1000: %.6f..%.6f", e[0], e[479]);
    CONFERIR(slt_mixer_position(mixer) == 1480, "posição %lld", static_cast<long long>(slt_mixer_position(mixer)));
    // Loop de 2000 a 2300: o bloco atravessa o fim e volta ao início, sem buraco.
    slt_mixer_set_loop(mixer, 2000, 2300);
    slt_mixer_play(mixer, 2200);
    slt_mixer_process(mixer, nullptr, nullptr, e.data(), d.data(), 480);
    CONFERIR(perto(e[99], 2299 / 48000.0, 1e-6) && perto(e[100], 2000 / 48000.0, 1e-6) && perto(e[400], 2000 / 48000.0, 1e-6), "loop: %.6f %.6f %.6f", e[99], e[100], e[400]);
    CONFERIR(slt_mixer_position(mixer) == 2080, "posição no loop %lld", static_cast<long long>(slt_mixer_position(mixer)));
    slt_mixer_set_loop(mixer, 0, 0);
    // Entrada ao vivo numa faixa armada com MON.
    std::vector<float> entrada(480, 0.25f);
    slt_mixer_play(mixer, 100000);  // depois do clipe: só a entrada
    slt_mixer_process(mixer, entrada.data(), nullptr, e.data(), d.data(), 480);
    CONFERIR(perto(e[10], 0.25, 1e-6), "monitoração da entrada: %.4f", e[10]);
    // Metrônomo: o clique do tempo forte cai no quadro 0.
    slt_mixer_set_metronome(mixer, 1);
    slt_mixer_play(mixer, 96000);
    slt_mixer_process(mixer, nullptr, nullptr, e.data(), d.data(), 480);
    double clique = 0;
    for (float v : d) clique += fabs(v);
    CONFERIR(clique > 1, "o metrônomo não soou (%.4f)", clique);
    slt_mixer_stop(mixer);
    CONFERIR(slt_mixer_play(mixer, -5) == SLT_ERROR_INVALID_ARGUMENT, "posição negativa aceita");
    slt_mixer_destroy(mixer);
}

/** Uma thread no papel do áudio e outra trocando o projeto sem parar: sem trava, sem queda. */
static void testarDuasThreads() {
    std::vector<float> midia(48000);
    for (size_t i = 0; i < midia.size(); i += 1) midia[i] = static_cast<float>(0.2 * sin(2 * PI * 220.0 * i / 48000));
    slt_mixer_handle* mixer = slt_mixer_create(48000);
    if (mixer == nullptr) return;
    slt_mixer_set_source(mixer, "m", midia.data(), nullptr, midia.size());
    std::string inicial = projetoDeTeste(0, false, 0.5);
    slt_mixer_set_project(mixer, inicial.data(), static_cast<uint32_t>(inicial.size()));
    slt_mixer_set_loop(mixer, 0, 48000);
    slt_mixer_play(mixer, 0);
    std::atomic<bool> parar{false};
    std::atomic<int> invalidos{0};
    std::atomic<int> blocos{0};
    std::thread audio([&] {
        std::vector<float> e(256), d(256);
        while (!parar.load()) {
            slt_mixer_process(mixer, nullptr, nullptr, e.data(), d.data(), 256);
            for (int i = 0; i < 256; i += 1)
                if (!isfinite(e[i]) || !isfinite(d[i]) || fabs(e[i]) > 4) invalidos += 1;
            blocos += 1;
        }
    });
    int publicados = 0;
    int medidoresInvalidos = 0;
    for (int i = 0; i < 400; i += 1) {
        float medidor[4]{};
        if (slt_mixer_meters(mixer, "master", medidor) != SLT_OK ||
            !isfinite(medidor[0]) || !isfinite(medidor[1]) || !isfinite(medidor[2]) || !isfinite(medidor[3]) ||
            medidor[2] > medidor[0] + 1e-6f || medidor[3] > medidor[1] + 1e-6f) medidoresInvalidos += 1;
        std::string p = projetoDeTeste(-(i % 12), i % 3 == 0, 0.3 + (i % 7) * 0.1);
        if (slt_mixer_set_project(mixer, p.data(), static_cast<uint32_t>(p.size())) == SLT_OK) publicados += 1;
        if (i % 16 == 0) std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
    while (blocos.load() < 200) std::this_thread::sleep_for(std::chrono::milliseconds(1));
    parar = true;
    audio.join();
    CONFERIR(publicados == 400, "só %d de 400 projetos publicados", publicados);
    CONFERIR(invalidos.load() == 0, "%d amostras inválidas com o projeto trocando", invalidos.load());
    CONFERIR(medidoresInvalidos == 0, "%d leituras inválidas dos medidores", medidoresInvalidos);
    slt_mixer_destroy(mixer);
}

int testarMixer() {
    testarJson();
    for (const char* arquivo : {"01-tres-faixas.json", "02-solo-e-mudo.json", "03-efeitos.json", "04-automacao-e-rampas.json", "05-barramentos.json", "06-metronomo-e-gravacao.json", "07-master.json", "13-pico-real.json"}) conferirCaso(arquivo);
    testarTrocaDeProjeto();
    testarTransporte();
    testarDuasThreads();
    printf("mixer: %d conferencias, %d falhas\n", conferenciasDoMixer, falhasDoMixer);
    return falhasDoMixer;
}
