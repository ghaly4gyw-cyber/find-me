package com.ghaly.findme.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.SweepGradient
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.ghaly.findme.model.PeerPosition
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A simple offline "radar" — there's no internet for real map tiles out in the
 * desert, so instead this draws you at the center and every teammate as a dot
 * placed by bearing + distance from your last GPS fix, north-up, with range
 * rings for scale. Distances beyond [maxRangeMeters] are clamped to the edge
 * (still shows direction even when exact distance is off-screen).
 */
class RadarMapView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var myLocation: Pair<Double, Double>? = null
        set(value) {
            field = value
            invalidate()
        }

    var peers: List<PeerPosition> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    var onPeerTap: ((PeerPosition) -> Unit)? = null

    var maxRangeMeters: Float = 2000f

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#28474B")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F2A541")
        style = Paint.Style.FILL
    }
    private val centerGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#33F2A541")
        style = Paint.Style.FILL
    }
    private val peerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#39C5BB") // the "blue dot" showing another person's live position
        style = Paint.Style.FILL
    }
    private val peerGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4039C5BB")
        style = Paint.Style.FILL
    }
    private val peerStalePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#AFC9C7")
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f
        textAlign = Paint.Align.CENTER
    }
    private val northPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#AFC9C7")
        textSize = 24f
        textAlign = Paint.Align.CENTER
    }
    private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    /** last drawn dot positions, for hit testing on tap */
    private data class DotHit(val peer: PeerPosition, val cx: Float, val cy: Float)
    private var lastDots: List<DotHit> = emptyList()

    // Slowly rotating radar sweep + a gentle pulse on live peer dots — the
    // only "animation" cost here is a periodic invalidate while the view is
    // actually on screen.
    private var sweepAngle = 0f
    private var pulsePhase = 0f
    private val uiHandler = Handler(Looper.getMainLooper())
    private val tickRunnable = object : Runnable {
        override fun run() {
            sweepAngle = (sweepAngle + 2.2f) % 360f
            pulsePhase = (pulsePhase + 0.07f) % (2f * Math.PI.toFloat())
            invalidate()
            uiHandler.postDelayed(this, 40L)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        uiHandler.post(tickRunnable)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        uiHandler.removeCallbacks(tickRunnable)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val radius = min(w, h) / 2f - 40f
        if (radius <= 0) return

        // range rings
        for (i in 1..3) {
            canvas.drawCircle(cx, cy, radius * i / 3f, ringPaint)
        }

        // rotating radar sweep — a soft teal wedge spinning around the center,
        // purely decorative, gives the "live" feel the radar concept wants
        sweepPaint.shader = SweepGradient(
            cx, cy,
            intArrayOf(Color.TRANSPARENT, Color.parseColor("#2639C5BB"), Color.TRANSPARENT),
            floatArrayOf(0f, 0.04f, 0.18f)
        )
        canvas.save()
        canvas.rotate(sweepAngle, cx, cy)
        canvas.drawCircle(cx, cy, radius, sweepPaint)
        canvas.restore()

        canvas.drawText("N", cx, cy - radius - 12f, northPaint)

        // me, in the middle — a soft breathing glow behind a solid dot
        val centerPulse = 1f + 0.25f * (0.5f + 0.5f * sin(pulsePhase.toDouble()).toFloat())
        canvas.drawCircle(cx, cy, 14f * centerPulse + 8f, centerGlowPaint)
        canvas.drawCircle(cx, cy, 14f, centerPaint)
        canvas.drawText(context.getString(com.ghaly.findme.R.string.map_center_you), cx, cy + 34f, labelPaint)

        val mine = myLocation
        val dots = mutableListOf<DotHit>()
        if (mine != null) {
            for (p in peers) {
                if (p.lat == 0.0 && p.lon == 0.0) continue
                val results = FloatArray(2)
                Location.distanceBetween(mine.first, mine.second, p.lat, p.lon, results)
                val distance = results[0]
                val bearingDeg = results[1]
                val clamped = min(distance, maxRangeMeters)
                val r = (clamped / maxRangeMeters) * radius
                val angleRad = Math.toRadians((bearingDeg - 90).toDouble()) // -90 so 0deg(N) draws upward
                val dx = (r * cos(angleRad)).toFloat()
                val dy = (r * sin(angleRad)).toFloat()
                val px = cx + dx
                val py = cy + dy
                val paint = if (p.connected) peerPaint else peerStalePaint
                if (p.connected) {
                    val peerPulse = 1f + 0.3f * (0.5f + 0.5f * sin((pulsePhase + px).toDouble()).toFloat())
                    canvas.drawCircle(px, py, 12f * peerPulse + 6f, peerGlowPaint)
                }
                canvas.drawCircle(px, py, 12f, paint)
                canvas.drawText(p.name, px, py + 30f, labelPaint)
                dots += DotHit(p, px, py)
            }
        }
        lastDots = dots
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            val x = event.x
            val y = event.y
            val hit = lastDots.minByOrNull { dist(it.cx, it.cy, x, y) }
            if (hit != null && dist(hit.cx, hit.cy, x, y) < 60f) {
                onPeerTap?.invoke(hit.peer)
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        return super.performClick()
    }

    private fun dist(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return sqrt(dx * dx + dy * dy)
    }
}
