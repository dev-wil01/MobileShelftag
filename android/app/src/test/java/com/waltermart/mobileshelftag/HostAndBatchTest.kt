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
}
