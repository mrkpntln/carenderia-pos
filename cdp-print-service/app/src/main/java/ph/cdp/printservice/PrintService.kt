package ph.cdp.printservice

import android.Manifest
import android.app.*
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import org.json.JSONObject
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.concurrent.thread

class PrintService : Service() {
    companion object {
        const val ACTION_CONNECT = "ph.cdp.printservice.CONNECT"
        const val ACTION_PRINT = "ph.cdp.printservice.PRINT"
        const val ACTION_TEST = "ph.cdp.printservice.TEST"
        const val ACTION_DISCONNECT = "ph.cdp.printservice.DISCONNECT"
        const val EXTRA_MAC = "mac"
        const val EXTRA_PAYLOAD = "payload"
        private const val CHANNEL = "cdp_printer_channel"
        private const val NOTIFICATION_ID = 58
    }
    private val spp = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var output: OutputStream? = null

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() { super.onCreate(); createChannel() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification("Background printer service is active"))
        when (intent?.action) {
            ACTION_CONNECT -> intent.getStringExtra(EXTRA_MAC)?.let { connect(it) }
            ACTION_PRINT -> intent.getStringExtra(EXTRA_PAYLOAD)?.let {
                try { printPayload(JSONObject(it)) } catch (_: Exception) { updateNotification("Could not read receipt data") }
            }
            ACTION_TEST -> printPayload(testPayload())
            ACTION_DISCONNECT -> {
                closePrinter()
                updateNotification("Printer disconnected")
                if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else stopForeground(true)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun hasBluetoothPermission() =
        Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun connect(mac: String) {
        thread {
            try {
                if (!hasBluetoothPermission()) throw SecurityException("Allow Nearby devices permission in CDP Print Service")
                val device = BluetoothAdapter.getDefaultAdapter()?.getRemoteDevice(mac)
                    ?: throw IllegalStateException("Bluetooth adapter unavailable")
                try { BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery() } catch (_: Exception) {}
                closePrinter()
                val s = device.createRfcommSocketToServiceRecord(spp)
                s.connect()
                socket = s
                output = s.outputStream
                getSharedPreferences("cdp_print", MODE_PRIVATE).edit().putString("printer_mac", mac).apply()
                updateNotification("Connected · ${device.name ?: mac} · ready to print")
            } catch (e: Exception) {
                updateNotification("Connection failed: ${e.message ?: "unknown error"}")
            }
        }
    }

    private fun printPayload(payload: JSONObject) {
        val receipt = payload.optJSONObject("receipt") ?: run { updateNotification("Invalid receipt payload"); return }
        val bytes = buildEscPos(receipt)
        thread {
            try {
                var stream = output
                if (stream == null) {
                    val mac = getSharedPreferences("cdp_print", MODE_PRIVATE).getString("printer_mac", null)
                        ?: throw IllegalStateException("Open CDP Print Service once and connect to the printer")
                    if (!hasBluetoothPermission()) throw SecurityException("Nearby devices permission required")
                    val device = BluetoothAdapter.getDefaultAdapter()?.getRemoteDevice(mac)
                        ?: throw IllegalStateException("Bluetooth unavailable")
                    val s = device.createRfcommSocketToServiceRecord(spp)
                    s.connect()
                    socket = s
                    stream = s.outputStream
                    output = stream
                }
                stream.write(bytes)
                stream.flush()
                updateNotification("Receipt printed successfully")
            } catch (e: Exception) {
                updateNotification("Print failed: ${e.message ?: "unknown error"}")
            }
        }
    }

    private fun closePrinter() {
        try { output?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        output = null
        socket = null
    }

    private fun testPayload() = JSONObject().put("protocol", "cdp-print-v1").put("receipt",
        JSONObject().put("receipt", "CDP-TEST").put("date", Date().toString()).put("total", 1.0)
            .put("cash", 1.0).put("change", 0.0).put("items",
                org.json.JSONArray().put(JSONObject().put("name", "Printer test").put("quantity", 1).put("lineTotal", 1.0))))

    private fun buildEscPos(r: JSONObject): ByteArray {
        val data = ArrayList<Byte>()
        fun raw(vararg b: Int) { b.forEach { data.add(it.toByte()) } }
        fun txt(s: String) { s.toByteArray(Charsets.UTF_8).forEach { data.add(it) } }
        fun line(s: String = "") { txt(s); raw(10) }
        fun center() { raw(0x1B, 0x61, 1) }
        fun left() { raw(0x1B, 0x61, 0) }
        fun bold(on: Boolean) { raw(0x1B, 0x45, if (on) 1 else 0) }
        raw(0x1B, 0x40); center(); bold(true); raw(0x1D, 0x21, 0x11); line("CDP")
        raw(0x1D, 0x21, 0); bold(false); line("Carenderia De Pantaleon"); line("OFFICIAL RECEIPT")
        left(); line("--------------------------------")
        line("Receipt: ${r.optString("receipt", "TEST")}")
        if (r.optBoolean("offline")) line("OFFLINE RECEIPT")
        if (r.optString("orderLabel").isNotBlank()) line(r.optString("orderLabel"))
        else if (r.optInt("tableNo", 0) > 0) line("Table ${r.optInt("tableNo")}")
        line("Date: ${r.optString("date", SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date()))}")
        line("--------------------------------")
        val items = r.optJSONArray("items")
        if (items != null) for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            line(item.optString("name", "Item").take(30))
            line("  ${item.optInt("quantity", 1)} x                   ${"%.2f".format(Locale.US, item.optDouble("lineTotal", 0.0))}")
        }
        line("--------------------------------"); bold(true)
        line("TOTAL: PHP ${"%.2f".format(Locale.US, r.optDouble("total", 0.0))}"); bold(false)
        line("Cash:  PHP ${"%.2f".format(Locale.US, r.optDouble("cash", 0.0))}")
        line("Change: PHP ${"%.2f".format(Locale.US, r.optDouble("change", 0.0))}")
        line("--------------------------------"); center(); line("Thank you!"); line("Please come again.")
        line(); line(); line(); raw(0x1D, 0x56, 0)
        return data.toByteArray()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "CDP Printer", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Keeps Bluetooth receipt printing available in the background"
            })
        }
    }
    private fun notification(message: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        return builder.setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("CDP Print Service").setContentText(message).setOngoing(true).build()
    }
    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(message))
    }
    override fun onDestroy() { closePrinter(); super.onDestroy() }
}