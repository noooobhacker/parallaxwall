package com.example.parallaxwall

import android.hardware.SensorManager

/** Turns rotation-vector sensor values into a smoothed x/y offset in the range -1..1. */
class TiltTracker {
    private val rot = FloatArray(9)
    private val ori = FloatArray(3)
    private var basePitch = 0f
    private var baseRoll = 0f
    @Volatile
    private var hasBase = false

    var x = 0f
        private set
    var y = 0f
        private set

    fun reset() {
        hasBase = false
    }

    fun update(values: FloatArray) {
        val v = if (values.size > 4) values.copyOfRange(0, 4) else values
        SensorManager.getRotationMatrixFromVector(rot, v)
        SensorManager.getOrientation(rot, ori)
        val pitch = ori[1]
        val roll = ori[2]
        if (!hasBase) {
            basePitch = pitch
            baseRoll = roll
            hasBase = true
        }
        val range = 0.5f // about 30 degrees of tilt = full shift
        val tx = ((roll - baseRoll) / range).coerceIn(-1f, 1f)
        val ty = ((pitch - basePitch) / range).coerceIn(-1f, 1f)
        x += (-tx - x) * 0.15f
        y += (ty - y) * 0.15f
    }
}
