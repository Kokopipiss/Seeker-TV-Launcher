package com.seeker.tv

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Picture
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.animation.PathInterpolator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The whole home screen, drawn on one canvas in 1920x1080 reference pixels and scaled to the view.
 * Layout numbers, colors and timings are ported from design/seeker-tv-mockup-v3.html.
 */
@SuppressLint("ViewConstructor")
class SeekerView(ctx: Context, private val host: Host) : View(ctx) {

    interface Host {
        fun launch(entry: AppEntry)
        fun openSettings()             // the gear
        fun hide(entry: AppEntry)
        fun saveOrder(keys: List<String>)
        fun firstFrame()
        fun back()                     // BACK on the wheel: ends a Flap APP LIST visit
    }

    private companion object {
        const val W = 1920f
        const val H = 1080f
        const val BG = 0xFF0B0C0E.toInt()
        const val GRID_DOT = 0xFF16181C.toInt()
        const val DATE_COLOR = 0xFF8B9098.toInt()
        const val CELL = 10f        // app name cell
        const val SP = 118f         // wheel slot spacing
        const val LIST_X = 150f
        const val CENTER_Y = 540f
        const val CURSOR_X = 101f   // css: left 96, width 10
        const val GEAR_CELL = 8f
        const val GEAR_X = W - 150f - 9 * GEAR_CELL  // 1698
        const val GEAR_Y = H - 150f - 9 * GEAR_CELL  // 858
        const val LONG_PRESS_MS = 600L
        const val LAUNCH_AT_MS = 900L  // spec v4: name screen shows longer before the app covers it
        const val MENU_CELL = 6f
        const val MENU_PAD = 26f
        const val MENU_GAP = 22f
        const val PANEL_BG = 0xFF0E0F12.toInt()
        const val LINE = 0xFF1F2126.toInt()
        val MENU = listOf("MOVE", "HIDE", "CANCEL")
    }

    // css cubic-beziers from the mockup
    private val wheelEase = PathInterpolator(.2f, .7f, .2f, 1f)
    private val takeoverEase = PathInterpolator(.3f, 0f, .1f, 1f)
    private val cssEase = PathInterpolator(.25f, .1f, .25f, 1f)
    private val easeInOut = PathInterpolator(.42f, 0f, .58f, 1f)

    /**
     * Android stretches every ValueAnimator by animator_duration_scale (0.5 on this TV), which would
     * halve the mockup's timings. Divide it back out so 300 ms means 300 ms.
     */
    private fun ms(d: Long): Long {
        val s = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        return if (s > 0f) (d / s).toLong() else d
    }

    // ---------- state ----------
    var apps: List<AppEntry> = emptyList(); private set
    private var sel = 0
    private var onGear = false
    private var busy = false
    private var centerDown = false
    private var menuOpen = false
    private var menuSel = 0
    private var menuAlpha = 0f
    private var menuAnim: ValueAnimator? = null
    private var reorder = false
    private var bob = 0f
    private val bobAnim = ValueAnimator.ofFloat(0f, 1f).apply {
        repeatCount = ValueAnimator.INFINITE
        interpolator = null
        addUpdateListener {
            // css: @keyframes bob, -10px / +10px / -10px over 1 s, ease-in-out per half
            val t = it.animatedFraction
            bob = -10f + 20f * easeInOut.getInterpolation(if (t < .5f) 2 * t else 2 - 2 * t)
            invalidate()
        }
    }
    private val longPress = Runnable { if (centerDown) { centerDown = false; openMenu() } }
    private var greeting = "HELLO STELIOS"
    private var weather = Weather.Now("cloud", "--°", 0)

    private val tz = TimeZone.getDefault()
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.US).apply { timeZone = tz }
    private val dateFmt = SimpleDateFormat("EEEE d MMMM", Locale.US).apply { timeZone = tz }

    // per-row animated transform (x offset, center y, scale, opacity), css-transition style
    private var curX = FloatArray(0); private var curY = FloatArray(0)
    private var curS = FloatArray(0); private var curA = FloatArray(0)
    private var fromX = FloatArray(0); private var fromY = FloatArray(0)
    private var fromS = FloatArray(0); private var fromA = FloatArray(0)
    private var toX = FloatArray(0); private var toY = FloatArray(0)
    private var toS = FloatArray(0); private var toA = FloatArray(0)
    private var rowState = IntArray(0)   // 0 = selected, 1 = near, 2 = far
    private val wheelAnim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 300
        interpolator = null
        addUpdateListener { stepWheel(it.animatedFraction) }
    }

    private val argb = ArgbEvaluator()
    private var cursorColor = Dots.FG
    private var cursorAnim: ValueAnimator? = null
    private var glowColor = Dots.FG
    private var glowAnim: ValueAnimator? = null
    private var glowCorner = false
    private var glowShader: RadialGradient? = null
    private var glowShaderKey = 0L

    // launch takeover
    private var takeoverScale = 0f
    private var takeoverX = LIST_X
    private var takeoverY = CENTER_Y
    private var takeoverColor = Dots.FG
    private var launchAlpha = 0f
    private var launchName = ""
    private var launchIcon: Drawable? = null
    private var launchAnims = ArrayList<ValueAnimator>()

    // ---------- paints & caches ----------
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gridPaint = Paint().apply {
        val tile = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
        Canvas(tile).drawCircle(12f, 12f, 1.7f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GRID_DOT })
        shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).apply {
            setLocalMatrix(Matrix().apply { setTranslate(12f, 12f) })  // css background-position 12px
        }
    }
    private val glowPaint = Paint()
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        pathEffect = DashPathEffect(floatArrayOf(6f, 6f), 0f)
    }
    private val linePaint = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 1f; color = LINE }
    private val monoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, 500, false)
        textSize = 22f
        letterSpacing = 0.3f
        textAlign = Paint.Align.CENTER
    }
    private var panel: Picture? = null
    private var firstFrameSent = false

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(BG)
    }

    // ---------- public API ----------
    /** New wheel contents. With [animate], rows that stay glide to their new slots (used by HIDE). */
    fun setApps(list: List<AppEntry>, animate: Boolean = false, keepSel: Int = -1) {
        val keepKey = apps.getOrNull(sel)?.key
        val oldIdx = apps.withIndex().associate { (i, a) -> a.key to i }
        val oX = curX; val oY = curY; val oS = curS; val oA = curA
        apps = list
        val n = list.size
        curX = FloatArray(n); curY = FloatArray(n); curS = FloatArray(n); curA = FloatArray(n)
        fromX = FloatArray(n); fromY = FloatArray(n); fromS = FloatArray(n); fromA = FloatArray(n)
        toX = FloatArray(n); toY = FloatArray(n); toS = FloatArray(n); toA = FloatArray(n)
        rowState = IntArray(n)
        sel = if (keepSel >= 0) min(keepSel, max(0, n - 1))
            else list.indexOfFirst { it.key == keepKey }.takeIf { it >= 0 } ?: min(sel, max(0, n - 1))
        if (animate) {
            layoutWheel(animate = false)
            for (i in list.indices) {
                val o = oldIdx[list[i].key] ?: continue
                curX[i] = oX[o]; curY[i] = oY[o]; curS[i] = oS[o]; curA[i] = oA[o]
            }
            layoutWheel(animate = true)
        } else {
            layoutWheel(animate = false)
        }
    }

    fun setGreeting(g: String) { greeting = g; panel = null; invalidate() }
    fun setWeather(w: Weather.Now) { weather = w; panel = null; invalidate() }
    fun tick() { panel = null; invalidate() }

    /** Called when we come back to the front (or the launch never happened): clear the takeover. */
    fun resetLaunch() {
        launchAnims.forEach { it.cancel() }
        launchAnims.clear()
        takeoverScale = 0f
        launchAlpha = 0f
        launchIcon = null
        busy = false
        centerDown = false
        removeCallbacks(longPress)
        invalidate()
    }

    /** Leaving the screen: close the item menu, drop out of reorder mode (saving the order). */
    fun resetModes() {
        if (menuOpen) closeMenu(animate = false)
        if (reorder) setReorder(false)
    }

    val isLaunching get() = busy

    // ---------- wheel layout (mockup layout()) ----------
    private fun layoutWheel(animate: Boolean) {
        for (i in apps.indices) {
            val off = i - sel
            val a = abs(off).toFloat()
            toY[i] = CENTER_Y + off * SP
            toS[i] = max(0.42f, 1 - a * 0.14f)
            toX[i] = a * a * 14
            toA[i] = if (a > 2) 0f else 1 - a * 0.22f
            rowState[i] = if (off == 0) (if (onGear) 1 else 0) else if (a > 2) 2 else 1
        }
        if (animate) {
            curX.copyInto(fromX); curY.copyInto(fromY); curS.copyInto(fromS); curA.copyInto(fromA)
            wheelAnim.cancel()
            wheelAnim.duration = ms(300)
            wheelAnim.start()
        } else {
            toX.copyInto(curX); toY.copyInto(curY); toS.copyInto(curS); toA.copyInto(curA)
        }
        val app = apps.getOrNull(sel)
        Toaster.brand = app?.color ?: Dots.FG
        animateCursor(app?.color ?: Dots.FG, animate)
        glowCorner = onGear
        animateGlow(if (onGear) Apps.SETTINGS_COLOR else app?.color ?: Dots.FG, animate)
        invalidate()
    }

    private fun stepWheel(t: Float) {
        val e = wheelEase.getInterpolation(t)
        val o = cssEase.getInterpolation(t)
        for (i in apps.indices) {
            curX[i] = fromX[i] + (toX[i] - fromX[i]) * e
            curY[i] = fromY[i] + (toY[i] - fromY[i]) * e
            curS[i] = fromS[i] + (toS[i] - fromS[i]) * e
            curA[i] = fromA[i] + (toA[i] - fromA[i]) * o
        }
        invalidate()
    }

    private fun animateCursor(target: Int, animate: Boolean) {
        cursorAnim?.cancel()
        if (!animate) { cursorColor = target; return }
        val from = cursorColor
        cursorAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ms(220)
            interpolator = cssEase
            addUpdateListener { cursorColor = argb.evaluate(it.animatedFraction, from, target) as Int; invalidate() }
            start()
        }
    }

    private fun animateGlow(target: Int, animate: Boolean) {
        glowAnim?.cancel()
        if (!animate) { glowColor = target; return }
        val from = glowColor
        glowAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ms(350)
            interpolator = cssEase
            addUpdateListener { glowColor = argb.evaluate(it.animatedFraction, from, target) as Int; invalidate() }
            start()
        }
    }

    // ---------- remote ----------
    fun onKey(e: KeyEvent): Boolean {
        val code = e.keyCode
        val isCenter = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER ||
            code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_BUTTON_A
        val handled = isCenter || code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_DPAD_UP ||
            code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
        if (!handled) return false
        if (busy) return true

        if (e.action == KeyEvent.ACTION_UP) {
            if (isCenter && centerDown) { centerDown = false; removeCallbacks(longPress); go() }
            return true
        }
        if (e.action != KeyEvent.ACTION_DOWN || e.repeatCount > 0) return true  // mockup ignores key repeat

        if (menuOpen) {
            when (code) {
                KeyEvent.KEYCODE_DPAD_DOWN -> if (menuSel < MENU.size - 1) { menuSel++; invalidate() }
                KeyEvent.KEYCODE_DPAD_UP -> if (menuSel > 0) { menuSel--; invalidate() }
                KeyEvent.KEYCODE_BACK -> closeMenu()
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> Unit
                else -> pickMenu()
            }
            return true
        }
        if (reorder) {
            when (code) {
                KeyEvent.KEYCODE_DPAD_DOWN -> if (sel < apps.size - 1) { swap(sel, sel + 1); sel++; layoutWheel(true) }
                KeyEvent.KEYCODE_DPAD_UP -> if (sel > 0) { swap(sel, sel - 1); sel--; layoutWheel(true) }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> Unit
                else -> setReorder(false)  // OK or BACK drops the app and saves
            }
            return true
        }

        when (code) {
            KeyEvent.KEYCODE_DPAD_DOWN -> if (!onGear && sel < apps.size - 1) { sel++; layoutWheel(true) }
            KeyEvent.KEYCODE_DPAD_UP -> if (!onGear && sel > 0) { sel--; layoutWheel(true) }
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (Milestone.M2 && !onGear) { onGear = true; layoutWheel(true) }
            KeyEvent.KEYCODE_DPAD_LEFT -> if (onGear) { onGear = false; layoutWheel(true) }
            KeyEvent.KEYCODE_BACK -> host.back()  // nothing in WHEEL mode; Flap's START after an APP LIST visit
            else -> if (onGear) host.openSettings() else if (apps.isNotEmpty()) {
                // wheel launches on key up; held 600 ms = item menu
                centerDown = true
                postDelayed(longPress, LONG_PRESS_MS)
            }
        }
        return true
    }

    // ---------- item menu + reorder (spec v4) ----------
    private fun openMenu() {
        if (apps.isEmpty()) return
        menuOpen = true
        menuSel = 0
        fadeMenu(1f, true)
    }

    private fun closeMenu(animate: Boolean = true) {
        menuOpen = false
        fadeMenu(0f, animate)
    }

    private fun fadeMenu(to: Float, animate: Boolean) {
        menuAnim?.cancel()
        if (!animate) { menuAlpha = to; invalidate(); return }
        val from = menuAlpha
        menuAnim = ValueAnimator.ofFloat(from, to).apply {
            duration = ms(160)
            interpolator = cssEase
            addUpdateListener { menuAlpha = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun pickMenu() {
        val app = apps.getOrNull(sel)
        closeMenu(animate = false)
        app ?: return
        when (MENU[menuSel]) {
            "MOVE" -> setReorder(true)
            "HIDE" -> {
                host.hide(app)
                setApps(apps.filter { it.key != app.key }, animate = true, keepSel = sel)
            }
        }
    }

    private fun setReorder(on: Boolean) {
        reorder = on
        if (on) {
            bobAnim.duration = ms(1000)
            bobAnim.start()
        } else {
            bobAnim.cancel()
            bob = 0f
            host.saveOrder(apps.map { it.key })
        }
        invalidate()
    }

    private fun swap(i: Int, j: Int) {
        apps = apps.toMutableList().also { val t = it[i]; it[i] = it[j]; it[j] = t }
        for (arr in arrayOf(curX, curY, curS, curA)) { val t = arr[i]; arr[i] = arr[j]; arr[j] = t }
    }

    // ---------- launch takeover (mockup go()) ----------
    private fun go() {
        if (busy) return
        val entry = apps.getOrNull(sel) ?: return
        if (!Milestone.M2) { host.launch(entry); return }
        busy = true
        takeoverColor = entry.color
        takeoverX = LIST_X
        takeoverY = CENTER_Y
        launchName = entry.dots
        launchIcon = entry.component?.let { Apps.iconOf(context, it) }

        launchAnims.forEach { it.cancel() }
        launchAnims.clear()
        launchAnims += ValueAnimator.ofFloat(0f, 130f).apply {
            duration = ms(550)
            interpolator = takeoverEase
            addUpdateListener { takeoverScale = it.animatedValue as Float; invalidate() }
        }
        launchAnims += ValueAnimator.ofFloat(0f, 1f).apply {
            startDelay = ms(350)
            duration = ms(250)
            interpolator = cssEase
            addUpdateListener { launchAlpha = it.animatedValue as Float; invalidate() }
        }
        launchAnims.forEach { it.start() }
        postDelayed({ if (busy) host.launch(entry) }, LAUNCH_AT_MS)  // app comes up behind the color
    }

    private fun lum(c: Int) = (0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c)) / 255f

    // ---------- drawing ----------
    override fun onDraw(canvas: Canvas) {
        canvas.save()
        canvas.scale(width / W, height / H)
        canvas.drawRect(0f, 0f, W, H, gridPaint)
        drawGlow(canvas)
        drawPanel(canvas)
        drawCursor(canvas)
        drawWheel(canvas)
        drawMenu(canvas)
        if (Milestone.M2) Dots.icon(canvas, "gear", GEAR_X, GEAR_Y, GEAR_CELL, if (onGear) Dots.FG else Dots.DIM, true)
        drawTakeover(canvas)
        canvas.restore()
        if (!firstFrameSent) { firstFrameSent = true; post { host.firstFrame() } }
    }

    private fun drawGlow(c: Canvas) {
        val key = (glowColor.toLong() shl 1) or (if (glowCorner) 1L else 0L)
        if (glowShader == null || key != glowShaderKey) {
            val cx = if (glowCorner) W else 0f
            val cy = if (glowCorner) H else CENTER_Y
            // radial-gradient(900px 700px at ..., color 14%, transparent 70%)
            glowShader = RadialGradient(
                cx, cy, 900f,
                intArrayOf(Dots.fade(glowColor, 0.14f), Dots.fade(glowColor, 0f)),
                floatArrayOf(0f, 0.7f), Shader.TileMode.CLAMP,
            ).apply { setLocalMatrix(Matrix().apply { setScale(1f, 700f / 900f, cx, cy) }) }
            glowShaderKey = key
            glowPaint.shader = glowShader
        }
        c.drawRect(0f, 0f, W, H, glowPaint)
    }

    /** Greeting / time / date / weather, right-aligned at x=1770 from y=120. Re-recorded once a minute. */
    private fun drawPanel(c: Canvas) {
        val pic = panel ?: Picture().also { p ->
            val pc = p.beginRecording(W.toInt(), H.toInt())
            val right = W - 150
            var y = 120f
            val gc = minOf(12f, 1000f / (greeting.length * 6))  // long NAMEs shrink instead of running into the wheel
            if (Milestone.M2) Dots.text(pc, greeting, right - Dots.textW(greeting, gc), y, gc, Dots.FG, true)
            y += 7 * gc + 54
            val hc = 11f
            val now = Date()
            val t = timeFmt.format(now).uppercase(Locale.US)
            Dots.text(pc, t, right - Dots.textW(t, hc), y, hc, Dots.FG, true)
            y += 7 * hc + 26
            if (!Milestone.M2) { p.endRecording(); panel = p; c.drawPicture(p); return }
            val dc = 5f
            val d = dateFmt.format(now).uppercase(Locale.US)
            Dots.text(pc, d, right - Dots.textW(d, dc), y, dc, DATE_COLOR, true)
            y += 7 * dc + 34
            val ic = 7f
            val tw = Dots.textW(weather.temp, ic)
            Dots.text(pc, weather.temp, right - tw, y + 7, ic, Dots.FG, true)
            Dots.icon(pc, weather.icon, right - tw - 30 - 9 * ic, y, ic, Dots.FG, true)
            p.endRecording()
            panel = p
        }
        c.drawPicture(pic)
    }

    private fun drawCursor(c: Canvas) {
        val alpha = if (onGear) 0.25f else 1f
        val cy = CENTER_Y + bob
        // box-shadow 0 0 18px: a soft halo out to ~23 px
        fill.shader = RadialGradient(
            CURSOR_X, cy, 24f,
            intArrayOf(Dots.fade(cursorColor, 0.75f * alpha), Dots.fade(cursorColor, 0.3f * alpha), Dots.fade(cursorColor, 0f)),
            floatArrayOf(0.15f, 0.45f, 1f), Shader.TileMode.CLAMP,
        )
        c.drawCircle(CURSOR_X, cy, 24f, fill)
        fill.shader = null
        fill.color = Dots.fade(cursorColor, alpha)
        c.drawCircle(CURSOR_X, cy, 5f, fill)
    }

    private fun drawWheel(c: Canvas) {
        for (i in apps.indices) {
            val op = curA[i]
            if (op < 0.01f) continue
            val app = apps[i]
            c.save()
            c.translate(LIST_X + curX[i], curY[i])  // transform-origin 0 50%
            c.scale(curS[i], curS[i])
            val top = -3.5f * CELL
            when (rowState[i]) {
                0 -> {
                    val parts = app.parts
                    if (parts != null) {
                        var x = 0f
                        for ((txt, col) in parts) {
                            Dots.text(c, txt, x, top, CELL, col, true, Dots.SEL_GRID, op)
                            x += txt.length * CELL * 6
                        }
                    } else {
                        Dots.text(c, app.dots, 0f, top, CELL, app.color, true, Dots.SEL_GRID, op)
                    }
                }
                1 -> Dots.text(c, app.dots, 0f, top, CELL, Dots.MUTE, false, opacity = op)
                else -> Dots.text(c, app.dots, 0f, top, CELL, Dots.DIM, false, opacity = op)
            }
            if (reorder && i == sel) {
                // css: outline 2px dashed brand, outline-offset 16px, radius 4
                framePaint.color = app.color
                val w = Dots.textW(app.dots, CELL)
                c.drawRoundRect(-17f, top - 17f, w + 17f, top + 7 * CELL + 17f, 4f, 4f, framePaint)
            }
            c.restore()
        }
    }

    /** MOVE / HIDE / CANCEL in dots, boxed like the toast, just right of the selected name. */
    private fun drawMenu(c: Canvas) {
        if (menuAlpha <= 0f) return
        val app = apps.getOrNull(sel) ?: return
        val a = menuAlpha
        val rowH = 7 * MENU_CELL
        val textX = 40f
        val bw = textX + Dots.textW("CANCEL", MENU_CELL) + MENU_PAD + 6
        val bh = MENU_PAD * 2 + MENU.size * rowH + (MENU.size - 1) * MENU_GAP
        val left = LIST_X + Dots.textW(app.dots, CELL) + 64
        val top = CENTER_Y - bh / 2
        fill.color = Dots.fade(PANEL_BG, a)
        c.drawRect(left, top, left + bw, top + bh, fill)
        linePaint.color = Dots.fade(LINE, a)
        c.drawRect(left + .5f, top + .5f, left + bw - .5f, top + bh - .5f, linePaint)
        fill.color = Dots.fade(app.color, a)
        c.drawRect(left, top, left + 4, top + bh, fill)
        for ((i, label) in MENU.withIndex()) {
            val y = top + MENU_PAD + i * (rowH + MENU_GAP)
            if (i == menuSel) {
                fill.color = Dots.fade(app.color, a)
                c.drawCircle(left + 22, y + rowH / 2, 4f, fill)
                Dots.text(c, label, left + textX, y, MENU_CELL, app.color, true, Dots.SEL_GRID, a)
            } else {
                Dots.text(c, label, left + textX, y, MENU_CELL, Dots.MUTE, false, opacity = a)
            }
        }
    }

    private fun drawTakeover(c: Canvas) {
        if (takeoverScale <= 0f) return
        fill.color = takeoverColor
        c.drawCircle(takeoverX, takeoverY, 20f * takeoverScale, fill)
        if (launchAlpha <= 0f) return
        val light = lum(takeoverColor) > 0.45f
        val ink = if (light) BG else Color.WHITE
        val faint = if (light) 0x2E000000 else 0x38FFFFFF  // rgba(0,0,0,.18) / rgba(255,255,255,.22)
        val name = launchName
        val cell = if (name.length > 8) 18f else 24f
        val w = Dots.textW(name, cell)
        val nameTop = CENTER_Y - 7 * cell / 2
        Dots.text(c, name, (W - w) / 2, nameTop, cell, ink, true, faint, launchAlpha)
        monoPaint.color = Dots.fade(ink, launchAlpha)
        c.drawText("OPENING", W / 2, CENTER_Y + 7 * cell / 2 + 70, monoPaint)
        launchIcon?.let { d ->
            val iw = d.intrinsicWidth.toFloat()
            val ih = d.intrinsicHeight.toFloat()
            val wide = iw > 0 && ih > 0 && iw / ih > 1.3f
            val bw = if (wide) 400f else 192f
            val bh = if (wide) bw * ih / iw else 192f
            val bottom = nameTop - 56
            d.setBounds(((W - bw) / 2).toInt(), (bottom - bh).toInt(), ((W + bw) / 2).toInt(), bottom.toInt())
            d.alpha = (255 * launchAlpha).toInt()
            d.draw(c)
        }
    }
}
