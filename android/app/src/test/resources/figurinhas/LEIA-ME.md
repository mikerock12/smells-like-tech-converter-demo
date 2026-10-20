# Regressão: duração do WebP animado

`animada-pulo3-10200ms.webp` é um arquivo sintético, sem mídia de terceiros, gerado pelo
FFmpeg `android-nativos-7` do próprio projeto no emulador Android 16. Tem 34 quadros de
300 ms, totalizando 10.200 ms, embora os 100 quadros de origem representem 10 s a 10 fps.

Receita: quadros ARGB 512×512, taxa de entrada 10, filtro
`select='not(mod(n\,3))',setpts=N/(3.333*TB)`, `libwebp_anim`, qualidade 15,
compression_level 4, loop 0, yuva420p. Os pontos são listras coloridas que se movem.

O validador oficial WhatsApp/stickers exige mais de um quadro, pelo menos 8 ms por quadro
e no máximo 10.000 ms no total. O teste exige que a correção preserve cada byte fora dos
campos de duração e que reaplicá-la não provoque novas mudanças.
