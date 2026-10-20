package dev.colormemo.app.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import dev.colormemo.core.model.NoteAttachment
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.util.Base64

/** Portable SVG images use bounded PNG pixels with EXIF orientation already applied. */
internal fun whiteboardImageDataUrl(attachment: NoteAttachment): String {
    val bitmap = requireNotNull(decodeNoteImage(attachment.bytes(), 2048)) { "白板图片无法解码，无法导出。" }
    try {
        val output = ByteArrayOutputStream()
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "白板图片转换失败。" }
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(output.toByteArray())
    } finally {
        bitmap.recycle()
    }
}

internal val NoteAttachment.isImage: Boolean
    get() = mimeType.startsWith("image/", ignoreCase = true)

internal fun imageMimeType(bytes: ByteArray): String {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outMimeType?.startsWith("image/") == true) {
        "请选择设备支持的图片文件。"
    }
    return requireNotNull(bounds.outMimeType)
}

/** Decode bounded previews off the main thread; keep the original bytes for sync/export. */
internal fun decodeNoteImage(bytes: ByteArray, maxDimension: Int): Bitmap? {
    require(maxDimension > 0)
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    if (options.outWidth <= 0 || options.outHeight <= 0) return null
    options.inSampleSize = 1
    while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > maxDimension) {
        options.inSampleSize *= 2
    }
    options.inJustDecodeBounds = false
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
    val orientation = try {
        bytes.inputStream().use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
    } catch (_: IOException) {
        ExifInterface.ORIENTATION_NORMAL
    }
    val matrix = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
            else -> return bitmap
        }
    }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
        if (it !== bitmap) bitmap.recycle()
    }
}
