# Arquivo artificial de teste

`video-com-audio.mp4` contém dois segundos de barras/padrão e tom de 440 Hz, sem dados
de usuário. Foi gerado no computador com FFmpeg, pois o binário Android enxuto não
inclui a entrada de geração `lavfi`:

```sh
ffmpeg -f lavfi -i testsrc=size=320x240:rate=8:duration=2 -f lavfi -i sine=frequency=440:duration=2 -c:v mpeg4 -pix_fmt yuv420p -c:a aac -shortest video-com-audio.mp4
```

O vídeo é empacotado somente no APK de testes, nunca no app publicado.
