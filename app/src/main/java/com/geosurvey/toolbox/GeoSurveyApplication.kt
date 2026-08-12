package com.geosurvey.toolbox

import android.app.Application
import androidx.room.Room
import com.geosurvey.toolbox.data.database.AppDatabase
import com.geosurvey.toolbox.domain.service.LocationForegroundService

class GeoSurveyApplication : Application() {

    companion object {
        lateinit var instance: GeoSurveyApplication
            private set
    }

    val database by lazy {
        AppDatabase.getDatabase(this)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 应用启动时自动启动定位服务（可选）
        // 如果需要后台定位，取消注释以下代码
        // LocationForegroundService.startService(this)
    }
}
