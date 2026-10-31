package br.com.smellsliketech.converter.estudio

object Criacao {
    val modelos = listOf("abertura" to "Título de abertura", "fotos" to "Fotos em movimento", "cartao" to "Cartão social")
    fun modelo(original: Projeto, id: String): Projeto {
        val p=atualizar(original)
        require(modelos.any { it.first==id } && p.clipes.isNotEmpty()) { "Adicione uma mídia e escolha um modelo." }
        if(id=="fotos") return p.copy(versao=4, clipes=p.clipes.map { if(it.ehImagem) it.copy(transformacao=Animacao.kenBurns(it.transformacao?:Transformacao(),Estudio.duracaoDoClipe(it))) else it })
        val texto=Texto("modelo-titulo",if(id=="cartao") "Sua mensagem aqui" else "Seu título aqui",0.0,minOf(Estudio.duracaoTotal(p),3.0),x=.5,y=if(id=="cartao") .85 else .2,tamanho=.075,caixa="#000000B3",fonte="anton")
        return p.copy(versao=4,fundo=if(id=="cartao") FundoDoProjeto("#7536E8","#FF7A18") else p.fundo,
            clipes=if(id=="cartao") p.clipes.map { it.copy(transformacao=(it.transformacao?:Transformacao()).copy(escala=.78,y=.42)) } else p.clipes,
            textos=p.textos.filter { it.id!="modelo-titulo" }+texto)
    }
    fun contagem(original: Projeto,inicio: Double=0.0,quantidade: Int=3): Projeto {
        val p=atualizar(original)
        require(inicio.isFinite()&&inicio>=0&&quantidade in 1..10&&inicio+quantidade<=Estudio.duracaoTotal(p)+.001) { "A contagem deve ter de 1 a 10 segundos e caber na linha do tempo." }
        val textos=(0 until quantidade).map { Texto("contagem-$it",(quantidade-it).toString(),inicio+it,inicio+it+1,x=.5,y=.5,tamanho=.25,fonte="anton") }
        return p.copy(versao=4,textos=p.textos.filter { !it.id.startsWith("contagem-") }+textos)
    }
    /** Divide a faixa principal mantendo o trecho da origem, a velocidade e a fase da animação. */
    fun dividirClipe(p: Projeto, id: String, tempo: Double, novoId: String): Projeto {
        val c = p.clipes.find { it.id == id }
        val segmento = Estudio.segmentos(p).find { it.id == id }
        require(c != null && segmento != null && p.clipes.size < 100) { "Selecione um clipe e use até 100 clipes." }
        val local = tempo - segmento.inicio
        val duracao = Estudio.duracaoDoClipe(c)
        require(local > .05 && local < duracao - .05) { "Posicione o cursor dentro do clipe para dividir." }
        // Dividir durante uma transição mudaria a sobreposição entre os vizinhos.
        require(!Estudio.temTransicoes(p)) { "Troque as transições por corte seco antes de dividir o clipe." }
        val t = c.transformacao
        val depois = if (t?.quadrosChave?.isNotEmpty() == true) t.copy(tempoDaAnimacao = t.tempoDaAnimacao + local) else t
        val corte = (c.inicio ?: 0.0) + local * Estudio.velocidadeDoClipe(c)
        val primeiro = if (c.ehImagem) c.copy(duracao=local,transicao=null) else c.copy(fim=corte,transicao=null)
        val segundo = if (c.ehImagem) c.copy(id=novoId,duracao=duracao-local,transformacao=depois) else c.copy(id=novoId,inicio=corte,transformacao=depois)
        return p.copy(clipes=p.clipes.flatMap { if(it.id==id) listOf(primeiro,segundo) else listOf(it) })
    }
    fun dividirCamada(original: Projeto,id: String,tempo: Double,novoId: String): Projeto {
        val p=atualizar(original)
        val c=p.camadas.find {it.id==id}
        require(c!=null&&tempo>c.inicio+.05&&tempo<c.fim-.05&&p.camadas.size<12) {"Posicione o cursor dentro da faixa e use até 12 camadas."}
        val local=tempo-c.inicio
        val t=c.transformacao
        val depois=if(t?.quadrosChave?.isNotEmpty()==true) t.copy(tempoDaAnimacao=t.tempoDaAnimacao+local) else t
        return p.copy(versao=4,camadas=p.camadas.flatMap {if(it.id==id) listOf(c.copy(fim=tempo),c.copy(id=novoId,inicio=tempo,deslocamento=c.deslocamento+(if(c.tipo=="imagem") 0.0 else local),transformacao=depois)) else listOf(it)})
    }
    fun atualizar(p: Projeto): Projeto {
        if(p.versao>=4) return p
        val total=Estudio.duracaoTotal(p)
        return p.copy(versao=4,camadas=p.camadas.filter {it.inicio<total}.map {it.copy(fim=minOf(total,it.fim))},
            audios=p.audios.filter {(it.entrada?:0.0)<total}.map {it.copy(fim=minOf(it.fim,it.inicio+total-(it.entrada?:0.0)))})
    }
    fun arquivos(p: Projeto)=(p.clipes.map {it.arquivo}+p.audios.map {it.arquivo}+p.camadas.map {it.arquivo}).distinct()
    fun mapear(p: Projeto,mapa: (String)->String)=p.copy(clipes=p.clipes.map {it.copy(arquivo=mapa(it.arquivo))},audios=p.audios.map {it.copy(arquivo=mapa(it.arquivo))},camadas=p.camadas.map {it.copy(arquivo=mapa(it.arquivo))})
}
