package com.pit.phonecleaner.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pit.phonecleaner.Screen
import com.pit.phonecleaner.core.Prefs
import com.pit.phonecleaner.core.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(onBack: () -> Unit, go: (Screen) -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs.get(ctx) }
    val scope = rememberCoroutineScope()
    var useRoot by remember { mutableStateOf(prefs.useRoot) }
    var scanHidden by remember { mutableStateOf(prefs.scanHidden) }
    var dupMin by remember { mutableFloatStateOf(prefs.dupMinKb.toFloat()) }
    var similar by remember { mutableFloatStateOf(prefs.similarThreshold.toFloat()) }
    var oldDays by remember { mutableFloatStateOf(prefs.oldDownloadsDays.toFloat()) }
    var msg by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }

    ScreenScaffold("Настройки", onBack) { pad ->
        Column(
            Modifier
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            SwitchRow(
                "Режим root",
                "Мгновенная очистка скрытого и видимого кэша любых приложений, доступ к Android/data на Android 11+. Только для телефонов с root (Magisk и т.п.).",
                useRoot
            ) { v ->
                if (!v) {
                    useRoot = false; prefs.useRoot = false
                } else {
                    checking = true
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { RootShell.isAvailable(force = true) }
                        checking = false
                        useRoot = ok
                        prefs.useRoot = ok
                        msg = if (ok) "Root-доступ получен" else "Root недоступен или доступ не предоставлен"
                    }
                }
            }
            HorizontalDivider()
            SwitchRow("Скрытые файлы и папки", "Учитывать файлы, начинающиеся с точки (.thumbnails, .cache и т.п.)", scanHidden) {
                scanHidden = it; prefs.scanHidden = it
            }
            HorizontalDivider()
            SliderRow("Мин. размер файла для поиска дубликатов: ${dupMin.toInt()} КБ", dupMin, 1f..1024f) {
                dupMin = it; prefs.dupMinKb = it.toInt()
            }
            SliderRow(
                "Чувствительность «Похожих фото»: ${similar.toInt()} " +
                    when {
                        similar <= 3 -> "(только почти одинаковые)"
                        similar <= 8 -> "(обычно)"
                        else -> "(много совпадений, проверяйте)"
                    },
                similar, 0f..14f
            ) { similar = it; prefs.similarThreshold = it.toInt() }
            SliderRow("Загрузки считаются старыми через: ${oldDays.toInt()} дн.", oldDays, 7f..365f) {
                oldDays = it; prefs.oldDownloadsDays = it.toInt()
            }
            HorizontalDivider()
            TextButton(onClick = { go(Screen.Permissions) }) { Text("Разрешения") }
            Text(
                "ЧистоФон 1.0 · работает полностью офлайн, без рекламы и без сбора данных.",
                Modifier.padding(top = 16.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    BusyOverlay(if (checking) "Проверяю root…" else null)
    MessageDialog(msg) { msg = null }
}

@Composable
private fun SwitchRow(title: String, text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SliderRow(title: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column(Modifier.padding(vertical = 10.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}
