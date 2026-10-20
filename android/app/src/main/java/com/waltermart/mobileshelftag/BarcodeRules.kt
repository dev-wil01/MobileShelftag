package com.waltermart.mobileshelftag

/**
 * Barcode rules (confirmed by the store, identical to the PC side):
 *  - barcodes are text, never numbers;
 *  - leading zeros on a SCANNED code are removed before lookup, because DC_UPDATE.txt stores
 *    barcodes without them (04808887300260 finds 4808887300260);
 *  - barcode "0" (all zeros) is a weighted item: never looked up, never printed.
 */
object BarcodeRules {
    const val MAX_LENGTH = 20

    /** The lookup key, or null when the input is not an acceptable barcode. */
    fun normalize(scanned: String?): String? {
        if (scanned == null) return null
        val text = scanned.trim()
        if (text.isEmpty() || text.length > MAX_LENGTH) return null
        if (!text.all { it in '0'..'9' }) return null
        val stripped = text.trimStart('0')
        return if (stripped.isEmpty()) null else stripped
    }

    fun isWeightedOrBlank(masterfileBarcode: String?): Boolean {
        if (masterfileBarcode == null) return true
        return masterfileBarcode.trim().trimStart('0').isEmpty()
    }

    /**
     * SKU lookup key (weighted items and "SKU" input). Letters and digits only, 1-20 characters,
     * kept exactly as typed: no zero stripping (02382 is not 2382). Same rule as the PC side.
     */
    fun normalizeSku(input: String?): String? {
        if (input.isNullOrBlank()) return null
        val text = input.trim()
        if (text.length > 20) return null
        if (!text.all { it in '0'..'9' || it in 'A'..'Z' || it in 'a'..'z' }) return null
        return text
    }
}
