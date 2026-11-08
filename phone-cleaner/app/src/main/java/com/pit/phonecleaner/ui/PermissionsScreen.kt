package com.pit.phonecleaner.ui

import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.pit.phonecleaner.core.Perms
import com.pit.phonecleaner.core.SafAccess

data class PermState(val usage: Boolean, val files: Boolean, val access: Boolean, val saf: Boolean) {
    val essentialOk get() = usage && files
}

fun permState(ctx: Context) = PermState(
    usage = Perms.hasUsageAccess(ctx),
    files = Perms.hasAllFiles(ctx),
    access = Perms.isAccessibilityEnabled(ctx),
    saf = SafAccess.hasDataAccess(ctx),
)

/** Счётчик, который увеличивается при каждом возвращении на экран (после выдачи разрешений в настройках) */
@Composable
fun rememberResumeTick(): Int {
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    return tick
}

@Composable
fun PermissionsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val tick = rememberResumeTick()
    var extra by remember { mutableIntStateOf(0) }
    val st = remember(tick, extra) { permState(ctx) }

    val legacy = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { extra++ }
    val saf = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.data?.let { SafAccess.takePermission(ctx, it) }
        extra++
    }

    ScreenScaffold("Разрешения", onBack) { pad ->
        Column(
            Modifier
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "ЧистоФон работает только на вашем телефоне и ничего никуда не отправляет. " +
                    "Каждое разрешение открывает свою часть функций.",
                style = MaterialTheme.typography.bodyMedium
            )
            PermCard(
                "Доступ к истории использования",
                "Нужен, чтобы узнать точный размер кэша каждого приложения (системных и пользовательских).",
                st.usage
            ) { Perms.launch(ctx, Perms.usageAccessIntent(ctx), android.content.Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)) }

            PermCard(
                "Доступ ко всем файлам",
                "Поиск мусора, больших файлов, посторонних папок и дубликатов, а также их удаление.",
                st.files
            ) {
                if (Build.VERSION.SDK_INT >= 30) Perms.launch(
                    ctx, Perms.allFilesIntent(ctx),
                    android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                )
                else legacy.launch(Perms.legacyStoragePermissions)
            }

            PermCard(
                "Спец. возможности (автоочистка)",
                "Позволяет ЧистоФону самому нажимать «Очистить кэш» в настройках каждого приложения — " +
                    "так очищается скрытый кэш без root. В списке служб выберите «ЧистоФон — автоочистка кэша».",
                st.access
            ) { Perms.launch(ctx, Perms.accessibilityIntent()) }

            if (SafAccess.supported) PermCard(
                "Папка Android/data (Android 11–12)",
                "Даёт очищать видимый кэш мгновенно, без автонажатий. В открывшемся окне нажмите «Использовать эту папку».",
                st.saf
            ) { saf.launch(SafAccess.requestIntent()) }
        }
    }
}

@Composable
private fun PermCard(title: String, text: String, granted: Boolean, onGrant: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (granted) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline, null,
                    tint = if (granted) MaterialTheme.colorScheme.primary else Warning
                )
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Text(text, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.padding(top = 10.dp)) {
                if (granted) OutlinedButton(onClick = onGrant) { Text("Выдано · открыть") }
                else Button(onClick = onGrant) { Text("Разрешить") }
            }
        }
    }
}
