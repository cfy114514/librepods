package android.bluetooth

import java.io.InputStream
import java.io.OutputStream

/** Only the Android socket surface; each integration test supplies transport behavior. */
abstract class BluetoothSocket {
    abstract val isConnected: Boolean
    abstract val inputStream: InputStream
    abstract val outputStream: OutputStream
    abstract fun close()
}
