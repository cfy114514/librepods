package me.kavishdevar.librepods.bluetooth

import android.bluetooth.BluetoothSocket

object BluetoothConnectionManager {
    @Volatile var attSocket: BluetoothSocket? = null
}
