package com.cypress.watch.face

import android.graphics.*
import kotlin.math.*
import kotlin.random.Random

/** The 20 Cypress watch faces: each is a background drawn to fit the watch's screen plus an accent colour for the digits. */
object FaceDesigns {
    class Design(val name: String, val accent: Int, val draw: (Canvas, Int, Int, Random) -> Unit)

    private fun c(hex: Long) = hex.toInt()
    private fun lin(w: Int, h: Int, vararg cols: Int) = LinearGradient(0f, 0f, w * 0.3f, h.toFloat(), cols, null, Shader.TileMode.CLAMP)
    private fun fill(cv: Canvas, shader: Shader) = cv.drawPaint(Paint().apply { this.shader = shader })
    private fun p(color: Int, stroke: Float = 0f, a: Int = 255) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; alpha = a; if (stroke > 0) { style = Paint.Style.STROKE; strokeWidth = stroke }
    }

    val all: List<Design> = listOf(
        Design("Midnight", c(0xFF8FB0FF)) { cv, w, h, _ -> fill(cv, lin(w, h, c(0xFF0B1230), c(0xFF02040A))) },
        Design("Carbon fiber", c(0xFFE8ECF2)) { cv, w, h, _ ->
            cv.drawColor(c(0xFF0D0E10))
            val a = p(c(0xFF1B1D21)); val b = p(c(0xFF141518)); val s = 6f
            var y = 0f; var row = 0
            while (y < h) { var x = if (row % 2 == 0) 0f else s; while (x < w) { cv.drawRect(x, y, x + s, y + s / 2, a); cv.drawRect(x, y + s / 2, x + s, y + s, b); x += s * 2 }; y += s; row++ }
        },
        Design("Topo", c(0xFFF2C14E)) { cv, w, h, r ->
            cv.drawColor(c(0xFF141A14))
            val cx = w * r.nextFloat(); val cy = h * r.nextFloat()
            for (i in 1..28) {
                val path = Path(); val rad = i * min(w, h) / 14f
                for (k in 0..72) {
                    val a = k / 72f * 2 * PI.toFloat()
                    val wob = 1 + 0.18f * sin(a * 3 + i * 0.7f) + 0.08f * cos(a * 5 + i)
                    val x = cx + cos(a) * rad * wob; val y = cy + sin(a) * rad * wob * 1.1f
                    if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                cv.drawPath(path, p(c(0xFF3D5A3D), 1.4f, if (i % 5 == 0) 255 else 150))
            }
        },
        Design("Woodland camo", c(0xFFE6DFC8)) { cv, w, h, r -> camo(cv, w, h, r, intArrayOf(c(0xFF4B5320), c(0xFF6B5B3A), c(0xFF2E3A1F), c(0xFF1E1E16))) },
        Design("Digital camo", c(0xFFDCE3E8)) { cv, w, h, r ->
            val cols = intArrayOf(c(0xFF3F4A52), c(0xFF5C6B73), c(0xFF2A3238), c(0xFF7A8890))
            val s = max(4, w / 40)
            for (y in 0 until h step s) for (x in 0 until w step s) {
                val n = (sin(x * 0.03 + y * 0.02) + sin(x * 0.011 - y * 0.027) + r.nextDouble() * 0.6)
                cv.drawRect(x.toFloat(), y.toFloat(), (x + s).toFloat(), (y + s).toFloat(), p(cols[((n + 2) * 1.2).toInt().coerceIn(0, 3)]))
            }
        },
        Design("Synthwave", c(0xFFFF4FD8)) { cv, w, h, _ ->
            fill(cv, LinearGradient(0f, 0f, 0f, h.toFloat(), intArrayOf(c(0xFF12002B), c(0xFF3B0A57), c(0xFF0A0014)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP))
            val hor = h * 0.62f
            cv.drawCircle(w / 2f, hor, w * 0.28f, Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = LinearGradient(0f, hor - w * 0.28f, 0f, hor, c(0xFFFFD34E), c(0xFFFF3E9A), Shader.TileMode.CLAMP) })
            cv.drawRect(0f, hor, w.toFloat(), h.toFloat(), p(c(0xFF0A0014)))
            val g = p(c(0xFF00E5FF), 1.5f, 200)
            for (i in 0..12) { val y = hor + (h - hor) * (i / 12f).pow(1.8f); cv.drawLine(0f, y, w.toFloat(), y, g) }
            for (i in -10..10) cv.drawLine(w / 2f + i * w * 0.05f, hor, w / 2f + i * w * 0.35f, h.toFloat(), g)
        },
        Design("Sunset dunes", c(0xFFFFE3B0)) { cv, w, h, _ ->
            fill(cv, LinearGradient(0f, 0f, 0f, h.toFloat(), intArrayOf(c(0xFF2B1B4A), c(0xFFE0605A), c(0xFFF7B267)), floatArrayOf(0f, 0.55f, 0.75f), Shader.TileMode.CLAMP))
            for ((i, col) in listOf(c(0xFF8C4A2F), c(0xFF6A3522), c(0xFF45210F)).withIndex()) {
                val path = Path(); val base = h * (0.68f + i * 0.1f)
                path.moveTo(0f, h.toFloat()); path.lineTo(0f, base)
                for (x in 0..w step 4) path.lineTo(x.toFloat(), base - sin(x / w.toFloat() * PI.toFloat() * (1.2f + i * 0.5f) + i) * h * 0.05f)
                path.lineTo(w.toFloat(), h.toFloat()); path.close(); cv.drawPath(path, p(col))
            }
        },
        Design("Deep ocean", c(0xFF7FE7FF)) { cv, w, h, r ->
            fill(cv, LinearGradient(0f, 0f, 0f, h.toFloat(), c(0xFF0A4A6E), c(0xFF020B16), Shader.TileMode.CLAMP))
            repeat(7) { val x = w * r.nextFloat(); cv.drawLine(x, 0f, x + w * 0.2f, h * 0.7f, p(Color.WHITE, w * 0.04f, 18)) }
            repeat(40) { val x = w * r.nextFloat(); val y = h * r.nextFloat(); cv.drawCircle(x, y, 1 + r.nextFloat() * 3, p(c(0xFF9BE7FF), 1f, 120)) }
        },
        Design("Aurora", c(0xFFB6FFDA)) { cv, w, h, r ->
            cv.drawColor(c(0xFF020812))
            repeat(60) { cv.drawCircle(w * r.nextFloat(), h * r.nextFloat(), r.nextFloat() * 1.3f + 0.3f, p(Color.WHITE, 0f, 160)) }
            for ((i, col) in listOf(c(0xFF39FF9F), c(0xFF31C6FF), c(0xFFB04DFF)).withIndex()) {
                val path = Path(); path.moveTo(0f, h * (0.25f + i * 0.08f))
                for (x in 0..w step 6) path.lineTo(x.toFloat(), h * (0.25f + i * 0.08f) + sin(x / w.toFloat() * 6 + i * 2) * h * 0.06f)
                cv.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col; alpha = 110; style = Paint.Style.STROKE; strokeWidth = h * 0.08f; maskFilter = BlurMaskFilter(h * 0.05f, BlurMaskFilter.Blur.NORMAL) })
            }
        },
        Design("Hex armor", c(0xFFFFB000)) { cv, w, h, _ ->
            cv.drawColor(c(0xFF101214))
            val s = w / 9f; val hh = s * sqrt(3f) / 2
            var row = 0; var y = 0f
            while (y < h + s) {
                var x = if (row % 2 == 0) 0f else s * 0.75f
                while (x < w + s) { hex(cv, x, y, s / 2 - 2, p(c(0xFF1E2226))); hex(cv, x, y, s / 2 - 2, p(c(0xFF2E343A), 1.5f)); x += s * 1.5f }
                y += hh; row++
            }
        },
        Design("Brushed steel", c(0xFF14171C)) { cv, w, h, r ->
            fill(cv, lin(w, h, c(0xFFB9C0C8), c(0xFF7E868F), c(0xFFC9CFD6)))
            repeat(h * 2) { val y = r.nextFloat() * h; cv.drawLine(0f, y, w.toFloat(), y, p(if (r.nextBoolean()) Color.WHITE else Color.BLACK, 0.6f, 25)) }
        },
        Design("Tactical HUD", c(0xFF6CFF6C)) { cv, w, h, _ ->
            cv.drawColor(c(0xFF040805))
            val g = p(c(0xFF1F6B2A), 1.5f)
            val cx = w / 2f; val cy = h / 2f
            for (rr in listOf(0.46f, 0.38f)) cv.drawCircle(cx, cy, min(w, h) * rr, g)
            for (i in 0 until 60) {
                val a = i / 60f * 2 * PI.toFloat(); val r0 = min(w, h) * 0.46f; val r1 = r0 - if (i % 5 == 0) 10 else 4
                cv.drawLine(cx + cos(a) * r0, cy + sin(a) * r0, cx + cos(a) * r1, cy + sin(a) * r1, g)
            }
            cv.drawLine(0f, cy, w.toFloat(), cy, p(c(0xFF14401A), 1f)); cv.drawLine(cx, 0f, cx, h.toFloat(), p(c(0xFF14401A), 1f))
        },
        Design("Racing stripes", c(0xFFFFFFFF)) { cv, w, h, _ ->
            cv.drawColor(c(0xFF0E0F12))
            cv.drawRect(w * 0.32f, 0f, w * 0.44f, h.toFloat(), p(c(0xFFD62828)))
            cv.drawRect(w * 0.56f, 0f, w * 0.68f, h.toFloat(), p(c(0xFFD62828)))
            cv.drawRect(w * 0.46f, 0f, w * 0.54f, h.toFloat(), p(c(0xFF2A2C31)))
        },
        Design("Starfield", c(0xFFFFE680)) { cv, w, h, r ->
            fill(cv, RadialGradient(w * 0.7f, h * 0.3f, max(w, h).toFloat(), c(0xFF1C1440), c(0xFF020206), Shader.TileMode.CLAMP))
            repeat(160) { cv.drawCircle(w * r.nextFloat(), h * r.nextFloat(), r.nextFloat().pow(3) * 2.2f + 0.3f, p(Color.WHITE, 0f, 90 + r.nextInt(165))) }
        },
        Design("Circuit", c(0xFF00F5C4)) { cv, w, h, r ->
            cv.drawColor(c(0xFF04120F))
            val g = p(c(0xFF0F5A4A), 1.6f); val dot = p(c(0xFF19A383))
            repeat(36) {
                var x = (r.nextInt(w / 8) * 8).toFloat(); var y = (r.nextInt(h / 8) * 8).toFloat()
                repeat(5) { val nx = x + (r.nextInt(5) - 2) * 16f; val ny = y + if (r.nextBoolean()) 16f else -16f; cv.drawLine(x, y, nx, y, g); cv.drawLine(nx, y, nx, ny, g); x = nx; y = ny }
                cv.drawCircle(x, y, 3f, dot)
            }
        },
        Design("Lava", c(0xFFFFD27A)) { cv, w, h, r ->
            cv.drawColor(c(0xFF1A0602))
            repeat(14) {
                val x = w * r.nextFloat(); val y = h * r.nextFloat(); val rad = min(w, h) * (0.1f + r.nextFloat() * 0.25f)
                cv.drawCircle(x, y, rad, Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = RadialGradient(x, y, rad, intArrayOf(c(0xFFFF6A00), c(0x66C21A00), 0), null, Shader.TileMode.CLAMP) })
            }
        },
        Design("Pine forest", c(0xFFF4F1DE)) { cv, w, h, r ->
            fill(cv, LinearGradient(0f, 0f, 0f, h.toFloat(), c(0xFF5B7DA6), c(0xFFC9D6DF), Shader.TileMode.CLAMP))
            for (layer in 0..2) {
                val col = listOf(c(0xFF4A6670), c(0xFF2C4A3E), c(0xFF142821))[layer]
                var x = -10f
                while (x < w + 10) {
                    val th = h * (0.18f + layer * 0.07f + r.nextFloat() * 0.08f); val base = h * (0.72f + layer * 0.1f); val tw = th * 0.35f
                    val path = Path(); path.moveTo(x, base - th); path.lineTo(x - tw, base); path.lineTo(x + tw, base); path.close(); cv.drawPath(path, p(col))
                    x += tw * (0.9f + r.nextFloat())
                }
                cv.drawRect(0f, h * (0.72f + layer * 0.1f), w.toFloat(), h.toFloat(), p(col))
            }
        },
        Design("Cypress swamp", c(0xFFE9F5DB)) { cv, w, h, r ->
            fill(cv, LinearGradient(0f, 0f, 0f, h.toFloat(), intArrayOf(c(0xFFF2A65A), c(0xFF8E5572), c(0xFF1B1F3B)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP))
            val water = h * 0.74f
            cv.drawRect(0f, water, w.toFloat(), h.toFloat(), p(c(0xFF141729)))
            val tree = p(c(0xFF0B0C14))
            for (i in 0..4) {
                val x = w * (0.08f + i * 0.22f + r.nextFloat() * 0.05f); val tw = w * (0.035f + r.nextFloat() * 0.02f); val top = h * (0.12f + r.nextFloat() * 0.18f)
                val path = Path(); path.moveTo(x - tw * 2.2f, water); path.quadTo(x - tw * 0.6f, water - h * 0.08f, x - tw * 0.5f, top); path.lineTo(x + tw * 0.5f, top)
                path.quadTo(x + tw * 0.6f, water - h * 0.08f, x + tw * 2.2f, water); path.close(); cv.drawPath(path, tree)
                cv.drawOval(x - tw * 4, top - h * 0.05f, x + tw * 4, top + h * 0.06f, tree)
                // cypress knees poking out of the water
                repeat(3) { val kx = x + (r.nextFloat() - 0.5f) * tw * 8; val kh = h * (0.015f + r.nextFloat() * 0.03f); cv.drawRoundRect(kx - 3, water - kh, kx + 3, water + 2, 3f, 3f, tree) }
            }
            cv.drawLine(0f, water, w.toFloat(), water, p(c(0xFFF2A65A), 1f, 120))
        },
        Design("Blueprint", c(0xFFFFFFFF)) { cv, w, h, _ ->
            cv.drawColor(c(0xFF0B3D91))
            val minor = p(Color.WHITE, 0.7f, 35); val major = p(Color.WHITE, 1.2f, 70)
            for (x in 0..w step 10) cv.drawLine(x.toFloat(), 0f, x.toFloat(), h.toFloat(), if (x % 50 == 0) major else minor)
            for (y in 0..h step 10) cv.drawLine(0f, y.toFloat(), w.toFloat(), y.toFloat(), if (y % 50 == 0) major else minor)
        },
        Design("Pure black", c(0xFFFFFFFF)) { cv, _, _, _ -> cv.drawColor(Color.BLACK) },
    )

    private fun camo(cv: Canvas, w: Int, h: Int, r: Random, cols: IntArray) {
        cv.drawColor(cols[0])
        for (layer in 1 until cols.size) repeat(26) {
            val x = w * r.nextFloat(); val y = h * r.nextFloat(); val s = min(w, h) * (0.06f + r.nextFloat() * 0.12f)
            val path = Path()
            for (k in 0..10) { val a = k / 10f * 2 * PI.toFloat(); val rr = s * (0.6f + r.nextFloat() * 0.7f); val px = x + cos(a) * rr * 1.5f; val py = y + sin(a) * rr; if (k == 0) path.moveTo(px, py) else path.lineTo(px, py) }
            path.close(); cv.drawPath(path, p(cols[layer]))
        }
    }

    private fun hex(cv: Canvas, x: Float, y: Float, r: Float, paint: Paint) {
        val path = Path()
        for (k in 0..5) { val a = (k * 60f) * PI.toFloat() / 180f; val px = x + cos(a) * r; val py = y + sin(a) * r; if (k == 0) path.moveTo(px, py) else path.lineTo(px, py) }
        path.close(); cv.drawPath(path, paint)
    }

    fun background(i: Int, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        all[i].draw(Canvas(bmp), w, h, Random(1000 + i))
        return bmp
    }

    /** A preview of the finished face: background + time in the accent colour. */
    fun preview(i: Int, w: Int, h: Int, digitBox: RectF? = null): Bitmap {
        val bmp = background(i, w, h)
        val cv = Canvas(bmp)
        val box = digitBox ?: RectF(w * 0.1f, h * 0.3f, w * 0.9f, h * 0.55f)
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = all[i].accent; typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            textSize = box.height(); textAlign = Paint.Align.CENTER
        }
        while (tp.measureText("10:08") > box.width() && tp.textSize > 8) tp.textSize -= 2
        cv.drawText("10:08", box.centerX(), box.centerY() + tp.textSize * 0.35f, tp)
        val sp = Paint(tp).apply { textSize = tp.textSize * 0.22f; typeface = Typeface.DEFAULT; alpha = 210 }
        cv.drawText("THU 12  ·  8,412 steps", box.centerX(), box.bottom + sp.textSize * 1.8f, sp)
        return bmp
    }
}
