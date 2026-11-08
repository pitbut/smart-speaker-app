package com.pit.phonecleaner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pit.phonecleaner.apps.AppsViewModel
import com.pit.phonecleaner.apps.CacheKind
import com.pit.phonecleaner.files.FilesViewModel
import com.pit.phonecleaner.ui.AppsScreen
import com.pit.phonecleaner.ui.BrowseScreen
import com.pit.phonecleaner.ui.CleanerTheme
import com.pit.phonecleaner.ui.DuplicatesScreen
import com.pit.phonecleaner.ui.ForeignScreen
import com.pit.phonecleaner.ui.HomeScreen
import com.pit.phonecleaner.ui.JunkScreen
import com.pit.phonecleaner.ui.LargeFilesScreen
import com.pit.phonecleaner.ui.PermissionsScreen
import com.pit.phonecleaner.ui.SettingsScreen

sealed interface Screen {
    data object Home : Screen
    data object Permissions : Screen
    data class Apps(val kind: CacheKind = CacheKind.ALL) : Screen
    data object Junk : Screen
    data object Large : Screen
    data object Foreign : Screen
    data class Browse(val path: String) : Screen
    data object Duplicates : Screen
    data object Settings : Screen
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CleanerTheme {
                Surface {
                    val appsVm: AppsViewModel = viewModel()
                    val filesVm: FilesViewModel = viewModel()
                    var stack by remember { mutableStateOf(listOf<Screen>(Screen.Home)) }
                    val go: (Screen) -> Unit = { stack = stack + it }
                    val back: () -> Unit = { if (stack.size > 1) stack = stack.dropLast(1) }
                    BackHandler(enabled = stack.size > 1, onBack = back)

                    when (val s = stack.last()) {
                        Screen.Home -> HomeScreen(appsVm, filesVm, go)
                        Screen.Permissions -> PermissionsScreen(back)
                        is Screen.Apps -> AppsScreen(appsVm, s.kind, back, go)
                        Screen.Junk -> JunkScreen(filesVm, back)
                        Screen.Large -> LargeFilesScreen(filesVm, back)
                        Screen.Foreign -> ForeignScreen(filesVm, back, go)
                        is Screen.Browse -> BrowseScreen(filesVm, s.path, back, go)
                        Screen.Duplicates -> DuplicatesScreen(filesVm, back)
                        Screen.Settings -> SettingsScreen(back, go)
                    }
                }
            }
        }
    }
}
