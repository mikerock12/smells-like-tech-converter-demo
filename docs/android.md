# O app para Android

Decidido em 28/09/2026. No celular, o plugin não funciona (ele é um programa para
Windows). Quem quer as funções pesadas no celular usa o **app para Android**, que
converte tudo sozinho, sem o site e sem enviar arquivo: 7 dias de teste completo e depois uma
licença vitalícia de R$ 249, o valor do aplicativo para Windows. O "Tudo, para sempre"
inclui o app.

**Só pela Google Play (30/09/2026).** O app sai pela loja oficial, com a compra dentro
dele pelo Google Play (docs/play-store.md). O site não oferece o APK: `/android` diz "em
breve na Google Play" até a página da loja ser publicada, e aí `ANDROID_NA_PLAY` em
`packages/converter-core/android.mjs` vira verdadeiro e o site passa a levar à loja. A 0.1.0
chegou a ficar no site por algumas horas, como APK, e foi retirada no mesmo dia.

## Como é feito

`android/` · Kotlin + Jetpack Compose · pacote `br.com.smellsliketech.converter` ·
Android 10 (API 29) ou mais novo, alvo Android 16 (API 36) · Gradle 8.14.5, Android Gradle
Plugin 8.13.2, Kotlin 2.2.21.

**Paridade com o Windows (0.3.0, 30/09/2026).** O app tem as operações do aplicativo para
Windows (`OperationCatalog`), com os mesmos formatos e ajustes: a lista está em
`conversao/Modelo.kt` (`Ferramenta`, `Opcoes`, `Listas`). Por tipo de arquivo:

| Ferramenta | Motor | Arquivo |
| --- | --- | --- |
| Imagem: JPG, PNG, WEBP (e HEIC, AVIF na entrada) | `ImageDecoder` + `Bitmap.compress` | `conversao/Imagem.kt` |
| Imagem: AVIF, TIFF, BMP, GIF na saída; TIFF na entrada | FFmpeg (libaom etc.) | `conversao/Imagem.kt` |
| Ícone (.ico, 16 a 256 px) | PNGs dentro do ICO | `conversao/Ico.kt` |
| Imagens em um PDF | `android.graphics.pdf.PdfDocument` | `conversao/Imagem.kt` |
| Vídeo (MP4, MKV, MOV, WEBM, AVI), GIF, quadros | FFmpeg; o H.264 pelo `MediaCodec` do celular | `conversao/Midia.kt`, `ffmpeg/` |
| Áudio (MP3, WAV, FLAC, AAC, M4A, OGG, OPUS, WMA) | FFmpeg (LAME, Opus, Vorbis, WMA) | `conversao/Midia.kt` |
| Transcrição (TXT, SRT, VTT) | filtro `whisper` do FFmpeg (whisper.cpp) | `conversao/Midia.kt`, `ModelosWhisper.kt` |
| Legendas (SRT, VTT, TXT) | Kotlin | `conversao/Legenda.kt` |
| PDF (juntar, dividir em 3 modos, girar) | PdfBox-Android | `conversao/Pdf.kt` |
| PDF para imagem (72 a 600 dpi), para documento | `PdfRenderer`, PdfBox e ML Kit | `conversao/Pdf.kt` |
| Documentos: DOC, DOCX, ODT, RTF, MD, HTML, EPUB, XLS, XLSX, ODS, CSV, PPT, PPTX, ODP | leitores em Kotlin (jsoup no HTML) | `conversao/documentos/` |
| Documentos para PDF, DOCX, TXT, HTML, MD, CSV | `PdfDocument` e escritores em Kotlin | `conversao/documentos/` |
| OCR | ML Kit, modelo embutido | `conversao/Ocr.kt` |
| Narração, em qualquer formato de áudio | Kokoro-82M + Sherpa-ONNX + FFmpeg | `conversao/Narracao.kt`, `Kokoro.kt` |

Os resultados vão para `Downloads/SmellsLikeTech` pelo MediaStore, sem permissão de
armazenamento.

**O FFmpeg do app.** Um executável (FFmpeg 8.1.3 com whisper.cpp 1.9.4, LAME, Opus, Vorbis,
libvpx e libaom, tudo LGPL ou mais permissivo, sem x264), compilado para arm64-v8a,
armeabi-v7a e x86_64 por `android/nativos/compilar.sh` no GitHub Actions
(`.github/workflows/android-nativos.yml`). Cada compilação vira uma release
`android-nativos-N`; `android/nativos/versao.json` diz qual o app usa e o SHA-256 de cada
arquivo, e `npm run android:nativos` baixa e confere (o `apk` e o `aab` fazem isso
sozinhos). O executável entra como `jniLibs/<abi>/libffmpeg.so` e o Android o extrai para a
pasta das bibliotecas nativas, de onde o app pode executá-lo (`useLegacyPackaging`). O app
roda o FFmpeg como processo à parte (`ffmpeg/Ffmpeg.kt`); os comandos saem de
`ffmpeg/Comandos.kt`, uma versão do `FfmpegCommandPlanner` do Windows, testada no
computador.

**H.264 pelo celular.** O MediaCodec chamado de dentro do FFmpeg para no meio (testado no
emulador: "Encoder returned EOF" depois de poucos quadros, e o H.265 travou). Então o FFmpeg
decodifica e filtra, e manda os quadros crus (YUV 4:2:0, ritmo constante) pela saída
padrão; `ffmpeg/CodificadorDoCelular.kt` os entrega ao `MediaCodec` pela API do Android e
grava com o `MediaMuxer`; o FFmpeg junta com o áudio AAC sem recodificar. Se o aparelho
recusa o tamanho, o vídeo sai em MPEG-4 pelo FFmpeg.

**Licenças.** `bash android/nativos/licencas.sh` monta `assets/licencas-nativas.txt` com o
texto de cada licença, tirado dos mesmos códigos-fonte, e o próprio `compilar.sh` (a
receita, que a LGPL pede), e copia para `public/codigo-aberto/licencas-android.txt`. O
arquivo vai dentro do app, junto de `assets/licencas.txt` (as bibliotecas Java/Kotlin); o
link "Código aberto" do app abre a página `/codigo-aberto` do site, que resume o código
aberto do site, do app e do aplicativo para Windows e aponta para o texto completo. Rode o
script de novo ao mudar uma versão no `compilar.sh`.

**Progresso e tempo estimado (0.3.1).** Toda conversão mostra a porcentagem e quanto falta
(`conversao/Andamento.kt`): o tempo que já levou, proporcional ao que falta, suavizado. As
que não têm como medir por dentro (imagem, documento, OCR, PDF) avançam por etapas. Na
transcrição, o filtro whisper só trabalha em blocos de 30 s, então a porcentagem vem de
`ProgressoDaTranscricao`: o log do filtro (em nível "info") avisa quando começa cada bloco,
e entre um aviso e outro a barra anda pelo ritmo do aparelho (segundos de processamento por
segundo de áudio), medido nos blocos anteriores ou guardado da última transcrição com o
mesmo modelo (`ModelosWhisper.ritmo`). O Whisper usa até 4 linhas de processamento.

**Transcrição na tela.** O texto volta para o cartão da conversão, em parágrafos (quebra a
cada pausa de 1,5 s), numa caixa com "Copiar o texto"; embaixo, "Salvar como" TXT, PDF,
Word (.docx), legenda SRT ou VTT e Markdown (`Transcricao.kt`, `Midia.salvar`). Nada é
gravado antes de a pessoa escolher.

**Conversões offline.** OCR e motores de mídia vêm no APK. Desde a 0.4.0, a narração
usa Kokoro-82M com Dora, Alex e Santa incluídos no pacote, sem baixar vozes do sistema.
Só a transcrição exige preparar um modelo do Whisper antes do primeiro uso offline.
Compras/restauração pela Play, instalar/atualizar, páginas externas e baixar arquivos da
nuvem precisam de internet; uma licença já ativada é verificada localmente. O rodapé do
app explica essas condições com asterisco. A permissão de internet do app permite
baixar um modelo do Whisper, quando a pessoa pede, pelo `DownloadManager`, do repositório
`ggerganov/whisper.cpp` no Hugging Face. O arquivo só é usado depois de conferido pelo
SHA-256 (`ModeloWhisper`). Os modelos: Tiny, Base, Small (recomendado), Medium, Large v3
Turbo e Large v3, os três últimos comprimidos (q5_0). O ML Kit mandaria estatísticas pela
mesma internet: o manifesto tira o `TransportBackendDiscovery` do datatransport, e os
eventos dele são descartados no aparelho (conferir no manifesto final a cada atualização
do ML Kit).

**Compartilhar com o app:** a galeria, o WhatsApp e o app Arquivos mostram "Converter" no
menu Compartilhar (`ACTION_SEND` e `ACTION_SEND_MULTIPLE`), para mídia, PDF, documentos do
Office e do LibreOffice, EPUB, legendas e `application/octet-stream` (o WhatsApp manda
documento assim; a extensão decide). Sem nome nem tipo, vale o tipo que o remetente
declarou. Texto compartilhado vai para a narração.

**Licença:** a mesma chave `SLT1.<carga>.<assinatura>` do site e do Windows, conferida no
celular com a mesma chave pública (`licenca/Licenca.kt`). Liberam o app os planos
`android` e `completo`. O teste de 7 dias conta da primeira abertura (`licenca/Acesso.kt`).
`tests/chave-publica.test.mjs` confere que a chave pública do Kotlin é a do site.

**Testado no emulador (0.3.0):** vídeo para MP4 480p (H.264 do celular + AAC), WAV para
OPUS, download do Tiny com conferência do SHA-256, transcrição em SRT, DOC e XLS para PDF,
narração em MP3 e JPG para AVIF.

## Compilar e testar

**0.3.3 (versionCode 7, 03/10/2026):** paridade com as 28 ferramentas atuais do site,
incluindo compressão de PDF preservando texto/vetores, GIF → vídeo com repetição,
edição de áudio (volume, velocidade, normalização, corte e fades) e atraso de legendas.
OCR de TIFF/AVIF usa o mesmo leitor local da conversão. PDFs protegidos/assinados não
são recomprimidos; imagens com máscaras, transparência ou perfis especiais ficam
intactas, e a saída nunca aumenta. `tests/android-paridade.test.mjs` exige equivalente
Android para cada ferramenta nova no catálogo do site.

O CI também executa os testes das duas variantes e compila um APK com R8; a chave
oficial nunca vai ao GitHub. Para teste real do APK oficial (12 casos, incluindo OCR,
PDF escaneado, narração e transcrição), compile:

```powershell
./android/gradlew.bat -p android :app:assembleSiteReleaseAndroidTest -PtesteRelease=true
```

Instale o APK oficial e o APK de teste assinados no emulador, instale Tiny e uma voz
offline em português antes de desligar a rede. Ative modo avião, desligue Wi-Fi/dados,
e rode `adb shell am instrument -w -e class br.com.smellsliketech.converter.conversao.OfflineTest br.com.smellsliketech.converter.test/androidx.test.runner.AndroidJUnitRunner`.
A suíte exige modo avião e comprova que uma conexão HTTP não alcança o site antes de
cada caso. Só remove os arquivos artificiais que ela própria criou. O build debug usa
o pacote `.teste`, separado da instalação oficial e da licença do usuário.

Para preparar o modelo em um emulador isolado sem baixar de novo, copie o Tiny oficial
verificado para `/data/local/tmp/ggml-tiny.bin` e execute apenas `PreparacaoTest` antes
da suíte. Esse teste grava como o próprio app e verifica tamanho e SHA-256; não copie
com `adb push` diretamente para `Android/data`, pois a pasta ficaria pertencendo ao
usuário shell e inacessível ao app. Nenhuma preparação entra no APK publicado.

**Validado em 03/10/2026:** os 12 casos de `OfflineTest` passaram no APK oficial
0.3.3 assinado e otimizado com R8, em Android 16, modo avião e Wi-Fi/dados desligados.
Incluem os novos recursos, OCR de PDF escaneado/TIFF, narração de quatro tipos de
arquivo e transcrição Tiny com seis formatos de saída. Os relatórios acompanham o backup.

O app tem duas variantes, `site` (este APK) e `play` (a Google Play, com a compra pelo
Google; ver docs/play-store.md). As tarefas levam o nome da variante:

```powershell
cd android
./gradlew.bat :app:testSiteDebugUnitTest :app:testPlayDebugUnitTest   # licença, recibo, comandos, documentos
./gradlew.bat :app:assembleSitePrevia     # APK de teste, assinado com a chave de depuração
```

Antes da primeira compilação, `npm run android:nativos` (o FFmpeg). A prévia
(`app/build/outputs/apk/site/previa/app-site-previa.apk`) é igual à de publicação (R8), mas
assinada com a chave de depuração. Serve para instalar no próprio celular.
O app oficial não instala por cima dela sem desinstalar antes, porque a assinatura é outra.

**Emulador:** Android 16 do Google Play (`Converter_Android16`, x86_64: usa o FFmpeg
x86_64). Precisa do driver AEHD, instalado como administrador pelo
`silent_install.bat` do SDK. Com o emulador ligado, pare o Gradle
(`gradlew --stop`) depois de compilar, senão o Chrome trava.

## A chave de publicação

```powershell
npm run android:chave          # uma vez na vida: cria em ~/.smellsliketech/android/
npm run android:certificado    # a impressão digital SHA-256 (o Google pede no registro)
npm run android:copia          # cópia criptografada no Drive (o gpg pede a senha numa janela)
```

A chave fica fora do repositório e das worktrees, que são apagadas. **Perder a chave é não
conseguir mais atualizar o app nos celulares de quem já instalou.** O script nunca
sobrescreve uma chave que já existe.

**A chave oficial existe desde 30/09/2026.** Certificado SHA-256:
`AB:3A:65:B9:7B:48:F1:EE:DD:6E:63:1D:BD:8D:F6:0F:08:A4:C5:84:CB:6F:7F:EC:2C:87:67:E4:62:B7:86:32`.
A cópia está em `chave-android-smells-like-tech-converter-2026-09-30.tar.asc`, na pasta
"Smells Like Tech Converter — Chaves das licenças" do Drive: AES-256, a mesma senha das
chaves das licenças, e o LEIA-ME da pasta explica como restaurar. A cópia foi decifrada e
conferida byte a byte com a original no dia em que foi feita.

## Publicar uma versão

1. Suba `versionCode` e `versionName` em `android/app/build.gradle.kts`.
2. `npm run android:aab`: roda os testes das duas variantes, gera o pacote da Play
   assinado, confere a assinatura com o certificado da chave oficial e copia para
   `android/artifacts/`.
3. Envie o `.aab` no Play Console, na faixa certa (teste interno, fechado ou produção).

`npm run android:apk` continua existindo para gerar um APK assinado da variante `site` e
instalar num celular de teste; ele não publica nada.

## Verificação de desenvolvedor do Google

A partir de 30/09/2026, no Brasil, celulares Android certificados exigem que os apps
venham de desenvolvedores verificados. A fase inicial vale para instalações feitas pelas
lojas participantes, e a exigência se estende a todos os apps em 2027. Sem verificação, o
cliente só instala pelo "fluxo avançado" (modo desenvolvedor, reiniciar, esperar 24 h),
o que é inviável.

A conta de desenvolvedor é a do **Play Console** ("Smells Like Tech Informática", conta
pessoal), com a identidade verificada em 30/09/2026. O registro do app fica em Play
Console → Verificação de desenvolvedor Android → Nomes de pacote → Registrar nome do
pacote: o pacote `br.com.smellsliketech.converter` e o certificado de
`npm run android:certificado`. Se o Google pedir a prova de posse da chave, ele entrega um
trecho de texto que vai em `android/app/src/main/assets/adi-registration.properties`, e
um APK de publicação com esse arquivo é enviado na mesma página.

Fontes: [Android developer verification](https://developer.android.com/developer-verification) ·
[Ajuda do Android Developer Console](https://support.google.com/android-developer-console/answer/16561738?hl=en)
