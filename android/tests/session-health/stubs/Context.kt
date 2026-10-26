package android.content

import android.net.Uri

/** Only Android method surfaces. Tests supply permission, service and launch results. */
open class Context {
    open val packageName: String = "me.kavishdevar.librepods.test"
    open fun checkSelfPermission(permission: String): Int = error("No permission result configured")
    open fun <T> getSystemService(serviceClass: Class<T>): T? = null
    open fun startActivity(intent: Intent): Unit = error("No activity launcher configured")
}

class Intent(val action: String, val data: Uri? = null) {
    var flags: Int = 0
        private set
    fun addFlags(value: Int): Intent { flags = flags or value; return this }
    companion object { const val FLAG_ACTIVITY_NEW_TASK = 0x10000000 }
}
