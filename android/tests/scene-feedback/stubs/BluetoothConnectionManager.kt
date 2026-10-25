package me.kavishdevar.librepods.bluetooth

import android.bluetooth.BluetoothSocket

/** A connection slot, without any production session or write logic. */
object BluetoothConnectionManager {
    @Volatile var aacpSocket: BluetoothSocket? = null
}
