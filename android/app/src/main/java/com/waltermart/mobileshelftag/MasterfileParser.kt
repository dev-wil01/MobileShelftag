package com.waltermart.mobileshelftag

import java.math.BigDecimal

data class MasterfileRow(
    val barcode: String,
    val sku: String,
    val shortDesc: String,
    val price: String,
    val longDesc: String,
    val rawLine: String
)

/**
 * One DC_UPDATE.txt line. Same rules as the PC side: at least 11 comma-separated fields,
 * a barcode that is not blank/zero, and a numeric price in field 4. Anything else is skipped.
 * The original line is kept exactly (it is what the label template reads on the PC).
 */
object MasterfileParser {
    /** A usable row for the BARCODE table: weighted/blank barcodes (0) are left out. */
    fun parseLine(rawLine: String): MasterfileRow? {
        val row = parseAny(rawLine) ?: return null
        return if (BarcodeRules.isWeightedOrBlank(row.barcode)) null else row
    }

    /**
     * A usable row for the SKU table: also weighted items (barcode 0), because those are found
     * by SKU. Needs at least 11 fields and a numeric price, like the PC side.
     */
    fun parseAny(rawLine: String): MasterfileRow? {
        val line = rawLine.trim()
        if (line.isEmpty()) return null

        val f = line.split(',')
        if (f.size < 11) return null

        val barcode = f[0].trim()

        val price = f[3].trim()
        if (price.toBigDecimalOrNull() == null) return null

        return MasterfileRow(barcode, f[1].trim(), f[2].trim(), price, f[10].trim(), line)
    }
}
