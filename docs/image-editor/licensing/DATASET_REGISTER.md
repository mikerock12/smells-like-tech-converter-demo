# Origem dos dados do editor

| Conjunto | Origem e uso | Escopo e retenção |
| --- | --- | --- |
| Cenas procedurais do ampliador 2× v1 | `laboratorio/imagem/super_resolucao.py` gera 128 cenas de treino e 32 de avaliação, com sementes distintas. Imagens, máscaras e alvos são calculados no processo. | A bancada preserva a receita, os hashes dos pixels por conjunto, os coeficientes e as métricas em `laboratorio/imagem/modelos/`. As cenas podem ser regeneradas. |

Os resultados se referem a ilustrações sintéticas opacas. Para acrescentar um conjunto de fotografias, registre origem, autorização para o uso pretendido, consentimentos aplicáveis, divisão de treino e teste e retenção antes do treino.
