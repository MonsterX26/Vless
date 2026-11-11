package com.cipherlink.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.concurrent.atomic.AtomicBoolean

class TunnelService : VpnService() {
    private val starting = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var connectivity: ConnectivityManager? = null
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            CoreBridge.networkChanged()
            if (CoreBridge.isRunning()) mainHandler.post {
                notifyStatus("connecté", "réseau rétabli")
            }
        }
        override fun onLost(network: Network) {
            mainHandler.post { notifyStatus("réseau en transition", "reconnexion au réseau en attente") }
        }
    }

    override fun onCreate() {
        super.onCreate()
        connectivity = getSystemService(ConnectivityManager::class.java)
        try { connectivity?.registerDefaultNetworkCallback(networkCallback) }
        catch (error: Exception) { Log.w(TAG, "surveillance réseau indisponible", error) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(LAST_CONFIG).apply()
                shutdown()
            }
            ACTION_START -> {
                val config = intent.getStringExtra(EXTRA_CONFIG).orEmpty()
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(LAST_CONFIG, config).apply()
                startTunnel(config)
            }
            else -> {
                // START_STICKY recovery after Android reclaims the process.
                val saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString(LAST_CONFIG, "").orEmpty()
                if (saved.isNotBlank()) startTunnel(saved) else stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startTunnel(uri: String) {
        if (!starting.compareAndSet(false, true)) return
        createChannel()
        // Promote immediately; Android enforces a short foreground-service deadline.
        startForeground(NOTIFICATION_ID, buildNotification("démarrage…", "CipherLink"))

        val node = VlessParser.parse(uri)
        if (node == null) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(LAST_CONFIG).apply()
            starting.set(false)
            notifyStatus("configuration invalide", "URI VLESS non reconnue")
            shutdown(removeNotification = false)
            return
        }
        if (!CoreBridge.isCoreAvailable()) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(LAST_CONFIG).apply()
            starting.set(false)
            notifyStatus("moteur absent", "libbox n'a pas été chargé par Gradle")
            shutdown(removeNotification = false)
            return
        }

        Thread {
            val error = try {
                CoreBridge.start(this, VlessParser.toSingBox(node))
            } catch (failure: Throwable) {
                failure.message ?: failure.javaClass.simpleName
            }
            mainHandler.post {
                starting.set(false)
                if (error == null) {
                    notifyStatus("connecté", node.name)
                    CoreBridge.networkChanged()
                }
                else {
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(LAST_CONFIG).apply()
                    Log.e(TAG, "sing-box start failed: $error")
                    notifyStatus("échec de connexion", error)
                    shutdown(removeNotification = false)
                }
            }
        }.start()
    }

    private fun shutdown(removeNotification: Boolean = true) {
        starting.set(false)
        Thread {
            CoreBridge.stop()
            mainHandler.post {
                stopForeground(removeNotification)
                stopSelf()
            }
        }.start()
    }

    override fun onRevoke() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(LAST_CONFIG).apply()
        shutdown()
        super.onRevoke()
    }

    override fun onDestroy() {
        try { connectivity?.unregisterNetworkCallback(networkCallback) }
        catch (_: Exception) { }
        CoreBridge.stop()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "CipherLink VPN", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification(title: String, text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_vpn_ic)
            .setContentIntent(pendingIntent)
            .setOngoing(title == "connecté" || title == "démarrage…")
            .setOnlyAlertOnce(true)
        if (title == "connecté" || title == "démarrage…") {
            val stopIntent = Intent(this, TunnelService::class.java).apply { action = ACTION_STOP }
            val stopPending = PendingIntent.getService(
                this, 2, stopIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(android.R.drawable.ic_media_pause, "Déconnecter", stopPending)
        }
        return builder.build()
    }

    private fun notifyStatus(title: String, text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(title, text))
    }

    companion object {
        const val ACTION_START = "com.cipherlink.vpn.action.START"
        const val ACTION_STOP = "com.cipherlink.vpn.action.STOP"
        const val EXTRA_CONFIG = "config"
        private const val TAG = "TunnelService"
        private const val CHANNEL = "cipherlink"
        private const val NOTIFICATION_ID = 1001
        private const val PREFS = "cipherlink"
        private const val LAST_CONFIG = "last_active_config"
    }
}
