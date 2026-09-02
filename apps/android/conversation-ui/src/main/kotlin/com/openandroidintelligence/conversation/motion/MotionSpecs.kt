package com.openandroidintelligence.conversation.motion

import androidx.compose.animation.core.*
import kotlin.math.abs

object MotionSpecs {
    // Apple / WWDC Fluid Interface springs
    // Critically damped default
    val StandardSpring = spring<Float>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )

    // Interactive momentum release / flick
    val MomentumSpring = spring<Float>(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )

    // Sheet / Drawer expansion
    val SheetSpring = spring<Float>(
        dampingRatio = 0.8f,
        stiffness = Spring.StiffnessMediumLow,
    )

    // Linear fallback for reduced motion
    val ReducedMotionSpec = tween<Float>(
        durationMillis = 150,
        easing = LinearEasing,
    )

    fun rubberband(overshoot: Float, dimension: Float, constant: Float = 0.55f): Float {
        if (dimension <= 0f) return 0f
        return (overshoot * dimension * constant) / (dimension + constant * abs(overshoot))
    }

    fun project(initialVelocityPxPerSec: Float, decelerationRate: Float = 0.998f): Float {
        return (initialVelocityPxPerSec / 1000f) * decelerationRate / (1f - decelerationRate)
    }
}
