# Notas da Google Play · 1.0.24

Código 49. Copie o bloco abaixo em "Notas da versão" (pt-BR, até 500 caracteres).

```text
<pt-BR>
• Separação de faixas, exportações e conversões continuam com a tela desligada ou em outro app, com porcentagem e tempo restante na notificação.
• Novo (experimental): separe as faixas de uma música num .zip, juntando voz, bateria, baixo e outros como quiser, com efeitos e equalização em cada uma.
• Novo (experimental): tire a voz de um vídeo ou deixe só a voz, com efeitos no áudio que fica.
• Separação mais rápida e opção de usar o acelerador de IA do celular.
</pt-BR>
```

Contagem: o bloco tem menos de 500 caracteres sem as marcas `<pt-BR>`.

## O que mudou (para a ficha, se quiser atualizar a descrição)

- Conversor → Áudio → "Separar faixas em .zip (experimental)": Spleeter (4 faixas) ou RoFormer
  (6 faixas, celulares de 64 bits), combinações prontas ou livres, formato WAV/FLAC/MP3/AAC/Opus,
  volume final e efeitos do estúdio musical por faixa.
- Conversor → Vídeo → "Tirar ou isolar a voz (experimental)": a imagem é copiada sem recodificar;
  só o áudio é refeito (AAC).
- Estúdio de música, estúdio de vídeo e conversões: o serviço em primeiro plano segura a CPU com a
  tela apagada e mostra etapa, porcentagem e tempo estimado na notificação, com botão Cancelar.
- Separação: threads só nos núcleos rápidos (big.LITTLE) e opção "Usar o acelerador de IA do
  celular (experimental)" pelo NNAPI, em float32, com volta automática para a CPU.
