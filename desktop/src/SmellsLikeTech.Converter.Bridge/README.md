# O plugin

Liga o hardware do computador ao site. O navegador manda o pedido; quem converte é o
mesmo motor do aplicativo desktop, com a mesma placa de vídeo, os mesmos codecs e os
mesmos modelos instalados na máquina.

A diferença para o aplicativo: **não tem janela**. Vive num ícone perto do relógio e só
aparece quando o usuário procura.

## O que ele não é

- **Não é o aplicativo desktop.** Instalador próprio, pasta própria, desinstalação
  própria. Os dois convivem na mesma máquina.
- **Não é um servidor.** Escuta somente em `127.0.0.1`. Nada da rede alcança ele.
- **Não guarda nada.** Não tem conta, não tem sessão, não aceita cookie.

## Como o site o encontra

`http://127.0.0.1:5199` — porta fixa, porque o site precisa saber onde procurar. Se
ninguém responder em 1,2 segundo, o site conclui que não há plugin e segue convertendo
no navegador, sem mostrar erro nenhum.

## As três travas

O plugin abre uma porta na máquina de quem instalou. É o ponto mais sensível do produto,
e por isso `Hosting/LocalGuard.cs` é a primeira coisa que roda em qualquer pedido:

1. **Só do próprio computador.** O Kestrel escuta apenas em loopback, e o guarda confere
   de novo o endereço da conexão e o cabeçalho `Host` — isso derruba *DNS rebinding*, em
   que um domínio do atacante aponta para `127.0.0.1`.
2. **Só das origens que autorizamos.** Origem desconhecida leva 403 **antes** de qualquer
   execução. Não basta o navegador esconder a resposta: o pedido não roda.
3. **Cabeçalho próprio no que altera.** Um `POST multipart/form-data` é considerado
   "simples" pelo navegador e escapa da verificação prévia. Exigir `X-Smells-Like-Tech`
   obriga a verificação — e é nela que a origem é conferida. Sem isso, qualquer página
   aberta em outra aba conseguiria disparar conversões nesta máquina.

`Access-Control-Allow-Private-Network` é devolvido na verificação prévia porque o Chrome
exige essa confirmação para uma página pública alcançar um endereço local.

## A API

| | |
| --- | --- |
| `GET /v1/ola` | quem sou, o que esta máquina consegue fazer e por quê |
| `POST /v1/trabalhos` | arquivo(s) enviado(s) pelo navegador (multipart; vários só para juntar PDF e imagens → PDF) |
| `POST /v1/trabalhos/caminho` | arquivo que já está no disco — **sem cópia** (`caminhos` para os extras) |
| `GET /v1/trabalhos/{id}/eventos` | progresso ao vivo |
| `GET /v1/trabalhos/{id}/arquivo/{i}` | baixa o resultado |
| `POST /v1/trabalhos/{id}/cancelar` | cancela |
| `POST /v1/escolher` | abre a caixa de seleção do Windows |
| `POST /v1/abrir-pasta` | abre a pasta dos resultados |

O JSON de opções **é o mesmo do produto** (`JobOptions`, com `operation` como
discriminador). Não existe um segundo dialeto para manter em dia.

### Por que a caixa de seleção dá tanto trabalho

Quem a abre é um processo **sem janela nenhuma**, e o primeiro plano pertence ao
navegador. Nessa situação o Windows deixa a caixa nascer atrás de tudo — e a pessoa acha
que o clique não fez nada. Três coisas, juntas, resolvem:

1. **Um vigia em paralelo.** `PickFile` é modal e só retorna depois que a pessoa fecha:
   não adianta empurrar a janela para frente depois da chamada, porque ali ela já se foi.
   Uma tarefa à parte procura a caixa enquanto ela existe.
2. **Ordem de empilhamento antes do foco.** `SetWindowPos` não pede permissão de primeiro
   plano: marcar como sempre-no-topo e desmarcar em seguida põe a janela acima das outras.
   É isso que garante que ela apareça.
3. **O foco pedido pela thread dona da janela.** `SetForegroundWindow` chamado de uma
   thread do pool é recusado. Marshalado para a thread de interface — a mesma que está
   presa no laço modal, e que despacha o trabalho de lá mesmo — ele passa.

Verificado com outra janela em primeiro plano: a caixa fica na posição 1 da pilha (só a
barra de tarefas acima, que é sempre-no-topo) e com o foco do teclado.

### Por que existe o caminho sem cópia

O navegador não entrega o caminho de um arquivo no disco — só o conteúdo. Para um vídeo
de dez gigabytes, subir os bytes por HTTP seria absurdo mesmo em loopback. `POST /v1/escolher`
abre a caixa de seleção do próprio Windows, devolve o caminho, e a conversão começa na
hora sem nada ter sido copiado.

## O que a 0.3.0 acrescenta

- `pdf.merge`, `pdf.split` e `pdf.fromImage`, pelo `PdfAssemblyEngine` (PdfPig), com
  vários arquivos por pedido — `ExtraInputPaths` no job, inspecionados um a um;
- o site passa a mandar as opções de todas as 26 ferramentas no dialeto do
  aplicativo (`lib/plugin/trabalhos.ts`), inclusive transcrição, narração e OCR;
- o protocolo continua na versão 1: nada do que existia mudou de forma.

## Compilar e empacotar

```powershell
dotnet build desktop/src/SmellsLikeTech.Converter.Bridge
pwsh desktop/installer/build-plugin-installer.ps1
```

O script publica, gera o instalador, **instala em silêncio, liga o plugin, pergunta quem
ele é, e desinstala** antes de dar o resultado como bom.

## Configuração

Nenhuma é necessária. Para desenvolvimento:

| Variável | Serve para |
| --- | --- |
| `SMELLSLIKETECH_PORTA` | trocar a porta (1024–65535) |
| `SMELLSLIKETECH_ORIGENS` | origens extras, separadas por `;` |

## O que ele reusa

Tudo. `SmellsLikeTech.Converter.Composition` é a mesma montagem que o aplicativo usa:
banco, pastas, detecção de hardware, fila e motores. O plugin não tem uma segunda
implementação de nada — se uma conversão funciona no aplicativo, funciona aqui.
