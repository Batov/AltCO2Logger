package com.altco2.logger

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.altco2.logger.db.AppDatabase
import com.altco2.logger.db.MeasurementEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel : ViewModel() {

    val status: StateFlow<LoggerStatus> = LoggerStatusStore.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LoggerStatus())

    private val measurementsDao by lazy {
        AppDatabase.getInstance(AppContextHolder.appContext).measurementDao()
    }

    val measurements: StateFlow<List<MeasurementEntity>> = measurementsDao.observeRecent(1200)
        .map { it.sortedBy { row -> row.timestampMs } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun startLogging(context: Context) {
        val intent = Intent(context, BleLoggerService::class.java).apply {
            action = BleLoggerService.ACTION_START
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun stopLogging(context: Context) {
        val intent = Intent(context, BleLoggerService::class.java).apply {
            action = BleLoggerService.ACTION_STOP
        }
        context.startService(intent)
    }

    fun requestBatteryOptimizationIgnore(context: Context) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(context.packageName)) return

        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun clearMeasurements() {
        viewModelScope.launch {
            measurementsDao.clearAll()
        }
    }

}

object AppContextHolder {
    lateinit var appContext: Context
}
