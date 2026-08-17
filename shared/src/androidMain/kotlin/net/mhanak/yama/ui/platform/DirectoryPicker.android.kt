package net.mhanak.yama.ui.platform

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import net.mhanak.yama.MyApplication

// Android folder picking via SAF (Storage Access Framework): ACTION_OPEN_DOCUMENT_TREE hands back a
// tree Uri granting read access to the chosen folder and everything under it — the scoped-storage
// equivalent of desktop's directory picker. The tree Uri *string* is what we hand back as the
// "folder path" (LocalSource stores it, the scanner walks it). This is why supportsDirectoryPicker is
// now true on Android: the source is folder-based like desktop, not MediaStore-wide.
actual val supportsDirectoryPicker: Boolean = true

@Composable
actual fun rememberDirectoryPicker(onResult: (String?) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) {
            onResult(null)
            return@rememberLauncherForActivityResult
        }
        // Persist the grant so the folder stays readable across process restarts — a plain SAF grant is
        // otherwise scoped to this process. Best-effort: a failure just means the user re-picks later.
        runCatching {
            MyApplication.appContext.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        onResult(uri.toString())
    }
    // null = no preferred initial directory.
    return { launcher.launch(null) }
}

// Decode a SAF tree Uri ("content://…/tree/primary%3AMusic%2FRock") into a readable folder path. The
// tree document id is "<volume>:<relative/path>"; drop the volume prefix to show "Music/Rock". Falls
// back to the raw string / a friendly volume name for edge cases (volume root, unexpected providers).
actual fun folderDisplayName(path: String): String {
    val uri = runCatching { Uri.parse(path) }.getOrNull() ?: return path
    val treeDocId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return path
    val relative = treeDocId.substringAfter(':', "")
    return when {
        relative.isNotBlank() -> relative
        treeDocId.startsWith("primary") -> "Internal storage"
        else -> treeDocId.substringBefore(':').ifBlank { path }
    }
}
