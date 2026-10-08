package pl.obd.readonly

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collect

/** Started only by an explicit recording action while the Activity is visible. No automatic restart. */
class RecordingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var task: Job? = null
    private var lastStartId = 0
    private val wakeLock by lazy { getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OBD:Recording").apply { setReferenceCounted(false) } }
    private val engine get() = (application as ObdApplication).engine
    companion object { const val STOP = "pl.obd.readonly.STOP_RECORDING"; const val CHANNEL = "obd_recording"; const val ID = 77 }
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "Rejestracja OBD", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        if (intent?.action == STOP) { engine.stopRecording(); return START_NOT_STICKY }
        if (task?.isActive == true) return START_NOT_STICKY
        val launch = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, RecordingService::class.java).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_obd)
            .setContentTitle("OBD — zapis parametrów").setContentText("Trwa rejestracja CSV. Dotknij, aby otworzyć wykresy.")
            .setOngoing(true).setContentIntent(launch).addAction(0, "Zatrzymaj zapis", stop).build()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            else startForeground(ID, notification)
        } catch (e: Exception) { engine.showMessage("Nie można uruchomić zapisu w tle: ${e.message}"); stopSelf(); return START_NOT_STICKY }
        task = scope.launch {
            try {
                if (engine.beginRecording()) {
                    wakeLock.acquire(30 * 60_000L)
                    val alerts = launch {
                        engine.state.map { it.analysis.alerts.lastOrNull { a -> a.endedUtc == null }?.message }
                            .distinctUntilChanged().collect { message ->
                                getSystemService(NotificationManager::class.java).notify(ID,
                                    NotificationCompat.Builder(this@RecordingService, CHANNEL).setSmallIcon(R.drawable.ic_obd)
                                        .setContentTitle(if (message == null) "OBD — zapis parametrów" else "OBD — alert parametru")
                                        .setContentText(message ?: "Trwa rejestracja CSV. Dotknij, aby otworzyć aplikację.")
                                        .setStyle(NotificationCompat.BigTextStyle().bigText(message ?: "Trwa rejestracja CSV."))
                                        .setOnlyAlertOnce(true).setOngoing(true).setContentIntent(launch).addAction(0, "Zatrzymaj zapis", stop).build())
                            }
                    }
                    val renewal = launch { while (isActive) { delay(15 * 60_000L); if (engine.state.value.recording) wakeLock.acquire(30 * 60_000L) } }
                    try { engine.state.first { !it.recording } } finally { renewal.cancel(); alerts.cancel() }
                }
            } finally {
                if (wakeLock.isHeld) wakeLock.release()
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(lastStartId)
            }
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() { engine.stopRecording("service_stopped"); scope.cancel(); if (wakeLock.isHeld) wakeLock.release(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
