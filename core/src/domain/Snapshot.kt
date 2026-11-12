package com.salahoard.mustnotify.domain

import kotlin.math.abs

/** Reading ids exactly as the vendor API sends them. Always match on id, never on title or position. */
object ReadingId {
    const val BATTERY_VOLTAGE = "battery_voltage"
    const val BMS_BATTERY_VOLTAGE = "bt_bms_battery_voltage"
    const val BATTERY_CURRENT = "battery_current"
    const val BATTERY_DISCHARGE_TOTAL = "battery_energy_total_discharge"
    const val PV_POWER = "pv_output_power"
    const val LOAD_POWER = "load_active_power"
    const val RATED_POWER = "rating_power"
    const val STATUS = "status"
}

/**
 * One poll of the inverter.
 *
 * @param raw reading id -> raw value string (the API sends every value as a string, some non-numeric).
 * @param deviceTime the `date` field, "yyyy-MM-dd HH:mm:ss", expressed in the DEVICE's timezone.
 */
class InverterSnapshot(
    private val raw: Map<String, String>,
    val deviceTime: String? = null,
) {
    fun number(id: String): Double? = raw[id]?.trim()?.toDoubleOrNull()
    fun text(id: String): String? = raw[id]

    val ratedPowerW: Double? get() = number(ReadingId.RATED_POWER)
    /** Magnitude in amps. The API reports this value as negative, but the app shows it without the sign. */
    val batteryCurrentA: Double? get() = number(ReadingId.BATTERY_CURRENT)?.let { abs(it) }
    val dischargeTotalKWh: Double? get() = number(ReadingId.BATTERY_DISCHARGE_TOTAL)
    val pvPowerW: Double? get() = number(ReadingId.PV_POWER)
    val loadPowerW: Double? get() = number(ReadingId.LOAD_POWER)
    val workState: String? get() = text(ReadingId.STATUS)

    /** BMS voltage when a BMS is present (non-zero), otherwise the inverter's own battery voltage. */
    val batteryVoltage: Double?
        get() {
            val bms = number(ReadingId.BMS_BATTERY_VOLTAGE)
            return if (bms != null && bms != 0.0) bms else number(ReadingId.BATTERY_VOLTAGE)
        }

    /** Epoch seconds (UTC) of the device's timestamp, or null if missing/unparseable. */
    fun epochSeconds(deviceUtcOffsetSeconds: Int): Long? =
        deviceTime?.let { parseDeviceTimeToEpochSeconds(it, deviceUtcOffsetSeconds) }
}

private val deviceTimeRegex = Regex("""(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2}):(\d{2})""")

/** "2026-11-11 08:20:29" in a timezone [utcOffsetSeconds] ahead of UTC -> epoch seconds. No platform APIs needed. */
fun parseDeviceTimeToEpochSeconds(text: String, utcOffsetSeconds: Int): Long? {
    val m = deviceTimeRegex.matchEntire(text.trim()) ?: return null
    val (y, mo, d, h, mi, s) = m.destructured
    val month = mo.toInt()
    val day = d.toInt()
    if (month !in 1..12 || day !in 1..31) return null
    val days = daysFromCivil(y.toInt(), month, day)
    return days * 86_400L + h.toInt() * 3_600L + mi.toInt() * 60L + s.toInt() - utcOffsetSeconds
}

/** Days since 1970-01-01 for a proleptic Gregorian date (Howard Hinnant's algorithm). */
private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
    val y = if (month <= 2) year - 1 else year
    val era = (if (y >= 0) y else y - 399) / 400
    val yoe = y - era * 400
    val doy = (153 * (month + (if (month > 2) -3 else 9)) + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era.toLong() * 146_097L + doe - 719_468L
}

/** True when the newest reading is older than [maxAgeSeconds]; show this instead of evaluating thresholds on old data. */
fun isStale(snapshotEpochSeconds: Long, nowEpochSeconds: Long, maxAgeSeconds: Long = 15 * 60L): Boolean =
    nowEpochSeconds - snapshotEpochSeconds > maxAgeSeconds
