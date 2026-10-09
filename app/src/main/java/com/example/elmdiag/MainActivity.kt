package com.example.elmdiag

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*

class MainActivity : ComponentActivity() {

    private val elm = Elm327Client()
    private var devices: List<BluetoothDevice> = emptyList()
    private var pollJob: Job? = null

    private lateinit var spinner: Spinner
    private lateinit var status: TextView
    private lateinit var live: TextView
    private lateinit var dtcView: TextView

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            if (ok) loadDevices() else status.text = "Bluetooth permission denied"
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        if (Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) permLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        else loadDevices()
    }

    private fun buildUi() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        spinner = Spinner(this)
        val btnConnect = Button(this).apply { text = "Connect"; setOnClickListener { connect() } }
        status = TextView(this).apply { text = "Pair your ELM327 in Android settings first" }
        live = TextView(this).apply {
            textSize = 22f; typeface = Typeface.MONOSPACE; setPadding(0, pad, 0, pad)
        }
        val btnRead = Button(this).apply { text = "Read fault codes"; setOnClickListener { readDtc() } }
        val btnClear = Button(this).apply { text = "Clear fault codes"; setOnClickListener { clearDtc() } }
        dtcView = TextView(this).apply { textSize = 16f; setPadding(0, pad, 0, 0) }

        listOf<View>(spinner, btnConnect, status, live, btnRead, btnClear, dtcView)
            .forEach { root.addView(it) }
        setContentView(ScrollView(this).apply { addView(root) })
    }

    @SuppressLint("MissingPermission")
    private fun loadDevices() {
        devices = elm.pairedDevices()
        val names = devices.map { "${it.name} (${it.address})" }
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        if (devices.isEmpty()) status.text = "No paired Bluetooth devices found"
    }

    private fun connect() {
        val dev = devices.getOrNull(spinner.selectedItemPosition) ?: return
        status.text = "Connecting..."
        lifecycleScope.launch {
            try {
                pollJob?.cancelAndJoin()
                elm.close()
                elm.connect(dev)
                status.text = "Connected"
                startPolling()
            } catch (e: Exception) {
                status.text = "Failed: ${e.message}"
            }
        }
    }

    private fun startPolling() {
        pollJob = lifecycleScope.launch {
            while (isActive) {
                try {
                    val rpm = elm.rpm()
                    val spd = elm.speedKmh()
                    val tmp = elm.coolantC()
                    val thr = elm.throttlePct()
                    live.text = "RPM      : ${rpm ?: "--"}\n" +
                            "Speed    : ${spd ?: "--"} km/h\n" +
                            "Coolant  : ${tmp ?: "--"} °C\n" +
                            "Throttle : ${thr ?: "--"} %"
                } catch (e: Exception) {
                    status.text = "Link lost: ${e.message}"
                    break
                }
                delay(250)
            }
        }
    }

    private fun readDtc() {
        lifecycleScope.launch {
            pollJob?.cancelAndJoin()
            try {
                val codes = elm.readDtcs()
                dtcView.text = if (codes.isEmpty()) "No fault codes" else codes.joinToString("\n")
            } catch (e: Exception) {
                dtcView.text = "Error: ${e.message}"
            }
            startPolling()
        }
    }

    private fun clearDtc() {
        lifecycleScope.launch {
            pollJob?.cancelAndJoin()
            try {
                dtcView.text = if (elm.clearDtcs()) "Codes cleared" else "Clear failed"
            } catch (e: Exception) {
                dtcView.text = "Error: ${e.message}"
            }
            startPolling()
        }
    }

    override fun onDestroy() {
        pollJob?.cancel()
        elm.close()
        super.onDestroy()
    }
}
