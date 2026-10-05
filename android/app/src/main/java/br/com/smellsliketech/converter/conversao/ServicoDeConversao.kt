package br.com.smellsliketech.converter.conversao

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import br.com.smellsliketech.converter.MainActivity
import br.com.smellsliketech.converter.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** Mantém execução e CPU ativas com tela apagada; não mantém a tela ligada. */
class ServicoDeConversao : Service() {
    private val escopo = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var fila: FilaDeConversoes
    private var trava: PowerManager.WakeLock? = null
    private var tipos = 0
    private var encerrando = false
    private val atualizar = Runnable { atualizarNotificacao() }

    override fun onCreate() {
        super.onCreate()
        fila = FilaDeConversoes.obter(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CANAL, "Conversões em andamento", NotificationManager.IMPORTANCE_LOW),
        )
        fila.aoMudar = {
            if (fila.trabalhos.none { !it.terminado }) atualizarNotificacao()
            else if (!handler.hasCallbacks(atualizar)) handler.postDelayed(atualizar, 500)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        encerrando = false // Nova ação recebida enquanto o sistema encerra o lote anterior.
        if (intent?.action == CANCELAR) {
            fila.interromper("Cancelada.")
            if (fila.trabalhos.none { !it.terminado }) encerrar()
            return START_NOT_STICKY
        }
        try {
            atualizarNotificacao()
            if (!encerrando) {
                if (trava == null) {
                    trava = getSystemService(PowerManager::class.java).newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK, "${packageName}:conversao",
                    ).apply {
                        setReferenceCounted(false)
                        acquire(6 * 60 * 60 * 1000L) // Segurança contra vazamento.
                    }
                }
                fila.iniciarPendentes(escopo)
            }
        } catch (erro: Exception) {
            fila.interromper("O Android não permitiu continuar em segundo plano: ${erro.message}")
            encerrar()
        }
        return START_NOT_STICKY // Não reiniciar sem arquivos após morte do processo.
    }

    private fun atualizarNotificacao() {
        if (encerrando) return
        val ativos = fila.trabalhos.filterNot { it.terminado }
        if (ativos.isEmpty()) { encerrar(); return }
        val trabalho = ativos.lastOrNull { it.etapa != "Na fila…" } ?: ativos.last()
        val tipo = if (Build.VERSION.SDK_INT >= 35 && ativos.all { it.midia })
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        tipos = tipos or tipo
        // minSdk 29: chamada nativa preserva também mediaProcessing (API 35).
        // Versões antigas do AndroidX filtram esse novo bit e passam tipo NONE.
        startForeground(NOTIFICACAO, notificacao(trabalho, ativos.size), tipos)
    }

    private fun notificacao(trabalho: Trabalho, quantidade: Int): Notification {
        val abrir = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val cancelar = PendingIntent.getService(this, 1, Intent(this, ServicoDeConversao::class.java).setAction(CANCELAR),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val descricao = "${trabalho.etapa} · ${textoDoAndamento(trabalho.progresso, trabalho.restante)}"
        return NotificationCompat.Builder(this, CANAL)
            .setSmallIcon(R.drawable.ic_conversao)
            .setContentTitle(if (quantidade == 1) trabalho.titulo else "$quantidade conversões · ${trabalho.titulo}")
            .setContentText(descricao).setStyle(NotificationCompat.BigTextStyle().bigText(descricao))
            .setContentIntent(abrir).setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setProgress(100, trabalho.progresso, trabalho.progresso == 0)
            .addAction(0, "Cancelar conversões", cancelar).build()
    }

    private fun encerrar() {
        encerrando = true
        handler.removeCallbacksAndMessages(null)
        if (trava?.isHeld == true) trava?.release()
        trava = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        fila.interromper("O Android atingiu o limite de execução em segundo plano. Abra o app e inicie novamente.")
        encerrar() // Não esperar JNI/cancelamento para atender ao prazo do sistema.
    }

    override fun onDestroy() {
        fila.aoMudar = null
        fila.escopo = null
        escopo.cancel()
        encerrar()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CANAL = "conversoes"
        private const val NOTIFICACAO = 41
        private const val CANCELAR = "br.com.smellsliketech.converter.CANCELAR_CONVERSOES"
    }
}
