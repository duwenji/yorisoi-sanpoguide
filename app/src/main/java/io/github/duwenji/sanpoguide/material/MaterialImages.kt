package io.github.duwenji.sanpoguide.material

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/** The picture of an image material, scaled down so its longer side is at most about [maxPx]; null for other material. */
fun Material.bitmap(maxPx: Int): Bitmap? = when (val c = content) {
    is Content.Image -> c.bitmap
    is Content.EncodedImage -> decodeImage(c.bytes, maxPx)
    else -> null
}

/** Decodes [bytes] at the smallest power-of-two reduction that still covers [maxPx]; null when it isn't an image. */
fun decodeImage(bytes: ByteArray, maxPx: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
}
