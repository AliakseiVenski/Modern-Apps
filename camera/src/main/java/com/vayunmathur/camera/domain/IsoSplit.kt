package com.vayunmathur.camera.domain

/**
 * Splits an effective ISO into the sensor's analog sensitivity and the post-raw digital boost
 * (100 = 1x), so a cap above the sensor range still reaches the frame.
 */
object IsoSplit {
    const val UNITY_BOOST = 100

    fun maxIso(sensorMax: Int, boostMax: Int): Int =
        (sensorMax.toLong() * boostMax.coerceAtLeast(UNITY_BOOST) / UNITY_BOOST).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    /** (sensorIso, boost): sensor first, the rest of the gain as boost clamped to [boostMax]. */
    fun split(iso: Int, sensorMax: Int, boostMax: Int): Pair<Int, Int> {
        if (iso <= sensorMax) return iso to UNITY_BOOST
        val boost = (iso * UNITY_BOOST.toLong() / sensorMax).toInt()
        return sensorMax to boost.coerceIn(UNITY_BOOST, boostMax.coerceAtLeast(UNITY_BOOST))
    }
}
