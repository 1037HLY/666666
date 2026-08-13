package com.geosurvey.toolbox

import android.app.Application

class GeoSurveyApplication : Application() {

    companion object {
        lateinit var instance: GeoSurveyApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }
}
