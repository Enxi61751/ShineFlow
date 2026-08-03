package com.android.calendar.ocr

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

object OcrUploader {

    /**
     * File -> Multipart part
     */
    fun fileToPart(file: File, partName: String = "file"): MultipartBody.Part {
        val mediaType = "image/*".toMediaType()
        val body = file.asRequestBody(mediaType)
        return MultipartBody.Part.createFormData(partName, file.name, body)
    }

    /**
     * Bitmap -> 临时文件（cache）-> File
     */
    fun bitmapToTempFile(
        context: Context,
        bitmap: Bitmap,
        quality: Int = 90
    ): File {
        val outFile = File(context.cacheDir, "ocr_${UUID.randomUUID()}.jpg")
        FileOutputStream(outFile).use { fos ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, fos)
        }
        return outFile
    }

    /**
     * Uri -> 复制到临时文件（cache）-> File
     * 适合从相册/文件选择器拿到的 Uri
     */
    fun uriToTempFile(
        context: Context,
        uri: Uri
    ): File {
        val outFile = File(context.cacheDir, "ocr_${UUID.randomUUID()}.jpg")
        val resolver: ContentResolver = context.contentResolver

        resolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Cannot open input stream for uri: $uri" }
            FileOutputStream(outFile).use { output ->
                input.copyTo(output)
            }
        }
        return outFile
    }
}
