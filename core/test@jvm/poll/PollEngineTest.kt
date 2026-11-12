package com.salahoard.mustnotify.poll

import com.salahoard.mustnotify.api.ApiResult
import com.salahoard.mustnotify.api.DeviceRef
import com.salahoard.mustnotify.api.ReadingsSource
import com.salahoard.mustnotify.data.AppData
import com.salahoard.mustnotify.data.InMemorySecretStore
import com.salahoard.mustnotify.data.InMemorySettingsStore
import com.salahoard.mustnotify.data.MonitoredDevice
import com.salahoard.mustnotify.data.RuleSet
import com.salahoard.mustnotify.domain.DischargeRule
import com.salahoard.mustnotify.domain.InverterSnapshot
import com.salahoard.mustnotify.domain.ParamId
import com.salahoard.mustnotify.domain.RangeMode
import com.salahoard.mustnotify.domain.RangeRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class FakeSource(var next: ApiResult<InverterSnapshot>) : ReadingsSource {
    val tokensSeen = mutableListOf<String?>()
    var calls = 0
    override suspend fun readDevice(device: DeviceRef, token: String?): ApiResult<InverterSnapshot> {
        calls++
        tokensSeen += token
        return next
    }
}

internal val testDevice = DeviceRef("Test inverter", "PH1800", "PN", "SN", 1, 4, null, utcOffsetSeconds = 10_800)
internal const val deviceTime = "2026-11-11 08:20:29"   // = 1794374429 UTC with a +3h device clock
internal const val freshEpoch = 1_794_374_429L

internal fun snap(voltage: Double = 12.9, pv: Double = 303.0, counter: Double = 7.0, time: String? = deviceTime) =
    InverterSnapshot(
        mapOf(
            "battery_voltage" to "$voltage", "battery_current" to "-15",
            "battery_energy_total_discharge" to "$counter", "pv_output_power" to "$pv",
            "load_active_power" to "17", "rating_power" to "2000", "status" to "OffGrid",
        ),
        time,
    )

internal fun appWith(rules: RuleSet = RuleSet(), monitoring: Boolean = true, ref: DeviceRef = testDevice) =
    AppData(devices = listOf(MonitoredDevice(ref, monitoring = monitoring, rules = rules)))

class PollEngineTest {
    private fun engine(source: ReadingsSource, store: InMemorySettingsStore, secrets: InMemorySecretStore = InMemorySecretStore(), now: Long = freshEpoch + 60) =
        PollEngine(source, store, secrets, { now })

    private val voltageExit = RangeRule(true, 11.5, 14.4, RangeMode.NOTIFY_ON_EXIT)
    private val voltageRules = RuleSet(ranges = mapOf(ParamId.BATTERY_VOLTAGE to voltageExit))

    @Test fun unknownInverterMeansNothingToDo() = runBlocking<Unit> {
        val source = FakeSource(ApiResult.Ok(snap()))
        assertEquals(PollOutcome.NoDevice, engine(source, InMemorySettingsStore(appWith())).pollOnce("OTHER"))
        assertEquals(0, source.calls)
    }

    @Test fun successReturnsReadingsAndSendsNoCredentials() = runBlocking<Unit> {
        val source = FakeSource(ApiResult.Ok(snap()))
        val ok = engine(source, InMemorySettingsStore(appWith())).pollOnce("SN") as PollOutcome.Success
        assertEquals(12.9, ok.readings.batteryVoltage)
        assertEquals(15.0, ok.readings.batteryCurrentA)
        assertEquals(false, ok.readings.stale)
        assertEquals(60L, ok.readings.ageSeconds)
        assertEquals(listOf<String?>(null), source.tokensSeen)
    }

    @Test fun alertsOnTransitionAcrossTwoPollsAndPersistsState() = runBlocking<Unit> {
        val store = InMemorySettingsStore(appWith(voltageRules))
        val source = FakeSource(ApiResult.Ok(snap(voltage = 12.5)))
        val engine = engine(source, store)
        assertTrue(engine.pollOnce("SN").alerts.isEmpty())                   // first sample: learned silently
        source.next = ApiResult.Ok(snap(voltage = 11.2))
        val alerts = engine.pollOnce("SN").alerts
        assertEquals(1, alerts.size)
        assertEquals("Battery voltage out of range", alerts[0].title)
        assertTrue(engine.pollOnce("SN").alerts.isEmpty())                   // still out: no repeat
        assertNotNull(store.data.first().device("SN")!!.runtime.ranges[ParamId.BATTERY_VOLTAGE])
    }

    @Test fun staleDataPausesRulesAndAlertsOnce() = runBlocking<Unit> {
        val store = InMemorySettingsStore(appWith(voltageRules))
        val source = FakeSource(ApiResult.Ok(snap(voltage = 9.0)))
        val engine = engine(source, store, now = freshEpoch + 40 * 60)
        val first = engine.pollOnce("SN") as PollOutcome.Success
        assertEquals(true, first.readings.stale)
        assertEquals(listOf("Inverter data is outdated"), first.alerts.map { it.title })
        assertTrue(engine.pollOnce("SN").alerts.isEmpty())                   // not repeated
        assertNull(store.data.first().device("SN")!!.runtime.ranges[ParamId.BATTERY_VOLTAGE])
    }

    @Test fun noInternetNeverAlertsEvenWithRulesOn() = runBlocking<Unit> {
        val store = InMemorySettingsStore(appWith(voltageRules))
        val outcome = engine(FakeSource(ApiResult.NetworkError(RuntimeException("offline"))), store).pollOnce("SN")
        assertEquals(PollOutcome.Failed(FailureReason.NETWORK), outcome)
        assertTrue(outcome.alerts.isEmpty())
    }

    @Test fun retriesWithTokenOnlyWhenAuthIsRequired() = runBlocking<Unit> {
        val source = object : ReadingsSource {
            val tokens = mutableListOf<String?>()
            override suspend fun readDevice(device: DeviceRef, token: String?): ApiResult<InverterSnapshot> {
                tokens += token
                return if (token == null) ApiResult.AuthRequired else ApiResult.Ok(snap())
            }
        }
        val outcome = engine(source, InMemorySettingsStore(appWith()), InMemorySecretStore("tok")).pollOnce("SN")
        assertTrue(outcome is PollOutcome.Success)
        assertEquals(listOf<String?>(null, "tok"), source.tokens)
    }

    @Test fun authRequiredWithoutTokenFails() = runBlocking<Unit> {
        val outcome = engine(FakeSource(ApiResult.AuthRequired), InMemorySettingsStore(appWith())).pollOnce("SN")
        assertEquals(PollOutcome.Failed(FailureReason.AUTH_REQUIRED), outcome)
    }

    @Test fun failuresAreMapped() = runBlocking<Unit> {
        val store = InMemorySettingsStore(appWith())
        assertEquals(PollOutcome.Failed(FailureReason.SERVER), engine(FakeSource(ApiResult.ServerError(500, null)), store).pollOnce("SN"))
        assertEquals(PollOutcome.Failed(FailureReason.BAD_RESPONSE), engine(FakeSource(ApiResult.BadResponse("x")), store).pollOnce("SN"))
    }

    @Test fun dischargeBudgetAlertFlowsThroughTheEngine() = runBlocking<Unit> {
        val store = InMemorySettingsStore(appWith(RuleSet(discharge = DischargeRule(true, 0.5))))
        val source = FakeSource(ApiResult.Ok(snap(pv = 0.0, counter = 7.0)))
        val engine = engine(source, store)
        assertTrue(engine.pollOnce("SN").alerts.isEmpty())
        source.next = ApiResult.Ok(snap(pv = 0.0, counter = 7.6))
        assertEquals(1, engine.pollOnce("SN").alerts.size)
    }

    @Test fun pollRemembersRatedPower() = runBlocking<Unit> {
        val store = InMemorySettingsStore(appWith())
        engine(FakeSource(ApiResult.Ok(snap())), store).pollOnce("SN")
        assertEquals(2000.0, store.data.first().device("SN")!!.ratedPowerW)
    }

    @Test fun peekWorksForUnmonitoredInverterWithoutTouchingState() = runBlocking<Unit> {
        val store = InMemorySettingsStore(appWith(voltageRules, monitoring = false))
        val status = engine(FakeSource(ApiResult.Ok(snap(voltage = 9.0))), store).peek("SN")!!
        assertEquals(false, status.uncertain)
        assertEquals(9.0, status.readings!!.batteryVoltage)
        val saved = store.data.first().device("SN")!!
        assertEquals(2000.0, saved.ratedPowerW)
        assertTrue(saved.runtime.ranges.isEmpty())
    }

    @Test fun peekReportsUncertainWhenOffline() = runBlocking<Unit> {
        val status = engine(FakeSource(ApiResult.NetworkError(RuntimeException())), InMemorySettingsStore(appWith())).peek("SN")!!
        assertEquals(true, status.uncertain)
        assertNull(status.readings)
        assertEquals(FailureReason.NETWORK, status.failure)
    }

    @Test fun inverterStatesAreIndependent() = runBlocking<Unit> {
        val second = testDevice.copy(name = "Second", sn = "SN2")
        val store = InMemorySettingsStore(
            AppData(devices = listOf(MonitoredDevice(testDevice, true, rules = voltageRules), MonitoredDevice(second, true, rules = voltageRules))),
        )
        val engine = engine(FakeSource(ApiResult.Ok(snap(voltage = 12.5))), store)
        engine.pollOnce("SN")
        val data = store.data.first()
        assertNotNull(data.device("SN")!!.runtime.ranges[ParamId.BATTERY_VOLTAGE])
        assertTrue(data.device("SN2")!!.runtime.ranges.isEmpty())
    }
}
