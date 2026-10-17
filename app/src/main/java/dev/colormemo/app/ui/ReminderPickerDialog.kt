package dev.colormemo.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReminderPickerDialog(
    initialTime: Long?,
    notificationPermissionGranted: Boolean,
    exactAlarmAllowed: Boolean,
    onRequestNotifications: () -> Unit,
    onRequestExactAlarm: () -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    val initial = remember { Instant.ofEpochMilli(initialTime ?: (System.currentTimeMillis() + 3_600_000)).atZone(ZoneId.systemDefault()) }
    var day by rememberSaveable { mutableLongStateOf(initial.toLocalDate().toEpochDay()) }
    var hour by rememberSaveable { mutableIntStateOf(initial.hour) }
    var minute by rememberSaveable { mutableIntStateOf(initial.minute) }
    var datePicker by rememberSaveable { mutableStateOf(false) }
    var timePicker by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    val selectedDate = LocalDate.ofEpochDay(day)

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.NotificationsNone, null) },
        title = { Text("提醒我") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("给这条便笺选一个时间。", style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = {
                        val later = ZonedDateTime.now().plusHours(2)
                        day = later.toLocalDate().toEpochDay(); hour = later.hour; minute = later.minute; error = false
                    }, label = { Text("2 小时后") })
                    AssistChip(onClick = { day = LocalDate.now().plusDays(1).toEpochDay(); hour = 9; minute = 0; error = false }, label = { Text("明早 9 点") })
                }
                OutlinedButton(onClick = { datePicker = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.CalendarToday, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(selectedDate.format(DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE", Locale.SIMPLIFIED_CHINESE)))
                }
                OutlinedButton(onClick = { timePicker = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.AccessTime, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(String.format(Locale.ROOT, "%02d:%02d", hour, minute))
                }
                if (error) Text("请选择一个未来的时间。", color = MaterialTheme.colorScheme.error)
                if (!notificationPermissionGranted) {
                    Text("通知权限尚未开启，届时无法显示提醒。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onRequestNotifications) { Text("开启通知权限") }
                }
                if (!exactAlarmAllowed) {
                    Text("开启准时提醒后可按设定时间通知；未开启时，系统可能延后提醒。", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onRequestExactAlarm) { Text("开启准时提醒") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val timestamp = selectedDate.atTime(hour, minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                if (timestamp <= System.currentTimeMillis()) error = true else onConfirm(timestamp)
            }) { Text(if (initialTime == null) "添加提醒" else "更新提醒") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )

    if (datePicker) {
        // Material date pickers express calendar dates at UTC midnight, not local midnight.
        // Imported reminders can be outside Material's default 1900..2100 range.
        val pickerDate = selectedDate.coerceIn(LocalDate.of(1900, 1, 1), LocalDate.of(2100, 12, 31))
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = pickerDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { datePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let {
                        day = Instant.ofEpochMilli(it).atOffset(ZoneOffset.UTC).toLocalDate().toEpochDay()
                        error = false
                    }
                    datePicker = false
                }, enabled = pickerState.selectedDateMillis != null) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { datePicker = false }) { Text("取消") } },
        ) {
            DatePicker(state = pickerState)
        }
    }
    if (timePicker) {
        val pickerState = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { timePicker = false },
            title = { Text("选择时间") },
            text = { TimeInput(state = pickerState) },
            confirmButton = {
                TextButton(onClick = { hour = pickerState.hour; minute = pickerState.minute; error = false; timePicker = false }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { timePicker = false }) { Text("取消") } },
        )
    }
}
