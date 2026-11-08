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
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.ghaly.findme.model.PeerPosition
import kotlin.math.cos
import kotlin.math.max
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

    /** Device id of the peer currently being "followed" — a live line is drawn
     *  from you to them for as long as they're in the last-known peer list.
     *  Set/cleared by the fragment from the tap menu. */
    var followedDeviceId: String? = null
        set(value) {
            field = value
            invalidate()
        }

    var maxRangeMeters: Float = 2000f

    // Pinch-to-zoom + one-finger pan. This is still a bearing/distance radar
    // (there's no offline map tile data to show out in the desert), but it's
    // no longer a fixed scale — zoom in to spread out a crowded cluster,
    // pan to look past the edge, double-tap to snap back to center.
    private var scaleFactor = 1f
    private var panX = 0f
    private var panY = 0f

    /** Returns the bearing (degrees, 0 = north) a peer was last seen moving in,
     *  or null if unknown/not enough data — wired up by the fragment. Only
     *  drawn for stale ("last known") peers: once someone's back in direct
     *  range their live dot already says all that matters. */
    var headingOf: ((deviceId: String) -> Float?)? = null

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
    private val headingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F2A541")
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
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
    private val followLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F2A541")
        style = Paint.Style.STROKE
        strokeWidth = 4f
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(18f, 14f), 0f)
    }

    /** last drawn dot positions, for hit testing on tap */
    private data class DotHit(val peer: PeerPosition, val cx: Float, val cy: Float)
    private var lastDots: List<DotHit> = emptyList()

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            scaleFactor = (scaleFactor * detector.scaleFactor).coerceIn(0.5f, 8f)
            invalidate()
            return true
        }
    })
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            scaleFactor = 1f
            panX = 0f
            panY = 0f
            invalidate()
            return true
        }
    })
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var downX = 0f
    private var downY = 0f
    private var isDragging = false

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
        val baseRadius = min(w, h) / 2f - 40f
        if (baseRadius <= 0) return

        // "me" is the anchor of the whole view: pan moves it around the screen,
        // zoom stretches every peer's distance from it.
        val ccx = cx + panX
        val ccy = cy + panY
        val radius = baseRadius * scaleFactor

        // range rings
        for (i in 1..3) {
            canvas.drawCircle(ccx, ccy, radius * i / 3f, ringPaint)
        }

        // rotating radar sweep — a soft teal wedge spinning around the center,
        // purely decorative, gives the "live" feel the radar concept wants
        sweepPaint.shader = SweepGradient(
            ccx, ccy,
            intArrayOf(Color.TRANSPARENT, Color.parseColor("#2639C5BB"), Color.TRANSPARENT),
            floatArrayOf(0f, 0.04f, 0.18f)
        )
        canvas.save()
        canvas.rotate(sweepAngle, ccx, ccy)
        canvas.drawCircle(ccx, ccy, radius, sweepPaint)
        canvas.restore()

        canvas.drawText("N", ccx, ccy - radius - 12f, northPaint)

        val mine = myLocation
        val dots = mutableListOf<DotHit>()
        var followPx: Float? = null
        var followPy: Float? = null
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
                val px = ccx + dx
                val py = ccy + dy
                val paint = if (p.connected) peerPaint else peerStalePaint
                if (p.connected) {
                    val peerPulse = 1f + 0.3f * (0.5f + 0.5f * sin((pulsePhase + px).toDouble()).toFloat())
                    canvas.drawCircle(px, py, 12f * peerPulse + 6f, peerGlowPaint)
                }
                canvas.drawCircle(px, py, 12f, paint)
                if (!p.connected) {
                    val heading = headingOf?.invoke(p.deviceId)
                    if (heading != null) drawHeadingArrow(canvas, px, py, heading)
                }
                canvas.drawText(p.name, px, py + 30f, labelPaint)
                dots += DotHit(p, px, py)
                if (p.deviceId == followedDeviceId) {
                    followPx = px
                    followPy = py
                }
            }
        }
        lastDots = dots

        // the "follow" line — drawn before the center dot so the dot sits on top of it
        if (followPx != null && followPy != null) {
            canvas.drawLine(ccx, ccy, followPx, followPy, followLinePaint)
        }

        // me, in the middle — a soft breathing glow behind a solid dot
        val centerPulse = 1f + 0.25f * (0.5f + 0.5f * sin(pulsePhase.toDouble()).toFloat())
        canvas.drawCircle(ccx, ccy, 14f * centerPulse + 8f, centerGlowPaint)
        canvas.drawCircle(ccx, ccy, 14f, centerPaint)
        canvas.drawText(context.getString(com.ghaly.findme.R.string.map_center_you), ccx, ccy + 34f, labelPaint)
    }

    /** Draws a short arrow out of a stale peer's dot, pointing the direction they
     *  appeared to be moving when last heard from — a "which way they walked off
     *  to" cue, separate from the dot's own position (which way to walk TO reach
     *  their last known spot). */
    private fun drawHeadingArrow(canvas: Canvas, px: Float, py: Float, headingDeg: Float) {
        val angleRad = Math.toRadians((headingDeg - 90).toDouble())
        val dx = cos(angleRad).toFloat()
        val dy = sin(angleRad).toFloat()
        val startR = 18f
        val endR = 36f
        val sx = px + dx * startR
        val sy = py + dy * startR
        val ex = px + dx * endR
        val ey = py + dy * endR
        canvas.drawLine(sx, sy, ex, ey, headingPaint)
        // small arrowhead
        val headAngle1 = angleRad + Math.toRadians(150.0)
        val headAngle2 = angleRad - Math.toRadians(150.0)
        canvas.drawLine(ex, ey, ex + (cos(headAngle1) * 10).toFloat(), ey + (sin(headAngle1) * 10).toFloat(), headingPaint)
        canvas.drawLine(ex, ey, ex + (cos(headAngle2) * 10).toFloat(), ey + (sin(headAngle2) * 10).toFloat(), headingPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                downX = event.x
                downY = event.y
                isDragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == 1 && !scaleDetector.isInProgress) {
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY
                    if (!isDragging && dist(downX, downY, event.x, event.y) > 16f) isDragging = true
                    if (isDragging) {
                        panX += dx
                        panY += dy
                        invalidate()
                    }
                    lastTouchX = event.x
                    lastTouchY = event.y
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!isDragging) {
                    val x = event.x
                    val y = event.y
                    val hit = lastDots.minByOrNull { dist(it.cx, it.cy, x, y) }
                    if (hit != null && dist(hit.cx, hit.cy, x, y) < max(60f, 60f * scaleFactor)) {
                        onPeerTap?.invoke(hit.peer)
                        performClick()
                    }
                }
                isDragging = false
            }
        }
        return true
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
