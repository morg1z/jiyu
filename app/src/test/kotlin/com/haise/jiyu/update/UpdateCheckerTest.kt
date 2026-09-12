package com.haise.jiyu.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun `a higher patch version is newer`() {
        assertTrue(isNewerVersion("1.2.68", "1.2.9"))
        assertFalse(isNewerVersion("1.2.9", "1.2.68"))
    }

    @Test
    fun `identical versions are not newer`() {
        assertFalse(isNewerVersion("1.2.68", "1.2.68"))
    }

    @Test
    fun `a higher minor or major version wins regardless of patch`() {
        assertTrue(isNewerVersion("1.3.0", "1.2.99"))
        assertTrue(isNewerVersion("2.0.0", "1.99.99"))
    }

    @Test
    fun `missing trailing segments are treated as zero`() {
        assertTrue(isNewerVersion("1.3", "1.2.9"))
        assertFalse(isNewerVersion("1.2", "1.2.0"))
    }

    @Test
    fun `sha256 is extracted from release notes regardless of case or dash`() {
        assertEquals(
            "a".repeat(64),
            extractSha256FromReleaseNotes("Changelog...\n\nsha256: ${"a".repeat(64)}\n"),
        )
        assertEquals(
            "b".repeat(64),
            extractSha256FromReleaseNotes("SHA-256:${"B".repeat(64)}"),
        )
    }

    @Test
    fun `sha256 is found even deep inside a long release body`() {
        val body = "x".repeat(600) + "\nsha256: ${"c".repeat(64)}\n" + "y".repeat(600)
        assertEquals("c".repeat(64), extractSha256FromReleaseNotes(body))
    }

    @Test
    fun `missing digest returns null instead of throwing`() {
        assertNull(extractSha256FromReleaseNotes("Just a regular changelog with no digest."))
    }

    @Test
    fun `a hex string shorter than 64 characters is not matched`() {
        assertNull(extractSha256FromReleaseNotes("sha256: ${"a".repeat(63)}"))
    }
}
