# Distribuição de pesos do editor

Os pesos próprios permanecem na bancada privada por decisão do titular em 21/11/2026. O verificador rejeita a aprovação de distribuição de modelos próprios e o exportador compara os arquivos do snapshot público com os hashes privados registrados. Licenças de acesso às funcionalidades comerciais seguem os controles do produto.

O registro `packages/image-editor-models/models.manifest.json` separa os pesos propostos daqueles habilitados para distribuição. Uma avaliação em bancada pode permanecer `pending`. Só `approved` associa um arquivo de distribuição ao modelo. A revisão precisa identificar o código, a licença dos pesos, a permissão comercial e de redistribuição, os dados e o SHA-256 do arquivo.

Para aprovar um artefato, registre as fontes e os documentos de evidência no manifesto. Em `docs/image-editor/licensing/approvals/<id>.json`, registre `reviewedBy`, a data `reviewedAt` e a cópia integral da entrada aprovada em `approved`. Alterações de origem, permissões, evidências ou hash exigem atualização do parecer. O registro preserva a decisão do responsável no histórico Git.

`node scripts/check-image-model-licenses.mjs` verifica o inventário dos pesos em `packages/image-editor-models/weights` e as cópias presentes nas pastas de distribuição de imagem web, desktop e Android. O escopo é o novo editor. O recorte anterior do Estúdio e os modelos de áudio têm inventários próprios. As imagens do usuário continuam locais.
