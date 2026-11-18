// SPDX-License-Identifier: MIT
package app.a4dy.core

import kotlin.math.roundToInt

enum class Paper(val label: String, val widthMm: Double, val heightMm: Double) {
    A4("A4", 210.0, 297.0), A5("A5", 148.0, 210.0), LETTER("Letter", 215.9, 279.4);
    val widthDots get() = (widthMm * 203.0 / 25.4).roundToInt()
    val heightDots get() = (heightMm * 203.0 / 25.4).roundToInt()
}
enum class Halftone { DIFFUSION, THRESHOLD }
enum class Layout { FIT, FILL }
enum class Feed { CONTINUOUS, LABEL }

data class PrintOptions(
    val paper: Paper = Paper.A4,
    val halftone: Halftone = Halftone.DIFFUSION,
    val threshold: Int = 128,
    val brightness: Int = 0,
    val contrast: Int = 100,
    val density: Int = 0,
    val rotation: Int = 0,
    val mirror: Boolean = false,
    val negative: Boolean = false,
    val marginMm: Int = 5,
    val cropHorizontal: Int = 0,
    val cropVertical: Int = 0,
    val layout: Layout = Layout.FIT,
    val feed: Feed = Feed.CONTINUOUS,
) {
    init {
        require(threshold in 0..255 && brightness in -100..100 && contrast in 20..200)
        require(density in 0..3 && rotation in setOf(0, 90, 180, 270))
        require(marginMm in 0..20 && cropHorizontal in 0..40 && cropVertical in 0..40)
    }
}

/** Zero based indices; preserve selection order, remove duplicates, never clamp invalid input. */
object PageSelection {
    fun parse(text: String, pageCount: Int): List<Int> {
        require(pageCount in 1..500) { "Documents must contain 1–500 pages." }
        require(text.length <= 2000) { "Page selection is too long." }
        if (text.isBlank() || text.trim().equals("all", true)) return (0 until pageCount).toList()
        val indices = linkedSetOf<Int>()
        for (part in text.split(',')) {
            val token = part.trim()
            require(token.matches(Regex("\\d+(?:\\s*-\\s*\\d+)?"))) { "Use pages such as 1–3,5 (with a normal hyphen)." }
            val ends = token.split('-').map { it.trim().toIntOrNull() ?: error("Invalid page number.") }
            val first = ends.first(); val last = ends.last()
            require(first in 1..pageCount && last in first..pageCount) { "Page range must be between 1 and $pageCount." }
            for (page in first..last) indices.add(page - 1)
        }
        require(indices.isNotEmpty()) { "Choose at least one page." }
        return indices.toList()
    }
    fun expand(pages: List<Int>, copies: Int, collate: Boolean): List<Int> {
        require(copies in 1..99) { "Choose 1–99 copies." }
        require(pages.isNotEmpty() && pages.size <= 500 && pages.all { it in 0..499 }) { "Choose 1–500 valid pages." }
        require(pages.size * copies <= 2000) { "A job is limited to 2,000 printed pages." }
        return if (collate) List(copies) { pages }.flatten() else pages.flatMap { p -> List(copies) { p } }
    }
}
