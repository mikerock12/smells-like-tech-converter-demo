# Avisos que acompanham o codec JPEG XL

O site distribui os avisos do empacotador jSquash e do libjxl em `public/licencas/jpeg-xl/`. O Android inclui os dois textos do jxl-coder 2.6.0 e o aviso do libjxl em `android/app/src/main/assets/licencas/jpeg-xl/`. `node scripts/check-third-party-notices.mjs` compara os cinco textos com os hashes registrados, normalizando os finais de linha.

O Windows e o plugin usam o delegate JXL fornecido pelo pacote Magick.NET. A bancada com `cjxl` e `djxl` é independente dos instaladores 0.9.21. Uma nova distribuição desses executáveis precisa incluir os avisos das bibliotecas da compilação escolhida.
