package android.widget
import android.content.Context
import android.content.Platform
class TextView(context: Context) { var gravity = 0; fun setText(id: Int) = Unit }
class Toast(private val id: Int) {
    fun show() { Platform.toasts += id }
    companion object { const val LENGTH_LONG = 1; fun makeText(context: Context, id: Int, duration: Int) = Toast(id) }
}
