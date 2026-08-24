package com.haise.jiyu.translate

import android.content.Context
import org.junit.Assume.assumeTrue

/**
 * CI (`.github/workflows/android-tests.yml`) checkoutuje BEZ `lfs: true` - schválně: repo má
 * přes 650 MB `.onnx` modelů přes Git LFS a stahovat je při KAŽDÉM z mnoha denních pushů by
 * během pár běhů vyčerpalo GitHubem daný free limit LFS přenosu (1 GB/měsíc), appka ale musí
 * zůstat 0 Kč i v CI (viz CLAUDE.md pravidlo 1). Assety proto v CI zůstávají jen Git LFS
 * pointer stub soubory (pár desítek bajtů textu), ne skutečný model - testy, co na reálném
 * obsahu záleží, by na tom bez týhle pojistky pořád padaly (viz nahlášené opakované selhání
 * MangaOcrPipelineOnDeviceTest/MangaOcrFallbackOnDeviceTest po přidání manga-ocr featury).
 *
 * Na vývojářově stroji (reálný LFS checkout) i na CI job `test` (JVM, tenhle soubor vůbec
 * nepoužívá) se nic nemění - jen `instrumented-tests` s pointer stuby testy přeskočí místo
 * falešného pádu.
 */
internal fun assumeRealOnnxAssets(context: Context, vararg assetPaths: String) {
    for (path in assetPaths) {
        val size = context.assets.openFd(path).length
        assumeTrue(
            "$path je jen Git LFS pointer (CI checkout bez lfs:true, viz android-tests.yml) - test se preskakuje",
            size > MIN_REAL_ONNX_BYTES,
        )
    }
}

/** Skutečné modely mají desítky až stovky MB, pointer stub pár desítek bajtů - velká rezerva mezi nimi. */
private const val MIN_REAL_ONNX_BYTES = 1_000_000L
