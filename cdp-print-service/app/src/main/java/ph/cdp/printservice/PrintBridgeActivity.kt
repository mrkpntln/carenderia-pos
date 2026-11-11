package ph.cdp.printservice

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import org.json.JSONObject

/** Transparent URL bridge: receives cdpprint:// from Chrome, starts the printer foreground service, then closes. */
class PrintBridgeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val uri = intent?.data
            val raw = uri?.getQueryParameter("payload")
            if (intent?.action == Intent.ACTION_VIEW && uri?.scheme == "cdpprint" && uri.host == "print" && !raw.isNullOrBlank()) {
                val payload = JSONObject(raw)
                if (payload.optString("protocol") == "cdp-print-v1") {
                    val serviceIntent = Intent(this, PrintService::class.java).apply {
                        action = PrintService.ACTION_PRINT
                        putExtra(PrintService.EXTRA_PAYLOAD, raw)
                    }
                    if (Build.VERSION.SDK_INT >= 26) startForegroundService(serviceIntent) else startService(serviceIntent)
                }
            }
        } catch (_: Exception) { }
        finish()
        overridePendingTransition(0, 0)
    }
}