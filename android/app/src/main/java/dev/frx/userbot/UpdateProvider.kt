package dev.frx.userbot

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * Отдаёт системному установщику скачанный APK обновления (cacheDir/updates/) по content://-ссылке — file://
 * установщик не принимает. Свой маленький провайдер вместо androidx FileProvider: приложение без зависимостей
 * androidx. Наружу не экспортируется; доступ — только по разовому разрешению FLAG_GRANT_READ_URI_PERMISSION.
 */
class UpdateProvider : ContentProvider() {
    companion object {
        fun authority(ctx: Context) = "${ctx.packageName}.updates"
        fun uri(ctx: Context, apk: File): Uri = Uri.parse("content://${authority(ctx)}/${Uri.encode(apk.name)}")
    }

    private fun file(uri: Uri): File {
        val dir = File(context!!.cacheDir, "updates")
        val name = uri.lastPathSegment ?: throw FileNotFoundException()
        val f = File(dir, name)
        if (f.parentFile?.canonicalPath != dir.canonicalPath || !f.isFile || !name.endsWith(".apk")) throw FileNotFoundException(name)
        return f
    }

    override fun onCreate() = true

    override fun getType(uri: Uri) = "application/vnd.android.package-archive"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("только чтение")
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val f = file(uri)
        return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply { addRow(arrayOf<Any>(f.name, f.length())) }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
