package br.com.smellsliketech.converter

import android.content.Intent
import android.net.Uri
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import br.com.smellsliketech.converter.conversao.Saida
import br.com.smellsliketech.converter.ui.Oficina
import br.com.smellsliketech.converter.ui.OficinaViewModel
import br.com.smellsliketech.converter.ui.TemaDoConverter

class MainActivity : ComponentActivity() {
    private val modelo: OficinaViewModel by viewModels()
    private val notificacoes = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val armazenamento = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    private val escolher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) modelo.adicionar(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // O app é sempre escuro: ícones claros nas barras, mesmo com o celular no tema claro.
        JanelaDoApp.preparar(window)
        if (savedInstanceState == null) receber(intent)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !getPreferences(MODE_PRIVATE).getBoolean("notificacoes-pedidas", false)) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("notificacoes-pedidas", true).apply()
            notificacoes.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // Android 9: sem esta permissão os resultados não chegam a Downloads (veja Saida).
        if (Saida.precisaDePermissao(this)) armazenamento.launch(Saida.PERMISSOES)
        setContent {
            TemaDoConverter {
                // "*/*" para converter (documentos vêm com dezenas de tipos, e o app recusa o que
                // não converte); áudio e vídeo para transcrever.
                Oficina(modelo) { tipos -> escolher.launch(tipos) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receber(intent)
    }

    override fun onResume() {
        super.onResume()
        modelo.atualizarSituacao()
    }

    /** "Compartilhar → Converter": arquivos entram na oficina; texto puro vai para a narração. */
    private fun receber(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SEND -> {
                val arquivo = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
                if (arquivo != null) modelo.adicionar(listOf(arquivo), intent.type)
                else intent.getStringExtra(Intent.EXTRA_TEXT)?.let { modelo.textoParaNarrar = it }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val arquivos = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                arquivos?.let { modelo.adicionar(it, intent.type) }
            }
        }
    }
}
