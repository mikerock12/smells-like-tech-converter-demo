# Narração Kokoro-82M (0.4.1)

Todas as saídas narradas usam o modelo oficial Kokoro v1.0 no Sherpa-ONNX
1.13.8, com `lang=pt-br` explícito: Dora (`pf_dora`, locutor 42), Alex (`pm_alex`, 43)
e Santa (`pm_santa`, 44). Português é o idioma alvo; não é detecção automática.
As vozes do Windows/Android não são fallback silencioso.
Todos os clientes usam os mesmos pesos oficiais fp32. O int8 apresentou saídas
silenciosas no WASM e foi removido também dos apps após relato em aparelho Android.
A falha desse aparelho não foi reproduzida no emulador x86_64; não se presume
validar o hardware ARM somente por esse teste. Os pesos completos evitam a
quantização, com maior tamanho de pacote e uso de memória.
Linhas de OCR sem letras/números são removidas; palavras e números são preservados.
Saídas silenciosas/não finitas são repetidas com blocos menores (até três níveis),
sem descartar trechos. Se ainda falhar, nenhum áudio inválido é entregue.

`npm run kokoro:all` prepara os ativos oficiais e confere SHA-256. Não versionar
modelos, AAR ou WASM; o script reconstitui tudo. Os builds web/APK/AAB chamam esse
preparo automaticamente. No Windows, executar `npm run kokoro:windows` antes de
`dotnet publish`; modelo e dados acompanham aplicativo e plugin em `kokoro/`.

## Uso offline

- Windows/Android: modelo e vozes incluídos no instalador/APK/AAB, sem downloads
  de voz ou dependência de configurações do sistema. Android copia os assets para
  a pasta privada na primeira narração, conferindo os hashes.
- Site: botão **Preparar narração offline** baixa somente o pacote público de
  aproximadamente 321 MB (fp32). O ZIP é autenticado por SHA-256 antes do Cache Storage.
  Runtime e manifesto entram no cache PWA; o modelo fica em cache independente
  das versões da página. Limpeza/expulsão de dados exige novo preparo.
- Nenhum texto, documento, nome de arquivo ou PCM é enviado para a rota do modelo.
  `/api/motores/kokoro` só faz GET de uma URL constante de pesos públicos.
- A rota aponta para a release `kokoro-82m-ptbr-v1`, arquivo
  `kokoro-82m-v1-fp32.zip`. Só publicar essa release e o site após a revisão de
  licenciamento abaixo; antes disso, os testes locais interceptam o GET do modelo.

TXT, SRT/VTT, PDF selecionável/escaneado, imagens e documentos já aceitos pela
plataforma passam pelos respectivos leitores/OCR e pelo mesmo motor de narração.
No site, documentos narráveis são DOCX, MD e HTML; outros formatos permanecem
no aplicativo Android. O site limita a 50 mil caracteres por narração para
proteger a memória, sem truncar; Windows/Android gravam WAV incrementalmente.
Blocos de até 200 caracteres evitam exceder a janela do Kokoro. O rate Windows
e site −10..10 vira `2 ** (rate/10)`, de 0,5 a 2x; Android usa diretamente esse fator.
Todos produzem PCM mono de 24 kHz antes da codificação.

Ao atualizar um workspace já preparado com 0.4.0, preserve as pastas geradas
packages/kokoro/model e android/app/src/main/assets/kokoro em outputs antes
de rodar kokoro:all. O preparador recusa misturar os assets int8 antigos com
fp32 para não duplicar pesos nos instaladores. Use uma pasta nova de publicação
dotnet/Inno. A instalação Android usa cache privado fp32 próprio, sem depender
do cache int8 da versão anterior.

## Licenças

Kokoro e Sherpa-ONNX são Apache-2.0, mas o runtime inclui **eSpeak-NG GPLv3**.
Fonte efetivamente fixada pelo Sherpa:
`csukuangfj/espeak-ng@ed530aa113046142eb5115cf2fc9157854d0ffe1`, conforme
`cmake/espeak-ng-for-piper.cmake` em `k2-fsa/sherpa-onnx@v1.13.8`.
ONNX Runtime é MIT. O preparador inclui os textos completos e `NOTICE.txt` nos
assets; o NOTICE aponta para os códigos-fonte e scripts de compilação oficiais.

O responsável autorizou explicitamente GPLv3 nos clientes integrados em 04/10/2026.
`LICENSING.md` delimita o código próprio e as exclusões; `LICENSE` contém o texto.
As releases devem oferecer fontes correspondentes e receitas, conforme
`docs/kokoro-fontes.md`; o backup privado não substitui essa obrigação.

`python scripts/kokoro.py fontes` reúne os arquivos oficiais e as receitas do
runtime em um ZIP, com hashes e inventário. Publicar junto ao fonte do commit.

## Verificação

`npm run check`; testes de vozes/rate/blocos/catálogo/rota em `tests/kokoro.test.mjs`.
`dotnet test desktop/tests/SmellsLikeTech.Converter.Tests` inclui síntese real das
três vozes e volume zero. Android testa PCM incremental e conserva os testes
offline do APK oficial (R8), incluindo PDF, imagem e DOCX narrados.
O PR executa jobs separados para site, Android e Windows.
`npm run test:kokoro` usa o build real servido pelo Wrangler na porta 3002.
Em localhost, o GET inicial recebe o ZIP oficial preparado no disco; não depende
de publicar os ativos de revisão. Testa dez narrações reais e recarga offline,
incluindo as três vozes, PDF, DOCX, Markdown, HTML, SRT, OCR e texto em blocos.

Os testes reais passaram: 10 narrações no Chromium sem rede e com recarga;
13 testes no APK oficial com R8 em modo avião (incluindo três vozes e TXT/PDF/
imagem/DOCX narrados). Windows valida síntese real e conversões completas.
O teste web usa perfil descartável em disco: perfis anônimos podem limitar o
Cache Storage por memória. Falha de quota é informada, sem prometer offline pronto.
