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
    private var agendada = false
    private var ultimoPedido = 0
    private val atualizar = Runnable { agendada = false; atualizarNotificacao() }
    // Entre duas etapas (separar → reduzir ruído → preparar tempo e tom) a lista fica vazia
    // por um instante. Encerrar ali e subir de novo com o app em outra tela falhava (o
    // Android não deixa iniciar serviço em primeiro plano em segundo plano), o processo ficava
    // sem o serviço e era congelado até o app voltar à frente: a porcentagem parava.
    private val encerrarSeVazio = Runnable { if (fila.ativos().isEmpty()) encerrar() }

    override fun onCreate() {
        super.onCreate()
        fila = FilaDeConversoes.obter(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CANAL, "Conversões e processamentos em andamento", NotificationManager.IMPORTANCE_LOW),
        )
        fila.aoMudar = {
            if (fila.ativos().isEmpty()) atualizarNotificacao()
            else if (!agendada) { agendada = true; handler.postDelayed(atualizar, 500) }
        }
        vivo = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        encerrando = false // Nova ação recebida enquanto o sistema encerra o lote anterior.
        ultimoPedido = startId
        if (intent?.action == CANCELAR) {
            fila.interromper("Cancelada.")
            if (fila.ativos().isEmpty()) encerrar()
            return START_NOT_STICKY
        }
        try {
            atualizarNotificacao()
            if (!encerrando) {
                segurarCpu()
                fila.iniciarPendentes(escopo)
            }
        } catch (erro: Exception) {
            fila.interromper("O Android não permitiu continuar em segundo plano: ${erro.message}")
            encerrar()
        }
        return START_NOT_STICKY // Não reiniciar sem arquivos após morte do processo.
    }

    /** A CPU continua com a tela apagada; refeita se o sistema soltou a trava (prazo, outro app). */
    private fun segurarCpu() {
        if (trava?.isHeld == true) return
        trava = getSystemService(PowerManager::class.java).newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "${packageName}:conversao",
        ).apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1000L) // Segurança contra vazamento.
        }
    }

    private fun atualizarNotificacao() {
        if (encerrando) return
        val ativos = fila.ativos()
        if (ativos.isEmpty()) {
            handler.removeCallbacks(encerrarSeVazio)
            handler.postDelayed(encerrarSeVazio, ESPERA_PARA_ENCERRAR_MS)
            return
        }
        handler.removeCallbacks(encerrarSeVazio)
        segurarCpu()
        val trabalho = ativos.lastOrNull { it.etapa != "Na fila…" } ?: ativos.last()
        if (Build.VERSION.SDK_INT < 29) {
            // Android 9 não tem tipos de serviço em primeiro plano: a notificação basta.
            startForeground(NOTIFICACAO, notificacao(trabalho, ativos.size))
            return
        }
        val tipo = if (Build.VERSION.SDK_INT >= 35 && ativos.all { it.midia })
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        tipos = tipos or tipo
        try {
            // Chamada nativa preserva também mediaProcessing (API 35).
            // Versões antigas do AndroidX filtram esse novo bit e passam tipo NONE.
            startForeground(NOTIFICACAO, notificacao(trabalho, ativos.size), tipos)
        } catch (erro: Exception) {
            // Só a atualização da notificação falhou: o serviço já está em primeiro plano.
            android.util.Log.w("ServicoDeConversao", "Notificação não atualizada: ${erro.message}")
        }
    }

    private fun notificacao(trabalho: Trabalho, quantidade: Int): Notification {
        val abrir = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val cancelar = PendingIntent.getService(this, 1, Intent(this, ServicoDeConversao::class.java).setAction(CANCELAR),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val descricao = "${trabalho.etapa} · ${textoDoAndamento(trabalho.progresso, trabalho.restante)}"
        return NotificationCompat.Builder(this, CANAL)
            .setSmallIcon(R.drawable.ic_conversao)
            .setContentTitle(if (quantidade == 1) trabalho.titulo else "$quantidade tarefas · ${trabalho.titulo}")
            .setSubText("Em segundo plano")
            .setContentText(descricao).setStyle(NotificationCompat.BigTextStyle().bigText(descricao))
            .setContentIntent(abrir).setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setProgress(100, trabalho.progresso, trabalho.progresso == 0)
            .addAction(0, "Cancelar", cancelar).build()
    }

    private fun encerrar() {
        encerrando = true
        handler.removeCallbacksAndMessages(null)
        agendada = false
        if (trava?.isHeld == true) trava?.release()
        trava = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        // Só o último pedido encerra: um trabalho que chegou agora mantém o serviço vivo.
        if (stopSelfResult(ultimoPedido)) vivo = false else encerrando = false
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        fila.interromper("O Android atingiu o limite de execução em segundo plano. Abra o app e inicie novamente.")
        encerrar() // Não esperar JNI/cancelamento para atender ao prazo do sistema.
    }

    override fun onDestroy() {
        vivo = false
        fila.aoMudar = null
        fila.escopo = null
        escopo.cancel()
        encerrar()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        /** Se o serviço está de pé: um trabalho novo só avisa, sem pedir outro início ao sistema. */
        @Volatile internal var vivo = false
        private const val ESPERA_PARA_ENCERRAR_MS = 2_500L
        private const val CANAL = "conversoes"
        private const val NOTIFICACAO = 41
        private const val CANCELAR = "br.com.smellsliketech.converter.CANCELAR_CONVERSOES"
    }
}
