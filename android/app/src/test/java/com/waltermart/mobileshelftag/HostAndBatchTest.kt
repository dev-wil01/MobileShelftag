package com.waltermart.mobileshelftag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class HostAndBatchTest {
    @Test fun loopbackAddressesAreRefused() {
        listOf("localhost", "LOCALHOST", "127.0.0.1", "127.1.2.3", "0.0.0.0", "10.0.2.2", "pc.localhost").forEach {
            assertNotNull(it, HostRules.problem(it))
        }
    }

    @Test fun realPcAddressesAreAccepted() {
        listOf("192.0.2.15", "192.168.1.20", "pc-name", "pc-name.example.local").forEach {
            assertNull(it, HostRules.problem(it))
        }
    }

    @Test fun badAddressesAreRefused() {
        listOf("", "  ", "10.0.0.1:5088", "http://10.0.0.1", "pc name").forEach {
            assertNotNull(it, HostRules.problem(it))
        }
    }

    @Test fun batchKeepsScanOrderAndRepeats() {
        // scanned: 9, 1, 5, 1  (not sorted, repeat kept)
        val lines = BatchPlan.lines(listOf("9", "1", "5", "1"))
        assertEquals(listOf(1, 2, 3, 4), lines.map { it.seq })
        assertEquals(listOf("9", "1", "5", "1"), lines.map { it.barcode })
    }

    @Test fun fingerprintChangesWhenTheListChanges() {
        val base = listOf(1L to "A", 2L to "B", 3L to "C")
        val same = BatchPlan.fingerprint(base)
        assertEquals(same, BatchPlan.fingerprint(base))
        assertNotEquals(same, BatchPlan.fingerprint(base.drop(1)))                 // item removed
        assertNotEquals(same, BatchPlan.fingerprint(base + (4L to "D")))           // item added
        assertNotEquals(same, BatchPlan.fingerprint(listOf(1L to "A", 3L to "C", 2L to "B"))) // reordered
    }

    @Test fun versionCodeComesFromTheEndOfTheTag() {
        assertEquals(12, UpdateRules.versionCodeFromTag("v1.0.12"))
        assertEquals(7, UpdateRules.versionCodeFromTag(" v1.0.7 "))
        assertEquals(103, UpdateRules.versionCodeFromTag("1.2.103"))
        assertNull(UpdateRules.versionCodeFromTag("latest"))
        assertNull(UpdateRules.versionCodeFromTag(""))
    }

    @Test fun onlyNewerVersionsAreOffered() {
        assertEquals(true, UpdateRules.isNewer(13, 12))
        assertEquals(false, UpdateRules.isNewer(12, 12))
        assertEquals(false, UpdateRules.isNewer(11, 12))
    }

    @Test fun downloadsMustComeFromTheRepositorysOwnReleases() {
        val repo = "owner/MobileShelftag"
        assertEquals(true, UpdateRules.isTrustedDownloadUrl("https://github.com/owner/MobileShelftag/releases/download/v1.0.2/MobileShelfTag.apk", repo))
        assertEquals(true, UpdateRules.isTrustedDownloadUrl("https://github.com/Owner/mobileshelftag/releases/download/v1.0.2/a.apk", repo)) // case-insensitive
        assertEquals(false, UpdateRules.isTrustedDownloadUrl("https://evil.example/owner/MobileShelftag/releases/download/v1/a.apk", repo))
        assertEquals(false, UpdateRules.isTrustedDownloadUrl("https://github.com/other/repo/releases/download/v1/a.apk", repo))
        assertEquals(false, UpdateRules.isTrustedDownloadUrl("http://github.com/owner/MobileShelftag/releases/download/v1/a.apk", repo))
        assertEquals(false, UpdateRules.isTrustedDownloadUrl("https://github.com/owner/MobileShelftag/releases/download/v1/a.apk", ""))
    }

    @Test fun digestIsCleaned() {
        assertEquals("abc123", UpdateRules.cleanDigest("sha256:ABC123"))
        assertEquals("abc123", UpdateRules.cleanDigest(" ABC123 "))
        assertEquals("", UpdateRules.cleanDigest(null))
    }
}
