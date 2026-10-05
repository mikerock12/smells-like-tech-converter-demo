# Adaptações da demonstração

Os motores, leitores, fila, formatos e UI de conversão são os fontes originais do snapshot.
Não houve remoção ou reescrita desses motores para ocultar código GPL.
Foram excluídos somente backend comercial/configuração privada e painel administrativo.
lib/conta/uso.ts é uma fachada GPL sem operações: não inclui o serviço de contas original.
vite.config.ts usa Vinext/Node sem Worker/D1/R2. package.json contém só scripts de cliente.
A rota de pesos Kokoro é a única exceção permitida em app/api: GET público sem entrada.
O inventário SHA-256 lista os blobs originais; esses três arquivos de adaptação e esta
documentação são gerados pelo exportador do projeto privado.
