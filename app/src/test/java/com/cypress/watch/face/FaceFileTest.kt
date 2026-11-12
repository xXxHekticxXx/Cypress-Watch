package com.cypress.watch.face

import net.jpountz.lz4.LZ4Factory
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

/** Builds small faces in both the run-length (v2) and LZ4 (v3) layouts, edits them, and checks they read back exactly. */
class FaceFileTest {
    private fun le16(o: ByteArrayOutputStream, v: Int) { o.write(v and 0xff); o.write((v ushr 8) and 0xff) }
    private fun le32(o: ByteArrayOutputStream, v: Int) { le16(o, v and 0xffff); le16(o, v ushr 16) }

    private fun header(w: Int, h: Int, version: Int, blocks: Int) = ByteArrayOutputStream().apply {
        write(byteArrayOf(0x71, 0xa8.toByte(), 0x05, 0x00)); le32(this, 0); le32(this, 0); le16(this, w); le16(this, h)
        write(byteArrayOf(0, 1, 0xff.toByte(), version.toByte(), blocks.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte()))
    }.toByteArray()

    private fun record(type: Int, w: Int, off: Int, h: Int, x: Int, y: Int, frames: Int) = ByteArrayOutputStream().apply {
        le16(this, type); le16(this, w); le32(this, off); le16(this, h); le16(this, x); le16(this, y); le16(this, 0)
        write(frames); write(0); write(0); write(0); write(0); write(0); write(0); write(0)
    }.toByteArray()

    private fun finish(b: ByteArray): ByteArray {
        FaceFile.putU32(b, 4, b.size - 24)
        val c = CRC32(); c.update(b, 24, b.size - 24); FaceFile.putU32(b, 8, c.value.toInt()); return b
    }

    private fun pattern(w: Int, h: Int, seed: Int) = IntArray(w * h) { i -> FaceFile.from565(FaceFile.to565(0xff000000.toInt() or ((i * 37 + seed) * 2654435761L).toInt() and 0xffffff), true) }

    private fun v2(): Pair<ByteArray, Map<Int, List<IntArray>>> {
        val bg = pattern(10, 8, 1); val digits = List(3) { pattern(4, 5, 10 + it) }
        val table = 2 * 24 + 2
        val bgData = FaceFile.rleEncodeFrame(bg, 10, 8)
        val dData = ByteArrayOutputStream().apply { digits.forEach { write(FaceFile.rleEncodeFrame(it, 4, 5)) } }.toByteArray()
        val o = ByteArrayOutputStream()
        o.write(header(10, 8, 2, 0xff))
        o.write(record(4, 4, 24 + table + bgData.size, 5, 1, 1, 3))
        o.write(record(17, 10, 24 + table, 8, 0, 0, 1))
        o.write(byteArrayOf(0, 0)); o.write(bgData); o.write(dData)
        return finish(o.toByteArray()) to mapOf(17 to listOf(bg), 4 to digits)
    }

    private fun v3(): Pair<ByteArray, Map<Int, List<IntArray>>> {
        val bg = pattern(10, 8, 2); val digits = List(2) { pattern(4, 5, 20 + it) }
        val lz = LZ4Factory.fastestJavaInstance().fastCompressor()
        fun frame(px: IntArray, w: Int, h: Int, at: Int): ByteArray {
            val raw = ByteArray(px.size * 2); px.forEachIndexed { i, p -> val v = FaceFile.to565(p); raw[i * 2] = v.toByte(); raw[i * 2 + 1] = (v ushr 8).toByte() }
            val c = lz.compress(raw)
            return ByteArrayOutputStream().apply { le32(this, at); le16(this, w); le16(this, h); write(0); write(2); le16(this, 0); le32(this, c.size); write(byteArrayOf(0xcc.toByte(), 0xcc.toByte(), 0xcc.toByte(), 0xcc.toByte())); write(c) }.toByteArray()
        }
        val table = 2 * 24 + 2
        val bgAt = 24 + table
        val bgF = frame(bg, 10, 8, bgAt)
        val dAt = bgAt + bgF.size
        val d0 = frame(digits[0], 4, 5, dAt); val d1 = frame(digits[1], 4, 5, dAt + d0.size)
        val o = ByteArrayOutputStream()
        o.write(header(10, 8, 3, 2))
        o.write(record(17, 10, bgAt, 8, 0, 0, 1))
        o.write(record(4, 4, dAt, 5, 2, 2, 2))
        o.write(byteArrayOf(0, 0)); o.write(bgF); o.write(d0); o.write(d1)
        return finish(o.toByteArray()) to mapOf(17 to listOf(bg), 4 to digits)
    }

    private fun check(bytes: ByteArray, want: Map<Int, List<IntArray>>) {
        val f = FaceFile(bytes)
        for ((t, frames) in want) {
            val got = f.decode(f.item(t)!!)!!
            assertEquals(frames.size, got.size)
            frames.forEachIndexed { i, px -> assertArrayEquals("type $t frame $i", px, got[i]) }
        }
        val c = CRC32(); c.update(bytes, 24, bytes.size - 24)
        assertEquals(c.value.toInt(), FaceFile.u32(bytes, 8))
        assertEquals(bytes.size - 24, FaceFile.u32(bytes, 4))
    }

    private fun roundTrip(make: () -> Pair<ByteArray, Map<Int, List<IntArray>>>) {
        val (bytes, frames) = make()
        check(bytes, frames)
        // untouched rebuild = identical file
        assertArrayEquals(bytes, FaceFile(bytes).build())
        // new background + recoloured digits
        val f = FaceFile(bytes)
        val bg = f.item(17)!!
        val newBg = pattern(bg.width, bg.height, 99)
        f.replaceFrame(17, 0, newBg)
        f.tintAll(0xff00ff00.toInt())
        val out = f.build()
        val g = FaceFile(out)
        assertArrayEquals(newBg, g.decode(g.item(17)!!)!![0])
        val tinted = g.decode(g.item(4)!!)!!
        assertEquals(frames[4]!!.size, tinted.size)
        // green only after tinting
        tinted.flatMap { it.toList() }.filter { it ushr 24 != 0 }.forEach { assertEquals(0, (it ushr 16) and 0xff); assertEquals(0, it and 0xff) }
        val c = CRC32(); c.update(out, 24, out.size - 24)
        assertEquals(c.value.toInt(), FaceFile.u32(out, 8))
    }

    @Test fun runLengthFaces() = roundTrip(::v2)
    @Test fun lz4Faces() = roundTrip(::v3)

    @Test fun lz4FramesPointAtThemselves() {
        val (bytes, _) = v3()
        val f = FaceFile(bytes)
        f.replaceFrame(17, 0, pattern(10, 8, 7)) // background changes size, so the digit frames move
        val out = f.build()
        val g = FaceFile(out)
        val d = g.item(4)!!
        assertEquals(d.offset, FaceFile.u32(out, d.offset))
    }
}
