package android.bluetooth
import android.bluetooth.le.BluetoothLeScanner
class BluetoothDevice(val address: String)
class BluetoothAdapter(var bluetoothLeScanner: BluetoothLeScanner?) { var isEnabled = true }
class BluetoothManager(val adapter: BluetoothAdapter?)
