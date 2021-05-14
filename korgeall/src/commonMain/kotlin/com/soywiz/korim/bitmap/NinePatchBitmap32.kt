package com.soywiz.korim.bitmap

import com.soywiz.kds.*
import com.soywiz.kds.iterators.*
import com.soywiz.kmem.*
import com.soywiz.korim.color.*
import com.soywiz.korim.format.*
import com.soywiz.korim.internal.*
import com.soywiz.korim.internal.max2
import com.soywiz.korio.file.*
import com.soywiz.korio.util.*
import com.soywiz.korma.geom.*
import com.soywiz.korma.geom.vector.*

class NinePatchInfo(
	val xranges: List<Pair<Boolean, IntRange>>,
	val yranges: List<Pair<Boolean, IntRange>>,
	val width: Int,
	val height: Int
) {
	constructor(
		width: Int, height: Int,
		left: Int, top: Int, right: Int, bottom: Int
	) : this(
		listOf(false to (0 until left), true to (left until right), false to (right until width)),
		listOf(false to (0 until top), true to (top until bottom), false to (bottom until height)),
		width, height
	)

	class AxisSegment(val scaled: Boolean, val range: IntRange) {
		val fixed get() = !scaled
		val length get() = range.length

		fun computedLength(axis: AxisInfo, boundsLength: Int): Double {
			val scale = (boundsLength.toDouble() / axis.totalLen.toDouble()).clamp(0.0, 1.0)
			return if (fixed) {
				length.toDouble() * scale
			} else {
				val variableSize = (boundsLength - (axis.fixedLen * scale))
				variableSize.toDouble() * (length.toDouble() / axis.scaledLen.toDouble())
			}
		}
	}

	class AxisInfo(ranges: List<Pair<Boolean, IntRange>>, val totalLen: Int) {
		val segments = ranges.map { AxisSegment(it.first, it.second) }
		val fixedLen = max2(1, segments.filter { it.fixed }.map { it.length }.sum())
		val scaledLen = max2(1, segments.filter { it.scaled }.map { it.length }.sum())
	}

	val xaxis = AxisInfo(xranges, width)
	val yaxis = AxisInfo(yranges, height)

	val xsegments = xaxis.segments
	val ysegments = yaxis.segments

	val fixedWidth = xaxis.fixedLen
	val fixedHeight = yaxis.fixedLen

	val scaledWidth = xaxis.scaledLen
	val scaledHeight = yaxis.scaledLen

	class Segment(val rect: RectangleInt, val x: AxisSegment, val y: AxisSegment) : Extra by Extra.Mixin() {
		val scaleX: Boolean = x.scaled
		val scaleY: Boolean = y.scaled
	}

	val segments = ysegments.map { y ->
		xsegments.map { x ->
			Segment(
				RectangleInt.fromBounds(x.range.start, y.range.start, x.range.endExclusive, y.range.endExclusive),
				x, y
			)
		}
	}

    fun computeScale(
        bounds: RectangleInt,
        new: Boolean = true,
        callback: (segment: Segment, x: Int, y: Int, width: Int, height: Int) -> Unit
    ) = if (new) computeScaleNew(bounds, callback) else computeScaleOld(bounds, callback)

	// Can be reused for textures using AG
	fun computeScaleOld(
		bounds: RectangleInt,
		callback: (segment: Segment, x: Int, y: Int, width: Int, height: Int) -> Unit
	) {
		//println("scaleFixed=($scaleFixedX,$scaleFixedY)")
		var ry = 0
		for ((yindex, y) in ysegments.withIndex()) {
			val segHeight = y.computedLength(this.yaxis, bounds.height).toInt()
			var rx = 0
			for ((xindex, x) in xsegments.withIndex()) {
				val segWidth = x.computedLength(this.xaxis, bounds.width).toInt()

				val seg = segments[yindex][xindex]
				val segLeft = (rx + bounds.left).toInt()
				val segTop = (ry + bounds.top).toInt()

				//println("($x,$y):($segWidth,$segHeight)")
				callback(seg, segLeft, segTop, segWidth.toInt(), segHeight.toInt())

				rx += segWidth
			}
			ry += segHeight
		}
	}

    private val xComputed = IntArray(64)
    private val yComputed = IntArray(64)

    fun computeScaleNew(
        bounds: RectangleInt,
        callback: (segment: NinePatchInfo.Segment, x: Int, y: Int, width: Int, height: Int) -> Unit
    ) {
        //println("scaleFixed=($scaleFixedX,$scaleFixedY)")

        ysegments.fastForEachWithIndex { index, _ -> yComputed[index] = Int.MAX_VALUE }
        xsegments.fastForEachWithIndex { index, _ -> xComputed[index] = Int.MAX_VALUE }

        ysegments.fastForEachWithIndex { yindex, y ->
            val segHeight = y.computedLength(this.yaxis, bounds.height)
            xsegments.fastForEachWithIndex { xindex, x ->
                val segWidth = x.computedLength(this.xaxis, bounds.width)
                if (x.fixed && y.fixed) {
                    val xScale = segWidth / x.length.toDouble()
                    val yScale = segHeight / y.length.toDouble()
                    val minScale = min2(xScale, yScale)
                    xComputed[xindex] = min2(xComputed[xindex], (x.length * minScale).toInt())
                    yComputed[yindex] = min2(yComputed[yindex], (y.length * minScale).toInt())
                } else {
                    xComputed[xindex] = min2(xComputed[xindex], segWidth.toInt())
                    yComputed[yindex] = min2(yComputed[yindex], segHeight.toInt())
                }
            }
        }

        val denormalizedWidth = xComputed.sum()
        val denormalizedHeight = yComputed.sum()
        val denormalizedScaledWidth = xsegments.mapIndexed { index, it -> if (it.scaled) xComputed[index] else 0 }.sum()
        val denormalizedScaledHeight = ysegments.mapIndexed { index, it -> if (it.scaled) yComputed[index] else 0 }.sum()
        val xScaledRatio = if (denormalizedWidth > 0) denormalizedScaledWidth.toDouble() / denormalizedWidth.toDouble() else 1.0
        val yScaledRatio = if (denormalizedWidth > 0) denormalizedScaledHeight.toDouble() / denormalizedHeight.toDouble() else 1.0

        for (n in 0 until 2) {
            val segments = if (n == 0) ysegments else xsegments
            val computed = if (n == 0) yComputed else xComputed
            val denormalizedScaledLen = if (n == 0) denormalizedScaledHeight else denormalizedScaledWidth
            val side = if (n == 0) bounds.height else bounds.width
            val scaledRatio = if (n == 0) yScaledRatio else xScaledRatio
            val scaledSide = side * scaledRatio

            segments.fastForEachWithIndex { index, v ->
                if (v.scaled) {
                    computed[index] = (scaledSide * (computed[index].toDouble() / denormalizedScaledLen.toDouble())).toInt()
                }
            }
        }

        val xRemaining = bounds.width - xComputed.sum()
        val yRemaining = bounds.height - yComputed.sum()
        val xScaledFirst = xsegments.indexOfFirst { it.scaled }
        val yScaledFirst = ysegments.indexOfFirst { it.scaled }
        if (xRemaining > 0 && xScaledFirst >= 0) xComputed[xScaledFirst] += xRemaining
        if (yRemaining > 0 && yScaledFirst >= 0) yComputed[yScaledFirst] += yRemaining

        var ry = 0
        for (yindex in ysegments.indices) {
            val segHeight = yComputed[yindex].toInt()
            var rx = 0
            for (xindex in xsegments.indices) {
                val segWidth = xComputed[xindex].toInt()

                val seg = segments[yindex][xindex]
                val segLeft = (rx + bounds.left).toInt()
                val segTop = (ry + bounds.top).toInt()

                //println("($x,$y):($segWidth,$segHeight)")
                callback(seg, segLeft, segTop, segWidth.toInt(), segHeight.toInt())

                rx += segWidth
            }
            ry += segHeight
        }
    }
}

open class NinePatchBmpSlice(
    val content: BmpSlice,
    val info: NinePatchInfo,
    val bmpSlice: BmpSlice = content
) {
    companion object {
        fun createSimple(bmp: BmpSlice, left: Int, top: Int, right: Int, bottom: Int): NinePatchBmpSlice {
            return NinePatchBmpSlice(bmp, NinePatchInfo(bmp.width, bmp.height, left, top, right, bottom))
        }

        operator fun invoke(bmp: Bitmap) = invoke(bmp.slice())
        operator fun invoke(bmpSlice: BmpSlice): NinePatchBmpSlice {
            val content = bmpSlice.sliceWithBounds(1, 1, bmpSlice.width - 1, bmpSlice.height - 1)
            return NinePatchBmpSlice(
                content = content,
                info = run {
                    val topPixels = bmpSlice.readPixels(0, 0, bmpSlice.width, 1)
                    val leftPixels = bmpSlice.readPixels(0, 0, 1, bmpSlice.height)
                    NinePatchInfo(
                        (1 until bmpSlice.width - 1).computeRle { topPixels[it].a != 0 },
                        (1 until bmpSlice.height - 1).computeRle { leftPixels[it].a != 0 },
                        content.width, content.height
                    )
                },
                bmpSlice = bmpSlice
            )
        }
    }

	val width get() = bmpSlice.width
	val height get() = bmpSlice.height
	val dwidth get() = width.toDouble()
	val dheight get() = height.toDouble()

    val NinePatchInfo.Segment.bmpSlice by Extra.PropertyThis<NinePatchInfo.Segment, BmpSlice> {
        this@NinePatchBmpSlice.content.slice(this.rect)
    }

    val NinePatchInfo.Segment.bmp by Extra.PropertyThis<NinePatchInfo.Segment, Bitmap> {
		this@NinePatchBmpSlice.bmpSlice.extract()
	}

    fun getSegmentBmpSlice(segment: NinePatchInfo.Segment) = segment.bmpSlice

	fun <T : Bitmap> drawTo(
		other: T,
		bounds: RectangleInt,
		antialiased: Boolean = true,
		drawRegions: Boolean = false
	): T {
		other.context2d(antialiased) {
			info.computeScale(bounds) { seg, segLeft, segTop, segWidth, segHeight ->
				drawImage(seg.bmp, segLeft, segTop, segWidth.toInt(), segHeight.toInt())
				if (drawRegions) {
					stroke(Colors.RED) { rect(segLeft, segTop, segWidth, segHeight) }
				}
			}
		}
		return other
	}

	fun renderedNative(width: Int, height: Int, antialiased: Boolean = true, drawRegions: Boolean = false): NativeImage = drawTo(
        NativeImage(width, height),
        //Bitmap32(width, height),
        RectangleInt(0, 0, width, height),
        antialiased = antialiased,
        drawRegions = drawRegions
    )

    fun rendered(width: Int, height: Int, antialiased: Boolean = true, drawRegions: Boolean = false): Bitmap32 = renderedNative(width, height, antialiased, drawRegions).toBMP32IfRequired()
}

typealias NinePatchBitmap32 = NinePatchBmpSlice

fun BmpSlice.asNinePatch() = NinePatchBmpSlice(this)
fun Bitmap.asNinePatch() = NinePatchBitmap32(this.toBMP32IfRequired())

fun BmpSlice.asNinePatchSimpleRatio(left: Double, top: Double, right: Double, bottom: Double) = this.asNinePatchSimple(
    (left * width).toInt(), (top * height).toInt(),
    (right * width).toInt(), (bottom * height).toInt()
)
fun BmpSlice.asNinePatchSimple(left: Int, top: Int, right: Int, bottom: Int) = NinePatchBmpSlice(this, NinePatchInfo(width, height, left, top, right, bottom))
fun Bitmap.asNinePatchSimple(left: Int, top: Int, right: Int, bottom: Int) = this.slice().asNinePatchSimple(left, top, right, bottom)

suspend fun VfsFile.readNinePatch(format: ImageFormat = RegisteredImageFormats) = NinePatchBitmap32(readBitmap(format).toBMP32())

private inline fun <T, R : Any> Iterable<T>.computeRle(callback: (T) -> R): List<Pair<R, IntRange>> {
    var first = true
    var pos = 0
    var startpos = 0
    lateinit var lastRes: R
    val out = arrayListOf<Pair<R, IntRange>>()
    for (it in this) {
        val current = callback(it)
        if (!first) {
            if (current != lastRes) {
                out += lastRes to (startpos until pos)
                startpos = pos
            }
        }
        lastRes = current
        first = false
        pos++
    }
    if (startpos != pos) {
        out += lastRes to (startpos until pos)
    }
    return out
}