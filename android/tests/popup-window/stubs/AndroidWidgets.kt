package android.widget
import android.content.Context
import android.view.View
class ImageButton(context: Context) : View(context)
class LinearLayout(context: Context) : View(context)
class TextView(context: Context) : View(context) { var text: CharSequence = "" }
class ImageView(context: Context) : View(context) {
    var imageResource: Int? = null
    fun setImageResource(resource: Int) { imageResource = resource }
}
class VideoView(context: Context) : View(context) {
    var playing = false
    var path: String? = null
    var completion: ((Any?) -> Unit)? = null
    var failStart = false
    var stopCalls = 0
    fun setAudioFocusRequest(request: Int) {}
    fun setVideoPath(value: String) { path = value }
    fun start() { if (failStart) error("Video preparation failed"); playing = true }
    fun stopPlayback() { stopCalls++; playing = false }
    fun setOnCompletionListener(listener: ((Any?) -> Unit)?) { completion = listener }
}
