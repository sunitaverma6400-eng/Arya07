package com.arya.ai.tools

import android.content.Context
import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object UtilityTools {

    fun getCurrentTime(): String {
        val fmt = SimpleDateFormat("EEEE, d MMM yyyy, hh:mm a", Locale.getDefault())
        return "🕒 Abhi: ${fmt.format(Date())}"
    }

    /** Generates a QR PNG under the app's cache dir and returns the file path (share it via ExportHelper). */
    fun generateQr(context: Context, text: String): String = try {
        val size = 512
        val writer = QRCodeWriter()
        val matrix = writer.encode(text, BarcodeFormat.QR_CODE, size, size)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
        val file = File(context.cacheDir, "arya_qr_${System.currentTimeMillis()}.png")
        FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        "📱 QR code ban gaya: ${file.absolutePath}"
    } catch (e: Exception) {
        "❌ QR generate nahi ho paaya: ${e.message}"
    }

    fun generatePassword(length: Int, useSymbols: Boolean): String {
        val letters = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val symbols = "!@#\$%^&*()-_=+"
        val pool = if (useSymbols) letters + symbols else letters
        val rng = SecureRandom()
        val password = (1..length.coerceIn(4, 128)).map { pool[rng.nextInt(pool.length)] }.joinToString("")
        return "🔑 $password"
    }

    fun textAnalyzer(text: String): String {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val chars = text.length
        val sentences = text.split(Regex("[.!?]+")).filter { it.isNotBlank() }.size
        return "📊 Words: ${words.size}, Characters: $chars, Sentences: $sentences"
    }

    fun getRandomQuote(): String {
        val quotes = listOf(
            "Zindagi wahi hai jo hum banate hain, baaki sab kahani hai.",
            "Mushkil raaste aksar khoobsurat manzilon tak le jaate hain.",
            "Har din ek nayi shuruaat hai.",
            "Mehnat kabhi bekaar nahi jaati.",
            "Khud par bharosa sabse badi taqat hai."
        )
        return "💬 ${quotes.random()}"
    }

    fun convertUnits(value: Double, from: String, to: String): String {
        val f = from.lowercase().trim()
        val t = to.lowercase().trim()
        // A small direct conversion table, same coverage as the original assistant's convert_units.
        val toBase: Map<String, Double> = mapOf(
            "km" to 1000.0, "m" to 1.0, "cm" to 0.01, "mm" to 0.001, "mile" to 1609.34, "miles" to 1609.34,
            "kg" to 1000.0, "g" to 1.0, "mg" to 0.001, "lb" to 453.592, "lbs" to 453.592,
            "l" to 1.0, "ml" to 0.001, "gallon" to 3.78541
        )
        // Temperature needs formulas, not a simple ratio.
        if (f in listOf("c", "celsius") || t in listOf("c", "celsius") ||
            f in listOf("f", "fahrenheit") || t in listOf("f", "fahrenheit")
        ) {
            val celsius = when {
                f.startsWith("c") -> value
                f.startsWith("f") -> (value - 32) * 5 / 9
                else -> return "❌ Unsupported temperature unit: $from"
            }
            val result = when {
                t.startsWith("c") -> celsius
                t.startsWith("f") -> celsius * 9 / 5 + 32
                else -> return "❌ Unsupported temperature unit: $to"
            }
            return "🔄 $value $from = ${"%.2f".format(result)} $to"
        }
        val fromFactor = toBase[f] ?: return "❌ '$from' unit pehchana nahi gaya"
        val toFactor = toBase[t] ?: return "❌ '$to' unit pehchana nahi gaya"
        val result = value * fromFactor / toFactor
        return "🔄 $value $from = ${"%.4f".format(result)} $to"
    }

    fun systemInfo(context: Context): String {
        val sdk = android.os.Build.VERSION.SDK_INT
        val model = android.os.Build.MODEL
        val manufacturer = android.os.Build.MANUFACTURER
        val storage = try {
            val stat = android.os.StatFs(context.filesDir.path)
            val freeGb = stat.availableBytes / (1024.0 * 1024 * 1024)
            "%.1f GB free".format(freeGb)
        } catch (e: Exception) { "?" }
        return "📱 $manufacturer $model — Android SDK $sdk, $storage"
    }
}
