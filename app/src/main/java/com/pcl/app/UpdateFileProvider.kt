package com.pcl.app

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

class UpdateFileProvider : ContentProvider() {
    companion object {
        const val DIR = "update"
        fun authority(c: Context) = "${c.packageName}.updates"
        fun uriFor(c: Context, file: File): Uri =
            Uri.Builder().scheme("content").authority(authority(c)).appendPath(file.name).build()
    }

    private fun fileFor(uri: Uri): File {
        val c = context ?: throw FileNotFoundException()
        val name = uri.lastPathSegment ?: throw FileNotFoundException()
        val dir = File(c.cacheDir, DIR).canonicalFile
        val f = File(dir, name).canonicalFile
        if (f.parentFile != dir || !f.isFile || !f.name.endsWith(".apk")) throw FileNotFoundException(name)
        return f
    }

    override fun onCreate() = true
    override fun getType(uri: Uri) = "application/vnd.android.package-archive"
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("Read-only")
        return ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val f = fileFor(uri)
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(cols, 1).apply {
            addRow(cols.map<String, Any?> { col ->
                when (col) {
                    OpenableColumns.DISPLAY_NAME -> f.name
                    OpenableColumns.SIZE -> f.length()
                    else -> null
                }
            }.toTypedArray())
        }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}