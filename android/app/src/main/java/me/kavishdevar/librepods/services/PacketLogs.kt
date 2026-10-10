package me.kavishdevar.librepods.services

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers

/** Only the application preferences survive service replacement, never a Service Context. */
internal object PacketLogs {
    private var history: PacketLogHistory? = null
    @Synchronized fun open(context: Context): PacketLogHistory {
        history?.let { return it }
        val preferences = context.applicationContext.getSharedPreferences("packet_logs", Context.MODE_PRIVATE)
        return PacketLogHistory(Dispatchers.IO,
            load = { preferences.getStringSet("packet_log", emptySet())?.toSet().orEmpty() },
            save = { snapshot -> preferences.edit {
                if (snapshot.isEmpty()) remove("packet_log") else putStringSet("packet_log", snapshot)
            } }, onError = { Log.w("PacketLogs", "Packet history persistence failed", it) }).also { history = it }
    }
}
