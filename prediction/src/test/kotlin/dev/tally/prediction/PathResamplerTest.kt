package dev.tally.prediction

import dev.tally.keyboard.engine.GlidePoint
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlin.math.sqrt

class PathResamplerTest : FunSpec({

    val resampler = PathResampler(targetCount = 8)

    test("empty input returns empty list") {
        resampler.resample(emptyList()) shouldBe emptyList()
    }

    test("single point input returns that point") {
        val pt = GlidePoint(5f, 5f)
        resampler.resample(listOf(pt)) shouldBe listOf(pt)
    }

    test("output has exactly targetCount points") {
        val pts = (0 until 20).map { GlidePoint(it.toFloat(), 0f) }
        resampler.resample(pts).size shouldBe 8
    }

    test("first and last points are preserved") {
        val pts = listOf(
            GlidePoint(0f, 0f),
            GlidePoint(100f, 0f),
            GlidePoint(200f, 50f),
        )
        val out = resampler.resample(pts)
        out.first().x shouldBe 0f
        out.last().x shouldBe 200f
        out.last().y shouldBe 50f
    }

    test("co-located points produce uniform output") {
        val pts = List(10) { GlidePoint(7f, 3f) }
        val out = resampler.resample(pts)
        out.size shouldBe 8
        out.forEach { pt ->
            pt.x shouldBe 7f
            pt.y shouldBe 3f
        }
    }

    test("resampled points are approximately equidistant on a straight line") {
        val n = 100
        val pts = (0 until n).map { GlidePoint(it.toFloat(), 0f) }
        val out = PathResampler(targetCount = 10).resample(pts)
        out.size shouldBe 10
        // Segments should be roughly equal
        val segLens = (1 until out.size).map { i ->
            val dx = out[i].x - out[i - 1].x
            val dy = out[i].y - out[i - 1].y
            sqrt(dx * dx + dy * dy)
        }
        val mean = segLens.average().toFloat()
        segLens.forEach { len ->
            (kotlin.math.abs(len - mean)) shouldBeLessThan 1f
        }
    }
})
