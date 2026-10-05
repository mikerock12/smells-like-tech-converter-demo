using Xunit;

// Boa parte destes testes sobe processos externos (FFmpeg) e mede tempo: quanto tempo
// o job levou, se o cancelamento chegou antes do fim, se a fila iniciou o trabalho.
// Rodando as classes em paralelo, varios FFmpeg disputam os mesmos nucleos e os
// timeouts estouram sem que exista defeito no produto. A execucao serial mantem as
// medicoes honestas.
[assembly: CollectionBehavior(DisableTestParallelization = true)]
