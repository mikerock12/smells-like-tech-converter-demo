package br.com.smellsliketech.converter.figurinhas

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * O que é preciso para reabrir uma figurinha no editor: as camadas, cada [Edicao], o trecho, a
 * taxa de quadros e os arquivos de mídia (imagem em PNG, GIF como veio, vídeo como um trecho
 * leve). Fica ao lado do pacote, em `projetos/<NN>/`, e o [ProvedorDeFigurinhas] nunca o
 * entrega ao WhatsApp. Figurinhas de antes desta versão não têm projeto: não dá para reabrir.
 */
data class ProjetoSalvo(
    val animada: Boolean,
    val quadrosPorSegundo: Int,
    val inicio: Double,
    val fim: Double,
    val corDeFundo: Int,
    val corFinalDoFundo: Int?,
    val emojis: List<String>,
    val principal: CamadaSalva,
    val camadas: List<CamadaSalva>,
)

/** `arquivo` e `mascara` são nomes dentro da pasta do projeto; texto não tem arquivo. */
data class CamadaSalva(
    val id: String,
    val nome: String,
    val tipo: TipoDaOrigem?,
    val arquivo: String?,
    val mascara: String?,
    val edicao: Edicao,
    val visivel: Boolean = true,
    val ordem: Int = 0,
    val texto: Boolean = false,
    val inicio: Double = 0.0,
    val fim: Double? = null,
)

object Projetos {
    const val ARQUIVO = "projeto.json"
    private const val VERSAO = 2

    fun gravar(pasta: File, projeto: ProjetoSalvo) {
        val objeto = JSONObject()
            .put("versao", VERSAO)
            .put("animada", projeto.animada)
            .put("quadrosPorSegundo", projeto.quadrosPorSegundo)
            .put("inicio", projeto.inicio)
            .put("fim", projeto.fim)
            .put("corDeFundo", projeto.corDeFundo)
            .put("corFinalDoFundo", projeto.corFinalDoFundo ?: JSONObject.NULL)
            .put("emojis", JSONArray(projeto.emojis))
            .put("principal", camadaParaJson(projeto.principal))
            .put("camadas", JSONArray(projeto.camadas.map(::camadaParaJson)))
        File(pasta, ARQUIVO).writeText(objeto.toString())
    }

    fun ler(pasta: File): ProjetoSalvo? {
        val arquivo = File(pasta, ARQUIVO).takeIf { it.isFile } ?: return null
        return runCatching {
            val o = JSONObject(arquivo.readText())
            val emojis = o.getJSONArray("emojis")
            val camadas = o.getJSONArray("camadas")
            ProjetoSalvo(
                animada = o.getBoolean("animada"),
                quadrosPorSegundo = o.optInt("quadrosPorSegundo", Regras.QUADROS_POR_SEGUNDO_PADRAO).takeIf { it in Regras.QUADROS_POR_SEGUNDO }
                    ?: Regras.QUADROS_POR_SEGUNDO_PADRAO,
                inicio = o.getDouble("inicio"),
                fim = o.getDouble("fim"),
                corDeFundo = o.getInt("corDeFundo"),
                corFinalDoFundo = if (o.isNull("corFinalDoFundo")) null else o.getInt("corFinalDoFundo"),
                emojis = (0 until emojis.length()).map { emojis.getString(it) },
                principal = camadaDeJson(o.getJSONObject("principal")),
                camadas = (0 until camadas.length()).map { camadaDeJson(camadas.getJSONObject(it)) },
            ).takeIf { projeto -> (listOf(projeto.principal) + projeto.camadas).all { camada -> arquivosExistem(pasta, camada) } }
        }.getOrNull()
    }

    private fun arquivosExistem(pasta: File, camada: CamadaSalva): Boolean =
        listOfNotNull(camada.arquivo, camada.mascara).all { nome -> nomeValido(nome) && File(pasta, nome).isFile } &&
            (camada.texto || camada.arquivo != null)

    /** Só nomes simples dentro da pasta do projeto: nada de `..` nem caminhos. */
    fun nomeValido(nome: String) = Regex("[a-z0-9-]{1,40}\\.(png|gif|mp4)").matches(nome)

    private fun camadaParaJson(c: CamadaSalva): JSONObject = JSONObject()
        .put("id", c.id)
        .put("nome", c.nome)
        .put("tipo", c.tipo?.name ?: JSONObject.NULL)
        .put("arquivo", c.arquivo ?: JSONObject.NULL)
        .put("mascara", c.mascara ?: JSONObject.NULL)
        .put("edicao", edicaoParaJson(c.edicao))
        .put("visivel", c.visivel)
        .put("ordem", c.ordem)
        .put("texto", c.texto)
        .put("inicio", c.inicio)
        .put("fim", c.fim ?: JSONObject.NULL)

    private fun camadaDeJson(o: JSONObject) = CamadaSalva(
        id = o.getString("id"),
        nome = o.optString("nome"),
        tipo = if (o.isNull("tipo")) null else TipoDaOrigem.valueOf(o.getString("tipo")),
        arquivo = if (o.isNull("arquivo")) null else o.getString("arquivo"),
        mascara = if (o.isNull("mascara")) null else o.getString("mascara"),
        edicao = edicaoDeJson(o.getJSONObject("edicao")),
        visivel = o.optBoolean("visivel", true),
        ordem = o.optInt("ordem"),
        texto = o.optBoolean("texto"),
        inicio = o.optDouble("inicio", 0.0).takeIf { it.isFinite() && it >= 0 } ?: 0.0,
        fim = o.optDouble("fim", Double.NaN).takeIf { it.isFinite() && it > o.optDouble("inicio", 0.0) },
    )

    fun edicaoParaJson(e: Edicao): JSONObject = JSONObject()
        .put("zoom", e.zoom.toDouble())
        .put("deslocamentoX", e.deslocamentoX.toDouble())
        .put("deslocamentoY", e.deslocamentoY.toDouble())
        .put("giro", e.giro)
        .put("inclinacaoX", e.inclinacaoX.toDouble())
        .put("inclinacaoY", e.inclinacaoY.toDouble())
        .put("opacidade", e.opacidade.toDouble())
        .put("mescla", e.mescla.name)
        .put("espelharHorizontal", e.espelharHorizontal)
        .put("espelharVertical", e.espelharVertical)
        .put("corDeFundo", e.corDeFundo)
        .put("removerFundo", e.removerFundo)
        .put("filtro", e.filtro.name)
        .put("brilho", e.brilho.toDouble())
        .put("contraste", e.contraste.toDouble())
        .put("saturacao", e.saturacao.toDouble())
        .put("texto", e.texto)
        .put("fonte", e.fonte.name)
        .put("corDoTexto", e.corDoTexto)
        .put("corDoContornoDoTexto", e.corDoContornoDoTexto)
        .put("tamanhoDoTexto", e.tamanhoDoTexto.toDouble())
        .put("posicaoDoTexto", e.posicaoDoTexto.name)
        .put("contorno", e.contorno)
        .put("corDoContorno", e.corDoContorno)
        .put("larguraDoContorno", e.larguraDoContorno.toDouble())

    /** Campo ausente ou desconhecido volta ao padrão: um projeto antigo continua abrindo. */
    fun edicaoDeJson(o: JSONObject): Edicao {
        val p = Edicao()
        fun f(nome: String, padrao: Float) = o.optDouble(nome, padrao.toDouble()).toFloat()
        return Edicao(
            zoom = f("zoom", p.zoom),
            deslocamentoX = f("deslocamentoX", p.deslocamentoX),
            deslocamentoY = f("deslocamentoY", p.deslocamentoY),
            giro = o.optInt("giro", p.giro),
            inclinacaoX = f("inclinacaoX", p.inclinacaoX),
            inclinacaoY = f("inclinacaoY", p.inclinacaoY),
            opacidade = f("opacidade", p.opacidade),
            mescla = Mescla.entries.firstOrNull { it.name == o.optString("mescla") } ?: p.mescla,
            espelharHorizontal = o.optBoolean("espelharHorizontal", p.espelharHorizontal),
            espelharVertical = o.optBoolean("espelharVertical", p.espelharVertical),
            corDeFundo = o.optInt("corDeFundo", p.corDeFundo),
            removerFundo = o.optBoolean("removerFundo", p.removerFundo),
            filtro = Filtro.entries.firstOrNull { it.name == o.optString("filtro") } ?: p.filtro,
            brilho = f("brilho", p.brilho),
            contraste = f("contraste", p.contraste),
            saturacao = f("saturacao", p.saturacao),
            texto = o.optString("texto", p.texto),
            fonte = Fonte.entries.firstOrNull { it.name == o.optString("fonte") } ?: p.fonte,
            corDoTexto = o.optInt("corDoTexto", p.corDoTexto),
            corDoContornoDoTexto = o.optInt("corDoContornoDoTexto", p.corDoContornoDoTexto),
            tamanhoDoTexto = f("tamanhoDoTexto", p.tamanhoDoTexto),
            posicaoDoTexto = PosicaoDoTexto.entries.firstOrNull { it.name == o.optString("posicaoDoTexto") } ?: p.posicaoDoTexto,
            contorno = o.optBoolean("contorno", p.contorno),
            corDoContorno = o.optInt("corDoContorno", p.corDoContorno),
            larguraDoContorno = f("larguraDoContorno", p.larguraDoContorno),
        )
    }
}
