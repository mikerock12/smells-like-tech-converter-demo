// Backend Oboe (Android): a saída comanda o relógio no callback de baixa latência do AAudio
// (ou do OpenSL ES em aparelhos antigos, que o Oboe escolhe sozinho); a captura, quando
// pedida, é um segundo fluxo lido sem bloquear dentro do mesmo callback.
//
// O fluxo roda na taxa do mixer: quando o aparelho está em outra, o Oboe converte. Fone
// Bluetooth funciona, mas a latência dele não aparece aqui nem é adequada para tocar ao vivo;
// a calibração por rota do app (CalibracaoDaRota) continua valendo por cima destes números.
#include <oboe/Oboe.h>

#include <memory>

#include "../../dispositivo.h"

namespace slt {

namespace {

int32_t idDoAparelho(const char* texto) {
    if (texto == nullptr || texto[0] == 0) return oboe::kUnspecified;
    int32_t valor = 0;
    for (const char* p = texto; *p != 0; p += 1) {
        if (*p < '0' || *p > '9') return oboe::kUnspecified;
        const int32_t digito = *p - '0';
        if (valor > (INT32_MAX - digito) / 10) return oboe::kUnspecified;
        valor = valor * 10 + digito;
    }
    return valor;
}

struct ErroOboe : oboe::AudioStreamErrorCallback {
    Atomico64 perdido;

    // O Oboe pode avisar de outra thread mesmo depois que o controle soltou o fluxo.
    void onErrorAfterClose(oboe::AudioStream*, oboe::Result) override { perdido.guardar(1); }
};

struct FluxoOboe : Fluxo, oboe::AudioStreamDataCallback {
    std::shared_ptr<oboe::AudioStream> saida;
    std::shared_ptr<oboe::AudioStream> entrada;
    std::shared_ptr<ErroOboe> erro;
    Vetor<float> intercalado;
    bool rodando = false;

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream*, void* dados, int32_t quadros) override {
        float* destino = static_cast<float*>(dados);
        int32_t feito = 0;
        while (feito < quadros) {
            const int32_t resto = quadros - feito;
            const uint32_t n = resto < static_cast<int32_t>(BLOCO_MAXIMO_DO_FLUXO) ? static_cast<uint32_t>(resto) : BLOCO_MAXIMO_DO_FLUXO;
            const bool temEntrada = entrada != nullptr;
            if (temEntrada) {
                // Sem esperar: o que o microfone ainda não entregou entra como silêncio.
                const oboe::ResultWithValue<int32_t> lidos = entrada->read(intercalado.dados, static_cast<int32_t>(n), 0);
                if (!lidos && lidos.error() == oboe::Result::ErrorDisconnected) perdido.guardar(1);
                const uint32_t validos = lidos && lidos.value() > 0 ? static_cast<uint32_t>(lidos.value()) : 0;
                for (uint32_t i = 0; i < validos; i += 1) {
                    entradaE[i] = intercalado[i * 2];
                    entradaD[i] = intercalado[i * 2 + 1];
                }
                for (uint32_t i = validos; i < n; i += 1) entradaE[i] = entradaD[i] = 0;
            }
            bloco(n, temEntrada);
            for (uint32_t i = 0; i < n; i += 1) {
                destino[(feito + i) * 2] = saidaE[i];
                destino[(feito + i) * 2 + 1] = saidaD[i];
            }
            feito += static_cast<int32_t>(n);
        }
        return oboe::DataCallbackResult::Continue;
    }

    void atualizarInfo() override {
        if (saida == nullptr) return;
        if (erro->perdido.ler() != 0) perdido.guardar(1);
        const oboe::ResultWithValue<int32_t> contadas = saida->getXRunCount();
        if (contadas && contadas.value() > 0) falhasDoAparelho.guardar(contadas.value());
        if (!rodando) return;
        const oboe::ResultWithValue<double> latencia = saida->calculateLatencyMillis();
        if (latencia && latencia.value() > 0) info.output_latency_frames = static_cast<uint32_t>(latencia.value() * info.sample_rate / 1000.0);
        if (entrada != nullptr) {
            const oboe::ResultWithValue<double> daEntrada = entrada->calculateLatencyMillis();
            if (daEntrada && daEntrada.value() > 0) info.input_latency_frames = static_cast<uint32_t>(daEntrada.value() * info.sample_rate / 1000.0);
        }
    }

    slt_result iniciar() override {
        if (perdido.ler() != 0 || erro->perdido.ler() != 0 || saida == nullptr) return SLT_ERROR_DEVICE;
        if (rodando) return SLT_OK;
        if (entrada != nullptr) {
            if (entrada->requestStart() != oboe::Result::OK) return SLT_ERROR_DEVICE;
            // Joga fora o que o microfone acumulou antes da saída começar.
            const int32_t capacidade = entrada->getBufferCapacityInFrames();
            for (int32_t drenados = 0; drenados < capacidade;) {
                const oboe::ResultWithValue<int32_t> lidos = entrada->read(intercalado.dados, static_cast<int32_t>(BLOCO_MAXIMO_DO_FLUXO), 0);
                if (!lidos || lidos.value() <= 0) break;
                drenados += lidos.value();
            }
        }
        if (saida->requestStart() != oboe::Result::OK) {
            if (entrada != nullptr) entrada->requestStop();
            return SLT_ERROR_DEVICE;
        }
        rodando = true;
        info.running = 1;
        return SLT_OK;
    }

    slt_result parar() override {
        if (rodando) {
            if (saida != nullptr) saida->stop();
            if (entrada != nullptr) entrada->stop();
        }
        rodando = false;
        info.running = 0;
        return SLT_OK;
    }

    void fechar() override {
        parar();
        if (saida != nullptr) saida->close();
        if (entrada != nullptr) entrada->close();
        saida.reset();
        entrada.reset();
        intercalado.soltar();
        soltarComum();
        this->~FluxoOboe();
        liberar(this);
    }
};

}  // namespace

slt_result listarOboe(slt_device_info* dispositivos, uint32_t capacidade, uint32_t* quantos) {
    // O Android só enumera aparelhos pelo AudioManager (Java): daqui sai só o padrão do
    // sistema. O app passa o id do AudioDeviceInfo em output_id/input_id quando quer outro.
    *quantos = 1;
    if (capacidade == 0) return SLT_OK;
    slt_device_info& d = dispositivos[0];
    memset(&d, 0, sizeof(d));
    d.backend = SLT_BACKEND_OBOE;
    d.input_channels = 2;
    d.output_channels = 2;
    d.is_default = 1;
    const char nome[] = "Padrao do sistema";
    memcpy(d.name, nome, sizeof(nome));
    return SLT_OK;
}

slt_result abrirOboe(const slt_stream_config& config, Misturador* misturador, Fluxo** fluxo) {
    if (config.buffer_frames > INT32_MAX) return SLT_ERROR_INVALID_ARGUMENT;
    FluxoOboe* f = criarObjeto<FluxoOboe>();
    if (f == nullptr) return SLT_ERROR_OUT_OF_MEMORY;
    const uint32_t taxa = misturador->taxa;
    if (!f->preparar(misturador, taxa * 30) || !f->intercalado.criar(static_cast<size_t>(BLOCO_MAXIMO_DO_FLUXO) * 2)) {
        f->fechar();
        return SLT_ERROR_OUT_OF_MEMORY;
    }
    oboe::AudioStreamBuilder saida;
    f->erro = std::make_shared<ErroOboe>();
    saida.setDirection(oboe::Direction::Output)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Exclusive)  // o Oboe cai para compartilhado quando o aparelho não dá
        ->setFormat(oboe::AudioFormat::Float)
        ->setFormatConversionAllowed(true)
        ->setChannelCount(oboe::ChannelCount::Stereo)
        ->setChannelConversionAllowed(true)
        ->setSampleRate(static_cast<int32_t>(taxa))
        ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
        ->setUsage(oboe::Usage::Media)
        ->setContentType(oboe::ContentType::Music)
        ->setDeviceId(idDoAparelho(config.output_id))
        ->setDataCallback(f)
        ->setErrorCallback(f->erro);
    if (config.buffer_frames != 0) saida.setFramesPerDataCallback(static_cast<int32_t>(config.buffer_frames));
    if (saida.openStream(f->saida) != oboe::Result::OK || f->saida == nullptr) {
        f->fechar();
        return SLT_ERROR_DEVICE;
    }
    // Dois "bursts" de folga: o menor buffer que costuma tocar sem falha.
    const int32_t rajada = f->saida->getFramesPerBurst();
    if (rajada > 0) f->saida->setBufferSizeInFrames(rajada * 2);
    f->info.backend = SLT_BACKEND_OBOE;
    f->info.sample_rate = taxa;
    f->info.output_channels = 2;
    f->info.buffer_frames = static_cast<uint32_t>(config.buffer_frames != 0 ? config.buffer_frames : (rajada > 0 ? rajada : 0));
    f->info.output_latency_frames = static_cast<uint32_t>(f->saida->getBufferSizeInFrames() > 0 ? f->saida->getBufferSizeInFrames() : 0);
    f->info.low_latency = f->saida->getPerformanceMode() == oboe::PerformanceMode::LowLatency ? 1 : 0;
    if (config.use_input != 0) {
        // Sem microfone (ou sem permissão) a saída abre do mesmo jeito: input_channels fica 0.
        const oboe::InputPreset predefinicoes[2] = {oboe::InputPreset::Unprocessed, oboe::InputPreset::VoiceRecognition};
        for (int tentativa = 0; tentativa < 2 && f->entrada == nullptr; tentativa += 1) {
            oboe::AudioStreamBuilder entrada;
            entrada.setDirection(oboe::Direction::Input)
                ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
                ->setSharingMode(oboe::SharingMode::Exclusive)
                ->setFormat(oboe::AudioFormat::Float)
                ->setFormatConversionAllowed(true)
                ->setChannelCount(oboe::ChannelCount::Stereo)
                ->setChannelConversionAllowed(true)
                ->setSampleRate(static_cast<int32_t>(taxa))
                ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
                ->setInputPreset(predefinicoes[tentativa])
                ->setDeviceId(idDoAparelho(config.input_id));
            if (entrada.openStream(f->entrada) != oboe::Result::OK) f->entrada.reset();
        }
        if (f->entrada != nullptr) {
            f->info.input_channels = 2;
            f->info.input_latency_frames = static_cast<uint32_t>(f->entrada->getBufferSizeInFrames() > 0 ? f->entrada->getBufferSizeInFrames() : 0);
        }
    }
    *fluxo = f;
    return SLT_OK;
}

}  // namespace slt
