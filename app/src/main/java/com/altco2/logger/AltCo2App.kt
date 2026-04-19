package com.altco2.logger

import android.app.Application

class AltCo2App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppContextHolder.appContext = applicationContext
    }
}
