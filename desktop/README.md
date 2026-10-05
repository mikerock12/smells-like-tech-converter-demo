# Smells Like Tech Converter Desktop

Aplicativo Windows local (`SmellsLikeTechConverter.exe`) em **C# + .NET 10 + WinUI 3**.
É a primeira interface do mesmo núcleo que, mais adiante, vai processar os jobs do SaaS.

```text
WinUI 3 (interface)
      |
      v
Converter.Core  ->  fila universal, job, presets, validação
      |
      +-- Engine.FFmpeg      vídeo, áudio, vídeo→áudio, GIF, frames
      +-- Engine.Image       imagens (ImageMagick / Magick.NET)
      +-- Engine.Speech      Whisper (STT) e SAPI (TTS)
      +-- Engine.Documents   PDF, OCR e documentos (marcos 6 e 7)
      |
      +-- Infrastructure     SQLite, pastas em D:, processos, logs, hardware
```

O princípio do projeto: **interface ≠ conversor**. A interface cria jobs e mostra resultados;
quem converte é o Core + engines, que serão reaproveitados como worker de nuvem sem reescrita.

---

## Estrutura

```text
desktop/
├── SmellsLikeTechConverter.slnx     (formato de solution do .NET 10 / VS 2022+)
├── Directory.Build.props
├── global.json
├── NuGet.Config
├── src/
│   ├── SmellsLikeTech.Converter.Core/
│   ├── SmellsLikeTech.Converter.Infrastructure/
│   ├── SmellsLikeTech.Converter.Engine.FFmpeg/
│   ├── SmellsLikeTech.Converter.Engine.Image/
│   ├── SmellsLikeTech.Converter.Engine.Speech/
│   ├── SmellsLikeTech.Converter.Engine.Documents/
│   └── SmellsLikeTech.Converter.Desktop/
└── tests/
    └── SmellsLikeTech.Converter.Tests/
```

---

## Compilar e executar

```bash
dotnet build desktop/SmellsLikeTechConverter.slnx
```

```bash
dotnet run --project desktop/src/SmellsLikeTech.Converter.Desktop
```

```bash
dotnet test desktop/tests/SmellsLikeTech.Converter.Tests
```

### Gerar o instalador

```bash
pwsh desktop/installer/build-installer.ps1
```

O script publica em Release (self-contained, `win-x64`) e empacota com o Inno Setup,
produzindo `desktop/artifacts/installer/SmellsLikeTechConverterSetup.exe` (~81 MB).
Use `-SkipPublish` para reempacotar sem republicar e `-Version 0.2.0` para carimbar
outra versão.

No fim, ele deixa ao lado uma cópia com a versão no nome
(`SmellsLikeTechConverterSetup-<versão>.exe`) e regrava `public/baixar/app.json` com
tamanho e SHA-256. O site não serve o `.exe` de `public/`: ele vai para o bucket R2 com
`npm run instaladores:publicar` (ver [docs/publicar.md](../docs/publicar.md#5-publicar)).
O `build-plugin-installer.ps1` faz o mesmo com o plugin e o `plugin.json`.

O instalador pergunta entre **instalar para todos** (`C:\Program Files\Smells Like Tech
Converter`, com UAC) e **somente para mim** (`%LOCALAPPDATA%\Programs\…`, sem elevação),
cria atalhos no Menu Iniciar e opcionalmente na área de trabalho, registra o programa no
Painel de Controle com desinstalador, e avisa se o FFmpeg não estiver no PATH.

A desinstalação remove o programa e os atalhos, mas **nunca** toca em
`D:\SmellsLikeTechConverter` — saídas, modelos, histórico e configurações do usuário ficam.

Requer o Inno Setup 6 (`winget install JRSoftware.InnoSetup`).

Para gerar apenas a pasta portátil, sem instalador:

```bash
dotnet publish desktop/src/SmellsLikeTech.Converter.Desktop -c Release -o dist/converter
```

> `EnableMsixTooling` precisa continuar `true` no csproj mesmo sem MSIX: é o que leva o
> XAML compilado e o `.pri` para a saída do publish. Sem isso, o app publicado não abre.

### Requisitos

| Item | Situação |
| --- | --- |
| .NET SDK 10 | obrigatório para compilar |
| Windows App SDK | vem por NuGet, embutido no executável (`WindowsAppSDKSelfContained`) |
| FFmpeg + FFprobe | obrigatórios em tempo de execução (PATH ou caminho configurado) |
| ImageMagick | embutido via Magick.NET, nada a instalar |
| PDF | embutido: PdfPig lê o texto, PDFium desenha as páginas, OpenXML escreve o DOCX |
| OCR | motor do próprio Windows; os idiomas vêm de Configurações do Windows › Idioma |
| Vozes SAPI | as que já estiverem instaladas no Windows |
| Modelos Whisper | baixados sob demanda em Configurações → Modelos, para `Models\Whisper` |

A build usada no desenvolvimento é a `ffmpeg-full` com `--enable-whisper`, que traz o
whisper.cpp embutido como filtro de áudio. Sem esse filtro, a transcrição não funciona.

---

## PDF e reconhecimento de texto

Tudo roda no próprio aplicativo, sem LibreOffice, Ghostscript ou Tesseract instalados:

- **PDF → DOCX, TXT, Markdown ou HTML.** O texto é lido com PdfPig e reagrupado em linhas
  pela posição das palavras na página. O DOCX sai como Office Open XML de verdade.
- **PDF escaneado.** Quando a página não tem camada de texto, ela é desenhada a 300 DPI
  (PDFium) e passa pelo reconhecimento do Windows. Um contrato de 18 páginas digitalizado
  levou 37 s. Páginas com texto continuam sendo lidas direto, sem OCR.
- **Imagem → texto.** Reconhecimento direto, com as quebras de linha preservadas.
- **PDF → imagens**, uma por página, de 72 a 600 DPI.

O `.doc` antigo (binário) e os formatos de escritório dependem do LibreOffice e ficam
para o marco 7. A interface só oferece o que o computador consegue entregar: os idiomas
de OCR listados são os realmente instalados no Windows.

## Vários arquivos de uma vez

A tela **Vários arquivos** aceita quantos arquivos a pessoa soltar — arrastando, ou pelo
botão *Selecionar vários* da tela principal. Soltar mais de um arquivo na tela principal
leva direto para lá.

Os arquivos são separados em grupos e **cada grupo faz uma pergunta só**:

```text
5 áudios            MP3, WAV · 42,3 MB
  (•) Converter todos do mesmo jeito
  ( ) Configurar um por um
  Conversão: Converter áudio      Formato: MP3
  [x] Normalizar volume (loudnorm)

3 PDFs              PDF · 8,1 MB
  (•) Converter todos do mesmo jeito
  Conversão: PDF → documento      Formato: DOCX
```

O critério do agrupamento não é a extensão: é **a lista de conversões que o arquivo
aceita**. MP3 e WAV caem no mesmo cartão porque oferecem as mesmas conversões; um vídeo
sem trilha sonora ganha cartão próprio, porque o cartão dos outros oferece "extrair
áudio" e "transcrever" — coisas que ele não tem como entregar.

**Configurar um por um** aparece em cada grupo, e não no lote inteiro: dá para deixar as
músicas todas juntas e ajustar só os vídeos. Ao trocar para esse modo, cada arquivo herda
o que já tinha sido escolhido para o grupo — quem só quer mudar um não recomeça do zero.

Tudo é montado e validado antes de qualquer coisa entrar na fila. Meia fila enviada e um
erro no meio deixaria a pessoa sem saber o que entrou e o que ficou de fora; o erro
aponta o arquivo pelo nome.

Não existe fila de lote: os jobs vão para a mesma fila universal de sempre, com os mesmos
limites de concorrência por família de operação.

Os campos de opção são um controle só (`Controls/ConversionOptionsView`), usado pela tela
de um arquivo e pela de lote. Duas cópias ficariam diferentes na primeira correção feita
em apenas uma delas.

Pastas ainda não são aceitas — só arquivos.

## Escolha do modelo de transcrição

Medido em um áudio real de conversa (60 s, voice note de WhatsApp a 19 kbps, português
com sotaque gaúcho e nomes de lugares), em CPU de quatro núcleos sem GPU:

| Modelo | Tamanho | Tempo | Fator | Resultado |
| --- | --- | --- | --- | --- |
| Base | 141 MB | 13 s | 0,2x | erra nomes próprios e fala regional |
| Small | 465 MB | 37 s | 0,6x | bom; ainda erra alguns nomes |
| Medium | 1,4 GB | 103 s | 1,7x | muito bom; erra palavras isoladas |
| Large v3 | 2,9 GB | 151 s | 2,5x | praticamente exato |

O padrão de fábrica é **Small**, que atende bem sem exigir 3 GB de download. Para
transcrição fiel, **Large v3** compensa: são 2,5x a duração do áudio. Para gravações
longas, vale descer para Medium ou Small.

O modelo padrão é escolhido em Configurações → Modelos. Se o escolhido não estiver
baixado, a conversão usa automaticamente o melhor que estiver instalado.

A janela de contexto entregue ao motor é de 30 s, a nativa do Whisper. Com a janela
padrão do filtro (3 s) o modelo perde o fio da frase, troca de idioma no meio e inventa
trechos — além de ficar várias vezes mais lento.

## Pastas de trabalho

```text
D:\SmellsLikeTechConverter\
├── Temp\<JobId>\{input,work,output,temp}
├── Cache\
├── Models\Whisper\
├── Logs\   (app.log, jobs.log, ffmpeg.log, speech.log, errors.log)
├── FailedJobs\
├── Output\
└── converter.db   (histórico, presets, configurações, últimos diretórios)
```

Se o disco `D:` não existir, o aplicativo cai automaticamente para
`%LOCALAPPDATA%\SmellsLikeTechConverter` em vez de falhar ao abrir.

---

## O que já funciona

| Marco | Estado |
| --- | --- |
| 1 — Base (drag and drop, detecção, job, fila, progresso, cancelamento, logs) | pronto |
| 2 — Vídeo e áudio (formato, resolução, proporção, FPS, corte, velocidade, GIF, frames) | pronto |
| Vídeo → áudio (MP3, WAV, FLAC, AAC, M4A, OGG, OPUS) | pronto |
| 3 — Imagens (converter, resize, crop, proporção, rotação, espelho, qualidade, metadados) | pronto |
| 4 — Speech-to-Text (TXT, SRT, VTT com Whisper local) | pronto |
| 5 — Text-to-Speech (WAV, MP3, M4A, OGG com SAPI + FFmpeg) | pronto |
| 6 — PDF → DOCX/TXT/MD/HTML, PDF → imagens, imagem → texto (OCR) | pronto |
| 6 — Juntar, dividir e imagens → PDF (PdfPig, sem ferramenta externa) | pronto no código da 0.3.0 |
| 7 — Documentos de escritório (LibreOffice/Pandoc) | pendente |
| 8 — Conversão em lote (vários arquivos, agrupados por tipo) | pronto |
| 8 — Lote a partir de pastas | pendente |
| 9 — Instalador | pronto |
| 10 — Licença vitalícia com 7 dias de teste (chave assinada, conferida sem internet) | pronto no código da 0.3.0 |

As operações dos marcos 6 e 7 aparecem na interface marcadas como indisponíveis: o produto
não promete o que ainda não existe.

---

## Licença

O aplicativo é pago, com licença vitalícia, e começa com sete dias de teste completo. A
chave é a mesma que o site emite (`SLT1.<carga>.<assinatura>`, ECDSA P-256), e é
conferida em `Core/Licensing/LicenseVerifier.cs` com a chave pública real já embutida.
As mesmas chaves continuam valendo nas atualizações; os segredos e a recuperação
estão documentados em `docs/publicar.md`. `TrialPolicy`
decide entre licenciado, em teste e vencido; `Desktop/Services/LicenseGate` é a única
porta entre "converter" e a fila. O plugin não passa por nada disso: é gratuito.

A versão 0.3.1 inclui a narração de PDF (com OCR nas páginas escaneadas), imagem,
DOCX, Markdown e HTML, tanto no aplicativo quanto no plugin. A chave pública real
já está embutida; não gere outro par ao atualizar. Os instaladores e seus metadados
seguem o fluxo de `docs/publicar.md`. Ver [notas da versão](../docs/lancamento-0.3.1.md).

## Regras que valem desde o primeiro código

- Todo job tem UUID e pasta isolada; o nome enviado pelo usuário nunca vira caminho.
- Nenhum comando externo é montado por concatenação de texto — sempre executável + lista de argumentos.
- Formatos, bitrates, sample rates, idiomas, modelos e vozes passam por allowlist.
- Cancelamento encerra a árvore de processos sem derrubar o aplicativo.
- `TEMP`/`TMP` dos processos filhos apontam para a pasta do job, sem alterar o TEMP do Windows.
- O texto enviado para síntese e o conteúdo transcrito **não** vão para banco, logs ou histórico.
- O histórico guarda somente metadados: nome, formatos, tamanhos, tempos e motor.
- Jobs com falha mantêm a pasta por algumas horas para diagnóstico; o resto é apagado.
- Cancelar não deixa temporário para trás. Matar o processo externo não devolve os handles
  dele no mesmo instante, então a remoção da pasta tenta de novo por até ~1,5 s antes de
  desistir — e, se desistir, registra em `errors.log` em vez de sumir em silêncio. A
  limpeza da abertura seguinte não cobriria isso: ela só olha pastas com mais de 12 horas.

---

## Testes

`dotnet test` cobre 206 casos, entre eles:

- catálogo de operações por tipo de arquivo (vídeo sem áudio não oferece extração nem transcrição);
- agrupamento do lote: mesmo tipo junto, vídeo mudo separado, arquivo repetido contado uma
  vez só, e a mesma seleção produzindo sempre a mesma ordem de cartões;
- validação das opções (bitrate em WAV, WebM com H.264, copiar stream com redimensionamento…);
- montagem das linhas de comando do FFmpeg (mapeamento de faixa, `9:16` com fundo desfocado, GIF com paleta, filtro whisper);
- segmentação de texto longo sem perder palavras;
- normalização da legenda: numeração a partir de 1, corte no fim da mídia e descarte de marcadores de ruído;
- fila: execução, falha controlada, cancelamento, pausa, histórico;
- workspace: contenção de caminho, limpeza, e a remoção da pasta do job quando um arquivo
  dentro dela ainda está preso (o caso do cancelamento, abaixo);
- integração real com FFmpeg (extração de áudio, vídeo sem áudio, 9:16, GIF, progresso);
- ponta a ponta pela fila com SQLite: vídeo → MP3, vídeo → 9:16, imagem → WEBP, texto → MP3, PDF → MP3
  e o percurso completo de voz — a voz do Windows dita uma frase e o Whisper local a
  transcreve de volta em TXT, SRT e VTT.

Testes que dependem do FFmpeg, de vozes SAPI ou de um modelo Whisper instalado saem sem
falhar quando esses componentes não existem na máquina.

As classes de teste rodam em série de propósito: várias sobem FFmpeg de verdade e medem
tempo, e a disputa por CPU produzia falhas intermitentes sem defeito no produto.

---

## Licenças

Antes de distribuir comercialmente, revisar: FFmpeg e codecs, ImageMagick, whisper.cpp e
modelos, vozes SAPI, e, quando entrarem, LibreOffice, Pandoc e Tesseract. Manter a pasta
`Licenses\` com as atribuições necessárias.

---

Smells Like Tech Informática — CNPJ 30.054.253/0001-09 — <https://www.smellsliketech.com.br>
