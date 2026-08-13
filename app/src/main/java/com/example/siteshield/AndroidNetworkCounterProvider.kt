package com.example.siteshield

import android.net.TrafficStats
import android.os.Process

object AndroidNetworkCounterProvider : NetworkCounterProvider {
    override fun read(): NetworkCounterSnapshot = NetworkCounterSnapshot(
        rxBytes = TrafficStats.getUidRxBytes(Process.myUid()).supportedOrNull(),
        txBytes = TrafficStats.getUidTxBytes(Process.myUid()).supportedOrNull(),
    )

    private fun Long.supportedOrNull(): Long? =
        takeUnless { it == TrafficStats.UNSUPPORTED.toLong() || it < 0 }
}
