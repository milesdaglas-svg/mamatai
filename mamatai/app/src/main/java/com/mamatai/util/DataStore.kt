package com.mamatai.util

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.mamatai.model.ConnectedUser
import com.mamatai.model.Voucher
import com.mamatai.model.VoucherStatus

object DataStore {

    private const val PREFS_NAME = "mamatai_prefs"
    private const val KEY_VOUCHERS = "vouchers"
    private const val KEY_USERS = "connected_users"
    private const val KEY_SETTINGS = "settings"

    private lateinit var prefs: SharedPreferences
    private val gson = Gson()

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // ── Vouchers ──────────────────────────────────────────────────────────────

    fun getVouchers(): MutableList<Voucher> {
        val json = prefs.getString(KEY_VOUCHERS, "[]") ?: "[]"
        val type = object : TypeToken<MutableList<Voucher>>() {}.type
        return gson.fromJson(json, type) ?: mutableListOf()
    }

    fun saveVouchers(vouchers: List<Voucher>) {
        prefs.edit().putString(KEY_VOUCHERS, gson.toJson(vouchers)).apply()
    }

    fun addVoucher(voucher: Voucher) {
        val list = getVouchers()
        list.add(voucher)
        saveVouchers(list)
    }

    fun findVoucherByCode(code: String): Voucher? {
        return getVouchers().find { it.code.equals(code.trim(), ignoreCase = true) }
    }

    fun updateVoucherStatus(code: String, status: VoucherStatus) {
        val list = getVouchers()
        list.find { it.code == code }?.status = status
        saveVouchers(list)
    }

    // ── Connected Users ───────────────────────────────────────────────────────

    fun getConnectedUsers(): MutableList<ConnectedUser> {
        val json = prefs.getString(KEY_USERS, "[]") ?: "[]"
        val type = object : TypeToken<MutableList<ConnectedUser>>() {}.type
        return gson.fromJson(json, type) ?: mutableListOf()
    }

    fun saveConnectedUsers(users: List<ConnectedUser>) {
        prefs.edit().putString(KEY_USERS, gson.toJson(users)).apply()
    }

    fun addOrUpdateUser(user: ConnectedUser) {
        val list = getConnectedUsers()
        val idx = list.indexOfFirst { it.macAddress == user.macAddress }
        if (idx >= 0) list[idx] = user else list.add(user)
        saveConnectedUsers(list)
    }

    fun removeUser(macAddress: String) {
        val list = getConnectedUsers()
        list.removeAll { it.macAddress == macAddress }
        saveConnectedUsers(list)
    }

    fun findUserByMac(mac: String): ConnectedUser? =
        getConnectedUsers().find { it.macAddress == mac }

    fun findUserByIp(ip: String): ConnectedUser? =
        getConnectedUsers().find { it.ipAddress == ip }

    // ── Settings ──────────────────────────────────────────────────────────────

    fun getSettings(): AppSettings {
        val json = prefs.getString(KEY_SETTINGS, null)
        return if (json != null) gson.fromJson(json, AppSettings::class.java)
               else AppSettings()
    }

    fun saveSettings(settings: AppSettings) {
        prefs.edit().putString(KEY_SETTINGS, gson.toJson(settings)).apply()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    fun getTotalRevenue(): Int = getVouchers()
        .filter { it.status != com.mamatai.model.VoucherStatus.UNUSED }
        .sumOf { it.priceUgx }

    fun getTotalDataServedMb(): Int = getConnectedUsers().sumOf { it.dataUsedMb }
}

data class AppSettings(
    val businessName: String = "MAMA.TAI",
    val ssid: String = "MAMA.TAI WiFi",
    val adminPin: String = "1234"
)
