package dev.heatcontrol.ble

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.heatcontrol.protocol.ModeType
import dev.heatcontrol.protocol.ScheduleMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** What to put back when a boost ends. */
data class Boost(
    val address: String,
    val until: Long,
    val temp: Int,
    val prevMode: ModeType,
    val prevScheduleMode: ScheduleMode,
    val prevTarget: Int,
    val restoreFailed: Boolean = false,
) {
    fun toJson(): String = JSONObject()
        .put("until", until).put("temp", temp).put("prevMode", prevMode.v)
        .put("prevSched", prevScheduleMode.v).put("prevTarget", prevTarget).put("failed", restoreFailed)
        .toString()

    companion object {
        fun fromJson(address: String, s: String): Boost? = runCatching {
            val o = JSONObject(s)
            Boost(
                address, o.getLong("until"), o.getInt("temp"), ModeType.of(o.getInt("prevMode")),
                ScheduleMode.of(o.getInt("prevSched")), o.getInt("prevTarget"), o.optBoolean("failed"),
            )
        }.getOrNull()
    }
}

/**
 * The heaters can't time a boost themselves (firmware ignores it), so the app does:
 * switch to Manual at a higher temperature now, and put the previous mode back later.
 *
 * The end is an inexact alarm that still fires in Doze (a few minutes late at most), which
 * hands off to a WorkManager job that reconnects and restores, retrying if the heater is out
 * of range. If the phone is away from home when the boost ends, the restore happens the next
 * time the phone is back in range - the heater stays boosted until then.
 */
class BoostManager private constructor(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("boosts", Context.MODE_PRIVATE)
    private val _boosts = MutableStateFlow(load())
    val boosts: StateFlow<Map<String, Boost>> = _boosts

    private fun load(): Map<String, Boost> =
        prefs.all.mapNotNull { (k, v) -> (v as? String)?.let { Boost.fromJson(k, it) }?.let { k to it } }.toMap()

    fun get(address: String) = _boosts.value[address]

    fun save(b: Boost) {
        prefs.edit().putString(b.address, b.toJson()).apply()
        _boosts.value = _boosts.value + (b.address to b)
    }

    /** [cancelWork] = false when called from the restore job itself. */
    fun clear(address: String, cancelWork: Boolean = true) {
        prefs.edit().remove(address).apply()
        _boosts.value = _boosts.value - address
        alarmManager().cancel(pendingIntent(address))
        if (cancelWork) WorkManager.getInstance(ctx).cancelUniqueWork(workName(address))
    }

    /** Records the boost and arms the end-of-boost alarm. */
    fun arm(b: Boost) {
        save(b)
        alarmManager().setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, b.until, pendingIntent(b.address))
        Log.d(TAG, "boost ${b.address} to ${b.temp} until ${b.until}")
    }

    /** Re-arm alarms after a reboot or app update; restore any that are already due. */
    fun rearmAll() {
        val now = System.currentTimeMillis()
        _boosts.value.values.forEach { b -> if (b.until <= now) enqueueRestore(b.address) else arm(b) }
    }

    fun enqueueRestore(address: String) {
        val req = OneTimeWorkRequestBuilder<RestoreWorker>()
            .setInputData(workDataOf("addr" to address))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 5, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(workName(address), ExistingWorkPolicy.REPLACE, req)
    }

    private fun alarmManager() = ctx.getSystemService(AlarmManager::class.java)

    private fun pendingIntent(address: String): PendingIntent = PendingIntent.getBroadcast(
        ctx, address.hashCode(),
        Intent(ctx, BoostEndReceiver::class.java).setAction(ACTION_END).putExtra("addr", address),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun workName(address: String) = "boost-restore-$address"

    companion object {
        private const val TAG = "Boost"
        const val ACTION_END = "dev.heatcontrol.BOOST_END"

        @Volatile private var instance: BoostManager? = null
        fun get(ctx: Context) = instance ?: synchronized(this) {
            instance ?: BoostManager(ctx.applicationContext).also { instance = it }
        }
    }
}

/** Fires when a boost should end (and after reboot) - hands the BLE work to WorkManager. */
class BoostEndReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val boosts = BoostManager.get(ctx)
        when (intent.action) {
            BoostManager.ACTION_END -> intent.getStringExtra("addr")?.let { boosts.enqueueRestore(it) }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> boosts.rearmAll()
        }
    }
}

/** Reconnects to the heater and puts its previous mode back. Retries every 5+ minutes. */
class RestoreWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val addr = inputData.getString("addr") ?: return Result.failure()
        val boosts = BoostManager.get(applicationContext)
        val b = boosts.get(addr) ?: return Result.success() // already stopped by the user
        val repo = HeaterRepository.get(applicationContext)
        val ok = repo.restoreBoost(b)
        return when {
            ok -> Result.success()
            runAttemptCount < MAX_ATTEMPTS -> {
                boosts.save(b.copy(restoreFailed = true))
                Result.retry()
            }
            else -> Result.failure() // keep the record so the app shows it and retries on open
        }
    }

    companion object { private const val MAX_ATTEMPTS = 40 } // ~ several hours of retries
}
