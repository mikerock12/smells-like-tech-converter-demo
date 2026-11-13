// Dispositivos de áudio no Windows:
//  - WASAPI no aparelho real desta máquina (em silêncio absoluto: o master do projeto fica em
//    −60 dB, que o fader trata como zero). Sem aparelho de saída (CI), o teste avisa e pula.
//  - ASIO com um driver simulado dentro do teste (a mesma vtable de um driver de verdade),
//    nos cinco formatos de amostra: conversões, captura com posição, latências e reinício.
#define _CRT_SECURE_NO_WARNINGS
#include <math.h>
#include <stdio.h>
#include <string.h>
#include <atomic>
#include <string>
#include <thread>
#include <vector>

#include "../src/platform/windows/asio_interno.h"

using namespace slt;

static int falhasDoWindows = 0;
static int conferenciasDoWindows = 0;

#define CONFERIR(condicao, ...)                           \
    do {                                                  \
        conferenciasDoWindows += 1;                       \
        if (!(condicao)) {                                \
            falhasDoWindows += 1;                         \
            printf("FALHOU %s:%d: ", __FILE__, __LINE__); \
            printf(__VA_ARGS__);                          \
            printf("\n");                                 \
        }                                                 \
    } while (0)

static const char* PROJETO_SILENCIOSO =
    "{\"taxa\":48000,\"bpm\":120,\"compasso\":[4,4],\"barramentos\":[],\"master\":{\"ganhoDb\":-60,\"efeitos\":[]},"
    "\"faixas\":[{\"id\":\"f\",\"ganhoDb\":0,\"pan\":0,\"saida\":\"master\",\"gravar\":true,\"monitorar\":true,\"efeitos\":[{\"id\":\"rev\",\"tipo\":\"reverberacao\",\"ligado\":true}],\"envios\":[],"
    "\"clipes\":[{\"midia\":\"m\",\"inicio\":0,\"inicioNaMidia\":0,\"duracao\":480000}]}]}";

static void testarWasapi() {
    CONFERIR((slt_device_backends() & (1u << SLT_BACKEND_WASAPI)) != 0, "WASAPI não está compilado");
    std::vector<slt_device_info> aparelhos(64);
    uint32_t quantos = 0;
    const slt_result listou = slt_device_list(SLT_BACKEND_WASAPI, aparelhos.data(), 64, &quantos);
    CONFERIR(listou == SLT_OK, "listar: %s", slt_result_message(listou));
    bool temSaida = false;
    bool temEntrada = false;
    for (uint32_t i = 0; i < quantos && i < 64; i += 1) {
        const slt_device_info& a = aparelhos[i];
        printf("  wasapi: %s%s | %u Hz | saida %u | entrada %u\n", a.name, a.is_default ? " (padrao)" : "", a.sample_rate, a.output_channels, a.input_channels);
        temSaida = temSaida || a.output_channels > 0;
        temEntrada = temEntrada || a.input_channels > 0;
        CONFERIR(a.id[0] != 0 && a.name[0] != 0, "aparelho sem id ou nome");
    }
    if (!temSaida) {
        printf("  wasapi: sem aparelho de saida nesta maquina; teste do fluxo pulado\n");
        return;
    }
    std::vector<float> midia(480000);
    for (size_t i = 0; i < midia.size(); i += 1) midia[i] = static_cast<float>(0.5 * sin(2 * PI * 440.0 * i / 48000));
    slt_mixer_handle* mixer = slt_mixer_create(48000);
    slt_mixer_set_source(mixer, "m", midia.data(), nullptr, midia.size());
    CONFERIR(slt_mixer_set_project(mixer, PROJETO_SILENCIOSO, static_cast<uint32_t>(strlen(PROJETO_SILENCIOSO))) == SLT_OK, "projeto");
    slt_stream_config config = {};
    config.struct_size = sizeof(config);
    config.backend = SLT_BACKEND_WASAPI;
    config.use_input = temEntrada ? 1 : 0;
    slt_stream_handle* fluxo = nullptr;
    const slt_result abriu = slt_stream_open(&config, mixer, &fluxo);
    CONFERIR(abriu == SLT_OK && fluxo != nullptr, "abrir o aparelho padrao: %s", slt_result_message(abriu));
    if (fluxo == nullptr) {
        slt_mixer_destroy(mixer);
        return;
    }
    slt_stream_info info = {};
    slt_stream_get_info(fluxo, &info);
    printf("  wasapi: bloco %u quadros (%.2f ms)%s | latencia de saida %u quadros (%.1f ms) | entrada %u canais, latencia %u quadros\n", info.buffer_frames, info.buffer_frames * 1000.0 / info.sample_rate, info.low_latency ? ", baixa latencia" : "",
           info.output_latency_frames, info.output_latency_frames * 1000.0 / info.sample_rate, info.input_channels, info.input_latency_frames);
    CONFERIR(info.sample_rate == 48000 && info.output_channels == 2 && info.buffer_frames > 0 && info.buffer_frames <= 48000, "info do fluxo");
    if (info.input_channels > 0) CONFERIR(slt_stream_capture(fluxo, 1) == SLT_OK, "ligar a captura");
    else CONFERIR(slt_stream_capture(fluxo, 1) == SLT_ERROR_INVALID_STATE, "captura sem entrada devia ser recusada");
    slt_mixer_play(mixer, 0);
    CONFERIR(slt_stream_start(fluxo) == SLT_OK, "iniciar");
    // Três trocas de projeto com o aparelho tocando, e a captura sendo lida como um gravador faria.
    std::vector<float> e(4096), d(4096);
    uint64_t capturados = 0;
    int64_t ultimaPosicao = -1;
    bool posicoesEmOrdem = true;
    for (int passo = 0; passo < 30; passo += 1) {
        std::this_thread::sleep_for(std::chrono::milliseconds(50));
        if (passo % 10 == 5) slt_mixer_set_project(mixer, PROJETO_SILENCIOSO, static_cast<uint32_t>(strlen(PROJETO_SILENCIOSO)));
        int64_t posicao = 0;
        uint32_t n;
        while ((n = slt_stream_read(fluxo, e.data(), d.data(), 4096, &posicao)) > 0) {
            capturados += n;
            if (posicao >= 0) {
                posicoesEmOrdem = posicoesEmOrdem && posicao > ultimaPosicao;
                ultimaPosicao = posicao;
            }
        }
    }
    slt_stream_stats stats = {};
    slt_stream_get_stats(fluxo, &stats);
    slt_stream_get_info(fluxo, &info);
    const int64_t posicaoFinal = slt_mixer_position(mixer);
    printf("  wasapi: 1,5 s tocando: %llu callbacks, %llu quadros, %u falhas, callback medio %u us, pior %u us, captura %llu quadros, %u transbordos\n", static_cast<unsigned long long>(stats.callbacks), static_cast<unsigned long long>(stats.frames), stats.xruns,
           stats.average_callback_us, stats.max_callback_us, static_cast<unsigned long long>(capturados), stats.capture_overflows);
    CONFERIR(info.running == 1 && info.lost == 0, "o fluxo devia estar rodando");
    CONFERIR(stats.callbacks >= 20, "poucos callbacks: %llu", static_cast<unsigned long long>(stats.callbacks));
    CONFERIR(stats.frames > 48000 && stats.frames < 48000 * 3, "quadros tocados em 1,5 s: %llu", static_cast<unsigned long long>(stats.frames));
    CONFERIR(posicaoFinal > 48000 && posicaoFinal < 48000 * 3, "o transporte andou %lld quadros", static_cast<long long>(posicaoFinal));
    CONFERIR(stats.max_callback_us < 20000, "callback de %u us", stats.max_callback_us);
    if (info.input_channels > 0) {
        CONFERIR(capturados > 24000, "captura curta: %llu quadros", static_cast<unsigned long long>(capturados));
        CONFERIR(posicoesEmOrdem && ultimaPosicao > 0, "posicoes da captura fora de ordem");
        CONFERIR(stats.capture_overflows == 0, "%u transbordos na captura", stats.capture_overflows);
    }
    CONFERIR(slt_stream_stop(fluxo) == SLT_OK, "parar");
    slt_stream_get_info(fluxo, &info);
    CONFERIR(info.running == 0, "parado devia informar running = 0");
    // Parar e voltar a tocar no mesmo fluxo.
    CONFERIR(slt_stream_start(fluxo) == SLT_OK, "iniciar de novo");
    std::this_thread::sleep_for(std::chrono::milliseconds(200));
    slt_stream_stats depois = {};
    slt_stream_get_stats(fluxo, &depois);
    CONFERIR(depois.callbacks > stats.callbacks, "o fluxo nao voltou a tocar");
    slt_stream_close(fluxo);
    slt_stream_close(nullptr);
    slt_mixer_destroy(mixer);
    // Abrir e fechar várias vezes não deixa o aparelho preso.
    for (int i = 0; i < 5; i += 1) {
        slt_mixer_handle* outro = slt_mixer_create(48000);
        slt_stream_handle* f = nullptr;
        config.use_input = 0;
        CONFERIR(slt_stream_open(&config, outro, &f) == SLT_OK, "reabrir %d", i);
        if (f != nullptr) {
            slt_stream_start(f);
            slt_stream_close(f);
        }
        slt_mixer_destroy(outro);
    }
    config.sample_rate = 44100;
    slt_mixer_handle* errado = slt_mixer_create(48000);
    slt_stream_handle* f = nullptr;
    CONFERIR(slt_stream_open(&config, errado, &f) == SLT_ERROR_INVALID_ARGUMENT, "taxa diferente da do mixer aceita");
    config.sample_rate = 0;
    config.output_id = "aparelho-que-nao-existe";
    CONFERIR(slt_stream_open(&config, errado, &f) == SLT_ERROR_DEVICE, "aparelho inexistente aceito");
    slt_mixer_destroy(errado);
}

/* ------------------------------------------------------------------ driver ASIO simulado */

struct DriverFalso : IDriverAsio {
    std::atomic<long> referencias{1};
    long tipo;
    long tamanho = 256;
    double taxa = 44100;
    AsioCallbacks* callbacks = nullptr;
    AsioInfoDoBuffer copia[4];
    long canais = 0;
    std::vector<std::vector<unsigned char>> memoria;
    std::thread thread;
    std::atomic<bool> rodando{false};
    std::atomic<long> trocas{0};
    int prontas = 0;
    bool iniciado = false;
    bool liberado = false;
    // O que o "aparelho" entregou na entrada e recebeu na saída, em float, para conferir.
    std::vector<float> entregue;
    std::vector<float> recebidoE, recebidoD;

    explicit DriverFalso(long tipo_) : tipo(tipo_) {}
    long bytesPorAmostra() const { return tipo == ASIO_INT16 ? 2 : tipo == ASIO_INT24 ? 3 : tipo == ASIO_FLOAT64 ? 8 : 4; }

    HRESULT __stdcall QueryInterface(REFIID, void** saida) override {
        *saida = this;
        AddRef();
        return S_OK;
    }
    ULONG __stdcall AddRef() override { return static_cast<ULONG>(++referencias); }
    ULONG __stdcall Release() override {
        const long r = --referencias;
        if (r == 0) liberado = true;  // o teste é o dono da memória
        return static_cast<ULONG>(r);
    }
    AsioBooleano __thiscall init(void*) override {
        iniciado = true;
        return 1;
    }
    void __thiscall getDriverName(char* nome) override { strcpy(nome, "Driver simulado"); }
    long __thiscall getDriverVersion() override { return 2; }
    void __thiscall getErrorMessage(char* texto) override { texto[0] = 0; }
    AsioErro __thiscall getChannels(long* entradas, long* saidas) override {
        *entradas = 2;
        *saidas = 2;
        return 0;
    }
    AsioErro __thiscall getLatencies(long* entrada, long* saida) override {
        *entrada = 300;
        *saida = 400;
        return 0;
    }
    AsioErro __thiscall getBufferSize(long* minimo, long* maximo, long* preferido, long* granularidade) override {
        *minimo = 64;
        *maximo = 2048;
        *preferido = 256;
        *granularidade = -1;  // potências de 2
        return 0;
    }
    AsioErro __thiscall canSampleRate(AsioTaxa t) override { return t == 44100 || t == 48000 ? 0 : -995; }
    AsioErro __thiscall getSampleRate(AsioTaxa* t) override {
        *t = taxa;
        return 0;
    }
    AsioErro __thiscall setSampleRate(AsioTaxa t) override {
        taxa = t;
        return 0;
    }
    AsioErro __thiscall getClockSources(AsioFonteDeRelogio*, long* quantas) override {
        *quantas = 0;
        return 0;
    }
    AsioErro __thiscall setClockSource(long) override { return 0; }
    AsioErro __thiscall getSamplePosition(Asio64*, Asio64*) override { return 0; }
    AsioErro __thiscall getChannelInfo(AsioInfoDoCanal* info) override {
        info->tipo = tipo;
        info->ativo = 1;
        strcpy(info->nome, info->entrada ? "Entrada" : "Saida");
        return 0;
    }
    AsioErro __thiscall createBuffers(AsioInfoDoBuffer* buffers, long quantos, long tamanhoPedido, AsioCallbacks* cb) override {
        tamanho = tamanhoPedido;
        callbacks = cb;
        canais = quantos;
        memoria.assign(static_cast<size_t>(quantos) * 2, std::vector<unsigned char>(static_cast<size_t>(tamanho) * bytesPorAmostra(), 0));
        for (long c = 0; c < quantos; c += 1) {
            buffers[c].buffers[0] = memoria[c * 2].data();
            buffers[c].buffers[1] = memoria[c * 2 + 1].data();
            copia[c] = buffers[c];
        }
        // Um driver de verdade costuma perguntar a versão do host já aqui.
        return cb->mensagem(ASIO_VERSAO_DO_MOTOR, 0, nullptr, nullptr) == 2 ? 0 : -1000;
    }
    AsioErro __thiscall disposeBuffers() override {
        memoria.clear();
        return 0;
    }
    AsioErro __thiscall controlPanel() override { return 0; }
    AsioErro __thiscall future(long, void*) override { return -998; }
    AsioErro __thiscall outputReady() override {
        prontas += 1;
        return 0;
    }

    void escrever(void* destino, long i, float v) const {
        if (tipo == ASIO_INT16) static_cast<int16_t*>(destino)[i] = static_cast<int16_t>(v * 32767.0f);
        else if (tipo == ASIO_INT24) {
            const int32_t x = static_cast<int32_t>(v * 8388607.0f);
            unsigned char* d = static_cast<unsigned char*>(destino) + i * 3;
            d[0] = static_cast<unsigned char>(x);
            d[1] = static_cast<unsigned char>(x >> 8);
            d[2] = static_cast<unsigned char>(x >> 16);
        } else if (tipo == ASIO_INT32) static_cast<int32_t*>(destino)[i] = static_cast<int32_t>(static_cast<double>(v) * 2147483647.0);
        else if (tipo == ASIO_FLOAT32) static_cast<float*>(destino)[i] = v;
        else static_cast<double*>(destino)[i] = v;
    }
    float ler(const void* origem, long i) const {
        if (tipo == ASIO_INT16) return static_cast<const int16_t*>(origem)[i] / 32767.0f;
        if (tipo == ASIO_INT24) {
            const unsigned char* o = static_cast<const unsigned char*>(origem) + i * 3;
            return ((static_cast<int32_t>(static_cast<int8_t>(o[2])) << 16) | (o[1] << 8) | o[0]) / 8388607.0f;
        }
        if (tipo == ASIO_INT32) return static_cast<float>(static_cast<const int32_t*>(origem)[i] / 2147483647.0);
        if (tipo == ASIO_FLOAT32) return static_cast<const float*>(origem)[i];
        return static_cast<float>(static_cast<const double*>(origem)[i]);
    }

    AsioErro __thiscall start() override {
        rodando = true;
        thread = std::thread([this] {
            long indice = 0;
            long quadro = 0;
            while (rodando.load() && trocas.load() < 60) {
                // Entradas primeiro na lista (como o host pediu), depois as saídas.
                const long entradas = canais - 2;
                for (long i = 0; i < tamanho; i += 1) {
                    const float v = static_cast<float>(0.5 * sin(2 * PI * 330.0 * (quadro + i) / taxa));
                    entregue.push_back(v);
                    for (long c = 0; c < entradas; c += 1) escrever(copia[c].buffers[indice], i, c == 0 ? v : -v);
                }
                callbacks->trocaDeBuffer(indice, 1);
                for (long i = 0; i < tamanho; i += 1) {
                    recebidoE.push_back(ler(copia[entradas].buffers[indice], i));
                    recebidoD.push_back(ler(copia[entradas + 1].buffers[indice], i));
                }
                quadro += tamanho;
                indice = 1 - indice;
                trocas += 1;
            }
        });
        return 0;
    }
    AsioErro __thiscall stop() override {
        rodando = false;
        if (thread.joinable()) thread.join();
        return 0;
    }
};

static void testarAsio() {
    std::vector<slt_device_info> drivers(16);
    uint32_t instalados = 0;
    CONFERIR(slt_device_list(SLT_BACKEND_ASIO, drivers.data(), 16, &instalados) == SLT_OK, "listar drivers ASIO");
    printf("  asio: %u driver(es) instalado(s) nesta maquina\n", instalados);
    for (uint32_t i = 0; i < instalados && i < 16; i += 1) printf("  asio: %s\n", drivers[i].name);

    const long tipos[5] = {ASIO_INT16, ASIO_INT24, ASIO_INT32, ASIO_FLOAT32, ASIO_FLOAT64};
    const double tolerancias[5] = {1.0 / 16000, 1e-6, 1e-6, 1e-7, 1e-7};
    for (int t = 0; t < 5; t += 1) {
        // O projeto toca uma rampa na esquerda (a amostra diz a posição) e monitora a entrada.
        std::vector<float> midia(48000);
        for (size_t i = 0; i < midia.size(); i += 1) midia[i] = static_cast<float>(i) / 96000.0f;
        Misturador* mixer = criarObjeto<Misturador>();
        mixer->criar(48000);
        mixer->definirFonte("m", midia.data(), nullptr, midia.size());
        const char* projeto = "{\"taxa\":48000,\"bpm\":120,\"compasso\":[4,4],\"barramentos\":[],\"master\":{\"ganhoDb\":0,\"efeitos\":[]},\"faixas\":["
                              "{\"id\":\"a\",\"ganhoDb\":0,\"pan\":-100,\"saida\":\"master\",\"efeitos\":[],\"envios\":[],\"clipes\":[{\"midia\":\"m\",\"inicio\":0,\"inicioNaMidia\":0,\"duracao\":48000}]},"
                              "{\"id\":\"b\",\"ganhoDb\":0,\"pan\":100,\"saida\":\"master\",\"gravar\":true,\"monitorar\":true,\"efeitos\":[],\"envios\":[],\"clipes\":[]}]}";
        CONFERIR(mixer->definirProjeto(projeto, strlen(projeto)) == SLT_OK, "projeto do ASIO");
        DriverFalso* driver = new DriverFalso(tipos[t]);
        slt_stream_config config = {};
        config.struct_size = sizeof(config);
        config.backend = SLT_BACKEND_ASIO;
        config.use_input = 1;
        config.buffer_frames = t == 0 ? 128 : t == 1 ? 100 : 0;  // 100 não é potência de 2: vale o preferido
        Fluxo* fluxo = nullptr;
        const slt_result abriu = abrirAsioCom(driver, config, mixer, &fluxo);
        CONFERIR(abriu == SLT_OK && fluxo != nullptr, "tipo %ld: abrir: %s", tipos[t], slt_result_message(abriu));
        if (fluxo == nullptr) continue;
        CONFERIR(driver->iniciado && driver->taxa == 48000, "tipo %ld: o driver devia ter ido para 48 kHz", tipos[t]);
        CONFERIR(fluxo->info.buffer_frames == (t == 0 ? 128u : 256u), "tipo %ld: bloco %u", tipos[t], fluxo->info.buffer_frames);
        CONFERIR(fluxo->info.output_latency_frames == 400 && fluxo->info.input_latency_frames == 300 && fluxo->info.input_channels == 2, "tipo %ld: latencias e canais", tipos[t]);
        // Um segundo fluxo ASIO ao mesmo tempo é recusado.
        DriverFalso* segundo = new DriverFalso(ASIO_INT32);
        Fluxo* outro = nullptr;
        CONFERIR(abrirAsioCom(segundo, config, mixer, &outro) == SLT_ERROR_INVALID_STATE && segundo->liberado, "tipo %ld: segundo fluxo ASIO aceito", tipos[t]);
        delete segundo;

        fluxo->capturando.guardar(1);
        mixer->posicao.guardar(0);
        mixer->tocando.guardar(1);
        CONFERIR(fluxo->iniciar() == SLT_OK, "tipo %ld: iniciar", tipos[t]);
        while (driver->trocas.load() < 60) std::this_thread::sleep_for(std::chrono::milliseconds(1));
        CONFERIR(fluxo->parar() == SLT_OK, "tipo %ld: parar", tipos[t]);
        const size_t quadros = driver->recebidoE.size();
        CONFERIR(quadros == 60u * fluxo->info.buffer_frames, "tipo %ld: %zu quadros recebidos", tipos[t], quadros);
        CONFERIR(driver->prontas >= 60, "tipo %ld: outputReady chamado %d vezes", tipos[t], driver->prontas);
        // Saída esquerda = a rampa do clipe; saída direita = a entrada monitorada (canal direito = −seno).
        double piorE = 0, piorD = 0;
        for (size_t i = 0; i < quadros; i += 1) {
            piorE = fmax(piorE, fabs(driver->recebidoE[i] - static_cast<float>(i) / 96000.0f));
            piorD = fmax(piorD, fabs(driver->recebidoD[i] - (-driver->entregue[i])));
        }
        CONFERIR(piorE < tolerancias[t] * 2 + 1e-6, "tipo %ld: saida da faixa com erro %.3g", tipos[t], piorE);
        CONFERIR(piorD < tolerancias[t] * 4 + 1e-6, "tipo %ld: monitoracao da entrada com erro %.3g", tipos[t], piorD);
        // A captura sai do anel na ordem, com a posição da linha do tempo de cada bloco.
        std::vector<float> e(4096), d(4096);
        size_t lidos = 0;
        double piorCaptura = 0;
        bool posicoesCertas = true;
        int64_t posicao = 0;
        uint32_t n;
        while ((n = fluxo->ler(e.data(), d.data(), 100, &posicao)) > 0) {
            posicoesCertas = posicoesCertas && posicao == static_cast<int64_t>(lidos);
            for (uint32_t i = 0; i < n; i += 1) piorCaptura = fmax(piorCaptura, fmax(fabs(e[i] - driver->entregue[lidos + i]), fabs(d[i] + driver->entregue[lidos + i])));
            lidos += n;
        }
        CONFERIR(lidos == quadros && posicoesCertas, "tipo %ld: captura de %zu quadros (esperados %zu), posicoes %s", tipos[t], lidos, quadros, posicoesCertas ? "certas" : "erradas");
        CONFERIR(piorCaptura < tolerancias[t] * 2 + 1e-6, "tipo %ld: captura com erro %.3g", tipos[t], piorCaptura);
        CONFERIR(fluxo->falhas.ler() == 0 && fluxo->transbordos.ler() == 0, "tipo %ld: falhas %lld, transbordos %lld", tipos[t], static_cast<long long>(fluxo->falhas.ler()), static_cast<long long>(fluxo->transbordos.ler()));
        // O driver pede reinício (trocaram o tamanho no painel): o fluxo fica marcado como perdido.
        CONFERIR(driver->callbacks->mensagem(ASIO_SELETOR_SUPORTADO, ASIO_PEDIDO_DE_REINICIO, nullptr, nullptr) == 1, "tipo %ld: seletor de reinicio", tipos[t]);
        driver->callbacks->mensagem(ASIO_RESSINCRONIA, 0, nullptr, nullptr);
        CONFERIR(fluxo->falhas.ler() == 1, "tipo %ld: a ressincronia devia contar uma falha", tipos[t]);
        driver->callbacks->mensagem(ASIO_PEDIDO_DE_REINICIO, 0, nullptr, nullptr);
        CONFERIR(fluxo->perdido.ler() == 1 && fluxo->iniciar() == SLT_ERROR_DEVICE, "tipo %ld: fluxo perdido ainda inicia", tipos[t]);
        fluxo->fechar();
        CONFERIR(driver->liberado && driver->memoria.empty(), "tipo %ld: o driver nao foi liberado ao fechar", tipos[t]);
        delete driver;
        mixer->soltar();
        liberar(mixer);
    }
    // Driver que não aceita a taxa do mixer: não abre, e é liberado.
    Misturador* mixer = criarObjeto<Misturador>();
    mixer->criar(96000);
    DriverFalso* driver = new DriverFalso(ASIO_INT32);
    slt_stream_config config = {};
    config.struct_size = sizeof(config);
    Fluxo* fluxo = nullptr;
    CONFERIR(abrirAsioCom(driver, config, mixer, &fluxo) == SLT_ERROR_DEVICE && driver->liberado, "driver sem 96 kHz aceito");
    delete driver;
    mixer->soltar();
    liberar(mixer);
    // Pelo registro: um nome que não existe dá erro de aparelho, sem travar.
    slt_mixer_handle* publico = slt_mixer_create(48000);
    slt_stream_handle* handle = nullptr;
    config.backend = SLT_BACKEND_ASIO;
    config.output_id = "Driver ASIO que nao existe";
    CONFERIR(slt_stream_open(&config, publico, &handle) == SLT_ERROR_DEVICE, "driver ASIO inexistente aceito");
    slt_mixer_destroy(publico);
}

int testarWindows() {
    testarWasapi();
    testarAsio();
    printf("windows: %d conferencias, %d falhas\n", conferenciasDoWindows, falhasDoWindows);
    return falhasDoWindows;
}
