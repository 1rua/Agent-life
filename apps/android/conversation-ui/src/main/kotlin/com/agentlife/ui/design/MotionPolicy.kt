package com.agentlife.ui.design

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Immutable
data class MotionPolicy(
    val reduceMotion: Boolean = false,
    val durationScale: Float = 1.0f,
)

object MotionSpecs {
    // Apple 规范弹簧：默认无过冲 critically damped (damping 1.0, response 0.34s)
    val StandardSpring = spring<Float>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )

    // 拖拽释放/动量弹簧 (damping 0.82, response 0.32s)
    val MomentumSpring = spring<Float>(
        dampingRatio = 0.82f,
        stiffness = Spring.StiffnessMedium,
    )

    // Reduced Motion 降级淡入淡出时长
    const val ReducedMotionCrossfadeDuration = 150
}

interface MotionPreferenceSource {
    val policy: StateFlow<MotionPolicy>
}

class DefaultMotionPreferenceSource : MotionPreferenceSource {
    private val _policy = MutableStateFlow(MotionPolicy())
    override val policy: StateFlow<MotionPolicy> = _policy.asStateFlow()

    fun updateReduceMotion(enabled: Boolean) {
        _policy.value = _policy.value.copy(reduceMotion = enabled)
    }
}
