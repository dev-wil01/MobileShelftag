package com.waltermart.mobileshelftag

import java.security.MessageDigest

/**
 * Turns the saved bulk list into the print request. The list arrives already in scan order
 * (oldest first); this only numbers it. Nothing here sorts by barcode or product, and repeated
 * barcodes stay repeated (one label per scan).
 */
object BatchPlan {
    data class Line(val seq: Int, val barcode: String)

    fun lines(barcodesInScanOrder: List<String>): List<Line> =
        barcodesInScanOrder.mapIndexed { i, b -> Line(i + 1, b) }

    /** Changes whenever the list changes (items added, removed or reordered), so a changed list gets a new batch id. */
    fun fingerprint(rowIdsAndBarcodes: List<Pair<Long, String>>): String {
        val md = MessageDigest.getInstance("SHA-256")
        rowIdsAndBarcodes.forEach { md.update("${it.first}:${it.second};".toByteArray()) }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
