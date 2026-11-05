package org.spdr.companion.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.spdr.companion.core.filter.FilterConfig
import org.spdr.companion.core.filter.ParticleFilter
import org.spdr.companion.core.graph.GraphJson
import kotlin.math.abs

class ParticleFilterTest {

    @Test
    fun graphBuilderWeldsTAndXJunctions() {
        val g = SimWalk.build(listOf(
            SimWalk.line(0.0, 0.0, 100.0, 0.0),
            SimWalk.line(50.0, 1.5, 50.0, 60.0),   // T: dangling end 1.5 m off the first line
            SimWalk.line(80.0, -30.0, 80.0, 30.0), // X: crosses the first line
        ))
        val degrees = g.nodes.map { it.incident.size }.sorted()
        // 5 dead ends + T junction (3) + X junction (4)
        assertEquals(listOf(1, 1, 1, 1, 1, 3, 4), degrees)
        assertEquals(6, g.edges.size)
        val again = GraphJson.read(GraphJson.write(g))
        assertEquals(g.edges.size, again.edges.size)
        assertEquals(g.totalLength, again.totalLength, 0.1)
    }

    @Test
    fun staysOnCorridorWithUnknownHeadingOffset() {
        val g = SimWalk.build(listOf(SimWalk.line(0.0, 0.0, 300.0, 40.0)))
        val (x0, y0) = SimWalk.local(g, 0.0, 0.0)
        val start = g.nearest(x0, y0)!!
        val pf = ParticleFilter(g, FilterConfig(particles = 800), seed = 3)
        pf.init(x0, y0, 3.0) // θ uniform
        val walker = SimWalk.Walker(g, start.edge, start.s, 1, kTrue = 1.0, thetaTrue = 1.2, seed = 5)
        repeat(200) { walker.step(pf) }
        assertTrue("error ${walker.error(pf)}", walker.error(pf) < 15.0)
    }

    @Test
    fun followsTurnsOnGrid() {
        val g = SimWalk.grid(6, 40.0)
        val (x0, y0) = SimWalk.local(g, 0.0, 0.0)
        val start = g.nearest(x0, y0)!!
        val pf = ParticleFilter(g, FilterConfig(particles = 1500), seed = 11)
        pf.init(x0, y0, 2.0, kMean = 1.0, kStd = 0.05, thetaMean = 0.4, thetaStd = 15 * DEG)
        val walker = SimWalk.Walker(g, start.edge, start.s, 1, kTrue = 1.05, thetaTrue = 0.4, seed = 2)
        var maxErr = 0.0
        var sumErr = 0.0
        val n = 600 // ~420 m
        repeat(n) {
            walker.step(pf)
            val e = walker.error(pf)
            sumErr += e
            if (e > maxErr) maxErr = e
        }
        assertTrue("mean error ${sumErr / n}", sumErr / n < 12.0)
        assertTrue("final error ${walker.error(pf)}", walker.error(pf) < 20.0)
    }

    @Test
    fun robustToDriftMissedStepsAndNoiseOnIrregularNetwork() {
        for (seed in 1L..5L) {
            val g = SimWalk.irregular(seed)
            val (x0, y0) = SimWalk.local(g, 0.0, 0.0)
            val start = g.nearest(x0, y0)!!
            val pf = ParticleFilter(g, FilterConfig(particles = 1000), seed = seed)
            pf.init(g.edges[start.edge].x(start.s), g.edges[start.edge].y(start.s), 2.0,
                thetaMean = 0.4, thetaStd = 15 * DEG)
            val walker = SimWalk.Walker(g, start.edge, start.s, 1, kTrue = 1.1, thetaTrue = 0.4,
                headingNoise = 25 * DEG, seed = seed * 7)
            val rnd = java.util.Random(seed)
            var drift = 0.0
            var sumErr = 0.0
            val n = 700 // ~500 m
            repeat(n) {
                val (step, psi) = walker.step(null)
                drift += 0.3 * DEG * step.dt // uncompensated gyro drift
                if (rnd.nextDouble() > 0.08) pf.step(step, wrap(psi - drift)) // 8% missed steps
                sumErr += walker.error(pf)
            }
            assertTrue("seed $seed mean error ${sumErr / n}", sumErr / n < 10.0)
            assertEquals("seed $seed learned bias", -0.3 * DEG, pf.estimate().biasMean, 0.15 * DEG)
        }
    }

    @Test
    fun resetsLearnStepScale() {
        // long winding corridor: the map can't tell the along-track error, only resets can
        val g = SimWalk.build(listOf(SimWalk.line(0.0, 0.0, 400.0, 0.0, 400.0, 400.0, 0.0, 400.0)))
        val (x0, y0) = SimWalk.local(g, 0.0, 0.0)
        val start = g.nearest(x0, y0)!!
        val pf = ParticleFilter(g, FilterConfig(particles = 1000), seed = 7)
        pf.init(x0, y0, 1.0, kMean = 1.0, kStd = 0.1, thetaMean = 0.0, thetaStd = 10 * DEG)
        val kTrue = 1.15
        val walker = SimWalk.Walker(g, start.edge, start.s, 1, kTrue = kTrue, thetaTrue = 0.0, seed = 9)
        val errors = ArrayList<Double>()
        repeat(4) {
            repeat(120) { walker.step(pf) }
            val p = walker.pos()
            errors.add(pf.reset(p.x, p.y, 2.0)!!)
        }
        val est = pf.estimate()
        assertEquals("k ${est.kMean}", kTrue, est.kMean, 0.035)
        assertTrue("errors $errors", errors.last() < errors.first())
    }

    @Test
    fun manualCorrectionsMoveTheEstimate() {
        val g = SimWalk.build(listOf(SimWalk.line(0.0, 0.0, 200.0, 0.0)))
        val (x0, y0) = SimWalk.local(g, 50.0, 0.0)
        val pf = ParticleFilter(g, FilterConfig(particles = 500), seed = 1)
        pf.init(x0, y0, 0.5, thetaMean = 0.0, thetaStd = 5 * DEG)
        val walker = SimWalk.Walker(g, g.nearest(x0, y0)!!.edge, g.nearest(x0, y0)!!.s, 1, 1.0, 0.0, seed = 4)
        repeat(10) { walker.step(pf) }
        val before = pf.estimate().best!!
        pf.nudge(10.0)
        val after = pf.estimate().best!!
        assertEquals(10.0, after.x - before.x, 1.5)
        pf.uTurn()
        assertTrue(abs(wrap(pf.estimate().best!!.heading - Math.PI)) < 0.3)
    }

    @Test
    fun deterministicWithSeed() {
        fun run(): Double {
            val g = SimWalk.grid(4, 30.0)
            val (x0, y0) = SimWalk.local(g, 0.0, 0.0)
            val pf = ParticleFilter(g, FilterConfig(particles = 300), seed = 42)
            pf.init(x0, y0, 2.0)
            val start = g.nearest(x0, y0)!!
            val w = SimWalk.Walker(g, start.edge, start.s, 1, 1.0, 0.3, seed = 1)
            repeat(50) { w.step(pf) }
            return pf.estimate().best!!.x
        }
        assertEquals(run(), run(), 0.0)
    }
}
