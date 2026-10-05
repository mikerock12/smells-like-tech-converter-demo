#!/usr/bin/env bash
# Monta o aviso de licenças do FFmpeg do app (android/app/src/main/assets/licencas-nativas.txt,
# que vai dentro do app, e public/codigo-aberto/licencas-android.txt, que o site publica): o
# texto de cada licença, tirado dos mesmos códigos-fonte
# que o compilar.sh baixa (mesmas URLs e SHA-256), e o próprio compilar.sh, que é a receita
# exata da compilação (a LGPL pede que quem recebe o binário saiba refazê-lo).
#
#   bash android/nativos/licencas.sh
#
# Roda no Git Bash do Windows, no Linux ou no macOS (curl, tar e sha256sum).
set -euo pipefail

RAIZ="$(cd "$(dirname "$0")" && pwd)"
FONTES="${TRABALHO:-$RAIZ/.trabalho}/fontes"
SAIDA="$RAIZ/../app/src/main/assets/licencas-nativas.txt"
mkdir -p "$FONTES" "$(dirname "$SAIDA")"

# As fontes são as linhas "baixar nome url sha256" do compilar.sh: uma lista só.
declare -A ARQUIVOS=(
  [ffmpeg.tar.xz]="COPYING.LGPLv2.1"
  [whisper.tar.gz]="LICENSE"
  [lame.tar.gz]="COPYING"
  [opus.tar.gz]="COPYING"
  [ogg.tar.xz]="COPYING"
  [vorbis.tar.xz]="COPYING"
  [vpx.tar.gz]="LICENSE PATENTS"
  [aom.tar.gz]="LICENSE PATENTS"
)

{
  echo "FFMPEG DO APP: COMPONENTES E LICENÇAS"
  echo "====================================="
  echo
  echo "O motor de vídeo, áudio e transcrição do app é o FFmpeg, compilado a partir dos"
  echo "códigos-fonte oficiais abaixo pela receita no fim deste texto (o script compilar.sh,"
  echo "com os poucos ajustes de compilação que ele faz). Cada componente é distribuído sob a"
  echo "sua licença, reproduzida aqui. O FFmpeg e o LAME são LGPL: você pode obter os"
  echo "códigos-fonte nos endereços abaixo (e, a pedido, pelo e-mail"
  echo "converter@smellsliketech.com.br) e recompilar o executável."
  echo
  grep -E '^baixar ' "$RAIZ/compilar.sh" | while read -r _ nome url hash; do
    echo "- $nome: $url"
    echo "  SHA-256 $hash"
  done
  echo
} > "$SAIDA"

grep -E '^baixar ' "$RAIZ/compilar.sh" | while read -r _ nome url hash; do
  arquivo="$FONTES/$nome"
  [[ -f "$arquivo" ]] || curl -fsSL --retry 5 -o "$arquivo" "$url"
  echo "$hash  $arquivo" | sha256sum -c --quiet -
  topo="$(tar -tf "$arquivo" | sed -n 1p | cut -d/ -f1)"  # sed lê tudo: head cortaria o tar (SIGPIPE)
  for licenca in ${ARQUIVOS[$nome]}; do
    {
      echo
      echo "================================================================================"
      echo "$topo/$licenca"
      echo "================================================================================"
      echo
      tar -xOf "$arquivo" "$topo/$licenca" | tr -d '\r'
    } >> "$SAIDA"
  done
done

{
  echo
  echo "================================================================================"
  echo "A receita: android/nativos/compilar.sh"
  echo "================================================================================"
  echo
  tr -d '\r' < "$RAIZ/compilar.sh"
} >> "$SAIDA"

# A mesma cópia no site, em /codigo-aberto/licencas-android.txt (a página /codigo-aberto aponta para ela).
NO_SITE="$RAIZ/../../public/codigo-aberto/licencas-android.txt"
mkdir -p "$(dirname "$NO_SITE")"
cp "$SAIDA" "$NO_SITE"

echo "Gravado $SAIDA e $NO_SITE ($(wc -c < "$SAIDA") bytes)"
