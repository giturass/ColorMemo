package dev.colormemo.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.colormemo.app.reminder.ReminderContract
import dev.colormemo.app.reminder.ReminderPermissions
import dev.colormemo.app.ui.ColorMemoApp
import dev.colormemo.app.ui.MemoCallbacks
import dev.colormemo.app.ui.theme.ColorMemoTheme
import java.time.LocalDate

class MainActivity : ComponentActivity() {
    private val model: MemoViewModel by viewModels()

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        model.refreshPermissions()
        if (!it) model.showMessage("提醒需要通知权限，可随时在设置中开启")
    }
    private val createBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let(model::exportBackup)
    }
    private val createMarkdown = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri?.let(model::exportMarkdown)
    }
    private val openBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(model::importBackup)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) model.openNote(intent.getStringExtra(ReminderContract.EXTRA_NOTE_ID))
        setContent {
            val state = model.state.collectAsStateWithLifecycle()
            ColorMemoTheme {
                ColorMemoApp(
                    state = state.value,
                    callbacks = MemoCallbacks(
                        onSaveNote = model::saveNote,
                        onSetPinned = model::setPinned,
                        onSetArchived = model::setArchived,
                        onTrash = model::trash,
                        onRestore = model::restore,
                        onDeleteForever = model::deleteForever,
                        onSaveSyncSettings = model::saveSyncSettings,
                        onTestSync = model::testSync,
                        onSync = model::syncNow,
                        onRequestNotificationPermission = ::requestNotifications,
                        onRequestExactAlarmPermission = {
                            ReminderPermissions.exactAlarmSettingsIntent(this)?.let(::openSettings)
                        },
                        onMessageShown = model::consumeMessage,
                        onConsumeRequestedNote = model::consumeRequestedNote,
                        onExportNotes = { createBackup.launch("ColorMemo-${LocalDate.now()}.json") },
                        onImportNotes = { openBackup.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                        onExportMarkdown = { createMarkdown.launch("ColorMemo-Markdown-${LocalDate.now()}.zip") },
                    ),
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        model.openNote(intent.getStringExtra(ReminderContract.EXTRA_NOTE_ID))
    }

    override fun onResume() {
        super.onResume()
        model.refreshPermissions()
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
            // The first request uses the system dialog; after denial, settings remains available.
            val preferences = getPreferences(MODE_PRIVATE)
            if (preferences.getBoolean("notification_requested", false)) {
                openSettings(ReminderPermissions.notificationSettingsIntent(this))
                return
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("notification_requested", true).apply()
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            openSettings(ReminderPermissions.notificationSettingsIntent(this))
        }
    }

    private fun openSettings(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            model.showMessage("请在系统设置中打开彩笺的通知或闹钟权限")
        }
    }
}
