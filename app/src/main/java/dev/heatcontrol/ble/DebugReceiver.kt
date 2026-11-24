package dev.heatcontrol.ble

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.core.content.ContextCompat
import dev.heatcontrol.protocol.Uuids
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Debug builds only: raw reads/writes for protocol experiments over adb, e.g.
 *   adb shell am broadcast -a dev.heatcontrol.DEBUG --es addr 00:A0:50:D0:58:97 --es uuid 1001 --es hex 05000002150000
 * Omit "hex" to read. Results go to logcat tag "HeaterDebug".
 */
object DebugReceiver {
    fun registerIfDebuggable(ctx: Context, repo: HeaterRepository) {
        if (ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val scope = CoroutineScope(Dispatchers.Main)
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val addr = i.getStringExtra("addr") ?: return
                i.getStringExtra("name")?.let { n ->
                    repo.rename(addr, n)
                    Log.d("HeaterDebug", "$addr renamed $n")
                    return
                }
                val boostMin = i.getIntExtra("boostmin", 0)
                if (boostMin > 0) {
                    Log.d("HeaterDebug", "$addr boost $boostMin min")
                    repo.boost(addr, boostMin)
                    return
                }
                val uuid = Uuids.short(i.getStringExtra("uuid")?.toInt(16) ?: return)
                val hex = i.getStringExtra("hex")
                scope.launch {
                    repo.command(addr) { h ->
                        h.run("debug") {
                            if (hex != null) {
                                conn.write(uuid, hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
                                Log.d("HeaterDebug", "$addr wrote $hex")
                            }
                            val v = conn.read(uuid)
                            Log.d("HeaterDebug", "$addr read ${v.joinToString(",") { (it.toInt() and 0xFF).toString() }}")
                        }
                    }
                }
            }
        }
        ContextCompat.registerReceiver(ctx, r, IntentFilter("dev.heatcontrol.DEBUG"), ContextCompat.RECEIVER_EXPORTED)
    }
}
