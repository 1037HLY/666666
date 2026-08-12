package com.geosurvey.toolbox.domain.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.geosurvey.toolbox.R
import com.geosurvey.toolbox.data.database.AppDatabase
import com.geosurvey.toolbox.data.repository.LocationRepository
import kotlinx.coroutines.*

/**
 * 前台定位服务
 * 支持应用在后台和熄屏状态下持续定位
 */
class LocationForegroundService : Service() {
    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "location_channel"
        private const val CHANNEL_NAME = "定位服务"
        const val ACTION_START = "START_LOCATION_SERVICE"
        const val ACTION_STOP = "STOP_LOCATION_SERVICE"

        /**
         * 启动服务
         */
        fun startService(context: Context) {
            val intent = Intent(context, LocationForegroundService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * 停止服务
         */
        fun stopService(context: Context) {
            val intent = Intent(context, LocationForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.stopService(intent)
        }
    }

    private lateinit var locationRepository: LocationRepository
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var isRunning = false

    override fun onCreate() {
        super.onCreate()
        // 初始化Repository
        val database = AppDatabase.getDatabase(this)
        locationRepository = LocationRepository(this, database.locationDao())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startLocationService()
            ACTION_STOP -> stopLocationService()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopLocationService()
        serviceScope.cancel()
    }

    /**
     * 启动定位服务
     */
    private fun startLocationService() {
        if (isRunning) return

        // 创建通知渠道（Android 8.0+）
        createNotificationChannel()

        // 创建通知
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)

        // 开始定位
        if (hasLocationPermission()) {
            locationRepository.startLocationUpdates()
            locationRepository.startTracking()
            isRunning = true
        }
    }

    /**
     * 停止定位服务
     */
    private fun stopLocationService() {
        if (!isRunning) return

        locationRepository.stopTracking()
        locationRepository.stopLocationUpdates()
        locationRepository.cleanup()

        stopForeground(true)
        isRunning = false
    }

    /**
     * 创建通知渠道
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "后台定位服务"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * 创建通知
     */
    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("地质勘查工具箱")
            .setContentText("正在记录GPS轨迹...")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setAutoCancel(false)
            .build()
    }

    /**
     * 检查定位权限
     */
    private fun hasLocationPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            this,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }
}
