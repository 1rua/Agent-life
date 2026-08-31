package com.agentlife.conversation.motion

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MotionPhysicsTest {
    @Test
    fun rubberbandResistsProgressively() {
        val smallOvershoot = MotionSpecs.rubberband(100f, 1000f)
        val largeOvershoot = MotionSpecs.rubberband(500f, 1000f)

        assertTrue(smallOvershoot < 100f)
        assertTrue(largeOvershoot < 500f)
        assertTrue(smallOvershoot / 100f > largeOvershoot / 500f, "Resistance must increase with overshoot")
    }

    @Test
    fun momentumProjectionProjectsForward() {
        val projected = MotionSpecs.project(1000f)
        assertTrue(projected > 0f)
    }
}
