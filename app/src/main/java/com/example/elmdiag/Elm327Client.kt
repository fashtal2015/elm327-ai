package com.example.elmdiag

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Minimal ELM327 (Bluetooth classic / SPP) client.
 * Manifest permissions needed:
 *   BLUETOOTH_CONNECT (Android 12+), BLUETOOTH + BLUETOOTH_ADMIN (<= Android 11)
 * Request BLUETOOTH_CONNECT at runtime before calling connect().
 */
class Elm327Client {

    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    private val sppUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    /** Paired ELM327 adapters (user picks one from a list). */
    @SuppressLint("MissingPermission")
    fun pairedDevices(): List<BluetoothDevice> =
        BluetoothAdapter.getDefaultAdapter()?.bondedDevices?.toList().orEmpty()

    @SuppressLint("MissingPermission")
    suspend fun connect(device: BluetoothDevice) = withContext(Dispatchers.IO) {
        BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery()
        val s = device.createRfcommSocketToServiceRecord(sppUuid)
        s.connect()
        socket = s
        input = s.inputStream
        output = s.outputStream
        initElm()
    }

    /**
     * Peugeot 206 with Valeo J35 talks KWP2000 on the K-line (ISO 14230).
     * Try fast init (5) first, then slow init (4), then auto (0).
     */
    private suspend fun initElm() {
        send("ATZ", 1500)
        send("ATE0"); send("ATL0"); send("ATS0"); send("ATH0")
        for (proto in listOf("5", "4", "0")) {
            send("ATSP$proto")
            val r = send("0100", 6000)
            if (!r.contains("UNABLE") && !r.contains("ERROR") && !r.contains("NO DATA")) return
        }
        throw IllegalStateException("ECU did not answer on any protocol")
    }

    /** Send a command, read until the '>' prompt or timeout. */
    suspend fun send(cmd: String, timeoutMs: Long = 3000): String = withContext(Dispatchers.IO) {
        val out = output ?: error("Not connected")
        val inp = input ?: error("Not connected")
        while (inp.available() > 0) inp.read()          // flush
        out.write((cmd + "\r").toByteArray())
        out.flush()

        val sb = StringBuilder()
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (inp.available() > 0) {
                val c = inp.read().toChar()
                if (c == '>') break
                sb.append(c)
            } else Thread.sleep(15)
        }
        sb.toString().replace("\r", " ").replace("\n", " ").trim()
    }

    /** Parse hex payload like "41 0C 1A F8" -> bytes after the mode+pid header. */
    private fun dataBytes(resp: String, header: String): List<Int>? {
        val tokens = resp.uppercase().split(" ").filter { it.length == 2 }
        val i = tokens.indexOfFirst { it == header.substring(0, 2) }
        if (i < 0 || tokens.size < i + 2 || tokens[i + 1] != header.substring(2, 4)) return null
        return tokens.drop(i + 2).mapNotNull { it.toIntOrNull(16) }
    }

    suspend fun rpm(): Int? = dataBytes(send("010C"), "410C")
        ?.takeIf { it.size >= 2 }?.let { (it[0] * 256 + it[1]) / 4 }

    suspend fun speedKmh(): Int? = dataBytes(send("010D"), "410D")?.firstOrNull()

    suspend fun coolantC(): Int? = dataBytes(send("0105"), "4105")?.firstOrNull()?.minus(40)

    suspend fun throttlePct(): Int? = dataBytes(send("0111"), "4111")?.firstOrNull()?.let { it * 100 / 255 }

    /** Read stored fault codes (mode 03). */
    suspend fun readDtcs(): List<String> {
        val tokens = send("03", 5000).uppercase().split(" ").filter { it.length == 2 }
        val start = tokens.indexOf("43").let { if (it < 0) return emptyList() else it + 1 }
        val bytes = tokens.drop(start).mapNotNull { it.toIntOrNull(16) }
        val codes = mutableListOf<String>()
        var i = 0
        while (i + 1 < bytes.size) {
            val a = bytes[i]; val b = bytes[i + 1]; i += 2
            if (a == 0 && b == 0) continue
            val letter = "PCBU"[(a shr 6) and 3]
            codes += "%c%d%d%02X".format(letter, (a shr 4) and 3, a and 0xF, b)
        }
        return codes
    }

    /** Clear fault codes (mode 04). */
    suspend fun clearDtcs(): Boolean = send("04", 5000).contains("44")

    fun close() {
        runCatching { socket?.close() }
        socket = null; input = null; output = null
    }
}
