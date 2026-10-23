package br.com.smellsliketech.converter.catalogos

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** A cifra dificulta procura estática; não torna uma credencial de cliente secreta. */
object ConfiguracaoGiphy {
    fun abrir(valor: String): String {
        if (!valor.startsWith("v1.")) return valor.takeIf { it.matches(Regex("[A-Za-z0-9_-]{16,256}")) }.orEmpty()
        return runCatching {
            val p = valor.split('.').drop(1).map { Base64.getDecoder().decode(it) }
            require(p.size == 5 && p[0].size == 12 && p[2].size == 16 && p[3].size == 32 && p[4].size == 32)
            for (i in 0..31) p[3][i] = (p[3][i].toInt() xor p[4][i].toInt()).toByte()
            try {
                val aes = Cipher.getInstance("AES/GCM/NoPadding")
                aes.init(Cipher.DECRYPT_MODE, SecretKeySpec(p[3], "AES"), GCMParameterSpec(128, p[0]))
                val claro = aes.doFinal(p[1] + p[2])
                try { String(claro, Charsets.UTF_8).takeIf { it.matches(Regex("[A-Za-z0-9_-]{16,256}")) }.orEmpty() }
                finally { claro.fill(0) }
            } finally { p[3].fill(0); p[4].fill(0) }
        }.getOrDefault("")
    }
}
