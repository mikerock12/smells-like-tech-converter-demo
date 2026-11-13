#include "slt_media_api.h"

#include <array>
#include <cmath>
#include <cstdio>
#include <filesystem>
#include <string>
#include <vector>

namespace {
int falhas = 0;
void conferir(bool passou, const char* mensagem) {
    if (passou) return;
    std::fprintf(stderr, "%s\n", mensagem);
    ++falhas;
}

bool perto(float a, float b) { return std::abs(a - b) < 1e-6f; }

std::string json(const char* texto) {
    std::string r = "\"";
    for (; *texto; ++texto) {
        if (*texto == '\\' || *texto == '"') r += '\\';
        r += *texto;
    }
    return r + '"';
}
}

int main(int argc, char** argv) {
    if (argc != 2) return 2;
    conferir((slt_get_capabilities() & SLT_CAP_VST3) != 0, "capacidade VST3 ausente");
    uint32_t n = 0;
    slt_plugin_info info = {};
    conferir(slt_plugin_scan(argv[1], &info, 1, &n) == SLT_OK && n == 1, "scan de ADelay");
    conferir(std::string(info.name) == "ADelay" && std::string(info.vendor).find("Steinberg") == 0, "metadados de ADelay");

    const auto inexistente = std::filesystem::path(argv[1]).parent_path() / "slt-teste-inexistente.vst3";
    const std::string caminhoRuim = inexistente.string();
    conferir(slt_plugin_scan(caminhoRuim.c_str(), nullptr, 0, &n) == SLT_ERROR_DEVICE, "módulo inválido");
    conferir(slt_plugin_scan(caminhoRuim.c_str(), nullptr, 0, &n) == SLT_OK && n == 0, "módulo em quarentena");

    slt_plugin_handle* plugin = nullptr;
    conferir(slt_plugin_open(info.path, info.class_id, 48000, 128, &plugin) == SLT_OK && plugin, "abrir ADelay");
    if (!plugin) return 1;
    conferir(slt_plugin_latency(plugin) == 0, "latência informada por ADelay");
    conferir(slt_plugin_parameter_count(plugin) == 2, "dois parâmetros");
    slt_plugin_parameter parametro = {};
    conferir(slt_plugin_get_parameter(plugin, 1, &parametro) == SLT_OK && parametro.id == 100, "parâmetro Delay");
    conferir(slt_plugin_set_parameter(plugin, 100, 0) == SLT_OK, "configurar atraso mínimo");

    std::array<float, 128> e{}, d{};
    e[0] = 0.25f;
    d[0] = -0.5f;
    conferir(slt_plugin_process(plugin, e.data(), d.data(), 128) == SLT_OK, "processar ADelay float32");
    conferir(perto(e[0], 0) && perto(e[1], 0.25f) && perto(d[1], -0.5f), "impulso atrasado em uma amostra");

    uint32_t tamanho = 0;
    conferir(slt_plugin_get_state(plugin, nullptr, 0, &tamanho) == SLT_ERROR_BUFFER_TOO_SMALL && tamanho > 12, "tamanho do preset");
    std::vector<char> estado(tamanho);
    conferir(slt_plugin_get_state(plugin, estado.data(), tamanho, &tamanho) == SLT_OK, "salvar preset");
    conferir(slt_plugin_set_parameter(plugin, 100, 0.1) == SLT_OK, "alterar atraso");
    conferir(slt_plugin_process(plugin, e.data(), d.data(), 128) == SLT_OK, "aplicar novo atraso");
    conferir(slt_plugin_set_state(plugin, estado.data(), tamanho) == SLT_OK, "restaurar preset");
    conferir(slt_plugin_get_parameter(plugin, 1, &parametro) == SLT_OK && parametro.value == 0, "restaurar valor do atraso");
    slt_plugin_close(plugin);

    auto* mixer = slt_mixer_create(48000);
    const float impulso[] = {0.25f};
    const std::string projeto = R"({"taxa":48000,"faixas":[{"id":"f","clipes":[{"midia":"m","duracao":1}],"efeitos":[{"id":"a","tipo":"vst3","caminho":)" +
        json(info.path) + R"(,"classe":)" + json(info.class_id) + R"(,"parametros":[{"id":100,"valor":0}]}]}]})";
    conferir(slt_mixer_set_source(mixer, "m", impulso, nullptr, 1) == SLT_OK, "fonte do mixer");
    conferir(slt_mixer_set_project(mixer, projeto.data(), static_cast<uint32_t>(projeto.size())) == SLT_OK, "projeto VST3");
    conferir(slt_mixer_render(mixer, 0, 128, e.data(), d.data(), nullptr) == SLT_OK, "render com VST3");
    conferir(perto(e[0], 0) && perto(e[1], 0.25f) && perto(d[1], 0.25f), "mixer processou ADelay");
    slt_mixer_destroy(mixer);
    return falhas == 0 ? 0 : 1;
}
