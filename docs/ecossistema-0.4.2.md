# Paridade de conversão — ecossistema 0.4.2

A revisão da 0.4.2 Android identificou correções também necessárias no site,
Windows e plugin. Os clientes continuam usando Kokoro-82M fp32, português
brasileiro (Dora, Alex e Santa), sem alterar pesos, cobrança ou assinaturas.

## Progresso e previsão

O progresso da narração mede caracteres dos blocos efetivamente concluídos,
não número de blocos de tamanhos diferentes nem avanço artificial por relógio.
Leitura/OCR e carga do modelo ficam fora da medição de velocidade.
O ETA aparece após três blocos e cinco segundos medidos, usa os oito blocos
mais recentes e é ocultado quando o bloco demora muito além da previsão.
O tempo informado é da etapa de narração; codificação/gravação são etapas
separadas, sem prometer duração ainda desconhecida.

Site e Windows atualizam o ETA enquanto um bloco está em execução, sem
avançar sua porcentagem. Ao concluir/cancelar/falhar o ETA é removido.
No Windows, blocos de até 200 caracteres aproximam a resposta do Android.
O plugin usa a mesma fila e o mesmo motor do aplicativo Windows.

## Segundo plano e limites

Android: fila no foreground service, notificação cancelável e PARTIAL_WAKE_LOCK,
já implementados/testados na 0.4.2 (código 10). Este complemento não exige
outro APK/AAB: conserva os binários já enviados ao teste fechado.
Parada forçada, desligamento, morte do processo e limites do Android continuam
podendo interromper a conversão. Não há retomada automática nesses casos.

Windows/plugin: cada conversão ativa mantém PowerRequestSystemRequired e,
quando suportado, PowerRequestExecutionRequired. A solicitação é liberada
em sucesso, erro ou cancelamento. Não usa DisplayRequired/AwayMode, não mantém
a tela acesa nem impede bloqueio. Suspensão solicitada pelo usuário ou por
políticas do sistema continua possível; Modern Standby em bateria também
tem limites definidos pelo Windows.

Navegador/PWA: workers não garantem execução com a página suspensa pelo
navegador/OS. Mantenha a aba aberta; para tarefas longas em segundo plano,
use um cliente nativo/plugin. Essa limitação aparece na fila e no rodapé.
Offline e segundo plano são propriedades distintas.

Referência Windows:
https://learn.microsoft.com/en-us/windows/win32/api/winbase/nf-winbase-powersetrequest

## Verificação

Testes determinísticos em C# e JavaScript cobrem tamanhos diferentes,
inicialização lenta, período mínimo de amostragem, desaceleração e previsão
obsoleta. A fila testa liberação da proteção em sucesso, falha e cancelamento.
O teste integrado gera um PDF fictício de 12 páginas, narra com Kokoro real
e codifica MP3 com FFmpeg, observando progresso monotônico e ETA por etapa.
Os relatórios e hashes da entrega ficam no backup privado, nunca no demo.
