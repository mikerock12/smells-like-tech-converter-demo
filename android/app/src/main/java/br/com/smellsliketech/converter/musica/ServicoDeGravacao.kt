package br.com.smellsliketech.converter.musica

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import br.com.smellsliketech.converter.MainActivity
import br.com.smellsliketech.converter.R

/**
 * Serviço em primeiro plano do tipo microfone enquanto a criação musical grava: a tomada
 * continua com a tela apagada, e o Android mostra a notificação fixa de que o microfone está
 * em uso. Só é iniciado com o app aberto (exigência do Android 14+), quando a pessoa aperta
 * REC, e para junto com a gravação.
 */
class ServicoDeGravacao : Service() {
    private var trava: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == PARAR) {
            parar?.invoke()
            encerrar()
            return START_NOT_STICKY
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CANAL, "Gravação da criação musical", NotificationManager.IMPORTANCE_LOW),
        )
        val abrir = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val pararGravacao = PendingIntent.getService(this, 1, Intent(this, ServicoDeGravacao::class.java).setAction(PARAR), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notificacao = NotificationCompat.Builder(this, CANAL)
            .setSmallIcon(R.drawable.ic_conversao)
            .setContentTitle("Gravando na criação musical")
            .setContentText(intent?.getStringExtra(FAIXA)?.let { "Faixa: $it · a tomada vai direto para o armazenamento" } ?: "A tomada vai direto para o armazenamento")
            .setContentIntent(abrir).setOngoing(true).setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "Parar a gravação", pararGravacao).build()
        // O tipo só existe a partir do Android 10; o ServiceCompat ignora no 9 (minSdk 28).
        ServiceCompat.startForeground(this, NOTIFICACAO, notificacao, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        if (trava == null) {
            trava = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "smellsliketech:gravacao").apply { acquire(4 * 60 * 60 * 1000L) }
        }
        return START_NOT_STICKY
    }

    private fun encerrar() {
        if (trava?.isHeld == true) trava?.release()
        trava = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (trava?.isHeld == true) trava?.release()
        super.onDestroy()
    }

    companion object {
        private const val CANAL = "gravacao-musical"
        private const val NOTIFICACAO = 4710
        private const val PARAR = "parar"
        private const val FAIXA = "faixa"

        /** O que a notificação "Parar a gravação" faz (o ViewModel registra). */
        @Volatile var parar: (() -> Unit)? = null

        fun iniciar(contexto: Context, faixa: String) {
            contexto.startForegroundService(Intent(contexto, ServicoDeGravacao::class.java).putExtra(FAIXA, faixa))
        }

        fun encerrar(contexto: Context) {
            contexto.stopService(Intent(contexto, ServicoDeGravacao::class.java))
        }
    }
}
