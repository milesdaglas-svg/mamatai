package com.mamatai.ui.admin

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Switch
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.mamatai.R
import com.mamatai.model.ConnectedUser

class UserAdapter(
    private val onToggle: (ConnectedUser) -> Unit
) : ListAdapter<ConnectedUser, UserAdapter.ViewHolder>(DIFF) {

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<ConnectedUser>() {
            override fun areItemsTheSame(a: ConnectedUser, b: ConnectedUser) = a.id == b.id
            override fun areContentsTheSame(a: ConnectedUser, b: ConnectedUser) = a == b
        }
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName:    TextView = view.findViewById(R.id.tv_user_name)
        val tvMeta:    TextView = view.findViewById(R.id.tv_user_meta)
        val tvData:    TextView = view.findViewById(R.id.tv_user_data)
        val tvStatus:  TextView = view.findViewById(R.id.tv_user_status)
        val swToggle:  Switch   = view.findViewById(R.id.sw_toggle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_user, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val user = getItem(position)
        holder.tvName.text   = user.voucher.customerName.ifEmpty { "Unknown device" }
        holder.tvMeta.text   = "${user.voucher.code} · ${user.ipAddress} · UGX ${String.format("%,d", user.voucher.priceUgx)}"
        holder.tvData.text   = "${user.dataUsedMb}MB used / ${if (user.voucher.dataLimitMb == 0) "∞" else "${user.voucher.dataLimitMb}MB"}"
        holder.tvStatus.text = when {
            user.isExpired      -> "EXPIRED"
            user.isForwarding   -> "ONLINE"
            else                -> "PAUSED"
        }
        holder.swToggle.isChecked = user.isForwarding && !user.isExpired
        holder.swToggle.setOnCheckedChangeListener(null)
        holder.swToggle.setOnCheckedChangeListener { _, _ -> onToggle(user) }
    }
}
