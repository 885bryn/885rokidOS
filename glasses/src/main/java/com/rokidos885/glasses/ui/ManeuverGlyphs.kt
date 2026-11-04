package com.rokidos885.glasses.ui

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import com.rokidos885.protocol.Maneuver

/** Cached open-stroke glyphs in the mockup's 84-unit box. No Path/Matrix creation during draw. */
class ManeuverGlyphs {
    private val paths = Array(Maneuver.entries.size) { index -> build(Maneuver.entries[index]) }
    fun draw(canvas: Canvas, maneuver: Maneuver, x: Float, y: Float, size: Float, paint: Paint) {
        canvas.save()
        canvas.translate(x, y)
        canvas.scale(size / 84f, size / 84f)
        canvas.drawPath(paths[maneuver.ordinal], paint)
        canvas.restore()
    }
    private fun build(m: Maneuver): Path {
        val path = Path()
        val left = m in setOf(Maneuver.TURN_LEFT, Maneuver.TURN_SLIGHT_LEFT, Maneuver.TURN_SHARP_LEFT,
            Maneuver.U_TURN_LEFT, Maneuver.MERGE_LEFT, Maneuver.FORK_LEFT, Maneuver.ON_RAMP_LEFT,
            Maneuver.OFF_RAMP_LEFT, Maneuver.DESTINATION_LEFT, Maneuver.ROUNDABOUT_COUNTERCLOCKWISE)
        with(path) {
            when (m) {
                Maneuver.TURN_LEFT, Maneuver.TURN_RIGHT -> {
                    moveTo(30f,76f); lineTo(30f,46f); quadTo(30f,32f,44f,32f); lineTo(64f,32f)
                    moveTo(50f,18f); lineTo(64f,32f); lineTo(50f,46f)
                }
                Maneuver.TURN_SHARP_LEFT, Maneuver.TURN_SHARP_RIGHT -> {
                    moveTo(28f,76f); lineTo(28f,30f); lineTo(62f,58f)
                    moveTo(44f,58f); lineTo(62f,58f); lineTo(62f,40f)
                }
                Maneuver.TURN_SLIGHT_LEFT, Maneuver.TURN_SLIGHT_RIGHT,
                Maneuver.FORK_LEFT, Maneuver.FORK_RIGHT, Maneuver.ON_RAMP_LEFT, Maneuver.ON_RAMP_RIGHT,
                Maneuver.OFF_RAMP_LEFT, Maneuver.OFF_RAMP_RIGHT -> {
                    moveTo(36f,78f); lineTo(36f,56f); quadTo(36f,42f,58f,24f)
                    moveTo(44f,22f); lineTo(58f,24f); lineTo(56f,38f)
                }
                Maneuver.U_TURN_LEFT, Maneuver.U_TURN_RIGHT -> {
                    moveTo(26f,76f); lineTo(26f,32f); cubicTo(26f,8f,60f,8f,60f,32f); lineTo(60f,54f)
                    moveTo(46f,40f); lineTo(60f,54f); lineTo(74f,40f)
                }
                Maneuver.MERGE_LEFT, Maneuver.MERGE_RIGHT, Maneuver.MERGE_UNSPECIFIED -> {
                    moveTo(28f,76f); lineTo(28f,58f); quadTo(28f,46f,42f,38f); lineTo(42f,18f)
                    moveTo(28f,32f); lineTo(42f,18f); lineTo(56f,32f)
                }
                Maneuver.ROUNDABOUT_CLOCKWISE, Maneuver.ROUNDABOUT_COUNTERCLOCKWISE -> {
                    moveTo(30f,78f); lineTo(30f,62f); cubicTo(4f,32f,28f,12f,50f,24f)
                    cubicTo(76f,42f,52f,70f,34f,56f)
                    moveTo(50f,24f); lineTo(68f,24f); moveTo(56f,12f); lineTo(68f,24f); lineTo(56f,36f)
                }
                Maneuver.DESTINATION, Maneuver.DESTINATION_LEFT, Maneuver.DESTINATION_RIGHT -> {
                    moveTo(26f,76f); lineTo(26f,16f); lineTo(66f,16f); lineTo(56f,34f); lineTo(26f,34f)
                }
                Maneuver.FERRY -> {
                    moveTo(14f,50f); lineTo(70f,50f); lineTo(60f,66f); lineTo(24f,66f); close()
                    moveTo(24f,50f); lineTo(24f,24f); lineTo(60f,24f); lineTo(60f,50f)
                    moveTo(12f,76f); quadTo(26f,68f,40f,76f); quadTo(54f,84f,72f,74f)
                }
                else -> {
                    moveTo(42f,76f); lineTo(42f,20f)
                    moveTo(28f,34f); lineTo(42f,20f); lineTo(56f,34f)
                }
            }
        }
        if (left) path.transform(Matrix().apply { setScale(-1f,1f,42f,42f) })
        return path
    }
}
