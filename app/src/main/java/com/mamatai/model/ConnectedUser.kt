package com.mamatai.model

data class ConnectedUser(
    val id: String,
    val macAddress: String,
    val deviceName: String,
    val ipAddress: String,
    val voucher: Voucher,
    val connectedAt: Long = System.currentTimeMillis(),
    var dataUsedMb: Int = 0,
    var isForwarding: Boolean = true,       // true = internet ON, false = internet OFF
    var activatedAt: Long = System.currentTimeMillis()
) {
    val dataRemainingMb: Int
        get() = if (voucher.dataLimitMb == 0) Int.MAX_VALUE
                else maxOf(0, voucher.dataLimitMb - dataUsedMb)

    val isExpiredByData: Boolean
        get() = voucher.dataLimitMb > 0 && dataUsedMb >= voucher.dataLimitMb

    val elapsedMinutes: Long
        get() = (System.currentTimeMillis() - activatedAt) / 60000

    val isExpiredByTime: Boolean
        get() = elapsedMinutes >= voucher.durationMinutes

    val isExpired: Boolean
        get() = isExpiredByData || isExpiredByTime

    val timeRemainingMinutes: Long
        get() = maxOf(0, voucher.durationMinutes - elapsedMinutes)
}
