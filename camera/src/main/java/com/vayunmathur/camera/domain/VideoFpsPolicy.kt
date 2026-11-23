package com.vayunmathur.camera.domain

/**
 * Frame-rate choice for video, from the lens's `CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES`.
 *
 * Only fixed ranges (lower == upper) are used: a variable range like [30,60] lets the HAL switch
 * sensor modes, which shows as a zoom flicker and lets fps sag in the dark.
 */
object VideoFpsPolicy {
    const val STANDARD = 30
    const val HIGH = 60
    private const val MIN_FPS = 24

    /** Fixed rates between 24 and 60 fps from (lower, upper) ranges, ascending. */
    fun fixedRates(ranges: List<Pair<Int, Int>>): List<Int> =
        ranges.filter { (lower, upper) -> lower == upper && upper in MIN_FPS..HIGH }
            .map { it.second }
            .distinct()
            .sorted()

    /**
     * Rates to try for one recording, best first. [wanted] is the user's choice (0 = auto).
     * Normal video prefers 60 over 30; cinematic stays at 30 because EIS often cannot hold 60
     * uncropped, and ignores [wanted]. Lower rates follow as fallbacks. Never empty: a HAL that
     * lists no fixed range still records at 30.
     */
    fun candidates(fixed: List<Int>, wanted: Int, cinematic: Boolean): List<Int> {
        val preferred = when {
            cinematic -> listOf(STANDARD)
            wanted > 0 -> listOf(wanted, HIGH, STANDARD)
            else -> listOf(HIGH, STANDARD)
        }
        val cap = if (cinematic) STANDARD else HIGH
        val ordered = preferred + fixed.filter { it <= cap }.sortedDescending()
        return ordered.filter { it in fixed }.distinct().ifEmpty { listOf(STANDARD) }
    }
}
