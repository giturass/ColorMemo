package dev.colormemo.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import dev.colormemo.app.data.remoteImageUrl

@Composable
internal fun RemoteImageDialog(loading: Boolean, error: String?, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var address by rememberSaveable { mutableStateOf("") }
    var validationError by rememberSaveable { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("插入远程图片") },
        text = {
            Column {
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it; validationError = null },
                    label = { Text("图片地址") },
                    placeholder = { Text("https://example.com/image.jpg") },
                    singleLine = true,
                    enabled = !loading,
                    isError = validationError != null || error != null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                (validationError ?: error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !loading && address.isNotBlank(), onClick = {
                val url = try { remoteImageUrl(address) } catch (error: IllegalArgumentException) {
                    validationError = error.message
                    null
                }
                if (url != null) onConfirm(url.toString())
            }) { Text(if (loading) "正在插入…" else "插入") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !loading) { Text("取消") } },
    )
}
