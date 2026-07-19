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

        // ML Kit "textBlocks" jsou odstavcová seskupení odladěná na fotky dokumentů/účtenek,
        // ne na manga bubliny - běžně buď slijí dvě sousední bubliny do jednoho bloku, nebo
        // naopak rozseknou jednu bublinu na víc bloků. Jdeme proto o úroveň níž na "lines"
        // (řádky) a slučujeme je vlastní geometrickou heuristikou (mergeNearbyLines), která
        // lépe odpovídá tomu, co člověk vnímá jako jednu bublinu.
        val lines = result.textBlocks.flatMap { it.lines }.mapNotNull { line ->
            val box = line.boundingBox ?: return@mapNotNull null
            if (line.text.isBlank()) return@mapNotNull null
            RawTextBlock(
                text = line.text,
                leftF = (box.left / w).coerceIn(0f, 1f),
                topF = (box.top / h).coerceIn(0f, 1f),
                rightF = (box.right / w).coerceIn(0f, 1f),
                bottomF = (box.bottom / h).coerceIn(0f, 1f),
            )
        }
        mergeNearbyLines(lines)
    }

    /**
     * Spojí OCR řádky, které leží blízko sebe (malá svislá mezera vůči výšce písma a
     * vodorovné překrytí/blízkost), do jednoho bloku - to bývá jedna bublina s víc řádky.
     * Union-Find nad dvojicovým testem [shouldMerge]: O(n²), ale n (řádků na stránku)
     * bývá v řádu jednotek až nízkých desítek, takže to není problém výkonu.
     */
    private fun mergeNearbyLines(lines: List<RawTextBlock>): List<RawTextBlock> {
        if (lines.isEmpty()) return emptyList()
        val parent = IntArray(lines.size) { it }
        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) r = parent[r]
            var c = x
            while (parent[c] != r) { val next = parent[c]; parent[c] = r; c = next }
            return r
        }
        fun union(a: Int, b: Int) {
            val ra = find(a); val rb = find(b)
            if (ra != rb) parent[ra] = rb
        }

        for (i in lines.indices) {
            for (j in i + 1 until lines.size) {
                if (shouldMerge(lines[i], lines[j])) union(i, j)
            }
        }

        return lines.indices.groupBy { find(it) }.map { (_, idxs) ->
            val group = idxs.map { lines[it] }.sortedWith(compareBy({ it.topF }, { it.leftF }))
            RawTextBlock(
                text = group.joinToString(" ") { it.text },
                leftF = group.minOf { it.leftF },
                topF = group.minOf { it.topF },
                rightF = group.maxOf { it.rightF },
                bottomF = group.maxOf { it.bottomF },
            )
        }
    }

    private fun shouldMerge(a: RawTextBlock, b: RawTextBlock): Boolean {
        val avgHeight = ((a.bottomF - a.topF) + (b.bottomF - b.topF)) / 2f
        if (avgHeight <= 0f) return false

        val verticalGap = maxOf(0f, maxOf(a.topF, b.topF) - minOf(a.bottomF, b.bottomF))
        val horizontalOverlap = minOf(a.rightF, b.rightF) - maxOf(a.leftF, b.leftF)
        val horizontalGap = maxOf(0f, maxOf(a.leftF, b.leftF) - minOf(a.rightF, b.rightF))

        // Řádky stejné bubliny mívají mezeru mnohem menší než výška písma; mezi bublinami
        // bývá mezera srovnatelná s výškou písma nebo větší (okraj bubliny, kresba).
        return verticalGap < avgHeight * 0.9f && (horizontalOverlap > 0f || horizontalGap < avgHeight * 1.8f)
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
