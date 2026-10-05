package br.com.smellsliketech.converter.licenca

import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.Base64

/**
 * A chave de acesso, a mesma do site e do aplicativo para Windows: `SLT1.<carga>.<assinatura>`.
 *
 * A carga é um JSON em base64url com o plano, o e-mail e as datas; a assinatura é ECDSA
 * P-256 sobre SHA-256, em r||s de 64 bytes (o formato do WebCrypto), feita pelo servidor
 * com a chave privada. O celular confere com a chave pública embutida, sem internet: a
 * chave se prova sozinha, e mudar uma letra da carga invalida a assinatura.
 *
 * Kotlin puro (java.security), sem nada do Android: os testes rodam no computador.
 */
object Licenca {
    const val PREFIXO = "SLT1"

    /**
     * A mesma chave pública de packages/licenca/chave-publica.mjs e de LicenseVerifier.cs.
     * tests/chave-publica.test.mjs confere que os três valores batem.
     */
    const val CHAVE_PUBLICA =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEw7i4eDfAptC3Fl8WTK9sEz7-w8LATA7-gJbf0eo0_WEax7hi5Jp1o5gIghd7tCHzrMNUAycDGeiLD5yG9ojZ0A"

    /** Os planos que liberam o app para Android. */
    val PLANOS_DO_ANDROID = setOf("android", "completo")

    data class Carga(
        val id: String,
        val plano: String,
        val email: String,
        val emitida: String,
        val expira: String?,
        val maquinas: Int,
    ) {
        val liberaAndroid: Boolean get() = plano in PLANOS_DO_ANDROID
        fun vencida(agora: Instant = Instant.now()): Boolean = expira != null && Instant.parse(expira).isBefore(agora)
    }

    sealed interface Resultado {
        data class Valida(val carga: Carga) : Resultado
        data class Invalida(val motivo: Motivo) : Resultado
    }

    enum class Motivo { FORMATO, ASSINATURA }

    fun verificar(texto: String, chavePublica: String = CHAVE_PUBLICA): Resultado {
        val partes = texto.trim().replace(Regex("\\s+"), "").split(".")
        if (partes.size != 3 || partes[0] != PREFIXO) return Resultado.Invalida(Motivo.FORMATO)

        val assinado: ByteArray
        val assinatura: ByteArray
        val carga: Carga
        try {
            assinado = deBase64Url(partes[1])
            assinatura = deBase64Url(partes[2])
            carga = lerCarga(String(assinado, Charsets.UTF_8)) ?: return Resultado.Invalida(Motivo.FORMATO)
        } catch (_: IllegalArgumentException) {
            return Resultado.Invalida(Motivo.FORMATO)
        }
        if (assinatura.size != 64) return Resultado.Invalida(Motivo.FORMATO)

        val confere = try {
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(publica(chavePublica))
                update(assinado)
                verify(derDeRaw(assinatura))
            }
        } catch (_: Exception) {
            false
        }
        return if (confere) Resultado.Valida(carga) else Resultado.Invalida(Motivo.ASSINATURA)
    }

    private fun publica(spkiBase64Url: String): PublicKey =
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(deBase64Url(spkiBase64Url)))

    fun deBase64Url(texto: String): ByteArray = Base64.getUrlDecoder().decode(texto)

    /** O Java quer a assinatura em DER; o WebCrypto entrega r||s. */
    fun derDeRaw(raw: ByteArray): ByteArray {
        fun inteiro(bytes: ByteArray): ByteArray {
            var valor = bytes.dropWhile { it == 0.toByte() }.toByteArray()
            if (valor.isEmpty()) valor = byteArrayOf(0)
            if (valor[0] < 0) valor = byteArrayOf(0) + valor
            return byteArrayOf(0x02, valor.size.toByte()) + valor
        }
        val corpo = inteiro(raw.copyOfRange(0, 32)) + inteiro(raw.copyOfRange(32, 64))
        return byteArrayOf(0x30, corpo.size.toByte()) + corpo
    }

    /** A carga é um objeto JSON plano, sempre na mesma forma; um leitor pequeno basta. */
    fun lerCarga(json: String): Carga? {
        val campos = JsonPlano.ler(json) ?: return null
        if (campos["v"] != 1L) return null
        return Carga(
            id = campos["id"] as? String ?: return null,
            plano = campos["plano"] as? String ?: return null,
            email = campos["email"] as? String ?: return null,
            emitida = campos["emitida"] as? String ?: return null,
            expira = if (campos.containsKey("expira") && campos["expira"] == null) null else campos["expira"] as? String ?: return null,
            maquinas = (campos["maquinas"] as? Long)?.toInt() ?: return null,
        )
    }
}

/**
 * Leitor de um objeto JSON plano: textos, números inteiros, true/false, null e listas desses
 * valores (o recibo da Google Play pode trazer "productIds": [...]). Objeto dentro de objeto
 * não é aceito.
 */
internal object JsonPlano {
    private object Falha

    fun ler(texto: String): Map<String, Any?>? {
        var i = 0
        fun espacos() { while (i < texto.length && texto[i].isWhitespace()) i++ }
        fun textoJson(): String? {
            if (texto.getOrNull(i) != '"') return null
            i++
            val saida = StringBuilder()
            while (i < texto.length) {
                val c = texto[i++]
                when {
                    c == '"' -> return saida.toString()
                    c == '\\' -> {
                        when (val e = texto.getOrNull(i++) ?: return null) {
                            'n' -> saida.append('\n'); 't' -> saida.append('\t'); 'r' -> saida.append('\r')
                            'b' -> saida.append('\b'); 'f' -> saida.append('\u000C')
                            'u' -> { if (i + 4 > texto.length) return null; saida.append(texto.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> saida.append(e)
                        }
                    }
                    else -> saida.append(c)
                }
            }
            return null
        }
        fun escalar(): Any? = when {
            texto.getOrNull(i) == '"' -> textoJson() ?: Falha
            texto.startsWith("null", i) -> { i += 4; null }
            texto.startsWith("true", i) -> { i += 4; true }
            texto.startsWith("false", i) -> { i += 5; false }
            else -> {
                val inicio = i
                if (texto.getOrNull(i) == '-') i++
                while (i < texto.length && texto[i].isDigit()) i++
                texto.substring(inicio, i).toLongOrNull() ?: Falha
            }
        }
        fun lista(): Any {
            i++
            val itens = mutableListOf<Any?>()
            espacos()
            if (texto.getOrNull(i) == ']') { i++; return itens }
            while (true) {
                espacos()
                val item = escalar()
                if (item === Falha) return Falha
                itens += item
                espacos()
                when (texto.getOrNull(i++)) {
                    ',' -> continue
                    ']' -> return itens
                    else -> return Falha
                }
            }
        }
        val campos = LinkedHashMap<String, Any?>()
        espacos()
        if (texto.getOrNull(i++) != '{') return null
        espacos()
        if (texto.getOrNull(i) == '}') return campos
        while (true) {
            espacos()
            val nome = textoJson() ?: return null
            espacos()
            if (texto.getOrNull(i++) != ':') return null
            espacos()
            val valor = if (texto.getOrNull(i) == '[') lista() else escalar()
            if (valor === Falha) return null
            campos[nome] = valor
            espacos()
            when (texto.getOrNull(i++)) {
                ',' -> continue
                '}' -> return campos
                else -> return null
            }
        }
    }
}
