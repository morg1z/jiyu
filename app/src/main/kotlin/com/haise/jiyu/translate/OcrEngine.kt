package com.haise.jiyu.translate

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class RawTextBlock(
    val text: String,
    val leftF: Float,
    val topF: Float,
    val rightF: Float,
    val bottomF: Float,
)

@Singleton
class OcrEngine @Inject constructor(
    private val httpClient: OkHttpClient,
) {
    // Lazy recognizers: CJK jazyky mají vlastní ML Kit model, ostatní spadají na latinkový výchozí
    private val japaneseRecognizer by lazy { TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build()) }
    private val chineseRecognizer by lazy { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) }
    private val koreanRecognizer by lazy { TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build()) }
    private val latinRecognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    private fun recognizerFor(language: String) = when (language) {
        "Japanese" -> japaneseRecognizer
        "Chinese", "Chinese (Traditional)" -> chineseRecognizer
        "Korean" -> koreanRecognizer
        else -> latinRecognizer
    }

    suspend fun recognize(pageUrl: String, language: String = "Japanese"): List<RawTextBlock> = withContext(Dispatchers.IO) {
        val bitmap = loadBitmap(pageUrl) ?: return@withContext emptyList()
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        if (w == 0f || h == 0f) return@withContext emptyList()

        val image = InputImage.fromBitmap(bitmap, 0)

        val result = suspendCancellableCoroutine { cont ->
            recognizerFor(language).process(image)
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }

        result.textBlocks.mapNotNull { block ->
            val box = block.boundingBox ?: return@mapNotNull null
            if (block.text.isBlank()) return@mapNotNull null
            RawTextBlock(
                text = block.text,
                leftF = (box.left / w).coerceIn(0f, 1f),
                topF = (box.top / h).coerceIn(0f, 1f),
                rightF = (box.right / w).coerceIn(0f, 1f),
                bottomF = (box.bottom / h).coerceIn(0f, 1f),
            )
        }
    }

    private fun loadBitmap(url: String): Bitmap? = try {
        if (url.startsWith("/") || url.startsWith("file://")) {
            val path = url.removePrefix("file://")
            BitmapFactory.decodeFile(path)
        } else {
            val cleanUrl = url.substringBeforeLast("#") // strip #mplus_key= fragment
            val req = Request.Builder().url(cleanUrl).build()
            httpClient.newCall(req).execute().use { resp ->
                resp.body?.bytes()?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            }
        }
    } catch (e: Exception) {
        null
    }
}
