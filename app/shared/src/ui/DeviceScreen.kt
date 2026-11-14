package com.salahoard.mustnotify.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.salahoard.mustnotify.data.MonitoredDevice
import com.salahoard.mustnotify.domain.DISCHARGE_MAX_BUDGET_KWH
import com.salahoard.mustnotify.domain.DischargeRule
import com.salahoard.mustnotify.domain.DischargeState
import com.salahoard.mustnotify.domain.ParamId
import com.salahoard.mustnotify.domain.ParamSpec
import com.salahoard.mustnotify.domain.ParamSpecs
import com.salahoard.mustnotify.domain.RangeMode
import com.salahoard.mustnotify.domain.RangeRule
import com.salahoard.mustnotify.domain.budgetForTargetKWh
import com.salahoard.mustnotify.domain.dischargeTargetKWh
import com.salahoard.mustnotify.domain.describeDischargeRule
import com.salahoard.mustnotify.domain.describeRangeRule
import com.salahoard.mustnotify.domain.formatNumber
import com.salahoard.mustnotify.domain.snapToStep
import com.salahoard.mustnotify.domain.usedSinceBaselineKWh
import com.salahoard.mustnotify.poll.DeviceStatus
import com.salahoard.mustnotify.poll.FailureReason
import com.salahoard.mustnotify.poll.LiveReadings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceScreen(state: DeviceUiState, actions: DeviceActions, background: BackgroundAccess?, widgets: WidgetAccess?, onBack: () -> Unit) {
    val device = state.device
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(device?.ref?.name ?: "Inverter") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { padding ->
        if (device == null) {
            Text("This inverter is no longer in your list.", Modifier.padding(padding).padding(24.dp))
            return@Scaffold
        }
        val readings = state.status?.readings
        val uncertain = state.status?.uncertain == true
        val specs = ParamSpecs.forRatedPower(device.ratedPowerW ?: readings?.ratedPowerW)
        fun spec(id: ParamId) = specs.first { it.id == id }

        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Tick a parameter to be notified about it. Set the allowed range with the two handles and choose " +
                    "whether you want a notification when the value leaves that range or when it enters it. " +
                    "The grey note under each slider says exactly what will happen. Changes apply from the next check.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            MonitoringCard(device.ref.sn, device.monitoring, device.showNotification, state.status, background, widgets, actions)

            RangeCard(spec(ParamId.BATTERY_VOLTAGE), device.rules.rangeFor(spec(ParamId.BATTERY_VOLTAGE)), liveValue(readings?.batteryVoltage, spec(ParamId.BATTERY_VOLTAGE), uncertain), actions)
            RangeCard(spec(ParamId.BATTERY_CURRENT), device.rules.rangeFor(spec(ParamId.BATTERY_CURRENT)), liveValue(readings?.batteryCurrentA, spec(ParamId.BATTERY_CURRENT), uncertain), actions)
            DischargeCard(device, readings, uncertain, actions)
            RangeCard(spec(ParamId.PV_POWER), device.rules.rangeFor(spec(ParamId.PV_POWER)), liveValue(readings?.pvPowerW, spec(ParamId.PV_POWER), uncertain), actions)
            RangeCard(spec(ParamId.LOAD_POWER), device.rules.rangeFor(spec(ParamId.LOAD_POWER)), liveValue(readings?.loadPowerW, spec(ParamId.LOAD_POWER), uncertain), actions)
        }
    }
}

private fun liveValue(value: Double?, spec: ParamSpec, uncertain: Boolean): String =
    if (value == null) "Now: –" else "Now: ${formatNumber(value, spec.decimals)} ${spec.unit}${if (uncertain) " ?" else ""}"

@Composable
private fun MonitoringCard(
    sn: String,
    monitoring: Boolean,
    showNotification: Boolean,
    status: DeviceStatus?,
    background: BackgroundAccess?,
    widgets: WidgetAccess?,
    actions: DeviceActions,
) {
    var widgetHint by remember { mutableStateOf<String?>(null) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Monitor this inverter", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Persistent notification, background checks and alerts.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = monitoring, onCheckedChange = actions::setMonitoring)
            }

            // Two ways to keep the values in view. The notification button is filled while the notification is on.
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (showNotification) {
                    FilledTonalButton(onClick = { actions.setShowNotification(false) }, enabled = monitoring, modifier = Modifier.weight(1f)) {
                        Text("Notification widget", textAlign = TextAlign.Center)
                    }
                } else {
                    OutlinedButton(onClick = { actions.setShowNotification(true) }, enabled = monitoring, modifier = Modifier.weight(1f)) {
                        Text("Notification widget", textAlign = TextAlign.Center)
                    }
                }
                if (widgets != null) {
                    OutlinedButton(
                        onClick = {
                            widgetHint = if (widgets.requestPin(sn)) null else
                                "Your launcher can't add it from here. Long-press the home screen, choose Widgets, then " +
                                    "\"Inverter status\" from Must Notify (within 2 minutes, so it shows this inverter)."
                        },
                        enabled = monitoring,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Home-screen widget", textAlign = TextAlign.Center)
                    }
                }
            }
            widgetHint?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            statusLine(status)?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (monitoring && background != null && background.isRestricted()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "While the screen is off and the phone is idle, Android can pause the checks, so values may show a ? " +
                        "for a long time. Choose \"Don't optimize\" (or \"Unrestricted\") for this app for reliable overnight monitoring.",
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = background::openSettings) { Text("Open battery settings") }
            }
        }
    }
}

private fun statusLine(status: DeviceStatus?): String? {
    if (status == null) return null
    val readings = status.readings
    // A local val can be smart-cast; `readings.ageSeconds` can't (it is a property of a class from another module).
    val ageMinutes = readings?.ageSeconds?.let { it / 60 }
    return when {
        status.failure == FailureReason.NETWORK -> "No internet connection: values marked ? are the last known ones."
        status.failure == FailureReason.AUTH_REQUIRED -> "The server asks for a login. Sign in again from the inverter list."
        status.failure != null -> "Couldn't read the inverter right now: values marked ? are the last known ones."
        readings == null -> null
        readings.stale -> "Outdated: no new data for ${ageMinutes ?: 0} minutes. Thresholds are paused."
        ageMinutes != null -> "Last data from the inverter: $ageMinutes min ago (when checked)."
        else -> null
    }
}

@Composable
private fun RangeCard(spec: ParamSpec, rule: RangeRule, liveText: String, actions: DeviceActions) {
    // Local drag state; saved only when the finger lifts so we don't write to disk on every pixel.
    var range by remember(rule.low, rule.high) { mutableStateOf(rule.low.toFloat()..rule.high.toFloat()) }
    val low = snapToStep(range.start.toDouble(), spec.min, spec.step)
    val high = snapToStep(range.endInclusive.toDouble(), spec.min, spec.step)
    val steps = ((spec.max - spec.min) / spec.step).toInt() - 1

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = rule.enabled, onCheckedChange = { actions.setRangeEnabled(spec, it) })
                Column(Modifier.weight(1f)) {
                    Text(spec.label, style = MaterialTheme.typography.titleMedium)
                    Text(liveText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(
                "${formatNumber(low, spec.decimals)} – ${formatNumber(high, spec.decimals)} ${spec.unit}",
                style = MaterialTheme.typography.bodyLarge,
            )
            RangeSlider(
                value = range,
                onValueChange = { range = it },
                valueRange = spec.min.toFloat()..spec.max.toFloat(),
                steps = steps.coerceAtLeast(0),
                enabled = rule.enabled,
                onValueChangeFinished = { actions.setRange(spec, low, high) },
            )
            Row {
                FilterChip(
                    selected = rule.mode == RangeMode.NOTIFY_ON_EXIT,
                    onClick = { actions.setMode(spec, RangeMode.NOTIFY_ON_EXIT) },
                    label = { Text("Leaves range") },
                    enabled = rule.enabled,
                )
                Spacer(Modifier.width(8.dp))
                FilterChip(
                    selected = rule.mode == RangeMode.NOTIFY_ON_ENTER,
                    onClick = { actions.setMode(spec, RangeMode.NOTIFY_ON_ENTER) },
                    label = { Text("Enters range") },
                    enabled = rule.enabled,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                describeRangeRule(spec, rule.copy(low = low, high = high)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DischargeCard(device: MonitoredDevice, readings: LiveReadings?, uncertain: Boolean, actions: DeviceActions) {
    val rule: DischargeRule = device.rules.discharge
    val tracked: DischargeState = device.runtime.discharge
    val counter = readings?.dischargeTotalKWh
    val baseline = tracked.baselineKWh ?: counter

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = rule.enabled, onCheckedChange = actions::setDischargeEnabled)
                Column(Modifier.weight(1f)) {
                    Text("Battery consumption (accumulated)", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (counter == null) "Counter now: –"
                        else "Counter now: ${formatNumber(counter, 1)} kWh${if (uncertain) " ?" else ""}" +
                            "  ·  used since sunset: ${formatNumber(usedSinceBaselineKWh(tracked.copy(baselineKWh = baseline), counter), 2)} kWh",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (baseline == null || counter == null) {
                Text(
                    "Waiting for the first reading…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                // The slider lives on the counter's own scale: it starts at the baseline and sits at baseline + budget.
                val min = baseline.toFloat()
                val max = (baseline + DISCHARGE_MAX_BUDGET_KWH).toFloat()
                val saved = dischargeTargetKWh(tracked.copy(baselineKWh = baseline), rule, counter).toFloat().coerceIn(min, max)
                var target by remember(baseline, rule.budgetKWh) { mutableStateOf(saved) }
                Text("Notify at ${formatNumber(target.toDouble(), 1)} kWh", style = MaterialTheme.typography.bodyLarge)
                Slider(
                    value = target,
                    onValueChange = { target = it },
                    valueRange = min..max,
                    steps = 99,
                    enabled = rule.enabled,
                    onValueChangeFinished = {
                        val budget = budgetForTargetKWh(tracked.copy(baselineKWh = baseline), counter, target.toDouble())
                        actions.setDischargeBudget(snapToStep(budget, 0.0, 0.1))
                    },
                )
                Text(
                    describeDischargeRule(rule, tracked.copy(baselineKWh = baseline), counter),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
