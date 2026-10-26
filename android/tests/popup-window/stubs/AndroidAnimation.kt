package android.animation
import android.view.View
open class Animator
open class AnimatorListenerAdapter { open fun onAnimationEnd(animation: Animator) {} }
class PropertyValuesHolder(val property: String, val values: FloatArray) {
    companion object { fun ofFloat(property: String, vararg values: Float) = PropertyValuesHolder(property, values) }
}
class ObjectAnimator(val view: View, val endY: Float) : Animator() {
    var duration = 0L
    var interpolator: Any? = null
    private val listeners = mutableListOf<AnimatorListenerAdapter>()
    fun addListener(listener: AnimatorListenerAdapter) { listeners.add(listener) }
    fun removeAllListeners() { listeners.clear() }
    fun start() {
        if (rejectStart) error("Animation start failed")
        running.add(this)
    }
    fun cancel() {
        running.remove(this)
        listeners.toList().forEach { it.onAnimationEnd(this) }
    }
    fun finish() {
        if (!running.remove(this)) return
        view.translationY = endY
        listeners.toList().forEach { it.onAnimationEnd(this) }
    }
    companion object {
        var rejectStart = false
        val running = linkedSetOf<ObjectAnimator>()
        fun ofPropertyValuesHolder(view: View, holder: PropertyValuesHolder) = ObjectAnimator(view, holder.values.last())
        fun ofFloat(view: View, property: String, vararg values: Float) = ObjectAnimator(view, values.last())
        fun finishAll() { running.toList().forEach { it.finish() } }
        fun reset() { running.clear(); rejectStart = false }
    }
}
