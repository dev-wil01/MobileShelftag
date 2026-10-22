package com.waltermart.mobileshelftag

import java.math.BigDecimal

/**
 * One line of DC_UPDATE.txt, kept ALWAYS (nothing in the masterfile is dropped on the handheld).
 * [problem] is empty for a row that can be printed, otherwise it says why it cannot
 * (the PC cannot print such a row either).
 */
data class MasterfileRow(
    val barcode: String,
    val sku: String,
    val shortDesc: String,
    val price: String,
    val longDesc: String,
    val rawLine: String,
    val problem: String
) {
    val usable: Boolean get() = problem.isEmpty()

    /** Barcode 0 / blank: a weighted item. It is found by SKU, never by barcode. */
    val weighted: Boolean get() = BarcodeRules.isWeightedOrBlank(barcode)
}

object MasterfileParser {
    /** Returns null only for a blank line (no data). Every other line becomes a row. The original line is kept exactly. */
    fun parse(rawLine: String): MasterfileRow? {
        val line = rawLine.trim()
        if (line.isEmpty()) return null

        val f = line.split(',')
        fun field(i: Int) = if (i < f.size) f[i].trim() else ""

        val price = field(3)
        val problem = when {
            f.size < 11 -> "the row has fewer than 11 fields"
            price.toBigDecimalOrNull() == null -> "the price is not a number"
            else -> ""
        }

        return MasterfileRow(field(0), field(1), field(2), price, field(10), line, problem)
    }
}
