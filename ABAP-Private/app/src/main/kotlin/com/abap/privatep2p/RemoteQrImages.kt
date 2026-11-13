package com.abap.privatep2p

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer

fun saveQrPng(context: Context, uri: Uri, bitmap: Bitmap) {
    val output = requireNotNull(context.contentResolver.openOutputStream(uri)) {
        "Could not open the selected destination"
    }
    output.use {
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) {
            "Could not save the QR image"
        }
    }
}

fun readQrPayload(context: Context, uri: Uri): String {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        ?: error("Could not open the selected image")

    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "The selected file is not a readable image" }
    var sample = 1
    while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2

    val bitmap = resolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    } ?: error("Could not decode the selected image")

    try {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
        val hints = mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            DecodeHintType.TRY_HARDER to true
        )
        return MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)), hints).text
    } catch (error: Exception) {
        throw IllegalArgumentException("No readable ABAP Private QR code was found in that image", error)
    } finally {
        bitmap.recycle()
    }
}
