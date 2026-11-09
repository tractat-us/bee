package us.tractat.bee.spike

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.webkit.WebView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import us.tractat.bee.host.BeeHost
import us.tractat.bee.host.HostConfig
import us.tractat.bee.host.Reach

/**
 * SPIKE, throwaway. Starts bee-host in-process on 8233 and shows its board: the WebView is the
 * whole screen. No service, no notification: the host lives exactly as long as the process.
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WebView.setWebContentsDebuggingEnabled(true)
        val web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        setContentView(web)
        if (host != null) {
            web.loadUrl("http://127.0.0.1:${host!!.port}/")
            return
        }
        Thread {
            val started = System.nanoTime()
            try {
                // Lan: the phone's own WebView is still on loopback; Wi-Fi peers pair or use the join link.
                val config = HostConfig(dataDir = filesDir.toPath().resolve("bee-host"), reach = Reach.Lan)
                val running = runBlocking { BeeHost.start(scope, config) }
                host = running
                Log.i(TAG, "host on ${running.port} after ${(System.nanoTime() - started) / 1_000_000} ms")
                runOnUiThread {
                    startForegroundService(Intent(this, KeepHosting::class.java))
                    web.loadUrl("http://127.0.0.1:${running.port}/")
                }
            } catch (failure: Throwable) {
                Log.e(TAG, "host failed to start", failure)
                val trace = Log.getStackTraceString(failure).replace("<", "&lt;")
                runOnUiThread { web.loadData("<pre style='font-size:9px'>$trace</pre>", "text/html", null) }
            }
        }.start()
    }

    companion object {
        private const val TAG = "bee-spike"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        @Volatile private var host: BeeHost? = null
    }
}
