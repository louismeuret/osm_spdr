package org.spdr.companion

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import org.spdr.companion.core.bearingDeg
import org.spdr.companion.core.cardinal
import org.spdr.companion.core.filter.Hypothesis
import org.spdr.companion.core.graph.Graph
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Schematic view of the graph, the particle cloud and the hypotheses, in local meters.
 * Drag to pan, pinch to zoom, double-tap to follow the estimate again, long-press to say
 * "I'm here", tap an orange hypothesis to pick it.
 */
class MapCanvasView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var onLongPressAt: ((x: Double, y: Double) -> Unit)? = null
    var onHypothesisTapped: ((Hypothesis) -> Unit)? = null

    private var snap: Snapshot? = null
    private var graph: Graph? = null
    private var scale = 1.5f // px per meter
    private var cx = 0.0
    private var cy = 0.0
    private var follow = true
    private val d = resources.displayMetrics.density

    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(120, 134, 144); strokeWidth = 2.5f * d; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(216, 27, 96) }
    private val junctionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(160, 170, 60) }
    private val particlePaint = Paint().apply { color = Color.rgb(255, 179, 0) }
    private val hypPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * d }
    private val bestPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(30, 136, 229) }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 13 * d }
    private val dimText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(176, 190, 197); textSize = 12 * d }
    private val dialPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(70, 82, 92); strokeWidth = 1.5f * d; style = Paint.Style.STROKE }
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(30, 136, 229); strokeWidth = 3 * d; strokeCap = Paint.Cap.ROUND }
    private val magPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 143, 0); strokeWidth = 1.6f * d; strokeCap = Paint.Cap.ROUND }
    private val phonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(176, 190, 197); strokeWidth = 1.6f * d; strokeCap = Paint.Cap.ROUND }
    private val path = Path()

    fun update(s: Snapshot) {
        val newGraph = s.graph !== graph
        snap = s
        graph = s.graph
        val b = s.estimate?.best
        if (newGraph && b == null) s.graph?.let { fitGraph(it) }
        if (follow && b != null) { cx = b.x; cy = b.y }
        invalidate()
    }

    private fun fitGraph(g: Graph) {
        val bb = g.bounds
        cx = (bb[0] + bb[2]) / 2; cy = (bb[1] + bb[3]) / 2
        val span = maxOf(bb[2] - bb[0], bb[3] - bb[1], 50.0)
        if (width > 0) scale = (minOf(width, height) / span * 0.9).toFloat()
    }

    private fun sx(x: Double) = (width / 2f + (x - cx) * scale).toFloat()
    private fun sy(y: Double) = (height / 2f - (y - cy) * scale).toFloat()
    private fun mx(px: Float) = cx + (px - width / 2f) / scale
    private fun my(py: Float) = cy - (py - height / 2f) / scale

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.rgb(16, 20, 24))
        val g = graph
        val s = snap
        if (g == null) {
            canvas.drawText("No graph. Use Graph… to download OSM ways or import galleries.", 12 * d, height / 2f, dimText)
            s?.let { drawCompass(canvas, it) } // which way you are facing does not need a map
            return
        }
        // only draw edges that intersect the screen
        val r = hypot(width.toDouble(), height.toDouble()) / scale / 2 + 20
        val visible = g.edgesNear(cx, cy, r)
        for (ei in visible) {
            val e = g.edges[ei]
            path.reset()
            path.moveTo(sx(e.xs[0]), sy(e.ys[0]))
            for (i in 1 until e.xs.size) path.lineTo(sx(e.xs[i]), sy(e.ys[i]))
            canvas.drawPath(path, edgePaint)
        }
        val showLabels = scale > 1.2f
        for (n in g.nodes) {
            if (kotlin.math.abs(n.x - cx) > r || kotlin.math.abs(n.y - cy) > r) continue
            if (n.landmark || n.name != null) {
                canvas.drawCircle(sx(n.x), sy(n.y), 5 * d, nodePaint)
                if (showLabels) canvas.drawText(n.name ?: "", sx(n.x) + 7 * d, sy(n.y) - 4 * d, dimText)
            } else if (n.incident.size >= 3 && scale > 2f) {
                canvas.drawCircle(sx(n.x), sy(n.y), 2.5f * d, junctionPaint)
            }
        }
        if (s != null) {
            for (i in s.px.indices) {
                particlePaint.alpha = (60 + 195 * (s.pw[i] * s.px.size).coerceAtMost(1f)).toInt()
                canvas.drawCircle(sx(s.px[i].toDouble()), sy(s.py[i].toDouble()), 1.8f * d, particlePaint)
            }
            s.estimate?.hypotheses?.forEachIndexed { i, h ->
                hypPaint.color = if (i == 0) Color.rgb(30, 136, 229) else Color.rgb(255, 143, 0)
                val rad = ((h.spread + 3) * scale).toFloat().coerceAtLeast(10 * d)
                canvas.drawCircle(sx(h.x), sy(h.y), rad, hypPaint)
                canvas.drawText("${(h.weight * 100).toInt()}%", sx(h.x) + rad + 3 * d, sy(h.y), textPaint)
            }
            s.estimate?.best?.let { b ->
                // the measured direction of travel, before the map snaps it to a gallery: the gap
                // between this ray and the arrow is the heading error the map is absorbing
                val travel = s.facing.travel
                if (!travel.isNaN()) {
                    val len = 42 * d
                    canvas.drawLine(sx(b.x), sy(b.y),
                        sx(b.x) + cos(travel).toFloat() * len, sy(b.y) - sin(travel).toFloat() * len,
                        magPaint)
                }
                drawArrow(canvas, sx(b.x), sy(b.y), b.heading)
            }
            drawCompass(canvas, s)
        }
        drawScaleBar(canvas)
    }

    /**
     * Compass dial, top right. The pale needle is where the **phone** points, live — that is the
     * one to hold next to an ordinary compass app. The thick blue needle is the direction of
     * **travel** the filter believes, which only moves when you take a step and disappears when
     * you stand still, because then there is no direction of travel to report. The label is the
     * phone bearing, since that is the live one.
     */
    private fun drawCompass(c: Canvas, s: Snapshot) {
        val f = s.facing
        if (f.phone.isNaN() && f.travel.isNaN()) return
        val r = 26 * d
        val x = width - r - 14 * d
        val y = r + 16 * d
        c.drawCircle(x, y, r, dialPaint)
        c.drawLine(x, y - r, x, y - r + 6 * d, dialPaint)
        c.drawText("N", x - dimText.measureText("N") / 2, y - r - 4 * d, dimText)
        if (!f.phone.isNaN()) needle(c, x, y, r * 0.9f, f.phone, phonePaint)
        if (!f.travel.isNaN()) needle(c, x, y, r * 0.78f, f.travel, needlePaint)
        val shown = if (f.phone.isNaN()) f.travel else f.phone
        val label = "${bearingDeg(shown).roundToInt()}° ${cardinal(shown)}"
        c.drawText(label, x - textPaint.measureText(label) / 2, y + r + 14 * d, textPaint)
    }

    private fun needle(c: Canvas, x: Float, y: Float, len: Float, heading: Double, paint: Paint) {
        val ax = cos(heading).toFloat(); val ay = -sin(heading).toFloat()
        c.drawLine(x - ax * len * 0.35f, y - ay * len * 0.35f, x + ax * len, y + ay * len, paint)
    }

    private fun drawArrow(c: Canvas, x: Float, y: Float, heading: Double) {
        val len = 16 * d
        // screen y points down: math heading → screen angle is -heading
        val ax = cos(heading).toFloat(); val ay = -sin(heading).toFloat()
        path.reset()
        path.moveTo(x + ax * len, y + ay * len)
        path.lineTo(x - ax * len * 0.6f - ay * len * 0.6f, y - ay * len * 0.6f + ax * len * 0.6f)
        path.lineTo(x - ax * len * 0.3f, y - ay * len * 0.3f)
        path.lineTo(x - ax * len * 0.6f + ay * len * 0.6f, y - ay * len * 0.6f - ax * len * 0.6f)
        path.close()
        c.drawPath(path, bestPaint)
    }

    private fun drawScaleBar(c: Canvas) {
        val target = 100 * d / scale
        val p = 10.0.pow(kotlin.math.floor(log10(target.toDouble())))
        val m = listOf(1.0, 2.0, 5.0, 10.0).map { it * p }.last { it <= target }
        val px = (m * scale).toFloat()
        val y = height - 16 * d
        c.drawLine(12 * d, y, 12 * d + px, y, textPaint)
        c.drawText("${m.toInt()} m  ·  N ↑${if (follow) "  · following" else ""}", 12 * d, y - 6 * d, dimText)
    }

    // ---- gestures ----------------------------------------------------------------------------

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(det: ScaleGestureDetector): Boolean {
            scale = (scale * det.scaleFactor).coerceIn(0.05f, 60f)
            invalidate()
            return true
        }
    })

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            follow = false
            cx += dx / scale; cy -= dy / scale
            invalidate()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            follow = true
            snap?.estimate?.best?.let { cx = it.x; cy = it.y } ?: graph?.let { fitGraph(it) }
            invalidate()
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (graph != null) onLongPressAt?.invoke(mx(e.x), my(e.y))
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            val hs = snap?.estimate?.hypotheses ?: return false
            val x = mx(e.x); val y = my(e.y)
            val hit = hs.drop(1).minByOrNull { hypot(it.x - x, it.y - y) } ?: return false
            if (hypot(hit.x - x, hit.y - y) * scale < 40 * d) onHypothesisTapped?.invoke(hit)
            return true
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (!scaleDetector.isInProgress) gestures.onTouchEvent(event)
        return true
    }
}
