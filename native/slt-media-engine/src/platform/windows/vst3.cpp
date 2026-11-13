#include "../../../include/slt_media_api.h"

#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstring>
#include <filesystem>
#include <mutex>
#include <unordered_map>
#include <vector>

#include "pluginterfaces/vst/ivstaudioprocessor.h"
#include "pluginterfaces/vst/ivsteditcontroller.h"
#include "public.sdk/source/common/memorystream.h"
#include "public.sdk/source/vst/hosting/hostclasses.h"
#include "public.sdk/source/vst/hosting/module.h"
#include "public.sdk/source/vst/hosting/parameterchanges.h"
#include "public.sdk/source/vst/hosting/plugprovider.h"

using namespace Steinberg;
using namespace Steinberg::Vst;
using VST3::Hosting::Module;

namespace {
constexpr uint32_t maxEstado = 16 * 1024 * 1024;
constexpr uint32_t cabecalhoEstado = 12;
constexpr uint32_t magicEstado = 0x31545356; // VST1
static_assert(std::atomic<double>::is_always_lock_free);

struct Situacao {
    std::atomic<bool> falhou = false;
    std::atomic<bool> listado = false;
};

std::shared_ptr<Situacao> situacaoDoModulo(const std::string& caminho) {
    static std::mutex mutex;
    static std::unordered_map<std::string, std::shared_ptr<Situacao>> modulos;
    std::lock_guard trava(mutex);
    auto& situacao = modulos[caminho];
    if (!situacao) situacao = std::make_shared<Situacao>();
    return situacao;
}

template<size_t N> void copiar(char (&destino)[N], const std::string& texto) {
    std::memcpy(destino, texto.data(), std::min(texto.size(), N - 1));
}

template<class F> bool chamarPlugin(F&& operacao) {
    try { return operacao(); }
    catch (...) { return false; }
}

struct Parametro {
    ParameterInfo info = {};
    std::atomic<double> valor = 0;
    double aplicado = -1; // thread de áudio
};

HostApplication& host() {
    static auto* aplicacao = new HostApplication;
    return *aplicacao;
}

bool classeDeEfeito(const VST3::Hosting::ClassInfo& classe) {
    if (classe.category() != kVstAudioEffectClass) return false;
    const auto& categorias = classe.subCategories();
    return std::find(categorias.begin(), categorias.end(), PlugType::kInstrument) == categorias.end();
}

std::string caminhoDoModulo(const char* caminho) {
    auto original = std::filesystem::u8path(caminho);
    if (_wcsicmp(original.extension().c_str(), L".vst3") != 0) return {};
    auto canonico = std::filesystem::weakly_canonical(original).u8string();
    return {reinterpret_cast<const char*>(canonico.data()), canonico.size()};
}
}

bool sltVst3FoiListado(const char* caminho) {
    try {
        const auto modulo = caminhoDoModulo(caminho);
        return !modulo.empty() && situacaoDoModulo(modulo)->listado.load();
    } catch (...) { return false; }
}

struct slt_plugin_handle {
    std::shared_ptr<Situacao> situacao;
    Module::Ptr modulo;
    std::unique_ptr<PlugProvider> provedor;
    IPtr<IComponent> componente;
    IPtr<IEditController> controlador;
    FUnknownPtr<IAudioProcessor> processador;
    std::unique_ptr<Parametro[]> parametros;
    uint32_t quantos = 0;
    uint32_t capacidade = 0;
    uint32_t latencia = 0;
    bool ativo = false;
    bool processando = false;
    ParameterChanges mudancas;
    std::vector<float> entradaE, entradaD, saidaE, saidaD;

    ~slt_plugin_handle() {
        if (processando) chamarPlugin([&] { return processador->setProcessing(false) == kResultOk; });
        if (ativo) chamarPlugin([&] { return componente->setActive(false) == kResultOk; });
    }

    bool preparar(uint32_t taxa, uint32_t bloco) {
        capacidade = bloco;
        entradaE.resize(bloco);
        entradaD.resize(bloco);
        saidaE.resize(bloco);
        saidaD.resize(bloco);

        if (!processador || processador->canProcessSampleSize(kSample32) != kResultTrue ||
            componente->getBusCount(kAudio, kInput) == 0 || componente->getBusCount(kAudio, kOutput) == 0) return false;
        SpeakerArrangement in[] = {SpeakerArr::kStereo};
        SpeakerArrangement out[] = {SpeakerArr::kStereo};
        processador->setBusArrangements(in, 1, out, 1);
        if (processador->getBusArrangement(kInput, 0, in[0]) != kResultOk || in[0] != SpeakerArr::kStereo ||
            processador->getBusArrangement(kOutput, 0, out[0]) != kResultOk || out[0] != SpeakerArr::kStereo) return false;
        for (const auto direcao : {kInput, kOutput}) {
            const int32 buses = componente->getBusCount(kAudio, direcao);
            for (int32 i = 0; i < buses; ++i) {
                if (componente->activateBus(kAudio, direcao, i, i == 0) != kResultOk) return false;
            }
        }
        ProcessSetup setup = {kRealtime, kSample32, static_cast<int32>(bloco), static_cast<double>(taxa)};
        if (processador->setupProcessing(setup) != kResultOk || componente->setActive(true) != kResultOk) return false;
        ativo = true;
        const auto inicio = processador->setProcessing(true);
        if (inicio != kResultOk && inicio != kNotImplemented) return false;
        processando = true;
        latencia = processador->getLatencySamples();

        const int32 n = controlador ? controlador->getParameterCount() : 0;
        if (n < 0 || n > 4096) return false;
        parametros = std::make_unique<Parametro[]>(n);
        mudancas.setMaxParameters(n);
        for (int32 i = 0; i < n; ++i) {
            auto& p = parametros[i];
            if (controlador->getParameterInfo(i, p.info) != kResultOk) return false;
            p.valor.store(controlador->getParamNormalized(p.info.id));
            // Reserva um ponto em cada fila fora da thread de áudio.
            int32 fila, ponto;
            if (mudancas.addParameterData(p.info.id, fila)->addPoint(0, p.valor.load(), ponto) != kResultOk) return false;
        }
        quantos = n;
        mudancas.clearQueue();
        return true;
    }
};

extern "C" {

SLT_API slt_result slt_plugin_scan(const char* path, slt_plugin_info* plugins, uint32_t capacity, uint32_t* count) {
    if (!count || (capacity && !plugins)) return SLT_ERROR_INVALID_ARGUMENT;
    *count = 0;
    try {
        auto caminhos = path ? Module::PathList{path} : Module::getModulePaths();
        for (const auto& item : caminhos) {
            const auto caminho = caminhoDoModulo(item.c_str());
            if (caminho.empty()) continue;
            const auto situacao = situacaoDoModulo(caminho);
            if (situacao->falhou.load()) continue;
            const bool carregou = chamarPlugin([&] {
                std::string erro;
                auto modulo = Module::create(caminho, erro);
                if (!modulo) return false;
                for (const auto& classe : modulo->getFactory().classInfos()) {
                    if (!classeDeEfeito(classe)) continue;
                    if (*count < capacity) {
                        auto& info = plugins[*count];
                        info = {};
                        copiar(info.path, caminho);
                        copiar(info.class_id, classe.ID().toString(false));
                        copiar(info.name, classe.name());
                        copiar(info.vendor, classe.vendor());
                        copiar(info.version, classe.version());
                    }
                    ++*count;
                }
                return true;
            });
            if (carregou) situacao->listado.store(true);
            else {
                situacao->falhou.store(true);
                if (path) return SLT_ERROR_DEVICE;
            }
        }
        return SLT_OK;
    } catch (...) { return SLT_ERROR_INTERNAL; }
}

SLT_API slt_result slt_plugin_open(const char* path, const char* class_id, uint32_t sample_rate,
                                  uint32_t max_frames, slt_plugin_handle** plugin) {
    if (!plugin) return SLT_ERROR_INVALID_ARGUMENT;
    *plugin = nullptr;
    if (!path || !class_id || std::strlen(class_id) != 32 || sample_rate < 8000 || sample_rate > 192000 ||
        max_frames == 0 || max_frames > 65536) return SLT_ERROR_INVALID_ARGUMENT;
    try {
        const auto caminho = caminhoDoModulo(path);
        const auto uid = VST3::UID::fromString(std::string(class_id), false);
        if (caminho.empty() || !uid) return SLT_ERROR_INVALID_ARGUMENT;
        auto p = std::make_unique<slt_plugin_handle>();
        p->situacao = situacaoDoModulo(caminho);
        if (p->situacao->falhou.load()) return SLT_ERROR_DEVICE;
        bool encontrou = false;
        static std::mutex abrir;
        std::lock_guard trava(abrir);
        const bool pronto = chamarPlugin([&] {
            std::string erro;
            p->modulo = Module::create(caminho, erro);
            if (!p->modulo) return false;
            const auto& fabrica = p->modulo->getFactory();
            fabrica.setHostContext(&host());
            PluginContextFactory::instance().setPluginContext(&host());
            for (const auto& classe : fabrica.classInfos()) {
                if (classe.ID() != *uid || !classeDeEfeito(classe)) continue;
                encontrou = true;
                p->provedor = std::make_unique<PlugProvider>(fabrica, classe);
                if (!p->provedor->initialize()) return false;
                p->componente = p->provedor->getComponentPtr();
                p->controlador = p->provedor->getControllerPtr();
                p->processador = FUnknownPtr<IAudioProcessor>(p->componente);
                return p->preparar(sample_rate, max_frames);
            }
            return false;
        });
        if (!pronto) {
            if (!p->modulo || encontrou) p->situacao->falhou.store(true);
            return p->modulo && !encontrou ? SLT_ERROR_UNSUPPORTED : SLT_ERROR_DEVICE;
        }
        *plugin = p.release();
        return SLT_OK;
    } catch (...) { return SLT_ERROR_INTERNAL; }
}

SLT_API void slt_plugin_close(slt_plugin_handle* plugin) {
    chamarPlugin([&] { delete plugin; return true; });
}

SLT_API uint32_t slt_plugin_latency(const slt_plugin_handle* plugin) { return plugin ? plugin->latencia : 0; }
SLT_API uint32_t slt_plugin_parameter_count(const slt_plugin_handle* plugin) { return plugin ? plugin->quantos : 0; }

SLT_API slt_result slt_plugin_get_parameter(const slt_plugin_handle* plugin, uint32_t index, slt_plugin_parameter* parameter) {
    if (!plugin || !parameter || index >= plugin->quantos) return SLT_ERROR_INVALID_ARGUMENT;
    try {
        const auto& p = plugin->parametros[index];
        *parameter = {};
        parameter->id = p.info.id;
        parameter->flags = p.info.flags;
        parameter->default_value = p.info.defaultNormalizedValue;
        parameter->value = p.valor.load();
        auto utf8 = [](const TChar* texto) {
            const int n = WideCharToMultiByte(CP_UTF8, 0, reinterpret_cast<const wchar_t*>(texto), -1, nullptr, 0, nullptr, nullptr);
            std::string r(n > 0 ? n : 0, '\0');
            if (n > 0) {
                WideCharToMultiByte(CP_UTF8, 0, reinterpret_cast<const wchar_t*>(texto), -1, r.data(), n, nullptr, nullptr);
                r.pop_back();
            }
            return r;
        };
        copiar(parameter->name, utf8(p.info.title));
        copiar(parameter->units, utf8(p.info.units));
        return SLT_OK;
    } catch (...) { return SLT_ERROR_INTERNAL; }
}

SLT_API slt_result slt_plugin_set_parameter(slt_plugin_handle* plugin, uint32_t id, double value) {
    if (!plugin || !std::isfinite(value) || value < 0 || value > 1) return SLT_ERROR_INVALID_ARGUMENT;
    for (uint32_t i = 0; i < plugin->quantos; ++i) {
        auto& p = plugin->parametros[i];
        if (p.info.id != id) continue;
        if ((p.info.flags & ParameterInfo::kIsReadOnly) != 0) return SLT_ERROR_INVALID_ARGUMENT;
        p.valor.store(value);
        return SLT_OK;
    }
    return SLT_ERROR_INVALID_ARGUMENT;
}

SLT_API slt_result slt_plugin_process(slt_plugin_handle* plugin, float* left, float* right, uint32_t frame_count) {
    if (!plugin || !left || !right || left == right || frame_count > plugin->capacidade) return SLT_ERROR_INVALID_ARGUMENT;
    if (plugin->situacao->falhou.load()) return SLT_ERROR_DEVICE;
    if (frame_count == 0) return SLT_OK;
    auto& p = *plugin;
    const size_t bytes = frame_count * sizeof(float);
    std::memcpy(p.entradaE.data(), left, bytes);
    std::memcpy(p.entradaD.data(), right, bytes);
    const bool ok = chamarPlugin([&] {
        p.mudancas.clearQueue();
        for (uint32_t i = 0; i < p.quantos; ++i) {
            auto& par = p.parametros[i];
            const double valor = par.valor.load();
            if (valor == par.aplicado || (par.info.flags & ParameterInfo::kIsReadOnly)) continue;
            int32 fila, ponto;
            p.mudancas.addParameterData(par.info.id, fila)->addPoint(0, valor, ponto);
            par.aplicado = valor;
        }
        float* entrada[] = {p.entradaE.data(), p.entradaD.data()};
        float* saida[] = {p.saidaE.data(), p.saidaD.data()};
        AudioBusBuffers in, out;
        in.numChannels = out.numChannels = 2;
        in.channelBuffers32 = entrada;
        out.channelBuffers32 = saida;
        ProcessData dados;
        dados.processMode = kRealtime;
        dados.symbolicSampleSize = kSample32;
        dados.numSamples = static_cast<int32>(frame_count);
        dados.numInputs = dados.numOutputs = 1;
        dados.inputs = &in;
        dados.outputs = &out;
        dados.inputParameterChanges = &p.mudancas;
        if (p.processador->process(dados) != kResultOk) return false;
        for (uint32_t i = 0; i < frame_count; ++i) {
            if (!std::isfinite(saida[0][i]) || !std::isfinite(saida[1][i])) return false;
        }
        return true;
    });
    if (!ok) {
        p.situacao->falhou.store(true);
        return SLT_ERROR_DEVICE;
    }
    std::memcpy(left, p.saidaE.data(), bytes);
    std::memcpy(right, p.saidaD.data(), bytes);
    return SLT_OK;
}

SLT_API slt_result slt_plugin_get_state(slt_plugin_handle* plugin, void* data, uint32_t capacity, uint32_t* size) {
    if (!plugin || !size || (capacity && !data)) return SLT_ERROR_INVALID_ARGUMENT;
    if (plugin->situacao->falhou.load()) return SLT_ERROR_DEVICE;
    try {
        MemoryStream componente, controlador;
        if (plugin->componente->getState(&componente) != kResultOk) return SLT_ERROR_UNSUPPORTED;
        if (plugin->controlador) {
            const auto r = plugin->controlador->getState(&controlador);
            if (r != kResultOk && r != kNotImplemented) return SLT_ERROR_DEVICE;
        }
        const auto a = componente.getSize();
        const auto b = controlador.getSize();
        if (a < 0 || b < 0 || a + b > maxEstado - cabecalhoEstado) return SLT_ERROR_BUFFER_TOO_SMALL;
        const uint32_t cabecalho[] = {magicEstado, static_cast<uint32_t>(a), static_cast<uint32_t>(b)};
        *size = cabecalhoEstado + cabecalho[1] + cabecalho[2];
        if (capacity < *size) return SLT_ERROR_BUFFER_TOO_SMALL;
        auto* bytes = static_cast<char*>(data);
        std::memcpy(bytes, cabecalho, cabecalhoEstado);
        if (a) std::memcpy(bytes + cabecalhoEstado, componente.getData(), a);
        if (b) std::memcpy(bytes + cabecalhoEstado + a, controlador.getData(), b);
        return SLT_OK;
    } catch (...) {
        plugin->situacao->falhou.store(true);
        return SLT_ERROR_DEVICE;
    }
}

SLT_API slt_result slt_plugin_set_state(slt_plugin_handle* plugin, const void* data, uint32_t size) {
    if (!plugin || !data || size < cabecalhoEstado || size > maxEstado) return SLT_ERROR_INVALID_ARGUMENT;
    if (plugin->situacao->falhou.load()) return SLT_ERROR_DEVICE;
    uint32_t cabecalho[3];
    std::memcpy(cabecalho, data, cabecalhoEstado);
    const auto a = cabecalho[1], b = cabecalho[2];
    if (cabecalho[0] != magicEstado || a > size - cabecalhoEstado || b != size - cabecalhoEstado - a) return SLT_ERROR_INVALID_ARGUMENT;
    try {
        const auto* bytes = static_cast<const char*>(data) + cabecalhoEstado;
        // O SDK só aceita um ponteiro mutável para a memória da stream.
        std::vector<char> copia(bytes, bytes + a + b);
        MemoryStream componente(a ? copia.data() : nullptr, a);
        if (plugin->componente->setState(&componente) != kResultOk) return SLT_ERROR_DEVICE;
        if (plugin->controlador) {
            componente.seek(0, IBStream::kIBSeekSet, nullptr);
            plugin->controlador->setComponentState(&componente);
            if (b) {
                MemoryStream controlador(copia.data() + a, b);
                if (plugin->controlador->setState(&controlador) != kResultOk) return SLT_ERROR_DEVICE;
            }
            for (uint32_t i = 0; i < plugin->quantos; ++i) {
                auto& p = plugin->parametros[i];
                p.valor.store(plugin->controlador->getParamNormalized(p.info.id));
                p.aplicado = -1;
            }
        }
        return SLT_OK;
    } catch (...) {
        plugin->situacao->falhou.store(true);
        return SLT_ERROR_DEVICE;
    }
}
}
