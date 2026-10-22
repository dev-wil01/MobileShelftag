package com.waltermart.mobileshelftag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RulesTest {
    @Test fun leadingZerosAreStripped() {
        assertEquals("4808887300260", BarcodeRules.normalize("04808887300260"))
        assertEquals("4808887300260", BarcodeRules.normalize(" 4808887300260 "))
        assertEquals("14804880221547", BarcodeRules.normalize("14804880221547"))
    }

    @Test fun zeroAndGarbageAreRejected() {
        assertNull(BarcodeRules.normalize(null))
        assertNull(BarcodeRules.normalize(""))
        assertNull(BarcodeRules.normalize("0"))
        assertNull(BarcodeRules.normalize("0000"))
        assertNull(BarcodeRules.normalize("48088A7300260"))
        assertNull(BarcodeRules.normalize("1".repeat(21)))
    }

    @Test fun goodLineIsParsedAndKeptVerbatim() {
        val line = "4804880221540,2382,BAGUIO OIL CCNT 1GL/6,847.50,6.00,6135,1,192,193,639,BAGUIO COCONUT OIL 1 GALLON, "
        val row = MasterfileParser.parse(line)!!
        assertEquals("4804880221540", row.barcode)
        assertEquals("2382", row.sku)
        assertEquals("847.50", row.price)
        assertEquals("BAGUIO COCONUT OIL 1 GALLON", row.longDesc)
        assertEquals(line.trim(), row.rawLine)
        assertEquals(true, row.usable)
        assertEquals(false, row.weighted)
    }

    @Test fun onlyABlankLineIsNotStored() {
        assertNull(MasterfileParser.parse("   "))
        assertNull(MasterfileParser.parse(""))
    }

    @Test fun everyOtherLineIsStoredWithItsProblem() {
        val short = MasterfileParser.parse("4800000000001,1,SHORT,5.00")!!
        assertEquals(false, short.usable)
        assertEquals("4800000000001", short.barcode)
        assertEquals("4800000000001,1,SHORT,5.00", short.rawLine)

        val badPrice = MasterfileParser.parse("4800000000002,2,BADPRICE,abc,1,1,1,1,1,1,BADPRICE,")!!
        assertEquals(false, badPrice.usable)

        assertNotNull(MasterfileParser.parse("4804880225555,2315,SAMPLE ITEM1,4015.00,1.00,6135,1,192,193,639,SAMPLE ITEM1, "))
        // no crash on a line with almost nothing in it
        assertEquals(false, MasterfileParser.parse(",")!!.usable)
    }

    @Test fun skusAreKeptExactAndValidated() {
        assertEquals("2382", BarcodeRules.normalizeSku(" 2382 "))
        assertEquals("02382", BarcodeRules.normalizeSku("02382")) // no zero stripping
        assertEquals("AB12", BarcodeRules.normalizeSku("AB12"))
        assertNull(BarcodeRules.normalizeSku(null))
        assertNull(BarcodeRules.normalizeSku("  "))
        assertNull(BarcodeRules.normalizeSku("23 82"))
        assertNull(BarcodeRules.normalizeSku("23,82"))
        assertNull(BarcodeRules.normalizeSku("1".repeat(21)))
    }

    @Test fun weightedRowIsStoredAndFoundBySku() {
        val line = "0,9999,WEIGHTED PORK,310.00,1.00,1,1,1,1,1,WEIGHTED PORK,"
        val row = MasterfileParser.parse(line)!!
        assertEquals("0", row.barcode)
        assertEquals("9999", row.sku)
        assertEquals("310.00", row.price)
        assertEquals(line, row.rawLine)
        assertEquals(true, row.usable)
        assertEquals(true, row.weighted)   // never found by barcode, found by SKU
    }
}
