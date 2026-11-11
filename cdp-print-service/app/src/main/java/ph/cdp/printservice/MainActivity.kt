package ph.cdp.printservice

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.*
import org.json.JSONObject
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private val sppUuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private var adapter: BluetoothAdapter? = null
    private var socket: BluetoothSocket? = null
    private var output: OutputStream? = null
    private var selectedDevice: BluetoothDevice? = null
    private var pendingPayload: JSONObject? = null
    private lateinit var status: TextView
    private lateinit var selectedLabel: TextView
    private lateinit var devicesBox: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        adapter = BluetoothAdapter.getDefaultAdapter()
        buildScreen()
        handleDeepLink(intent)
        requestBluetoothPermissionIfNeeded()
        refreshDevices()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    private fun buildScreen() {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 30, 28, 28)
            setBackgroundColor(0xFFFBFAF5.toInt())
        }
        val scroll = ScrollView(this).apply { addView(panel) }
        setContentView(scroll)
        panel.addView(TextView(this).apply {
            text = "CDP PRINT SERVICE"
            textSize = 23f
            setTextColor(0xFF203D30.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        panel.addView(TextView(this).apply {
            text = "Carenderia De Pantaleon · Bluetooth receipt printing"
            textSize = 13f
            setTextColor(0xFF68736B.toInt())
            setPadding(0, 6, 0, 20)
        })
        status = TextView(this).apply {
            text = "Status: Checking Bluetooth…"
            textSize = 14f
            setTextColor(0xFF203D30.toInt())
            setPadding(14, 14, 14, 14)
            setBackgroundColor(0xFFEAF0E8.toInt())
        }
        panel.addView(status, matchWrap())
        selectedLabel = TextView(this).apply {
            text = "Selected printer: none"
            textSize = 14f
            setPadding(0, 18, 0, 8)
            setTextColor(0xFF26372D.toInt())
        }
        panel.addView(selectedLabel)
        panel.addView(actionButton("Refresh paired devices") { refreshDevices() })
        panel.addView(TextView(this).apply {
            text = "PAIRED BLUETOOTH DEVICES"
            textSize = 12f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(0xFF68736B.toInt())
            setPadding(0, 18, 0, 8)
        })
        devicesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(devicesBox, matchWrap())
        panel.addView(actionButton("Connect to selected printer") { connectPrinter() })
        panel.addView(actionButton("Print test receipt") { printTest() })
        panel.addView(actionButton("Disconnect") { disconnectPrinter() })
        panel.addView(TextView(this).apply {
            text = "How to use\n1. Pair the Officom printer in Android Bluetooth settings.\n2. Select it from the paired-device list above.\n3. Tap Connect, then Print test receipt.\n4. In CDP POS, choose PRINT RECEIPT to send a sale receipt here."
            textSize = 13f
            setTextColor(0xFF68736B.toInt())
            setPadding(0, 22, 0, 0)
        })
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = 10 }

    private fun actionButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        setOnClickListener { action() }
        layoutParams = matchWrap().apply { topMargin = 6 }
    }

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun requestBluetoothPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 31 && !hasConnectPermission()) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 401)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 401) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) refreshDevices()
            else setStatus("Bluetooth permission denied. Allow Nearby devices permission in Android settings.")
        }
    }

    private fun refreshDevices() {
        if (!hasConnectPermission()) {
            requestBluetoothPermissionIfNeeded()
            setStatus("Allow Nearby devices permission, then refresh.")
            return
        }
        val bt = adapter
        if (bt == null) { setStatus("This Android device does not have Bluetooth."); return }
        if (!bt.isEnabled) { setStatus("Bluetooth is turned off. Turn it on in Android settings."); return }
        devicesBox.removeAllViews()
        val devices = try { bt.bondedDevices?.toList() ?: emptyList() } catch (_: SecurityException) { emptyList() }
        if (devices.isEmpty()) {
            devicesBox.addView(TextView(this).apply {
                text = "No paired devices found. Pair your printer in Android Bluetooth settings first."
                textSize = 13f
                setPadding(10, 12, 10, 12)
            })
        } else devices.forEach { device ->
            devicesBox.addView(Button(this).apply {
                val name = try { device.name ?: "Bluetooth device" } catch (_: SecurityException) { "Bluetooth device" }
                text = "$name · ${device.address}"
                isAllCaps = false
                textSize = 12f
                setOnClickListener {
                    selectedDevice = device
                    selectedLabel.text = "Selected printer: $name"
                    setStatus("Selected $name. Tap Connect.")
                }
            }, matchWrap())
        }
        setStatus("Bluetooth ready · ${devices.size} paired device(s)")
    }

    private fun connectPrinter() {
        val device = selectedDevice
        if (device == null) { setStatus("Select a paired printer first."); return }
        if (!hasConnectPermission()) { requestBluetoothPermissionIfNeeded(); return }
        setStatus("Connecting to printer…")
        thread {
            try {
                try { adapter?.cancelDiscovery() } catch (_: Exception) {}
                val s = device.createRfcommSocketToServiceRecord(sppUuid)
                s.connect()
                socket = s
                output = s.outputStream
                runOnUiThread {
                    setStatus("Connected to printer · Bluetooth SPP")
                    pendingPayload?.let { pendingPayload = null; printPayload(it) }
                }
            } catch (e: Exception) {
                try { socket?.close() } catch (_: Exception) {}
                socket = null
                output = null
                runOnUiThread { setStatus("Connection failed: ${e.message ?: "SPP unavailable"}. Confirm printer protocol and pairing.") }
            }
        }
    }

    private fun disconnectPrinter() {
        thread {
            try { output?.close() } catch (_: Exception) {}
            try { socket?.close() } catch (_: Exception) {}
            output = null
            socket = null
            runOnUiThread { setStatus("Disconnected") }
        }
    }

    private fun printTest() {
        val test = JSONObject().apply {
            put("protocol", "cdp-print-v1")
            put("receipt", JSONObject().apply {
                put("receipt", "CDP-TEST")
                put("date", Date().toString())
                put("total", 1.0); put("cash", 1.0); put("change", 0.0); put("test", true)
                put("items", org.json.JSONArray().put(JSONObject().apply {
                    put("name", "Printer test"); put("quantity", 1); put("lineTotal", 1.0)
                }))
            })
        }
        if (output == null) { setStatus("Connect to the printer before testing."); return }
        printPayload(test)
    }

    private fun handleDeepLink(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri: Uri = intent.data ?: return
        if (uri.scheme != "cdpprint" || uri.host != "print") return
        val raw = uri.getQueryParameter("payload")
        if (raw.isNullOrBlank()) { setStatus("CDP POS opened the service, but no receipt payload was received."); return }
        try {
            val payload = JSONObject(raw)
            if (payload.optString("protocol") != "cdp-print-v1") { setStatus("Unsupported receipt format."); return }
            if (output == null) {
                pendingPayload = payload
                setStatus("Receipt received. Connect to your selected printer to print it.")
            } else printPayload(payload)
        } catch (e: Exception) { setStatus("Could not read receipt: ${e.message}") }
    }

    private fun printPayload(payload: JSONObject) {
        val receipt = payload.optJSONObject("receipt") ?: run { setStatus("Invalid receipt data."); return }
        val bytes = buildEscPos(receipt)
        thread {
            try {
                val stream = output ?: throw IllegalStateException("Printer is not connected")
                stream.write(bytes); stream.flush()
                runOnUiThread { setStatus("Receipt sent to printer successfully.") }
            } catch (e: Exception) {
                runOnUiThread { setStatus("Print failed: ${e.message ?: "unknown error"}") }
            }
        }
    }

    private fun buildEscPos(r: JSONObject): ByteArray {
        val data = ArrayList<Byte>()
        fun raw(vararg b: Int) { b.forEach { data.add(it.toByte()) } }
        fun text(s: String) { s.toByteArray(Charsets.UTF_8).forEach { data.add(it) } }
        fun line(s: String = "") { text(s); raw(10) }
        fun center() = raw(0x1B, 0x61, 1)
        fun left() = raw(0x1B, 0x61, 0)
        fun bold(on: Boolean) = raw(0x1B, 0x45, if (on) 1 else 0)
        fun divider() = line("--------------------------------")
        raw(0x1B, 0x40)
        center(); bold(true); raw(0x1D, 0x21, 0x11); line("CDP")
        raw(0x1D, 0x21, 0x00); bold(false)
        line("Carenderia De Pantaleon"); line("OFFICIAL RECEIPT")
        left(); divider()
        line("Receipt: ${r.optString("receipt", "TEST")}")
        if (r.optBoolean("offline")) line("OFFLINE RECEIPT")
        if (r.optString("orderLabel").isNotBlank()) line(r.optString("orderLabel"))
        else if (r.optInt("tableNo", 0) > 0) line("Table ${r.optInt("tableNo")}")
        line("Date: ${r.optString("date", SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date()))}")
        divider()
        val items = r.optJSONArray("items")
        if (items != null) for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val name = item.optString("name", "Item")
            val qty = item.optInt("quantity", 1)
            val amount = item.optDouble("lineTotal", 0.0)
            line(name.take(30)); line("  $qty x                         ${"%.2f".format(Locale.US, amount)}")
        }
        divider()
        bold(true); line("TOTAL:       PHP ${"%.2f".format(Locale.US, r.optDouble("total", 0.0))}"); bold(false)
        line("Cash:        PHP ${"%.2f".format(Locale.US, r.optDouble("cash", 0.0))}")
        line("Change:      PHP ${"%.2f".format(Locale.US, r.optDouble("change", 0.0))}")
        divider(); center(); line("Thank you!"); line("Please come again.")
        line(); line(); line(); raw(0x1D, 0x56, 0x00)
        return data.toByteArray()
    }

    private fun setStatus(message: String) {
        if (::status.isInitialized) status.text = "Status: $message"
    }

    override fun onDestroy() {
        try { output?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        super.onDestroy()
    }
}