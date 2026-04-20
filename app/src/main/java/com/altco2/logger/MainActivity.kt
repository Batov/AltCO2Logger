package com.altco2.logger

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.altco2.logger.db.MeasurementEntity
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.Description
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val context = LocalContext.current
            val permissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestMultiplePermissions(),
                onResult = { }
            )

            LaunchedEffect(Unit) {
                val missing = requiredPermissions().filterNot { permission ->
                    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
                }
                if (missing.isNotEmpty()) {
                    permissionLauncher.launch(missing.toTypedArray())
                }
            }

            AppScreen(vm)
        }
    }

    private fun requiredPermissions(): List<String> {
        val out = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            out += Manifest.permission.BLUETOOTH_SCAN
            out += Manifest.permission.BLUETOOTH_CONNECT
        }
        out += Manifest.permission.ACCESS_FINE_LOCATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            out += Manifest.permission.POST_NOTIFICATIONS
        }
        return out
    }
}

@Composable
private fun AppScreen(vm: MainViewModel) {
    val status by vm.status.collectAsState()
    val measurements by vm.measurements.collectAsState()
    val context = LocalContext.current
    var showCleanDialog by remember { mutableStateOf(false) }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFFEAF8F1), Color(0xFFF7FBFF))
                    )
                )
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "AltCO2 Night Logger",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Status: ${status.stateText}")
                    Text("Connected: ${status.connectedDevice ?: "-"}")
                    Text("CO2: ${status.latestCo2Ppm?.toString() ?: "-"} ppm")
                    Text("Temp: ${status.latestTempCentiDeg?.let { formatTemp(it) } ?: "-"}")
                    Text("Last sample: ${status.lastUpdateMs?.let(::formatTime) ?: "-"}")
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { vm.startLogging(context) }) {
                    Text("Start")
                }
                Button(onClick = { vm.stopLogging(context) }) {
                    Text("Stop")
                }
                Button(onClick = { vm.requestBatteryOptimizationIgnore(context) }) {
                    Text("No Sleep")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { exportCsv(context, measurements) }) {
                    Text("Export CSV")
                }
                Button(onClick = { showCleanDialog = true }) {
                    Text("Clean")
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text("CO2 Trend", fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Co2Chart(measurements)
                }
            }

            Text("Recent samples", fontWeight = FontWeight.SemiBold)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(measurements.takeLast(25).reversed()) { row ->
                    Text(
                        text = "${formatTime(row.timestampMs)} | CO2 ${row.co2Ppm} ppm | T ${row.tempCentiDeg?.let { formatTemp(it) } ?: "-"}"
                    )
                }
            }
        }

        if (showCleanDialog) {
            AlertDialog(
                onDismissRequest = { showCleanDialog = false },
                title = { Text("Clear all samples?") },
                text = { Text("This will permanently delete all saved measurements on this phone.") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            vm.clearMeasurements()
                            showCleanDialog = false
                            Toast.makeText(context, "All samples deleted", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Text("Clean")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showCleanDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

@Composable
private fun Co2Chart(measurements: List<MeasurementEntity>) {
    AndroidView(
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp),
        factory = { context ->
            LineChart(context).apply {
                description = Description().apply { text = "" }
                axisRight.isEnabled = false
                legend.isEnabled = false
                xAxis.isEnabled = true
            }
        },
        update = { chart ->
            val points = measurements.mapIndexed { idx, sample ->
                Entry(idx.toFloat(), sample.co2Ppm.toFloat())
            }

            val dataSet = LineDataSet(points, "CO2").apply {
                color = android.graphics.Color.parseColor("#1D6B4F")
                lineWidth = 2f
                setDrawCircles(false)
                setDrawValues(false)
            }

            chart.data = LineData(dataSet)
            chart.invalidate()
        }
    )
}

private fun formatTime(ts: Long): String {
    return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ts))
}

private fun formatTemp(cdeg: Int): String {
    return String.format(Locale.getDefault(), "%.2f C", cdeg / 100.0)
}

private fun exportCsv(context: android.content.Context, rows: List<MeasurementEntity>) {
    if (rows.isEmpty()) {
        Toast.makeText(context, "No data to export yet", Toast.LENGTH_SHORT).show()
        return
    }

    val ordered = rows.sortedBy { it.timestampMs }
    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    val outFile = File(context.cacheDir, "altco2_$stamp.csv")

    outFile.bufferedWriter().use { writer ->
        writer.appendLine("date,time,timestamp_ms,co2_ppm,temp_c")
        ordered.forEach { row ->
            val dt = Date(row.timestampMs)
            val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(dt)
            val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(dt)
            val temp = row.tempCentiDeg?.let { String.format(Locale.US, "%.2f", it / 100.0) } ?: ""
            writer.appendLine("$dateStr,$timeStr,${row.timestampMs},${row.co2Ppm},$temp")
        }
    }

    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        outFile,
    )

    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "AltCO2 export")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    context.startActivity(Intent.createChooser(shareIntent, "Share CSV"))
}
