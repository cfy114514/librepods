package android.content
import android.bluetooth.BluetoothManager
interface SharedPreferences { fun getString(key: String, default: String?): String? }
class Context(private val preferences: SharedPreferences, private val bluetooth: BluetoothManager) {
    companion object { const val MODE_PRIVATE=0; const val BLUETOOTH_SERVICE="bluetooth" }
    fun getSharedPreferences(name: String, mode: Int) = preferences
    fun getSystemService(name: String): Any = bluetooth
}
