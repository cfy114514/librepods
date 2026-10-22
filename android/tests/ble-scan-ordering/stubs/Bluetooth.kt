package android.bluetooth
import android.bluetooth.le.BluetoothLeScanner
class BluetoothDevice(val address: String)
class BluetoothAdapter(val bluetoothLeScanner: BluetoothLeScanner) { val isEnabled = true }
class BluetoothManager(val adapter: BluetoothAdapter?)
