package uk.vo.groundtrace

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.location.*
import android.os.*
import java.io.File
import java.util.Locale

class TrackingService : Service(), LocationListener {
    private lateinit var manager: LocationManager
    private var last: Location? = null
    private var lastSaved = 0L
    private var activeProject = ""
    private val channel = "groundtrace_tracking"
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        manager = getSystemService(LOCATION_SERVICE) as LocationManager
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
            NotificationChannel(channel, "GPS survey tracking", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopLocation(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY
        }
        activeProject = intent?.getStringExtra("project") ?: getSharedPreferences("settings", MODE_PRIVATE).getString("project", "") ?: ""
        if (activeProject.isBlank()) { stopSelf(); return START_NOT_STICKY }
        val notification = Notification.Builder(this, channel)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("GroundTrace recording")
            .setContentText("Project: $activeProject • tap to open")
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setOngoing(true).build()
        startForeground(100, notification)
        getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("recording", true).putString("project", activeProject).apply()
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            try {
                if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this)
                if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 3000L, 0f, this)
            } catch (_: SecurityException) {}
        }
        return START_STICKY
    }

    override fun onLocationChanged(location: Location) {
        if (location.latitude !in -90.0..90.0 || location.longitude !in -180.0..180.0) return
        if (location.hasAccuracy() && location.accuracy > 30f) return
        val now = System.currentTimeMillis()
        if (location.time < now - 15000L || location.time > now + 10000L) return
        if (last != null) {
            if (location.time <= last!!.time) return
            if (location.distanceTo(last!!) < 3f && now - lastSaved < 15000L) return
            val dt = (location.time - last!!.time) / 1000.0
            if (dt > 0 && dt < 45 && location.distanceTo(last!!) / dt > 12) return
        }
        val dir = File(filesDir, "projects/$activeProject")
        dir.mkdirs()
        synchronized(TrackingService::class.java) {
            File(dir, "track.csv").appendText("${location.time},${location.latitude},${location.longitude},${if (location.hasAccuracy()) location.accuracy else -1f},${location.provider ?: "unknown"}\n")
        }
        last = location
        lastSaved = now
        getSharedPreferences("settings", MODE_PRIVATE).edit().putString("last_fix", "${location.latitude},${location.longitude}").putLong("last_fix_time", location.time).apply()
    }

    override fun onProviderDisabled(provider: String) {}
    private fun stopLocation() {
        try { manager.removeUpdates(this) } catch (_: Exception) {}
        getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("recording", false).apply()
    }
    override fun onDestroy() { stopLocation(); super.onDestroy() }
    override fun onTaskRemoved(rootIntent: Intent?) { /* Foreground service persists where Android permits */ super.onTaskRemoved(rootIntent) }
}
