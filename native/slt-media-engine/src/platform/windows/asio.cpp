// Backend ASIO: fala direto com o driver da interface de áudio (sem o mixer do Windows no
// caminho), que é o que dá a menor latência em interfaces USB/Thunderbolt de estúdio.
//
// O driver chama `trocaDeBuffer` na thread dele: ali o fluxo converte a entrada para float,
// roda o mixer e converte a saída para o formato do driver. Os callbacks do ASIO não levam
// contexto, então só um fluxo ASIO fica aberto por processo (como em qualquer host).
// As chamadas de abrir, iniciar, parar e fechar precisam vir da mesma thread.
#include "asio_interno.h"

namespace slt {

namespace {

constexpr int CANAIS = 2;

struct FluxoAsio;
FluxoAsio* ativo = nullptr;

inline float limitarAmostra(float x) { return x > 1.0f ? 1.0f : x < -1.0f ? -1.0f : x; }

void paraFloat(const void* origem, long tipo, float* destino, long n) {
    switch (tipo) {
        case ASIO_INT16: {
            const int16_t* o = static_cast<const int16_t*>(origem);
            for (long i = 0; i < n; i += 1) destino[i] = o[i] / 32768.0f;
            break;
        }
        case ASIO_INT24: {
            const unsigned char* o = static_cast<const unsigned char*>(origem);
            for (long i = 0; i < n; i += 1) {
                const int32_t v = (static_cast<int32_t>(static_cast<int8_t>(o[i * 3 + 2])) << 16) | (o[i * 3 + 1] << 8) | o[i * 3];
                destino[i] = v / 8388608.0f;
            }
            break;
        }
        case ASIO_INT32: {
            const int32_t* o = static_cast<const int32_t*>(origem);
            for (long i = 0; i < n; i += 1) destino[i] = static_cast<float>(o[i] / 2147483648.0);
            break;
        }
        case ASIO_FLOAT32:
            memcpy(destino, origem, static_cast<size_t>(n) * sizeof(float));
            break;
        case ASIO_FLOAT64: {
            const double* o = static_cast<const double*>(origem);
            for (long i = 0; i < n; i += 1) destino[i] = static_cast<float>(o[i]);
            break;
        }
        default:
            memset(destino, 0, static_cast<size_t>(n) * sizeof(float));
    }
}

void deFloat(const float* origem, long tipo, void* destino, long n) {
    switch (tipo) {
        case ASIO_INT16: {
            int16_t* d = static_cast<int16_t*>(destino);
            for (long i = 0; i < n; i += 1) d[i] = static_cast<int16_t>(limitarAmostra(origem[i]) * 32767.0f);
            break;
        }
        case ASIO_INT24: {
            unsigned char* d = static_cast<unsigned char*>(destino);
            for (long i = 0; i < n; i += 1) {
                const int32_t v = static_cast<int32_t>(limitarAmostra(origem[i]) * 8388607.0f);
                d[i * 3] = static_cast<unsigned char>(v);
                d[i * 3 + 1] = static_cast<unsigned char>(v >> 8);
                d[i * 3 + 2] = static_cast<unsigned char>(v >> 16);
            }
            break;
        }
        case ASIO_INT32: {
            int32_t* d = static_cast<int32_t*>(destino);
            for (long i = 0; i < n; i += 1) d[i] = static_cast<int32_t>(static_cast<double>(limitarAmostra(origem[i])) * 2147483647.0);
            break;
        }
        case ASIO_FLOAT32:
            memcpy(destino, origem, static_cast<size_t>(n) * sizeof(float));
            break;
        case ASIO_FLOAT64: {
            double* d = static_cast<double*>(destino);
            for (long i = 0; i < n; i += 1) d[i] = origem[i];
            break;
        }
        default:
            break;
    }
}

bool tipoAceito(long tipo) { return tipo == ASIO_INT16 || tipo == ASIO_INT24 || tipo == ASIO_INT32 || tipo == ASIO_FLOAT32 || tipo == ASIO_FLOAT64; }

struct FluxoAsio : Fluxo {
    IDriverAsio* driver = nullptr;
    AsioInfoDoBuffer buffers[CANAIS * 2];
    AsioCallbacks callbacks_ = {};
    long tipoDaSaida[CANAIS] = {0, 0};
    long tipoDaEntrada[CANAIS] = {0, 0};
    long entradas = 0;
    long tamanho = 0;
    bool avisaSaidaPronta = false;
    bool buffersCriados = false;
    bool rodando = false;

    void troca(long indice) {
        const long n = tamanho;
        // As entradas vêm primeiro na lista de buffers; depois as duas saídas.
        if (entradas > 0) {
            paraFloat(buffers[0].buffers[indice], tipoDaEntrada[0], entradaE.dados, n);
            if (entradas > 1) paraFloat(buffers[1].buffers[indice], tipoDaEntrada[1], entradaD.dados, n);
            else memcpy(entradaD.dados, entradaE.dados, static_cast<size_t>(n) * sizeof(float));
        }
        bloco(static_cast<uint32_t>(n), entradas > 0);
        deFloat(saidaE.dados, tipoDaSaida[0], buffers[entradas].buffers[indice], n);
        deFloat(saidaD.dados, tipoDaSaida[1], buffers[entradas + 1].buffers[indice], n);
        if (avisaSaidaPronta) driver->outputReady();
    }

    static void trocaDeBuffer(long indice, AsioBooleano) {
        if (ativo != nullptr && (indice == 0 || indice == 1)) ativo->troca(indice);
    }

    static AsioTempo* trocaComTempo(AsioTempo* tempo, long indice, AsioBooleano direto) {
        trocaDeBuffer(indice, direto);
        return tempo;
    }

    static void taxaMudou(AsioTaxa) {
        // O driver trocou de taxa por fora (painel dele): o fluxo deixa de valer.
        if (ativo != nullptr) ativo->perdido.guardar(1);
    }

    static long mensagem(long seletor, long valor, void*, double*) {
        switch (seletor) {
            case ASIO_SELETOR_SUPORTADO:
                return valor == ASIO_VERSAO_DO_MOTOR || valor == ASIO_PEDIDO_DE_REINICIO || valor == ASIO_RESSINCRONIA || valor == ASIO_LATENCIAS_MUDARAM || valor == ASIO_TAMANHO_MUDOU ? 1 : 0;
            case ASIO_VERSAO_DO_MOTOR:
                return 2;
            case ASIO_PEDIDO_DE_REINICIO:
            case ASIO_TAMANHO_MUDOU:
                if (ativo != nullptr) ativo->perdido.guardar(1);
                return 1;
            case ASIO_RESSINCRONIA:
                if (ativo != nullptr) ativo->anotarFalha();
                return 1;
            case ASIO_LATENCIAS_MUDARAM:
                return 1;
            default:
                return 0;
        }
    }

    slt_result iniciar() override {
        if (rodando) return SLT_OK;
        if (perdido.ler() != 0 || driver->start() != 0) return SLT_ERROR_DEVICE;
        rodando = true;
        info.running = 1;
        return SLT_OK;
    }

    slt_result parar() override {
        if (rodando) driver->stop();
        rodando = false;
        info.running = 0;
        return SLT_OK;
    }

    void fechar() override {
        parar();
        if (driver != nullptr) {
            if (buffersCriados) driver->disposeBuffers();
            driver->Release();
            driver = nullptr;
        }
        if (ativo == this) ativo = nullptr;
        soltarComum();
        this->~FluxoAsio();
        liberar(this);
    }
};

bool lerClsid(HKEY chave, CLSID* clsid) {
    wchar_t texto[64];
    DWORD bytes = sizeof(texto);
    DWORD tipo = 0;
    if (RegQueryValueExW(chave, L"CLSID", nullptr, &tipo, reinterpret_cast<BYTE*>(texto), &bytes) != ERROR_SUCCESS || tipo != REG_SZ) return false;
    texto[63] = 0;
    return SUCCEEDED(CLSIDFromString(texto, clsid));
}

}  // namespace

slt_result abrirAsioCom(IDriverAsio* driver, const slt_stream_config& config, Misturador* misturador, Fluxo** fluxo) {
    if (driver == nullptr) return SLT_ERROR_INVALID_ARGUMENT;
    if (ativo != nullptr) {
        driver->Release();
        return SLT_ERROR_INVALID_STATE;
    }
    FluxoAsio* f = criarObjeto<FluxoAsio>();
    if (f == nullptr) {
        driver->Release();
        return SLT_ERROR_OUT_OF_MEMORY;
    }
    f->driver = driver;
    const uint32_t taxa = misturador->taxa;
    slt_result resultado = SLT_ERROR_DEVICE;
    do {
        if (!f->preparar(misturador, taxa * 30)) {
            resultado = SLT_ERROR_OUT_OF_MEMORY;
            break;
        }
        if (!driver->init(GetDesktopWindow())) break;
        long entradas = 0;
        long saidas = 0;
        if (driver->getChannels(&entradas, &saidas) != 0 || saidas < CANAIS) break;
        if (driver->canSampleRate(taxa) != 0) break;
        AsioTaxa atual = 0;
        if (driver->getSampleRate(&atual) != 0 || atual != taxa) {
            if (driver->setSampleRate(taxa) != 0) break;
        }
        long minimo = 0, maximo = 0, preferido = 0, granularidade = 0;
        if (driver->getBufferSize(&minimo, &maximo, &preferido, &granularidade) != 0 || preferido <= 0) break;
        long tamanho = preferido;
        if (config.buffer_frames != 0) {
            // O pedido vale se o driver aceita esse tamanho; senão fica o preferido dele.
            const long pedido = static_cast<long>(config.buffer_frames);
            const bool naGrade = granularidade == -1 ? (pedido & (pedido - 1)) == 0 : granularidade > 0 ? (pedido - minimo) % granularidade == 0 : pedido == preferido;
            if (pedido >= minimo && pedido <= maximo && naGrade) tamanho = pedido;
        }
        if (static_cast<uint32_t>(tamanho) > BLOCO_MAXIMO_DO_FLUXO) break;
        f->entradas = config.use_input != 0 ? (entradas < CANAIS ? entradas : CANAIS) : 0;
        long quantos = 0;
        for (long c = 0; c < f->entradas; c += 1) f->buffers[quantos++] = {1, c, {nullptr, nullptr}};
        for (long c = 0; c < CANAIS; c += 1) f->buffers[quantos++] = {0, c, {nullptr, nullptr}};
        f->callbacks_ = {FluxoAsio::trocaDeBuffer, FluxoAsio::taxaMudou, FluxoAsio::mensagem, FluxoAsio::trocaComTempo};
        f->tamanho = tamanho;
        ativo = f;  // antes de criar: alguns drivers já chamam `mensagem` aqui dentro
        if (driver->createBuffers(f->buffers, quantos, tamanho, &f->callbacks_) != 0) break;
        f->buffersCriados = true;
        bool tiposOk = true;
        for (long c = 0; c < f->entradas + CANAIS; c += 1) {
            AsioInfoDoCanal canal = {};
            canal.canal = f->buffers[c].canal;
            canal.entrada = f->buffers[c].entrada;
            if (driver->getChannelInfo(&canal) != 0 || !tipoAceito(canal.tipo)) {
                tiposOk = false;
                break;
            }
            if (c < f->entradas) f->tipoDaEntrada[c] = canal.tipo;
            else f->tipoDaSaida[c - f->entradas] = canal.tipo;
        }
        if (!tiposOk) break;
        f->avisaSaidaPronta = driver->outputReady() == 0;
        long latenciaDeEntrada = 0;
        long latenciaDeSaida = 0;
        driver->getLatencies(&latenciaDeEntrada, &latenciaDeSaida);
        f->info.backend = SLT_BACKEND_ASIO;
        f->info.sample_rate = taxa;
        f->info.buffer_frames = static_cast<uint32_t>(tamanho);
        f->info.output_channels = CANAIS;
        f->info.input_channels = f->entradas > 0 ? CANAIS : 0;
        f->info.output_latency_frames = latenciaDeSaida > 0 ? static_cast<uint32_t>(latenciaDeSaida) : 0;
        f->info.input_latency_frames = latenciaDeEntrada > 0 ? static_cast<uint32_t>(latenciaDeEntrada) : 0;
        f->info.low_latency = 1;
        resultado = SLT_OK;
    } while (false);
    if (resultado != SLT_OK) {
        f->fechar();
        return resultado;
    }
    *fluxo = f;
    return SLT_OK;
}

slt_result listarAsio(slt_device_info* dispositivos, uint32_t capacidade, uint32_t* quantos) {
    *quantos = 0;
    HKEY raiz = nullptr;
    // Sem a chave não há driver ASIO instalado: lista vazia, não erro.
    if (RegOpenKeyExW(HKEY_LOCAL_MACHINE, L"SOFTWARE\\ASIO", 0, KEY_READ, &raiz) != ERROR_SUCCESS) return SLT_OK;
    uint32_t total = 0;
    for (DWORD i = 0;; i += 1) {
        wchar_t nome[256];
        DWORD tamanho = 256;
        if (RegEnumKeyExW(raiz, i, nome, &tamanho, nullptr, nullptr, nullptr, nullptr) != ERROR_SUCCESS) break;
        HKEY chave = nullptr;
        if (RegOpenKeyExW(raiz, nome, 0, KEY_READ, &chave) != ERROR_SUCCESS) continue;
        CLSID clsid;
        if (lerClsid(chave, &clsid)) {
            if (total < capacidade) {
                slt_device_info& d = dispositivos[total];
                memset(&d, 0, sizeof(d));
                d.backend = SLT_BACKEND_ASIO;
                WideCharToMultiByte(CP_UTF8, 0, nome, -1, d.id, sizeof(d.id), nullptr, nullptr);
                wchar_t descricao[256];
                DWORD bytes = sizeof(descricao);
                DWORD tipo = 0;
                if (RegQueryValueExW(chave, L"Description", nullptr, &tipo, reinterpret_cast<BYTE*>(descricao), &bytes) == ERROR_SUCCESS && tipo == REG_SZ) {
                    descricao[255] = 0;
                    WideCharToMultiByte(CP_UTF8, 0, descricao, -1, d.name, sizeof(d.name), nullptr, nullptr);
                } else {
                    memcpy(d.name, d.id, sizeof(d.name) - 1);
                }
                // Canais e taxa só se sabem carregando o driver; quem abre o fluxo descobre.
            }
            total += 1;
        }
        RegCloseKey(chave);
    }
    RegCloseKey(raiz);
    *quantos = total;
    return SLT_OK;
}

slt_result abrirAsio(const slt_stream_config& config, Misturador* misturador, Fluxo** fluxo) {
    if (config.output_id == nullptr || config.output_id[0] == 0) return SLT_ERROR_INVALID_ARGUMENT;
    wchar_t caminho[600] = L"SOFTWARE\\ASIO\\";
    if (MultiByteToWideChar(CP_UTF8, 0, config.output_id, -1, caminho + 14, 580) == 0) return SLT_ERROR_INVALID_ARGUMENT;
    HKEY chave = nullptr;
    if (RegOpenKeyExW(HKEY_LOCAL_MACHINE, caminho, 0, KEY_READ, &chave) != ERROR_SUCCESS) return SLT_ERROR_DEVICE;
    CLSID clsid;
    const bool achou = lerClsid(chave, &clsid);
    RegCloseKey(chave);
    if (!achou) return SLT_ERROR_DEVICE;
    // O driver ASIO é um objeto COM de apartamento: a thread que abre fica com o COM iniciado.
    const HRESULT com = CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);
    if (FAILED(com) && com != RPC_E_CHANGED_MODE) return SLT_ERROR_DEVICE;
    IDriverAsio* driver = nullptr;
    // Por convenção do ASIO, o IID pedido é o próprio CLSID do driver.
    if (FAILED(CoCreateInstance(clsid, nullptr, CLSCTX_INPROC_SERVER, clsid, reinterpret_cast<void**>(&driver))) || driver == nullptr) return SLT_ERROR_DEVICE;
    return abrirAsioCom(driver, config, misturador, fluxo);
}

}  // namespace slt
