package br.com.smellsliketech.converter.loja

import android.app.Activity

/**
 * Como este app vende a licença. Cada variante tem a sua, criada por `novaLoja` em
 * src/site (abre a página de preços do site) e src/play (faturamento da Google Play).
 */
interface Loja {
    /** O preço como a loja mostra ("R$ 249,00"), ou nulo quando ela não sabe. */
    suspend fun preco(): String?

    /** Abre a compra. O que acontecer chega pelo [Ouvinte]. */
    fun comprar(atividade: Activity)

    /**
     * Confere as compras que a loja conhece: reinstalação, outro celular com a mesma conta,
     * compra paga depois (boleto, Pix) ou estorno. Sem resposta da loja, não mexe em nada.
     */
    suspend fun sincronizar()

    fun encerrar()

    interface Ouvinte {
        /** Compra paga. Devolve se o recibo conferiu; só então a loja confirma a entrega. */
        fun comprou(json: String, assinatura: String): Boolean

        /** A compra existe, mas o pagamento ainda não caiu. */
        fun pendente()

        /** A loja respondeu e esta conta não tem a compra. */
        fun semCompra()

        fun falhou(mensagem: String)
    }
}
