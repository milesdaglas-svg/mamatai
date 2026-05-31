package com.mamatai.model

import java.util.UUID

data class Voucher(
    val id: String = UUID.randomUUID().toString(),
    val code: String,
    val customerName: String = "",
    val dataLimitMb: Int,        // 0 = unlimited
    val durationMinutes: Int,
    val priceUgx: Int,
    val createdAt: Long = System.currentTimeMillis(),
    var status: VoucherStatus = VoucherStatus.UNUSED
)

enum class VoucherStatus { UNUSED, ACTIVE, PAUSED, EXPIRED }
