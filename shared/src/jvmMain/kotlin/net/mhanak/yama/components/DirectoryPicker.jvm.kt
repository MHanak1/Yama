package net.mhanak.yama.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.swing.JFileChooser
import javax.swing.UIManager

actual val supportsDirectoryPicker: Boolean = true

@Composable
actual fun rememberDirectoryPicker(onResult: (String?) -> Unit): () -> Unit {
    val scope = rememberCoroutineScope()
    val callback by rememberUpdatedState(onResult)
    return {
        // Swing's JFileChooser is modal/blocking, so run it off the compose thread. The callback only
        // mutates a thread-safe StateFlow (LocalSource.addFolder), so it's fine to invoke off-thread.
        scope.launch(Dispatchers.IO) {
            val path = runCatching {
                runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
                val chooser = JFileChooser().apply {
                    fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                    dialogTitle = "Select music folder"
                }
                if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                    chooser.selectedFile?.absolutePath
                } else null
            }.getOrNull()
            callback(path)
        }
    }
}
