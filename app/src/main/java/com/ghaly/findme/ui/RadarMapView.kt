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
import com.ghaly.findme.model.SavedPlace
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

    companion object {
        /** The Kaaba, Mecca — published coordinates (21.4225°N, 39.8262°E),
         *  the same precision used by widely-referenced Qibla calculators.
         *  At thousands of kilometers' distance, sub-100m uncertainty in this
         *  point moves the computed bearing by a negligible fraction of a
         *  degree, well under what a phone's own compass can resolve. */
        private const val KAABA_LAT = 21.4225
        private const val KAABA_LON = 39.8262
    }

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

    /** Spots the user marked themselves (current position, campsite, water,
     *  parked vehicle...) — fixed points, not other people. */
    var savedPlaces: List<SavedPlace> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    var onPlaceTap: ((SavedPlace) -> Unit)? = null

    /** Where this phone has physically walked, oldest first — purely local,
     *  recorded from GPS fixes regardless of mesh/SOS state, so there's
     *  always a way back even if nothing else went wrong. */
    var trail: List<Pair<Double, Double>> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    /** Device id of the peer currently being "followed" — a live line is drawn
     *  from you to them for as long as they're in the last-known peer list.
     *  Set/cleared by the fragment from the tap menu. */
    var followedDeviceId: String? = null
        set(value) {
            field = value
            invalidate()
        }

    /** Device ids this phone has a confirmed, mutual location link with (see
     *  LinkRepository) — drawn as a solid, always-on line distinct from the
     *  temporary, one-sided, dashed "Follow" line: a link is a standing
     *  relationship both sides agreed to, not something either person set up
     *  silently just by tapping a dot. */
    var linkedDeviceIds: Set<String> = emptySet()
        set(value) {
            field = value
            invalidate()
        }

    /** Id of the saved place currently being "navigated to" — same idea as
     *  [followedDeviceId] but for a fixed spot instead of a moving person.
     *  Distance is clamped to the edge of the radar just like everything
     *  else here, so a place hundreds of km away still shows the right
     *  direction, it just won't look close. */
    var followedPlaceId: String? = null
        set(value) {
            field = value
            invalidate()
        }

    var maxRangeMeters: Float = 2000f

    /** Toggled on/off by the fragment. When on, draws a fixed-direction arrow
     *  toward the Kaaba (Mecca) using the exact same geodesic bearing
     *  computation ([Location.distanceBetween]) already used for every other
     *  bearing in this view (peers, saved places, the follow lines) — just
     *  pointed at the Kaaba's published coordinates instead of another
     *  phone's GPS fix. Like every bearing drawn here, its on-screen accuracy
     *  still depends on the phone's own compass/magnetometer being correctly
     *  calibrated — that's a property of the device, not of this calculation. */
    var showQibla: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    /** The compass heading the phone is currently pointing, 0 = true/magnetic
     *  north, fed by the fragment from the rotation-vector sensor. When this is
     *  kept updated, the whole radar becomes "heading-up" — whatever the phone
     *  is pointed at is drawn straight up, so a dot to your actual left appears
     *  on the left of the screen and one behind you appears at the bottom,
     *  without the user needing to know which way true north is first. Left at
     *  0 (no sensor available), it falls back to plain north-up. */
    var deviceHeadingDeg: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

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
    /** Solid (not dashed) line to a linked peer — "solid" is deliberate here
     *  too, same reasoning as the Qibla line: a link doesn't come and go the
     *  way a tap-to-follow line does, so it shouldn't look temporary. Violet
     *  so it's never confused with the amber follow line or the teal
     *  place/Qibla lines. */
    private val linkedLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B388EB")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    /** A solid, distinct-colored line for the Qibla direction — deliberately
     *  not dashed like the follow lines, so it doesn't read as "navigating
     *  to a person/place" (which it isn't: it never moves and always points
     *  the same real-world direction regardless of distance). */
    private val qiblaLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#39C5BB")
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val qiblaLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#39C5BB")
        textSize = 24f
        textAlign = Paint.Align.CENTER
    }
    private val placeLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#39C5BB")
        style = Paint.Style.STROKE
        strokeWidth = 4f
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(18f, 14f), 0f)
    }
    private val placePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D8C3A5") // a fixed-spot marker, distinct from a person's dot
        style = Paint.Style.FILL
    }
    private val placeOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0E1B1D")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    /** The breadcrumb trail of where you've physically walked — a thin dotted
     *  line, distinct from the solid-dash "follow"/"navigate to" lines, since
     *  this one isn't pointing anywhere, it's just showing where you've been. */
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#88AFC9C7")
        style = Paint.Style.STROKE
        strokeWidth = 3f
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(6f, 8f), 0f)
    }

    /** last drawn dot positions, for hit testing on tap */
    private data class DotHit(val peer: PeerPosition, val cx: Float, val cy: Float)
    private var lastDots: List<DotHit> = emptyList()

    private data class PlaceHit(val place: SavedPlace, val cx: Float, val cy: Float)
    private var lastPlaceDots: List<PlaceHit> = emptyList()

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            scaleFactor = (scaleFactor * detector.scaleFactor).coerceIn(0.5f, 8f)
            invalidate()
            return true
        }
    })
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            resetView()
            return true
        }
    })

    /** Back to centered-on-me, no zoom — same as the double-tap gesture,
     *  exposed for the small "⊙" re-center button (less discoverable than
     *  a gesture, so it's there as the obvious equivalent). */
    fun resetView() {
        scaleFactor = 1f
        panX = 0f
        panY = 0f
        invalidate()
    }
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
        // Everything below is computed from live GPS fixes and whatever
        // peers/saved places/trail happen to be in memory right now — any one
        // bad value (a stray NaN from a malformed coordinate, say) used to be
        // able to crash this draw call, and because it's the view redrawing
        // on every tick, that meant the map was unusable every single time it
        // was opened rather than just a one-off glitch. One bad frame now
        // just gets skipped; the next tick tries again with whatever's
        // changed by then.
        try {
            drawRadar(canvas)
        } catch (e: Exception) {
            // skip this frame — see comment above
        }
    }

    private fun drawRadar(canvas: Canvas) {
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

        // All four cardinal points rotate with the phone's own heading in
        // heading-up mode (deviceHeadingDeg != 0) instead of sitting fixed on
        // screen — they're the reference points letting the user check the
        // view still makes sense, and line up with a real compass without
        // needing to know which way true north is first.
        for ((bearingDeg, labelRes) in listOf(
            0f to com.ghaly.findme.R.string.compass_label_north,
            90f to com.ghaly.findme.R.string.compass_label_east,
            180f to com.ghaly.findme.R.string.compass_label_south,
            270f to com.ghaly.findme.R.string.compass_label_west
        )) {
            val angleRad = Math.toRadians((bearingDeg - deviceHeadingDeg - 90).toDouble())
            val lx = ccx + ((radius + 24f) * cos(angleRad)).toFloat()
            val ly = ccy + ((radius + 24f) * sin(angleRad)).toFloat()
            canvas.drawText(context.getString(labelRes), lx, ly + 8f, northPaint)
        }

        val mine = myLocation

        // Qibla direction — a fixed real-world bearing to the Kaaba, computed
        // with the same Location.distanceBetween geodesic bearing used for
        // every peer/place in this view. Always drawn at the very edge
        // (there's no meaningful "distance to clamp" for something this far
        // away), as a solid line distinct from the dashed follow lines.
        if (mine != null && showQibla) {
            val results = FloatArray(2)
            Location.distanceBetween(mine.first, mine.second, KAABA_LAT, KAABA_LON, results)
            val bearingDeg = results[1]
            val angleRad = Math.toRadians((bearingDeg - deviceHeadingDeg - 90).toDouble())
            val qx = ccx + (radius * cos(angleRad)).toFloat()
            val qy = ccy + (radius * sin(angleRad)).toFloat()
            canvas.drawLine(ccx, ccy, qx, qy, qiblaLinePaint)
            val lx = ccx + ((radius + 24f) * cos(angleRad)).toFloat()
            val ly = ccy + ((radius + 24f) * sin(angleRad)).toFloat()
            canvas.drawText(context.getString(com.ghaly.findme.R.string.qibla_map_label), lx, ly + 8f, qiblaLabelPaint)
        }

        val dots = mutableListOf<DotHit>()
        var followPx: Float? = null
        var followPy: Float? = null
        val linkedPositions = mutableListOf<Pair<Float, Float>>()
        if (mine != null) {
            for (p in peers) {
                if (p.lat == 0.0 && p.lon == 0.0) continue
                val results = FloatArray(2)
                Location.distanceBetween(mine.first, mine.second, p.lat, p.lon, results)
                val distance = results[0]
                val bearingDeg = results[1]
                val clamped = min(distance, maxRangeMeters)
                val r = (clamped / maxRangeMeters) * radius
                val angleRad = Math.toRadians((bearingDeg - deviceHeadingDeg - 90).toDouble()) // minus device heading so the view is heading-up, minus 90 so 0deg(N) draws upward
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
                if (p.deviceId in linkedDeviceIds) {
                    linkedPositions += px to py
                }
            }
        }
        lastDots = dots

        // saved places — fixed spots, not people. Distance is clamped to the
        // radar edge the same way a peer's is, so a place hundreds of km away
        // (another mining site, say) still points the right direction even
        // though it can't possibly be drawn at true scale.
        val placeDots = mutableListOf<PlaceHit>()
        var followPlacePx: Float? = null
        var followPlacePy: Float? = null
        if (mine != null) {
            for (place in savedPlaces) {
                val results = FloatArray(2)
                Location.distanceBetween(mine.first, mine.second, place.lat, place.lon, results)
                val distance = results[0]
                val bearingDeg = results[1]
                val clamped = min(distance, maxRangeMeters)
                val r = (clamped / maxRangeMeters) * radius
                val angleRad = Math.toRadians((bearingDeg - deviceHeadingDeg - 90).toDouble())
                val px = ccx + (r * cos(angleRad)).toFloat()
                val py = ccy + (r * sin(angleRad)).toFloat()
                // small diamond marker so it reads as "a fixed spot", not a person
                canvas.save()
                canvas.rotate(45f, px, py)
                canvas.drawRect(px - 9f, py - 9f, px + 9f, py + 9f, placePaint)
                canvas.drawRect(px - 9f, py - 9f, px + 9f, py + 9f, placeOutlinePaint)
                canvas.restore()
                canvas.drawText(place.name, px, py + 30f, labelPaint)
                placeDots += PlaceHit(place, px, py)
                if (place.id == followedPlaceId) {
                    followPlacePx = px
                    followPlacePy = py
                }
            }
        }
        lastPlaceDots = placeDots

        // breadcrumb trail of where you've walked — same bearing/distance/clamp
        // transform as everything else, connected point-to-point. Drawn before
        // the follow lines and center dot so those sit on top of it.
        if (mine != null && trail.size >= 2) {
            val path = android.graphics.Path()
            var started = false
            for ((lat, lon) in trail) {
                val results = FloatArray(2)
                Location.distanceBetween(mine.first, mine.second, lat, lon, results)
                val clamped = min(results[0], maxRangeMeters)
                val r = (clamped / maxRangeMeters) * radius
                val angleRad = Math.toRadians((results[1] - deviceHeadingDeg - 90).toDouble())
                val px = ccx + (r * cos(angleRad)).toFloat()
                val py = ccy + (r * sin(angleRad)).toFloat()
                if (!started) { path.moveTo(px, py); started = true } else path.lineTo(px, py)
            }
            canvas.drawPath(path, trailPaint)
        }

        // standing location-link lines — drawn before the (possibly
        // overlapping) follow line so a temporary follow on top of an
        // existing link still reads clearly as both.
        for ((lx, ly) in linkedPositions) {
            canvas.drawLine(ccx, ccy, lx, ly, linkedLinePaint)
        }

        // the "follow"/"navigate to" lines — drawn before the center dot so it sits on top
        if (followPx != null && followPy != null) {
            canvas.drawLine(ccx, ccy, followPx, followPy, followLinePaint)
        }
        if (followPlacePx != null && followPlacePy != null) {
            canvas.drawLine(ccx, ccy, followPlacePx, followPlacePy, placeLinePaint)
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
        val angleRad = Math.toRadians((headingDeg - deviceHeadingDeg - 90).toDouble())
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
                    val threshold = max(60f, 60f * scaleFactor)
                    val peerHit = lastDots.minByOrNull { dist(it.cx, it.cy, x, y) }
                        ?.takeIf { dist(it.cx, it.cy, x, y) < threshold }
                    val placeHit = lastPlaceDots.minByOrNull { dist(it.cx, it.cy, x, y) }
                        ?.takeIf { dist(it.cx, it.cy, x, y) < threshold }
                    when {
                        peerHit != null && (placeHit == null || dist(peerHit.cx, peerHit.cy, x, y) <= dist(placeHit.cx, placeHit.cy, x, y)) -> {
                            onPeerTap?.invoke(peerHit.peer)
                            performClick()
                        }
                        placeHit != null -> {
                            onPlaceTap?.invoke(placeHit.place)
                            performClick()
                        }
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
