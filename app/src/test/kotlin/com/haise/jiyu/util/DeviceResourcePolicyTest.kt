package com.haise.jiyu.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceResourcePolicyTest {

    private val plentyOfMemory = 4L * 1024 * 1024 * 1024 // 4 GB
    private val tightMemory = 400L * 1024 * 1024 // 400 MB, above the on-device-model floor but tight

    @Test
    fun `low memory device gets the minimum OCR concurrency regardless of CPU cores`() {
        val result = DeviceResourcePolicy.ocrConcurrencyFor(availableMemBytes = plentyOfMemory, lowMemory = true, cpuCores = 8)
        assertEquals(1, result)
    }

    @Test
    fun `low memory device gets the minimum bitmap concurrency`() {
        val result = DeviceResourcePolicy.bitmapConcurrencyFor(availableMemBytes = plentyOfMemory, lowMemory = true)
        assertEquals(2, result)
    }

    @Test
    fun `a decent device with plenty of memory and cores gets more than the historical fixed constant`() {
        val ocr = DeviceResourcePolicy.ocrConcurrencyFor(availableMemBytes = plentyOfMemory, lowMemory = false, cpuCores = 8)
        // Puvodni pevna konstanta byla 3 pro VSECHNA zarizeni - na silnem telefonu s dost
        // pameti uz nema duvod byt strop, staci hardware/pamet.
        assertTrue("expected more than the old fixed 3, got $ocr", ocr > 3)
    }

    @Test
    fun `OCR concurrency never exceeds the hard cap even with huge memory and many cores`() {
        val ocr = DeviceResourcePolicy.ocrConcurrencyFor(availableMemBytes = 32L * 1024 * 1024 * 1024, lowMemory = false, cpuCores = 32)
        assertTrue("expected a hard cap, got $ocr", ocr <= 6)
    }

    @Test
    fun `OCR concurrency reserves roughly a quarter of cores for system and UI`() {
        val ocr = DeviceResourcePolicy.ocrConcurrencyFor(availableMemBytes = plentyOfMemory, lowMemory = false, cpuCores = 4)
        // 4 jadra * 3/4 = 3 - i kdyz je pameti dost, nemel by vyuzit vsechna 4 jadra.
        assertEquals(3, ocr)
    }

    @Test
    fun `tight but not officially low memory still scales concurrency down relative to plentiful memory`() {
        val tight = DeviceResourcePolicy.ocrConcurrencyFor(availableMemBytes = tightMemory, lowMemory = false, cpuCores = 8)
        val plenty = DeviceResourcePolicy.ocrConcurrencyFor(availableMemBytes = plentyOfMemory, lowMemory = false, cpuCores = 8)
        assertTrue("expected tight-memory concurrency ($tight) to be lower than plenty-of-memory concurrency ($plenty)", tight < plenty)
    }

    @Test
    fun `concurrency is never zero or negative even for pathological input`() {
        assertTrue(DeviceResourcePolicy.ocrConcurrencyFor(availableMemBytes = 0L, lowMemory = false, cpuCores = 1) >= 1)
        assertTrue(DeviceResourcePolicy.bitmapConcurrencyFor(availableMemBytes = 0L, lowMemory = false) >= 1)
        assertTrue(DeviceResourcePolicy.ocrConcurrencyFor(availableMemBytes = -1L, lowMemory = false, cpuCores = 0) >= 1)
    }

    @Test
    fun `on-device ONNX models are skipped on a low memory device even with lots of free RAM reported`() {
        assertFalse(DeviceResourcePolicy.shouldAttemptOnDeviceModels(availableMemBytes = plentyOfMemory, lowMemory = true))
    }

    @Test
    fun `on-device ONNX models are skipped when available memory is below the safety floor`() {
        assertFalse(DeviceResourcePolicy.shouldAttemptOnDeviceModels(availableMemBytes = 100L * 1024 * 1024, lowMemory = false))
    }

    @Test
    fun `on-device ONNX models are attempted on a normal device with enough free memory`() {
        assertTrue(DeviceResourcePolicy.shouldAttemptOnDeviceModels(availableMemBytes = plentyOfMemory, lowMemory = false))
    }
}
