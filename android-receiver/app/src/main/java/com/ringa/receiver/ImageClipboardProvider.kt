package com.ringa.receiver

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Base64
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID

class ImageClipboardProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? = imageFile(uri)?.let { file ->
        MIME_BY_EXTENSION[file.extension]
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Read only")
        val file = imageFile(uri) ?: throw FileNotFoundException("Invalid image URI")
        if (!file.isFile) throw FileNotFoundException("Image missing")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val file = imageFile(uri) ?: return null
        if (!file.isFile) return null
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply {
            addRow(columns.map { column ->
                when (column) {
                    OpenableColumns.DISPLAY_NAME -> file.name
                    OpenableColumns.SIZE -> file.length()
                    else -> null
                }
            })
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private fun imageFile(uri: Uri): File? {
        val appContext = context ?: return null
        if (uri.authority != "${appContext.packageName}.images") return null
        val segments = uri.pathSegments
        if (segments.size != 2 || segments[0] != "image") return null
        val name = segments[1]
        if (!IMAGE_FILE_NAME.matches(name)) return null
        return File(imageDirectory(appContext), name)
    }

    companion object {
        private const val MAX_IMAGE_BYTES = 4 * 1024 * 1024
        private val IMAGE_FILE_NAME = Regex("[0-9a-f-]{36}\\.(png|jpg|gif|webp)")
        private val MIME_BY_EXTENSION = mapOf(
            "png" to "image/png",
            "jpg" to "image/jpeg",
            "gif" to "image/gif",
            "webp" to "image/webp",
        )
        private val EXTENSION_BY_MIME = MIME_BY_EXTENSION.entries.associate { (extension, mime) -> mime to extension }

        fun copyToClipboard(context: Context, mimeType: String, encodedData: String): Int {
            val extension = EXTENSION_BY_MIME[mimeType]
                ?: throw IllegalArgumentException("unsupported image type")
            if (encodedData.length > ((MAX_IMAGE_BYTES + 2) / 3) * 4 + 4) {
                throw IllegalArgumentException("image exceeds the 4 MB limit")
            }
            val bytes = try {
                Base64.decode(encodedData, Base64.DEFAULT)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("invalid image_data_b64", e)
            }
            if (bytes.isEmpty() || bytes.size > MAX_IMAGE_BYTES) {
                throw IllegalArgumentException("image must be between 1 byte and 4 MB")
            }

            val directory = imageDirectory(context)
            if (!directory.isDirectory && !directory.mkdirs()) {
                throw IllegalStateException("could not create image directory")
            }
            val file = File(directory, "${UUID.randomUUID()}.$extension")
            file.writeBytes(bytes)
            val uri = Uri.Builder()
                .scheme("content")
                .authority("${context.packageName}.images")
                .appendPath("image")
                .appendPath(file.name)
                .build()
            val clipboard = context.getSystemService(ClipboardManager::class.java)
                ?: throw IllegalStateException("ClipboardManager unavailable")
            clipboard.setPrimaryClip(ClipData.newUri(context.contentResolver, "ringa image", uri))

            val cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
            directory.listFiles()?.forEach { oldFile ->
                if (oldFile != file && oldFile.lastModified() < cutoff) oldFile.delete()
            }
            return bytes.size
        }

        private fun imageDirectory(context: Context): File = File(context.filesDir, "clipboard_images")
    }
}
