package android.view

import android.content.Context
import android.widget.*
import me.kavishdevar.librepods.R

open class View(val context: Context) {
    var parent: Any? = null
    val windowToken: Any? get() = parent
    var visibility = VISIBLE
    var systemUiVisibility = 0
    var translationY = 0f
    var alpha = 1f
    var height = 400
    var top = 0
    val children = linkedMapOf<Int, View>()
    private var click: ((View) -> Unit)? = null
    private var touch: ((View, MotionEvent) -> Boolean)? = null
    @Suppress("UNCHECKED_CAST")
    fun <T : View> findViewById(id: Int): T = children.getValue(id) as T
    fun setOnClickListener(listener: (View) -> Unit) { click = listener }
    fun setOnTouchListener(listener: (View, MotionEvent) -> Boolean) { touch = listener }
    fun performClick() { click?.invoke(this) }
    fun dispatchTouchEvent(event: MotionEvent) = touch?.invoke(this, event) ?: false
    companion object {
        const val VISIBLE = 0
        const val GONE = 8
        const val SYSTEM_UI_FLAG_LAYOUT_STABLE = 1
        const val SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION = 2
        const val SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN = 4
        const val SYSTEM_UI_FLAG_HIDE_NAVIGATION = 8
        const val SYSTEM_UI_FLAG_FULLSCREEN = 16
        const val SYSTEM_UI_FLAG_IMMERSIVE_STICKY = 32
        const val TRANSLATION_Y = "translationY"
    }
}
class MotionEvent(val action: Int, val rawY: Float = 0f) {
    companion object { const val ACTION_DOWN = 0; const val ACTION_OUTSIDE = 4 }
}
object Gravity { const val BOTTOM = 1; const val CENTER_HORIZONTAL = 2 }
class LayoutInflater(val context: Context) {
    var rejectInflation = false
    var lastView: View? = null
    fun inflate(resource: Int, parent: Any?): View {
        if (rejectInflation) error("Inflation failed")
        return View(context).apply {
            children[R.id.close_button] = ImageButton(context)
            children[R.id.linear_layout] = LinearLayout(context)
            for (id in listOf(R.id.name, R.id.left_battery, R.id.right_battery, R.id.case_battery)) children[id] = TextView(context)
            children[R.id.video] = VideoView(context)
            children[R.id.connection_artwork] = ImageView(context)
            lastView = this
        }
    }
}
class WindowManager {
    val attached = linkedSetOf<View>()
    var addCalls = 0
    var removeCalls = 0
    var rejectAdd = false
    var failAfterAttach = false
    fun addView(view: View, params: LayoutParams) {
        addCalls++
        if (rejectAdd) throw SecurityException("Overlay permission denied")
        check(view.parent == null)
        attached.add(view)
        view.parent = this
        if (failAfterAttach) error("Partial attach failed")
    }
    fun removeView(view: View) {
        removeCalls++
        check(attached.remove(view)) { "View not attached" }
        view.parent = null
    }
    class LayoutParams {
        var height = 0; var width = 0; var type = 0; var format = 0; var gravity = 0
        var dimAmount = 0f; var flags = 0; var x = 0; var y = 0
        companion object {
            const val WRAP_CONTENT = -2; const val MATCH_PARENT = -1
            const val TYPE_APPLICATION_OVERLAY = 2038
            const val FLAG_LAYOUT_NO_LIMITS = 1; const val FLAG_FULLSCREEN = 2
            const val FLAG_NOT_FOCUSABLE = 4; const val FLAG_DIM_BEHIND = 8
            const val FLAG_WATCH_OUTSIDE_TOUCH = 16
        }
    }
}
