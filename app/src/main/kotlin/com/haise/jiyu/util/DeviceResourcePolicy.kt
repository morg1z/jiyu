package com.haise.jiyu.util

import android.app.ActivityManager
import android.content.Context
import androidx.core.content.getSystemService

/**
 * Nahrazuje pevné konstanty `TranslateRepository.OCR_PARALLELISM`/`BITMAP_LOAD_PARALLELISM`
 * (donedávna 3 a 5 pro VŠECHNA zařízení) odvozením ze skutečného stavu telefonu - dostupné
 * RAM, počtu jader a systémového "low memory" příznaku. Pevná konstanta byla buď zbytečně
 * opatrná na výkonném zařízení, nebo riskovala OOM/ANR na slabém/paměťově vytíženém - viz
 * `TRANSLATION_BENCHMARK_REPORT.md`, kde nezávislý Android konkurent (Manga Translator
 * Android) přesně tenhle problém řeší stejným principem.
 *
 * Čistá matematika (`ocrConcurrencyFor`/`bitmapConcurrencyFor`/`shouldAttemptOnDeviceModels`)
 * je JVM-testovatelná bez Androidu - stejný vzor jako [com.haise.jiyu.translate.OcrEngine]'s
 * `resolveAutoLanguage`, který taky bere data jako obyčejné parametry místo volání živého API
 * přímo uvnitř testovatelné logiky. `Context`-závislé obalové funkce jsou jen tenká vrstva
 * navrch.
 */
object DeviceResourcePolicy {

    /** Kolik stránek smí OCR běžet souběžně - paměťově nejnáročnější krok (plné rozlišení
     *  bitmapy + ML Kit/ONNX pracovní buffery na každé souběžně běžící vlákno). */
    fun recommendedOcrConcurrency(context: Context): Int =
        ocrConcurrencyFor(availableMemBytes(context), isLowMemory(context), Runtime.getRuntime().availableProcessors())

    /** Kolik stránek smí [com.haise.jiyu.translate.PageBitmapLoader] stahovat souběžně -
     *  čistě síťové I/O čekání, snese vyšší souběžnost než samotné OCR. */
    fun recommendedBitmapConcurrency(context: Context): Int =
        bitmapConcurrencyFor(availableMemBytes(context), isLowMemory(context))

    /** Jestli se má vůbec zkoušet načíst ~150-200MB ONNX model (manga-ocr/YOLO detektor) na
     *  tomhle zařízení, nebo rovnou skočit na ML-Kit-only cestu bez rizika OOM. */
    fun shouldAttemptOnDeviceModels(context: Context): Boolean =
        shouldAttemptOnDeviceModels(availableMemBytes(context), isLowMemory(context))

    private fun availableMemBytes(context: Context): Long {
        val am = context.getSystemService<ActivityManager>() ?: return DEFAULT_AVAILABLE_MEM_BYTES
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.availMem
    }

    private fun isLowMemory(context: Context): Boolean {
        val am = context.getSystemService<ActivityManager>() ?: return false
        if (am.isLowRamDevice) return true
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.lowMemory
    }

    // ---- čistá, JVM-testovatelná matematika ----

    /** @param cpuCores [Runtime.availableProcessors] - vyčleněný jako parametr, ne volaný
     *   přímo tady, aby šlo testovat na JVM bez závislosti na běžícím Androidu. */
    internal fun ocrConcurrencyFor(availableMemBytes: Long, lowMemory: Boolean, cpuCores: Int): Int {
        if (lowMemory) return MIN_OCR_PARALLELISM
        // Rezervuje ~1/4 jader systému/UI, ať OCR na pozadí nezadusí zbytek appky.
        val cpuBudget = ((cpuCores * 3) / 4).coerceAtLeast(1)
        val memBudget = saturatingDiv(availableMemBytes, OCR_PER_WORKER_BYTES)
        return minOf(cpuBudget, memBudget, MAX_OCR_PARALLELISM).coerceAtLeast(MIN_OCR_PARALLELISM)
    }

    internal fun bitmapConcurrencyFor(availableMemBytes: Long, lowMemory: Boolean): Int {
        if (lowMemory) return MIN_BITMAP_PARALLELISM
        val memBudget = saturatingDiv(availableMemBytes, BITMAP_PER_WORKER_BYTES)
        return minOf(memBudget, MAX_BITMAP_PARALLELISM).coerceAtLeast(MIN_BITMAP_PARALLELISM)
    }

    internal fun shouldAttemptOnDeviceModels(availableMemBytes: Long, lowMemory: Boolean): Boolean =
        !lowMemory && availableMemBytes >= ON_DEVICE_MODEL_MIN_AVAILABLE_MEM_BYTES

    /** Dělení, které nikdy nepřeteče do záporné/nesmyslné hodnoty ani pro extrémní vstupy. */
    private fun saturatingDiv(numerator: Long, denominator: Long): Int {
        if (numerator <= 0L) return 0
        val result = numerator / denominator
        return result.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    }

    private const val DEFAULT_AVAILABLE_MEM_BYTES = 512L * 1024 * 1024

    // Odhad paměťového nároku jednoho souběžného OCR "workeru" - plné rozlišení stránky
    // (typicky ~15-40MB dekódovaná bitmapa) + ML Kit/ONNX pracovní buffery. Konzervativní
    // odhad schválně - radši méně souběžnosti než OOM (viz BubbleShapeDetector.kt komentář
    // o zaznamenaném OOM incidentu na reálném zařízení).
    private const val OCR_PER_WORKER_BYTES = 90L * 1024 * 1024
    private const val BITMAP_PER_WORKER_BYTES = 40L * 1024 * 1024

    private const val MIN_OCR_PARALLELISM = 1
    private const val MAX_OCR_PARALLELISM = 6
    private const val MIN_BITMAP_PARALLELISM = 2
    private const val MAX_BITMAP_PARALLELISM = 8

    // Pod touhle hranicí dostupné RAM se ani nezkouší načítat ~150-200MB ONNX modely
    // (manga-ocr/YOLO detektor) - místo rizika OOM na slabém telefonu appka rovnou
    // použije jen ML-Kit-only cestu.
    private const val ON_DEVICE_MODEL_MIN_AVAILABLE_MEM_BYTES = 350L * 1024 * 1024
}
