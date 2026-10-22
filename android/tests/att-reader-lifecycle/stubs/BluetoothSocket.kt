package android.bluetooth

import java.io.InputStream
import java.io.OutputStream

/** JVM-only API surface; the harness supplies the stream behavior. */
abstract class BluetoothSocket {
    abstract val isConnected: Boolean
    abstract val inputStream: InputStream
    abstract val outputStream: OutputStream
    abstract fun close()
}
