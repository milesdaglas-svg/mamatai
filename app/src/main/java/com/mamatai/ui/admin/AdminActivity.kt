package com.mamatai.ui.admin

import android.app.AlertDialog
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mamatai.R
import com.mamatai.model.Voucher
import com.mamatai.model.VoucherStatus
import com.mamatai.service.HotspotService
import com.mamatai.service.MamaTaiVpnService
import com.mamatai.service.PortalServerService
import com.mamatai.util.DataStore
import kotlinx.coroutines.*

class AdminActivity : AppCompatActivity() {

    private lateinit var userAdapter: UserAdapter
    private val VPN_REQUEST = 100
    private var refreshJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin)
        DataStore.init(applicationContext)

        setupTabs()
        setupVoucherForm()
        setupUserList()
        startServices()
    }

    override fun onResume() {
        super.onResume()
        startRefreshLoop()
    }

    override fun onPause() {
        super.onPause()
        refreshJob?.cancel()
    }

    private fun setupTabs() {
        val tabs = listOf(
            R.id.tab_dashboard to R.id.page_dashboard,
            R.id.tab_vouchers  to R.id.page_vouchers,
            R.id.tab_users     to R.id.page_users,
            R.id.tab_settings  to R.id.page_settings
        )
        tabs.forEach { (tabId, pageId) ->
            findViewById<Button>(tabId).setOnClickListener {
                tabs.forEach { (_, pid) ->
                    findViewById<LinearLayout>(pid).visibility = android.view.View.GONE
                }
                findViewById<LinearLayout>(pageId).visibility = android.view.View.VISIBLE
                refreshDashboard()
            }
        }
    }

    private fun setupVoucherForm() {
        findViewById<Button>(R.id.btn_generate).setOnClickListener {
            val name     = findViewById<EditText>(R.id.et_name).text.toString().trim()
            val dataSp   = findViewById<Spinner>(R.id.sp_data)
            val timeSp   = findViewById<Spinner>(R.id.sp_time)
            val priceStr = findViewById<EditText>(R.id.et_price).text.toString()

            val dataMb = when (dataSp.selectedItemPosition) {
                0 -> 500; 1 -> 1024; 2 -> 2048; 3 -> 5120; 4 -> 10240; else -> 0
            }
            val durationMin = when (timeSp.selectedItemPosition) {
                0 -> 60; 1 -> 180; 2 -> 720; 3 -> 1440; 4 -> 4320; 5 -> 10080; else -> 43200
            }
            val price = priceStr.toIntOrNull() ?: 0
            val code  = "WIFI-${(1000..9999).random()}"

            val voucher = Voucher(
                code = code,
                customerName = name.ifEmpty { "Customer" },
                dataLimitMb = dataMb,
                durationMinutes = durationMin,
                priceUgx = price
            )
            DataStore.addVoucher(voucher)

            val codeView = findViewById<TextView>(R.id.tv_new_code)
            val codeCard = findViewById<LinearLayout>(R.id.card_new_code)
            codeView.text = code
            codeCard.visibility = android.view.View.VISIBLE

            findViewById<Button>(R.id.btn_copy_code).setOnClickListener {
                val clipboard = getSystemService(android.content.ClipboardManager::class.java)
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("code", code))
                Toast.makeText(this, "Copied: $code", Toast.LENGTH_SHORT).show()
            }

            Toast.makeText(this, "Voucher created: $code", Toast.LENGTH_SHORT).show()
            findViewById<EditText>(R.id.et_name).text.clear()
            findViewById<EditText>(R.id.et_price).text.clear()
            refreshDashboard()
        }
    }

    private fun setupUserList() {
        userAdapter = UserAdapter(
            onToggle = { user ->
                user.isForwarding = !user.isForwarding
                DataStore.addOrUpdateUser(user)
                val msg = if (user.isForwarding) "Internet ON for ${user.voucher.customerName}"
                          else "Internet OFF for ${user.voucher.customerName}"
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                refreshDashboard()
            }
        )
        val rv = findViewById<RecyclerView>(R.id.rv_users)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = userAdapter
    }

    private fun startServices() {
        startForegroundService(Intent(this, PortalServerService::class.java))
        startForegroundService(Intent(this, HotspotService::class.java))

        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) {
            startActivityForResult(vpnIntent, VPN_REQUEST)
        } else {
            startVpnService()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST && resultCode == RESULT_OK) {
            startVpnService()
        }
    }

    private fun startVpnService() {
        val intent = Intent(this, MamaTaiVpnService::class.java).apply {
            action = MamaTaiVpnService.ACTION_START
        }
        startForegroundService(intent)
    }

    private fun startRefreshLoop() {
        refreshJob = lifecycleScope.launch {
            while (true) {
                refreshDashboard()
                delay(15_000) // refresh every 15 seconds — not too aggressive
            }
        }
    }

    private fun refreshDashboard() {
        val users    = DataStore.getConnectedUsers()
        val vouchers = DataStore.getVouchers()
        val active   = users.count { it.isForwarding && !it.isExpired }
        val revenue  = DataStore.getTotalRevenue()

        runOnUiThread {
            try {
                findViewById<TextView>(R.id.tv_active_count).text  = "$active"
                findViewById<TextView>(R.id.tv_total_users).text   = "${users.size}"
                findViewById<TextView>(R.id.tv_revenue).text       = "UGX ${String.format("%,d", revenue)}"
                findViewById<TextView>(R.id.tv_voucher_count).text = "${vouchers.size}"
                userAdapter.submitList(users.toMutableList())
            } catch (e: Exception) {}
        }
    }
}
