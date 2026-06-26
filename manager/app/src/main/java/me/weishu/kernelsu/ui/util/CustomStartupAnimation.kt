package me.weishu.kernelsu.ui.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import java.util.Locale

const val CUSTOM_STARTUP_ANIMATION_URI_KEY = "custom_startup_animation_uri"
const val CUSTOM_STARTUP_ANIMATION_GIF_MIME_TYPE = "image/gif"

val CUSTOM_STARTUP_ANIMATION_MIME_TYPES = arrayOf(
    "image/*",
    CUSTOM_STARTUP_ANIMATION_GIF_MIME_TYPE,
    "video/*",
)

fun takePersistableStartupAnimationReadPermission(context: Context, uri: Uri) {
    runCatching {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
    }
}

fun releasePersistableStartupAnimationReadPermission(context: Context, uriString: String?) {
    if (uriString.isNullOrBlank()) return
    runCatching {
        context.contentResolver.releasePersistableUriPermission(
            Uri.parse(uriString),
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
    }
}

fun isCustomStartupAnimationVideo(context: Context, uri: Uri): Boolean {
    val mimeType = runCatching { context.contentResolver.getType(uri) }.getOrNull()
    if (mimeType?.startsWith("video/") == true) return true

    return hasVideoExtension(uri.toString()) ||
        hasVideoExtension(queryDisplayName(context, uri))
}

fun isCustomStartupAnimationGif(context: Context, uri: Uri): Boolean {
    val mimeType = runCatching { context.contentResolver.getType(uri) }.getOrNull()
    if (mimeType.equals(CUSTOM_STARTUP_ANIMATION_GIF_MIME_TYPE, ignoreCase = true)) return true

    return hasGifExtension(uri.toString()) ||
        hasGifExtension(queryDisplayName(context, uri))
}

private fun queryDisplayName(context: Context, uri: Uri): String? {
    return runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex < 0) null else cursor.getString(nameIndex)
        }
    }.getOrNull()
}

private fun hasVideoExtension(value: String?): Boolean {
    val text = value?.lowercase(Locale.ROOT) ?: return false
    return text.endsWith(".mp4") ||
        text.endsWith(".webm") ||
        text.endsWith(".mkv") ||
        text.endsWith(".3gp") ||
        text.endsWith(".mov") ||
        text.endsWith(".video")
}

private fun hasGifExtension(value: String?): Boolean {
    val text = value?.lowercase(Locale.ROOT) ?: return false
    return text.endsWith(".gif")
}
