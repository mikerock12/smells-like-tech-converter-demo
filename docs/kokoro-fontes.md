# Fontes e recompilação — Kokoro 0.4.0

O commit indicado na release contém leitores, fila, UI, runner e `scripts/kokoro.py`.
`package-lock.json`, os projetos .NET e o Gradle fixam as dependências.
A única alteração ao JS oficial remove uma chamada de preload; o preparador
mostra a alteração exata. Não há alteração ao código nativo oficial.

- Site: Node 22+, Python 3.14+, `npm ci`, `npm run check`.
- Windows x64: Windows 10 2004+, .NET 10 e Python 3.14+.
  `npm run kokoro:windows`, `dotnet publish` dos projetos Desktop e Bridge.
  `desktop/installer/*.iss` recompilam os instaladores com Inno Setup 6.
- Android: JDK 17, SDK/build-tools 36, Python 3.14+, `npm run android:nativos`,
  `npm run kokoro:android`, `cd android`, `gradlew :app:assembleSitePrevia`.
  Para release com assinatura própria, veja `docs/android.md`.
  A chave comercial original não é necessária para modificar/recompilar o app.

## Runtime

O pacote público de fontes deve incluir os arquivos abaixo e seus hashes,
além do código próprio do commit. Os fontes contêm as receitas CMake, Kotlin/JNI,
.NET e os workflows de release que produzem os artefatos usados.

- Sherpa-ONNX `v1.13.8`: https://github.com/k2-fsa/sherpa-onnx/tree/v1.13.8
- eSpeak-NG `ed530aa113046142eb5115cf2fc9157854d0ffe1`:
  https://github.com/csukuangfj/espeak-ng/tree/ed530aa113046142eb5115cf2fc9157854d0ffe1
  Receita `cmake/espeak-ng-for-piper.cmake` no Sherpa.
- ONNX Runtime: as receitas `cmake/onnxruntime-*.cmake` do Sherpa fixam
  versão, URL e hash por plataforma; este release usa 1.28.2.
  Código e receitas: https://github.com/microsoft/onnxruntime
  e https://github.com/csukuangfj/onnxruntime-libs.

Para recompilar o runtime, siga os workflows da plataforma correspondente
no checkout Sherpa `v1.13.8`, incluindo requisitos de Emscripten/NDK/CMake.
As receitas também identificam e fixam as dependências nativas auxiliares.
O preparador verifica os artefatos oficiais por SHA-256, mas não recompila
o Sherpa do zero. Essa recompilação nativa não foi validada nesta integração.

Preserve os textos de licença e disponibilize fontes correspondentes,
inclusive os scripts de compilação, junto a cada versão binária distribuída.
