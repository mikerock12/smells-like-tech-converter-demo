# Origem dos dados do editor

| Conjunto | Origem e uso | Escopo e retenção |
| --- | --- | --- |
| Cenas procedurais do ampliador 2× v1 | `laboratorio/imagem/super_resolucao.py` gera 128 cenas de treino e 32 de avaliação, com sementes distintas. Imagens, máscaras e alvos são calculados no processo. | A bancada preserva a receita, os hashes dos pixels por conjunto, os coeficientes e as métricas em `laboratorio/imagem/modelos/`. As cenas podem ser regeneradas. |
| Fotografias de avaliação | `chelsea.png` (gato, Stefan van der Walt) e `coffee.png` (xícara, Rachel Michetti). A documentação do scikit-image registra a dedicação CC0 pelos fotógrafos. | Avaliação em 21/11/2026 com os pesos congelados. Zero fotografias no treino. PNGs de referência na bancada local. O script confere os hashes da versão v0.25.2. [Documentação das imagens](https://scikit-image.org/docs/0.25.x/api/skimage.data.html) · [Registro de hashes](https://github.com/scikit-image/scikit-image/blob/v0.25.2/skimage/data/_registry.py) |

Para acrescentar um conjunto de treino, registre origem, autorização para o uso pretendido, consentimentos aplicáveis, divisão de treino e teste e retenção antes do treino.
