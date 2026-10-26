@file:OptIn(ExperimentalMaterial3Api::class)

package dev.anom.pixelbar

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        setContent {
            val dark = isSystemInDarkTheme()
            val ctx = LocalContext.current
            MaterialTheme(colorScheme = if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)) {
                Screen()
            }
        }
    }
}

private fun a11yOn(ctx: Context): Boolean {
    val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
    val cn = ComponentName(ctx, StatusBarService::class.java)
    return enabled.split(':').any {
        it.equals(cn.flattenToString(), true) || it.equals(cn.flattenToShortString(), true)
    }
}

private fun phoneOk(ctx: Context) =
    ctx.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

@Composable
fun Screen() {
    val ctx = LocalContext.current
    val cfg by Prefs.config.collectAsState()
    val monitor = remember { StatusMonitor(ctx) }
    DisposableEffect(Unit) { monitor.start(); onDispose { monitor.stop() } }
    val status by monitor.state.collectAsState()

    var a11y by remember { mutableStateOf(a11yOn(ctx)) }
    var phone by remember { mutableStateOf(phoneOk(ctx)) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val o = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                a11y = a11yOn(ctx); phone = phoneOk(ctx); monitor.refresh()
            }
        }
        lifecycle.addObserver(o)
        onDispose { lifecycle.removeObserver(o) }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        phone = it; monitor.refresh()
    }

    Scaffold { inner ->
        Column(
            Modifier.fillMaxSize().padding(inner).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Pixel Bar", style = MaterialTheme.typography.headlineMedium)
            Text("Live preview", style = MaterialTheme.typography.titleSmall)
            val hDp = with(LocalDensity.current) { ctx.statusBarHeightPx().toDp() }
            AndroidView(
                factory = { StatusBarView(it) },
                update = { it.update(cfg, status) },
                modifier = Modifier.fillMaxWidth().height(hDp).clip(RoundedCornerShape(8.dp))
            )

            if (!a11y) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Accessibility service is OFF. Android only lets a normal app draw above the system status bar through an accessibility service. This app does not read your screen, taps or notifications; it only draws the bar. Find \"Pixel Bar\" under Installed apps and turn it on. If the toggle is greyed out: App info > three dots > Allow restricted settings.")
                        Button(onClick = { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) {
                            Text("Open accessibility settings")
                        }
                    }
                }
            }
            if (!phone) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Phone state permission is needed to read cellular signal strength. Without it the signal icon stays empty. Nothing is stored or sent anywhere.")
                        Button(onClick = { launcher.launch(Manifest.permission.READ_PHONE_STATE) }) {
                            Text("Grant phone state")
                        }
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Enable Pixel status bar", Modifier.weight(1f))
                Switch(checked = cfg.enabled, onCheckedChange = { on -> Prefs.update { it.copy(enabled = on) } })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Show battery percentage", Modifier.weight(1f))
                Switch(checked = cfg.showPercent, onCheckedChange = { on -> Prefs.update { it.copy(showPercent = on) } })
            }
            ChipRow("Colors", listOf("Auto", "Light", "Dark"), cfg.theme) { i -> Prefs.update { it.copy(theme = i) } }
            ChipRow("Clock", listOf("System", "12h", "24h"), cfg.clockFormat) { i -> Prefs.update { it.copy(clockFormat = i) } }
            SliderRow("Icon size", cfg.iconScale, 0.8f..1.3f) { v -> Prefs.update { it.copy(iconScale = v) } }
            SliderRow("Icon spacing", cfg.spacing, 4f..14f) { v -> Prefs.update { it.copy(spacing = v) } }
            SliderRow("Side padding", cfg.sidePadding, 8f..28f) { v -> Prefs.update { it.copy(sidePadding = v) } }

            Spacer(Modifier.height(8.dp))
            Text(
                "Recovery: turn the switch off, or disable Pixel Bar in Accessibility settings, or uninstall. Any of those instantly brings back the stock bar.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
fun ChipRow(title: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEachIndexed { i, o ->
                FilterChip(selected = i == selected, onClick = { onSelect(i) }, label = { Text(o) })
            }
        }
    }
}

@Composable
fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column {
        Text("$label: ${"%.1f".format(value)}")
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}
