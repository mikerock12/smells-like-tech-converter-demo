// Backend WASAPI: modo compartilhado, por eventos, numa thread própria com prioridade de
// áudio (MMCSS "Pro Audio"). A saída comanda o relógio; a captura (opcional) é drenada no
// mesmo laço e entra no mixer como monitoração e gravação.
//
// Baixa latência: quando o aparelho já está na taxa do mixer, em float32 estéreo, o fluxo
// pede ao IAudioClient3 o menor período que o driver aceita (em geral ~10 ms vira 2,7 a
// 5 ms). Senão, abre pelo caminho comum e o próprio WASAPI converte taxa e canais.
// Modo exclusivo fica para depois de medir em hardware real (plano v1.1, fase 4).
#define NOMINMAX
#include <windows.h>

#include <initguid.h>  // antes dos cabeçalhos abaixo: define PKEY_Device_FriendlyName e o subformato float aqui

#include <audioclient.h>
#include <avrt.h>
#include <mmdeviceapi.h>
#include <mmreg.h>
#include <propsys.h>

#include <functiondiscoverykeys_devpkey.h>
#include <ks.h>
#include <ksmedia.h>

#include "../../dispositivo.h"

namespace slt {

namespace {

const CLSID CLSID_Enumerador = __uuidof(MMDeviceEnumerator);
const IID IID_Enumerador = __uuidof(IMMDeviceEnumerator);
const IID IID_Cliente = __uuidof(IAudioClient);
const IID IID_Cliente3 = __uuidof(IAudioClient3);
const IID IID_Saida = __uuidof(IAudioRenderClient);
const IID IID_Captura = __uuidof(IAudioCaptureClient);

template <typename T>
void soltar(T*& p) {
    if (p != nullptr) {
        p->Release();
        p = nullptr;
    }
}

void paraUtf8(const wchar_t* texto, char* destino, int capacidade) {
    destino[0] = 0;
    if (texto == nullptr) return;
    const int n = WideCharToMultiByte(CP_UTF8, 0, texto, -1, destino, capacidade, nullptr, nullptr);
    if (n == 0) destino[capacidade - 1] = 0;
}

/** COM nesta thread, sem atrapalhar quem já iniciou de outro jeito. */
struct Com {
    HRESULT resultado;
    Com() : resultado(CoInitializeEx(nullptr, COINIT_MULTITHREADED)) {}
    ~Com() {
        if (SUCCEEDED(resultado)) CoUninitialize();
    }
    bool ok() const { return SUCCEEDED(resultado) || resultado == RPC_E_CHANGED_MODE; }
};

WAVEFORMATEXTENSIBLE formatoFloat(uint32_t taxa, uint32_t canais) {
    WAVEFORMATEXTENSIBLE f = {};
    f.Format.wFormatTag = WAVE_FORMAT_EXTENSIBLE;
    f.Format.nChannels = static_cast<WORD>(canais);
    f.Format.nSamplesPerSec = taxa;
    f.Format.wBitsPerSample = 32;
    f.Format.nBlockAlign = static_cast<WORD>(canais * 4);
    f.Format.nAvgBytesPerSec = taxa * canais * 4;
    f.Format.cbSize = sizeof(WAVEFORMATEXTENSIBLE) - sizeof(WAVEFORMATEX);
    f.Samples.wValidBitsPerSample = 32;
    f.dwChannelMask = canais == 1 ? SPEAKER_FRONT_CENTER : (SPEAKER_FRONT_LEFT | SPEAKER_FRONT_RIGHT);
    f.SubFormat = KSDATAFORMAT_SUBTYPE_IEEE_FLOAT;
    return f;
}

bool ehFloatEstereo(const WAVEFORMATEX* f, uint32_t taxa) {
    if (f->nChannels != 2 || f->nSamplesPerSec != taxa || f->wBitsPerSample != 32) return false;
    if (f->wFormatTag == WAVE_FORMAT_IEEE_FLOAT) return true;
    return f->wFormatTag == WAVE_FORMAT_EXTENSIBLE && reinterpret_cast<const WAVEFORMATEXTENSIBLE*>(f)->SubFormat == KSDATAFORMAT_SUBTYPE_IEEE_FLOAT;
}

IMMDevice* aparelho(IMMDeviceEnumerator* enumerador, const char* id, EDataFlow fluxo) {
    IMMDevice* device = nullptr;
    if (id != nullptr && id[0] != 0) {
        wchar_t largo[512];
        if (MultiByteToWideChar(CP_UTF8, 0, id, -1, largo, 512) == 0) return nullptr;
        enumerador->GetDevice(largo, &device);
    } else {
        enumerador->GetDefaultAudioEndpoint(fluxo, eConsole, &device);
    }
    return device;
}

struct FluxoWasapi : Fluxo {
    IAudioClient* clienteDeSaida = nullptr;
    IAudioRenderClient* saida = nullptr;
    IAudioClient* clienteDeEntrada = nullptr;
    IAudioCaptureClient* captura = nullptr;
    HANDLE evento = nullptr;
    HANDLE parada = nullptr;
    HANDLE thread = nullptr;
    uint32_t bufferDaSaida = 0;
    // Fila da captura até o próximo bloco de saída (só a thread de áudio mexe).
    Vetor<float> filaE, filaD;
    uint32_t naFila = 0;
    bool capturaComecou = false;

    static DWORD WINAPI entrada(void* eu) {
        static_cast<FluxoWasapi*>(eu)->laco();
        return 0;
    }

    void drenarCaptura() {
        if (captura == nullptr) return;
        for (;;) {
            UINT32 pacote = 0;
            if (FAILED(captura->GetNextPacketSize(&pacote)) || pacote == 0) return;
            BYTE* dados = nullptr;
            UINT32 quadros = 0;
            DWORD sinais = 0;
            if (FAILED(captura->GetBuffer(&dados, &quadros, &sinais, nullptr, nullptr))) return;
            // O primeiro pacote depois de iniciar sempre vem marcado como descontínuo: não é falha.
            if ((sinais & AUDCLNT_BUFFERFLAGS_DATA_DISCONTINUITY) != 0 && capturaComecou) anotarFalha();
            capturaComecou = true;
            const float* amostras = reinterpret_cast<const float*>(dados);
            const bool silencio = (sinais & AUDCLNT_BUFFERFLAGS_SILENT) != 0;
            for (UINT32 i = 0; i < quadros; i += 1) {
                // A saída não está consumindo (parada?): o que não cabe se perde.
                if (naFila == filaE.n) break;
                filaE[naFila] = silencio ? 0.0f : amostras[i * 2];
                filaD[naFila] = silencio ? 0.0f : amostras[i * 2 + 1];
                naFila += 1;
            }
            captura->ReleaseBuffer(quadros);
        }
    }

    void laco() {
        Com com;
        DWORD tarefa = 0;
        HANDLE prioridade = AvSetMmThreadCharacteristicsW(L"Pro Audio", &tarefa);
        const HANDLE esperas[2] = {parada, evento};
        for (;;) {
            const DWORD acordou = WaitForMultipleObjects(2, esperas, FALSE, 2000);
            if (acordou == WAIT_OBJECT_0) break;
            if (acordou == WAIT_TIMEOUT) {
                // Dois segundos sem evento: o aparelho parou de pedir áudio.
                perdido.guardar(1);
                break;
            }
            UINT32 ocupado = 0;
            HRESULT hr = clienteDeSaida->GetCurrentPadding(&ocupado);
            if (FAILED(hr)) {
                perdido.guardar(1);
                break;
            }
            UINT32 livre = bufferDaSaida - ocupado;
            drenarCaptura();
            while (livre > 0) {
                const UINT32 n = livre < BLOCO_MAXIMO_DO_FLUXO ? livre : BLOCO_MAXIMO_DO_FLUXO;
                BYTE* dados = nullptr;
                hr = saida->GetBuffer(n, &dados);
                if (FAILED(hr)) {
                    perdido.guardar(1);
                    break;
                }
                const bool temEntrada = captura != nullptr;
                if (temEntrada) {
                    const uint32_t tirar = naFila < n ? naFila : n;
                    memcpy(entradaE.dados, filaE.dados, tirar * sizeof(float));
                    memcpy(entradaD.dados, filaD.dados, tirar * sizeof(float));
                    memset(entradaE.dados + tirar, 0, (n - tirar) * sizeof(float));
                    memset(entradaD.dados + tirar, 0, (n - tirar) * sizeof(float));
                    memmove(filaE.dados, filaE.dados + tirar, (naFila - tirar) * sizeof(float));
                    memmove(filaD.dados, filaD.dados + tirar, (naFila - tirar) * sizeof(float));
                    naFila -= tirar;
                }
                bloco(n, temEntrada);
                float* intercalado = reinterpret_cast<float*>(dados);
                for (UINT32 i = 0; i < n; i += 1) {
                    intercalado[i * 2] = saidaE[i];
                    intercalado[i * 2 + 1] = saidaD[i];
                }
                saida->ReleaseBuffer(n, 0);
                livre -= n;
            }
            if (perdido.ler() != 0) break;
        }
        if (prioridade != nullptr) AvRevertMmThreadCharacteristics(prioridade);
    }

    slt_result iniciar() override {
        if (thread != nullptr) return SLT_OK;
        if (perdido.ler() != 0) return SLT_ERROR_DEVICE;
        ResetEvent(parada);
        // O primeiro buffer vai em silêncio, para o aparelho começar a pedir.
        BYTE* dados = nullptr;
        if (SUCCEEDED(saida->GetBuffer(bufferDaSaida, &dados))) saida->ReleaseBuffer(bufferDaSaida, AUDCLNT_BUFFERFLAGS_SILENT);
        if (clienteDeEntrada != nullptr && FAILED(clienteDeEntrada->Start())) return SLT_ERROR_DEVICE;
        thread = CreateThread(nullptr, 0, entrada, this, 0, nullptr);
        if (thread == nullptr) return SLT_ERROR_DEVICE;
        if (FAILED(clienteDeSaida->Start())) {
            parar();
            return SLT_ERROR_DEVICE;
        }
        info.running = 1;
        return SLT_OK;
    }

    slt_result parar() override {
        if (thread != nullptr) {
            SetEvent(parada);
            WaitForSingleObject(thread, 5000);
            CloseHandle(thread);
            thread = nullptr;
        }
        if (clienteDeSaida != nullptr) {
            clienteDeSaida->Stop();
            clienteDeSaida->Reset();
        }
        if (clienteDeEntrada != nullptr) {
            clienteDeEntrada->Stop();
            clienteDeEntrada->Reset();
        }
        naFila = 0;
        capturaComecou = false;
        info.running = 0;
        return SLT_OK;
    }

    void fechar() override {
        parar();
        soltar(saida);
        soltar(captura);
        soltar(clienteDeSaida);
        soltar(clienteDeEntrada);
        if (evento != nullptr) CloseHandle(evento);
        if (parada != nullptr) CloseHandle(parada);
        filaE.soltar();
        filaD.soltar();
        soltarComum();
        this->~FluxoWasapi();
        liberar(this);
    }
};

/** Abre o cliente no formato do mixer; tenta o período curto do IAudioClient3 quando dá. */
HRESULT iniciarCliente(IAudioClient* cliente, uint32_t taxa, uint32_t blocoDesejado, bool porEvento, bool* baixaLatencia) {
    *baixaLatencia = false;
    WAVEFORMATEXTENSIBLE formato = formatoFloat(taxa, 2);
    WAVEFORMATEX* doAparelho = nullptr;
    const DWORD evento = porEvento ? AUDCLNT_STREAMFLAGS_EVENTCALLBACK : 0;
    if (SUCCEEDED(cliente->GetMixFormat(&doAparelho)) && doAparelho != nullptr) {
        const bool direto = ehFloatEstereo(doAparelho, taxa);
        IAudioClient3* cliente3 = nullptr;
        if (direto && SUCCEEDED(cliente->QueryInterface(IID_Cliente3, reinterpret_cast<void**>(&cliente3)))) {
            UINT32 padrao = 0, fundamental = 0, minimo = 0, maximo = 0;
            if (SUCCEEDED(cliente3->GetSharedModeEnginePeriod(doAparelho, &padrao, &fundamental, &minimo, &maximo)) && fundamental > 0) {
                UINT32 periodo = blocoDesejado == 0 ? minimo : blocoDesejado;
                periodo = (periodo / fundamental) * fundamental;
                if (periodo < minimo) periodo = minimo;
                if (periodo > maximo) periodo = maximo;
                if (SUCCEEDED(cliente3->InitializeSharedAudioStream(evento, periodo, doAparelho, nullptr))) {
                    *baixaLatencia = periodo < padrao;
                    cliente3->Release();
                    CoTaskMemFree(doAparelho);
                    return S_OK;
                }
            }
            cliente3->Release();
        }
        CoTaskMemFree(doAparelho);
    }
    const REFERENCE_TIME duracao = blocoDesejado > 0 ? static_cast<REFERENCE_TIME>(blocoDesejado) * 10000000 / taxa : 0;
    return cliente->Initialize(AUDCLNT_SHAREMODE_SHARED, evento | AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM | AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY, duracao, 0, &formato.Format, nullptr);
}

uint32_t latenciaEmQuadros(IAudioClient* cliente, uint32_t taxa) {
    REFERENCE_TIME latencia = 0;
    if (FAILED(cliente->GetStreamLatency(&latencia))) return 0;
    return static_cast<uint32_t>(latencia * taxa / 10000000);
}

}  // namespace

slt_result listarWasapi(slt_device_info* dispositivos, uint32_t capacidade, uint32_t* quantos) {
    Com com;
    if (!com.ok()) return SLT_ERROR_DEVICE;
    IMMDeviceEnumerator* enumerador = nullptr;
    if (FAILED(CoCreateInstance(CLSID_Enumerador, nullptr, CLSCTX_ALL, IID_Enumerador, reinterpret_cast<void**>(&enumerador)))) return SLT_ERROR_DEVICE;
    uint32_t total = 0;
    for (int sentido = 0; sentido < 2; sentido += 1) {
        const EDataFlow fluxo = sentido == 0 ? eRender : eCapture;
        wchar_t* padrao = nullptr;
        IMMDevice* aparelhoPadrao = nullptr;
        if (SUCCEEDED(enumerador->GetDefaultAudioEndpoint(fluxo, eConsole, &aparelhoPadrao))) {
            aparelhoPadrao->GetId(&padrao);
            aparelhoPadrao->Release();
        }
        IMMDeviceCollection* colecao = nullptr;
        if (FAILED(enumerador->EnumAudioEndpoints(fluxo, DEVICE_STATE_ACTIVE, &colecao))) continue;
        UINT n = 0;
        colecao->GetCount(&n);
        for (UINT i = 0; i < n; i += 1) {
            IMMDevice* device = nullptr;
            if (FAILED(colecao->Item(i, &device))) continue;
            if (total < capacidade) {
                slt_device_info& d = dispositivos[total];
                memset(&d, 0, sizeof(d));
                d.backend = SLT_BACKEND_WASAPI;
                wchar_t* id = nullptr;
                if (SUCCEEDED(device->GetId(&id))) {
                    paraUtf8(id, d.id, sizeof(d.id));
                    d.is_default = padrao != nullptr && wcscmp(id, padrao) == 0 ? 1 : 0;
                    CoTaskMemFree(id);
                }
                IPropertyStore* propriedades = nullptr;
                if (SUCCEEDED(device->OpenPropertyStore(STGM_READ, &propriedades))) {
                    PROPVARIANT nome;
                    PropVariantInit(&nome);
                    if (SUCCEEDED(propriedades->GetValue(PKEY_Device_FriendlyName, &nome)) && nome.vt == VT_LPWSTR) paraUtf8(nome.pwszVal, d.name, sizeof(d.name));
                    PropVariantClear(&nome);
                    propriedades->Release();
                }
                IAudioClient* cliente = nullptr;
                if (SUCCEEDED(device->Activate(IID_Cliente, CLSCTX_ALL, nullptr, reinterpret_cast<void**>(&cliente)))) {
                    WAVEFORMATEX* formato = nullptr;
                    if (SUCCEEDED(cliente->GetMixFormat(&formato)) && formato != nullptr) {
                        d.sample_rate = formato->nSamplesPerSec;
                        if (sentido == 0) d.output_channels = formato->nChannels;
                        else d.input_channels = formato->nChannels;
                        CoTaskMemFree(formato);
                    }
                    cliente->Release();
                }
            }
            total += 1;
            device->Release();
        }
        colecao->Release();
        if (padrao != nullptr) CoTaskMemFree(padrao);
    }
    enumerador->Release();
    *quantos = total;
    return SLT_OK;
}

slt_result abrirWasapi(const slt_stream_config& config, Misturador* misturador, Fluxo** fluxo) {
    Com com;
    if (!com.ok()) return SLT_ERROR_DEVICE;
    FluxoWasapi* f = criarObjeto<FluxoWasapi>();
    if (f == nullptr) return SLT_ERROR_OUT_OF_MEMORY;
    const uint32_t taxa = misturador->taxa;
    // Meio minuto de captura no anel: o consumidor lê muito antes disso.
    if (!f->preparar(misturador, taxa * 30) || !f->filaE.criar(taxa) || !f->filaD.criar(taxa)) {
        f->fechar();
        return SLT_ERROR_OUT_OF_MEMORY;
    }
    slt_result resultado = SLT_ERROR_DEVICE;
    IMMDeviceEnumerator* enumerador = nullptr;
    IMMDevice* deSaida = nullptr;
    IMMDevice* deEntrada = nullptr;
    bool baixaLatencia = false;
    do {
        if (FAILED(CoCreateInstance(CLSID_Enumerador, nullptr, CLSCTX_ALL, IID_Enumerador, reinterpret_cast<void**>(&enumerador)))) break;
        deSaida = aparelho(enumerador, config.output_id, eRender);
        if (deSaida == nullptr) break;
        if (FAILED(deSaida->Activate(IID_Cliente, CLSCTX_ALL, nullptr, reinterpret_cast<void**>(&f->clienteDeSaida)))) break;
        if (FAILED(iniciarCliente(f->clienteDeSaida, taxa, config.buffer_frames, true, &baixaLatencia))) break;
        f->evento = CreateEventW(nullptr, FALSE, FALSE, nullptr);
        f->parada = CreateEventW(nullptr, TRUE, FALSE, nullptr);
        if (f->evento == nullptr || f->parada == nullptr) break;
        if (FAILED(f->clienteDeSaida->SetEventHandle(f->evento))) break;
        if (FAILED(f->clienteDeSaida->GetBufferSize(&f->bufferDaSaida)) || f->bufferDaSaida == 0) break;
        if (FAILED(f->clienteDeSaida->GetService(IID_Saida, reinterpret_cast<void**>(&f->saida)))) break;
        f->info.backend = SLT_BACKEND_WASAPI;
        f->info.sample_rate = taxa;
        f->info.output_channels = 2;
        f->info.low_latency = baixaLatencia ? 1 : 0;
        REFERENCE_TIME periodo = 0;
        f->clienteDeSaida->GetDevicePeriod(&periodo, nullptr);
        f->info.buffer_frames = baixaLatencia || periodo == 0 ? f->bufferDaSaida : static_cast<uint32_t>(periodo * taxa / 10000000);
        f->info.output_latency_frames = latenciaEmQuadros(f->clienteDeSaida, taxa) + f->bufferDaSaida;
        if (config.use_input != 0) {
            // Sem microfone a saída abre do mesmo jeito: quem chamou vê input_channels = 0.
            deEntrada = aparelho(enumerador, config.input_id, eCapture);
            bool lixo = false;
            if (deEntrada != nullptr && SUCCEEDED(deEntrada->Activate(IID_Cliente, CLSCTX_ALL, nullptr, reinterpret_cast<void**>(&f->clienteDeEntrada))) &&
                SUCCEEDED(iniciarCliente(f->clienteDeEntrada, taxa, config.buffer_frames, false, &lixo)) && SUCCEEDED(f->clienteDeEntrada->GetService(IID_Captura, reinterpret_cast<void**>(&f->captura)))) {
                UINT32 bufferDaEntrada = 0;
                f->clienteDeEntrada->GetBufferSize(&bufferDaEntrada);
                f->info.input_channels = 2;
                f->info.input_latency_frames = latenciaEmQuadros(f->clienteDeEntrada, taxa) + f->info.buffer_frames;
            } else {
                soltar(f->captura);
                soltar(f->clienteDeEntrada);
            }
        }
        resultado = SLT_OK;
    } while (false);
    soltar(deEntrada);
    soltar(deSaida);
    soltar(enumerador);
    if (resultado != SLT_OK) {
        f->fechar();
        return resultado;
    }
    *fluxo = f;
    return SLT_OK;
}

}  // namespace slt
