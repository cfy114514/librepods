package android.bluetooth.le
import android.bluetooth.BluetoothDevice
open class ScanCallback {
    open fun onScanResult(callbackType: Int, result: ScanResult) {}
    open fun onBatchScanResults(results: List<ScanResult>) {}
    open fun onScanFailed(errorCode: Int) {}
}
class ScanRecord(private val data: ByteArray) { fun getManufacturerSpecificData(id: Int): ByteArray? = if (id == 76) data else null }
class ScanResult(val device: BluetoothDevice, val scanRecord: ScanRecord?, val timestampNanos: Long)
class BluetoothLeScanner {
    var callback: ScanCallback? = null
    fun startScan(filters: List<ScanFilter>, settings: ScanSettings, callback: ScanCallback?) { this.callback = callback }
    fun stopScan(callback: ScanCallback) { if (this.callback === callback) this.callback = null }
}
class ScanFilter { class Builder {
    fun setManufacturerData(id: Int, data: ByteArray, mask: ByteArray) = this
    fun build() = ScanFilter()
} }
class ScanSettings {
    companion object { const val SCAN_MODE_LOW_LATENCY=2; const val MATCH_MODE_AGGRESSIVE=1; const val CALLBACK_TYPE_ALL_MATCHES=1; const val MATCH_NUM_MAX_ADVERTISEMENT=3 }
    class Builder {
        fun setScanMode(value: Int) = this
        fun setMatchMode(value: Int) = this
        fun setCallbackType(value: Int) = this
        fun setNumOfMatches(value: Int) = this
        fun setReportDelay(value: Long) = this
        fun build() = ScanSettings()
    }
}
