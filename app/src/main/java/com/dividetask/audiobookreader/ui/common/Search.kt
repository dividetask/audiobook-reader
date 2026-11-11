package com.dividetask.audiobookreader.ui.common

/**
 * True when every word of [query] appears (ignoring case) in one of [fields], so
 * "crouch rec" finds "Recursion" by Blake Crouch. A blank query matches everything.
 */
fun matchesSearch(query: String, vararg fields: String?): Boolean {
    val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return true
    val text = fields.filterNotNull().joinToString(" ").lowercase()
    return words.all { it in text }
}
