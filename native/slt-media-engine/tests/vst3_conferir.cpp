// Confere um plugin VST3 no host do núcleo, como o aplicativo o usa: acha as classes do
// bundle, abre cada uma a 48 kHz, processa 200 blocos estéreo float32 de um sinal de teste
// (acorde com ataque) e diz se a saída é finita, se mudou e quanto. Ferramenta da curadoria
// da biblioteca de VST3 (docs/curadoria-vst3.md); não roda no CTest.
#include "slt_media_api.h"

#include <cmath>
#include <cstdio>
#include <vector>

int main(int argc, char** argv) {
    if (argc < 2) {
        std::fprintf(stderr, "uso: slt_vst3_conferir <bundle.vst3> [...]\n");
        return 2;
    }
    int falhas = 0;
    for (int a = 1; a < argc; ++a) {
        std::vector<slt_plugin_info> classes(16);
        uint32_t n = 0;
        const slt_result r = slt_plugin_scan(argv[a], classes.data(), static_cast<uint32_t>(classes.size()), &n);
        if (r != SLT_OK || n == 0) {
            std::printf("FALHOU scan %s (resultado %d, %u classes)\n", argv[a], static_cast<int>(r), n);
            ++falhas;
            continue;
        }
        for (uint32_t c = 0; c < n && c < classes.size(); ++c) {
            const slt_plugin_info& info = classes[c];
            slt_plugin_handle* plugin = nullptr;
            if (slt_plugin_open(info.path, info.class_id, 48000, 512, &plugin) != SLT_OK || plugin == nullptr) {
                std::printf("FALHOU abrir \"%s\" (%s)\n", info.name, info.vendor);
                ++falhas;
                continue;
            }
            std::vector<float> e(512), d(512);
            double entrada = 0, saida = 0, diferenca = 0;
            bool finita = true;
            uint64_t t = 0;
            for (int bloco = 0; bloco < 200; ++bloco) {
                for (int i = 0; i < 512; ++i, ++t) {
                    const double s = static_cast<double>(t) / 48000.0;
                    const double env = std::exp(-3.0 * std::fmod(s, 1.0));
                    const double x = 0.3 * env * (std::sin(6.2832 * 110 * s) + 0.5 * std::sin(6.2832 * 165 * s) + 0.3 * std::sin(6.2832 * 220.5 * s));
                    e[i] = static_cast<float>(x);
                    d[i] = static_cast<float>(x * 0.9);
                    entrada += x * x;
                }
                std::vector<float> e0 = e;
                if (slt_plugin_process(plugin, e.data(), d.data(), 512) != SLT_OK) { finita = false; break; }
                for (int i = 0; i < 512; ++i) {
                    if (!std::isfinite(e[i]) || !std::isfinite(d[i])) finita = false;
                    saida += static_cast<double>(e[i]) * e[i];
                    diferenca += (static_cast<double>(e[i]) - e0[i]) * (static_cast<double>(e[i]) - e0[i]);
                }
            }
            const double ganho = 10 * std::log10((saida + 1e-30) / (entrada + 1e-30));
            const double mudou = 10 * std::log10((diferenca + 1e-30) / (entrada + 1e-30));
            std::printf("%s \"%s\" (%s, %s) classe %s · latência %u · %u parâmetros · ganho %+.1f dB · diferença %.1f dB\n",
                finita ? "OK" : "FALHOU", info.name, info.vendor, info.version, info.class_id,
                slt_plugin_latency(plugin), slt_plugin_parameter_count(plugin), ganho, mudou);
            if (!finita) ++falhas;
            slt_plugin_close(plugin);
        }
    }
    return falhas == 0 ? 0 : 1;
}
