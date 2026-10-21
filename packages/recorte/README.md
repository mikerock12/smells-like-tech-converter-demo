# Remoção de fundo local

U²-Net-p, de Xuebin Qin e colaboradores, sob Apache-2.0 (arquivo LICENSE).
Modelo: https://github.com/xuebinqin/U-2-Net
Distribuição ONNX: https://github.com/danielgatis/rembg/releases/download/v0.0.0/u2netp.onnx

Arquivo original, sem quantização ou alteração:

- SHA-256: `309c8469258dda742793dce0ebea8e6dd393174f89934733ecc8b14c76f4ddd8`
- MD5 publicado pelo rembg: `8e83ca70e441ab06c318d82300c84806`
- Entrada RGB NCHW 1×3×320×320; máximo dos canais para normalizar, médias
  0.485/0.456/0.406 e desvios 0.229/0.224/0.225.
- Primeira saída: máscara; normalização mínimo/máximo com proteção contra divisão por zero.

Site e Windows processam localmente com ONNX Runtime (MIT). O Android usa o ML Kit de
segmentação de assunto já integrado no criador de figurinhas. Cada mídia é recortada antes
dos filtros, da perspectiva e da mesclagem; o alfa original é multiplicado pela máscara.
Nenhuma imagem é enviada a um serviço de remoção de fundo.
