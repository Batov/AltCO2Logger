package com.altco2.logger

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.altco2.logger.db.AppDatabase
import com.altco2.logger.db.MeasurementEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.ArrayDeque

class BleLoggerService : Service() {

    companion object {
        const val ACTION_START = "com.altco2.logger.action.START"
        const val ACTION_STOP = "com.altco2.logger.action.STOP"

        private const val CHANNEL_ID = "altco2_logger_channel"
        private const val NOTIFICATION_ID = 1001
        private const val TARGET_NAME = "AltCO2"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dao by lazy { AppDatabase.getInstance(applicationContext).measurementDao() }

    private var scanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    private var scanning = false
    private val pendingNotifyQueue = ArrayDeque<BluetoothGattCharacteristic>()

    private var lastCo2Ppm: Int? = null
    private var lastTempCentiDeg: Int? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startLogging()
            ACTION_STOP -> stopLogging()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopScan()
        closeGatt()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startLogging() {
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Запуск BLE логгера"))

        LoggerStatusStore.update {
            it.copy(running = true, stateText = "Starting")
        }

        if (!hasBlePermissions()) {
            LoggerStatusStore.update {
                it.copy(stateText = "Missing BLE permissions")
            }
            stopSelf()
            return
        }

        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = manager.adapter

        if (adapter == null || !adapter.isEnabled) {
            LoggerStatusStore.update {
                it.copy(stateText = "Bluetooth is disabled")
            }
            stopSelf()
            return
        }

        scanner = adapter.bluetoothLeScanner
        startScan()
    }

    private fun stopLogging() {
        LoggerStatusStore.reset()
        stopScan()
        closeGatt()
        pendingNotifyQueue.clear()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "AltCO2 logger",
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AltCO2 logging")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
    }

    private fun updateForeground(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun startScan() {
        val bleScanner = scanner
        if (bleScanner == null) {
            LoggerStatusStore.update {
                it.copy(stateText = "BLE scanner unavailable")
            }
            updateForeground("BLE scanner unavailable")
            return
        }
        if (scanning) return

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanning = true
        LoggerStatusStore.update {
            it.copy(stateText = "Scanning for $TARGET_NAME")
        }
        updateForeground("Scanning for $TARGET_NAME")
        try {
            bleScanner.startScan(null, settings, scanCallback)
        } catch (t: Throwable) {
            scanning = false
            LoggerStatusStore.update {
                it.copy(stateText = "Scan start failed: ${t.message}")
            }
            updateForeground("Scan start failed")
        }
    }

    private fun stopScan() {
        val bleScanner = scanner ?: return
        if (!scanning) return

        runCatching { bleScanner.stopScan(scanCallback) }
        scanning = false
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val name = device.name ?: result.scanRecord?.deviceName
            if (name == TARGET_NAME) {
                stopScan()
                connect(device)
            }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { item ->
                val device = item.device ?: return@forEach
                val name = device.name ?: item.scanRecord?.deviceName
                if (name == TARGET_NAME) {
                    stopScan()
                    connect(device)
                    return
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            LoggerStatusStore.update {
                it.copy(stateText = "Scan failed: $errorCode")
            }
            updateForeground("Scan failed: $errorCode")
        }
    }

    private fun connect(device: BluetoothDevice) {
        LoggerStatusStore.update {
            it.copy(stateText = "Connecting", connectedDevice = device.address)
        }
        updateForeground("Connecting to ${device.address}")

        closeGatt()
        @Suppress("MissingPermission")
        val createdGatt = device.connectGatt(this, false, gattCallback)
        gatt = createdGatt
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothGatt.STATE_CONNECTED -> {
                    stopScan()
                    LoggerStatusStore.update {
                        it.copy(stateText = "Connected, discovering services")
                    }
                    updateForeground("Connected, discovering services")
                    @Suppress("MissingPermission")
                    gatt.discoverServices()
                }

                BluetoothGatt.STATE_DISCONNECTED -> {
                    LoggerStatusStore.update {
                        it.copy(stateText = "Disconnected, reconnecting", connectedDevice = null)
                    }
                    updateForeground("Disconnected, reconnecting")
                    closeGatt()
                    startScan()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                LoggerStatusStore.update {
                    it.copy(stateText = "Service discovery failed: $status")
                }
                updateForeground("Service discovery failed")
                return
            }

            val ess = gatt.getService(BleUuids.ESS_SERVICE)
            val co2Char = ess?.getCharacteristic(BleUuids.CO2_CHAR)
            val tempChar = ess?.getCharacteristic(BleUuids.TEMP_CHAR)

            if (ess == null || co2Char == null || tempChar == null) {
                LoggerStatusStore.update {
                    it.copy(stateText = "ESS/CO2/TEMP characteristic not found")
                }
                updateForeground("Required characteristics not found")
                return
            }

            startNotificationSetup(gatt, listOf(co2Char, tempChar))
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            handleCharacteristic(characteristic.uuid, characteristic.value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handleCharacteristic(characteristic.uuid, value)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failNotificationSetup("Descriptor write failed: $status")
                return
            }

            if (pendingNotifyQueue.isNotEmpty()) {
                pendingNotifyQueue.removeFirst()
            }
            enableNextNotification(gatt)
        }
    }

    private fun startNotificationSetup(gatt: BluetoothGatt, chars: List<BluetoothGattCharacteristic>) {
        pendingNotifyQueue.clear()
        chars.forEach { pendingNotifyQueue.addLast(it) }
        LoggerStatusStore.update {
            it.copy(stateText = "Enabling notifications")
        }
        updateForeground("Enabling notifications")
        enableNextNotification(gatt)
    }

    private fun enableNextNotification(gatt: BluetoothGatt) {
        val characteristic = pendingNotifyQueue.firstOrNull() ?: run {
            LoggerStatusStore.update {
                it.copy(stateText = "Logging started")
            }
            updateForeground("Logging started")
            return
        }

        @Suppress("MissingPermission")
        val local = gatt.setCharacteristicNotification(characteristic, true)
        if (!local) {
            failNotificationSetup("setCharacteristicNotification failed")
            return
        }

        val descriptor = characteristic.getDescriptor(BleUuids.CCC_DESC)
        if (descriptor == null) {
            failNotificationSetup("CCC descriptor missing")
            return
        }

        @Suppress("DEPRECATION")
        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE

        @Suppress("MissingPermission", "DEPRECATION")
        val remote = gatt.writeDescriptor(descriptor)
        if (!remote) {
            failNotificationSetup("writeDescriptor failed")
        }
    }

    private fun failNotificationSetup(reason: String) {
        pendingNotifyQueue.clear()
        LoggerStatusStore.update {
            it.copy(stateText = "Failed to enable notifications: $reason")
        }
        updateForeground("Notification setup failed")
    }

    private fun handleCharacteristic(uuid: java.util.UUID, value: ByteArray?) {
        val payload = value ?: return
        when (uuid) {
            BleUuids.CO2_CHAR -> {
                val co2 = parseUInt16Le(payload) ?: return
                lastCo2Ppm = co2
                persistSample(co2, lastTempCentiDeg)
            }

            BleUuids.TEMP_CHAR -> {
                val temp = parseInt16Le(payload) ?: return
                lastTempCentiDeg = temp
                LoggerStatusStore.update {
                    it.copy(latestTempCentiDeg = temp, lastUpdateMs = System.currentTimeMillis())
                }
            }
        }
    }

    private fun persistSample(co2: Int, tempCentiDeg: Int?) {
        val now = System.currentTimeMillis()

        LoggerStatusStore.update {
            it.copy(
                latestCo2Ppm = co2,
                latestTempCentiDeg = tempCentiDeg,
                lastUpdateMs = now,
            )
        }

        serviceScope.launch {
            dao.insert(
                MeasurementEntity(
                    timestampMs = now,
                    co2Ppm = co2,
                    tempCentiDeg = tempCentiDeg,
                )
            )
        }
    }

    private fun parseUInt16Le(data: ByteArray): Int? {
        if (data.size < 2) return null
        val lo = data[0].toInt() and 0xFF
        val hi = data[1].toInt() and 0xFF
        return (hi shl 8) or lo
    }

    private fun parseInt16Le(data: ByteArray): Int? {
        if (data.size < 2) return null
        val lo = data[0].toInt() and 0xFF
        val hi = data[1].toInt()
        return (hi shl 8) or lo
    }

    private fun hasBlePermissions(): Boolean {
        val base = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            base += Manifest.permission.BLUETOOTH_SCAN
            base += Manifest.permission.BLUETOOTH_CONNECT
        }
        base += Manifest.permission.ACCESS_FINE_LOCATION

        return base.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun closeGatt() {
        pendingNotifyQueue.clear()
        runCatching {
            @Suppress("MissingPermission")
            gatt?.disconnect()
        }
        runCatching { gatt?.close() }
        gatt = null
    }
}
