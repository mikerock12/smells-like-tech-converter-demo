# Smells Like Tech Converter — demonstração e fontes GPL

Repositório público separado do produto comercial. Os clientes convertem no aparelho:
site, Android, Windows e plugin, incluindo narração Kokoro-82M em português
(Dora, Alex e Santa), OCR, extração de áudio de vídeo e transcrição.

Copyright (C) 2026 Smells Like Tech Informática e colaboradores.
Código próprio dos clientes: GNU GPLv3 ou posterior, **sem garantia**.
Veja LICENSE e LICENSING.md. Terceiros preservam suas licenças originais.
Marcas não são concedidas pela licença do código.

## O que está aqui — e o que não está

Snapshot dos clientes do commit `0a8832e8be4a85e24dcfc0d2d0ef52e8c10e7faa`, sem histórico do repositório privado.
Não inclui servidor de cobrança, contas, administração, banco, dados de clientes,
configuração Cloudflare, chaves de assinatura ou segredos.
Os catálogos públicos e verificadores locais de licença fazem parte dos clientes.
Não é um espelho do backend: compras, contas, e-mails e administração não funcionam aqui.
O histórico de uso foi substituído por uma fachada sem armazenamento/envio;
o Vite usa Node sem os bindings comerciais. A única rota incluída é um GET
constante dos pesos Kokoro públicos, sem receber arquivos ou dados do usuário.
INVENTARIO-PUBLICO.json relaciona os arquivos originais; ADAPTACOES-DEMO.md explica as adaptações.

## Compilar

Site: Node 22.13+, Python 3.14+, `npm ci`, `npm run check`, `npm run dev`.
Conversões continuam locais; preparar narração offline exige baixar os pesos uma vez
(~321 MB) e aguardar os avisos de cache pronto. Limpar esse cache exige novo preparo.
Licenciamento comercial não é ativado por esta demonstração; nenhum checkout real está incluído.

Windows: .NET 10/Windows 10 2004+, Python 3.14+; `npm run kokoro:windows`,
`dotnet test desktop/tests/SmellsLikeTech.Converter.Tests`, depois `dotnet publish`
dos projetos Desktop e Bridge. Instaladores: receitas Inno Setup em desktop/installer.

Android: JDK 17, SDK/build-tools 36, Python 3.14+; `npm run kokoro:android`.
Compile o FFmpeg por `android/nativos/compilar.sh` para as três ABIs e copie os
resultados para android/app/src/main/jniLibs/ABI/libffmpeg.so; então, em android,
`gradlew :app:assembleSitePrevia`. Use sua própria chave para distribuir uma versão modificada.
A chave comercial original não é necessária nem fornecida.

## Fontes dos runtimes

As [releases](https://github.com/mikerock12/smells-like-tech-converter-demo/releases) oferecem o snapshot público deste commit,
os pesos Kokoro e os fontes/receitas do runtime Sherpa-ONNX/eSpeak-NG/ONNX Runtime.
São componentes de terceiros, com suas licenças originais, não código comercial proprietário.
Veja docs/kokoro-fontes.md e o inventário FONTES.json do pacote de fontes.
O script confere os binários oficiais por SHA-256; a integração não validou recompilar
o runtime nativo do zero. Os fontes oficiais incluem os workflows e dependências fixadas.

Produto: https://converter.smellsliketech.com.br — contato: converter@smellsliketech.com.br.
