# Pesos do editor de imagem

| Modelo | Situação em 28/11/2026 | Evidências |
| --- | --- | --- |
| SLT ampliador 2× v1 | Criação da SLTI, autorizada para instalação licenciada. O site entrega os pesos no primeiro uso com chave paga válida. Avaliação interna com duas fotografias CC0. | [Dados](DATASET_REGISTER.md) · [Decisão do titular](LEGAL_REVIEW.md) |

Os candidatos externos e a distinção entre licença do código, pesos e dados estão registrados na [auditoria de modelos](../auditoria-modelos.md). Em `packages/image-editor-models/models.manifest.json`, `null` significa decisão pendente. O diretório `weights` desse pacote recebe somente artefatos com revisão concluída.

O Windows e o Android ativado com chave SLT solicitam o modelo ao serviço com a licença da própria plataforma e conferem o SHA-256 antes da instalação. Para a edição da Play, o bundle privado leva o pack sob demanda `ampliador_slti`. O app consulta a compra ou a assinatura antes de solicitar o pack e antes de cada uso. O APK do site usa o download autenticado. Os instaladores Windows e o snapshot dos clientes excluem os pesos.
