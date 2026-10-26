package android.content

import android.content.res.Resources
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager

abstract class BroadcastReceiver {
    abstract fun onReceive(context: Context?, intent: Intent?)
}
class Intent(var action: String? = null) {
    var data: ArrayList<*>? = null
    @Suppress("UNCHECKED_CAST")
    fun <T> getParcelableArrayListExtra(key: String): ArrayList<T>? = data as ArrayList<T>?
    fun <T> getParcelableArrayListExtra(key: String, type: Class<T>): ArrayList<T>? = getParcelableArrayListExtra(key)
}
class IntentFilter(val action: String)
open class Context {
    val resources = Resources()
    val windowManager = WindowManager()
    val inflater = LayoutInflater(this)
    val receivers = linkedSetOf<BroadcastReceiver>()
    var rejectRegistration = false
    var failAfterRegistration = false
    var unregisterCalls = 0
    open fun getSystemService(name: String): Any = when (name) {
        LAYOUT_INFLATER_SERVICE -> inflater
        WINDOW_SERVICE -> windowManager
        else -> error("Unexpected service $name")
    }
    fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter): Intent? {
        if (rejectRegistration) throw SecurityException("Registration denied")
        check(receiver != null && receivers.add(receiver))
        if (failAfterRegistration) throw SecurityException("Partial registration failed")
        return null
    }
    fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter, flags: Int): Intent? = registerReceiver(receiver, filter)
    fun unregisterReceiver(receiver: BroadcastReceiver) {
        unregisterCalls++
        check(receivers.remove(receiver)) { "Receiver not registered" }
    }
    fun broadcast(intent: Intent) = receivers.toList().forEach { it.onReceive(this, intent) }
    companion object {
        const val LAYOUT_INFLATER_SERVICE = "inflater"
        const val WINDOW_SERVICE = "window"
        const val RECEIVER_EXPORTED = 2
    }
}
