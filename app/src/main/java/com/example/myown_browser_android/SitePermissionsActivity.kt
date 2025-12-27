package com.example.myown_browser_android

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class SitePermissionsActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: SitePermissionsAdapter
    private lateinit var clearAllButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_site_permissions)

        recyclerView = findViewById(R.id.site_permissions_recycler_view)
        recyclerView.layoutManager = LinearLayoutManager(this)
        clearAllButton = findViewById(R.id.clear_all_button)

        loadSitePermissions()

        clearAllButton.setOnClickListener {
            val sharedPrefs = getSharedPreferences("SitePermissions", Context.MODE_PRIVATE)
            sharedPrefs.edit {
                clear()
            }
            adapter.clear()
        }
    }

    private fun loadSitePermissions() {
        val sharedPrefs = getSharedPreferences("SitePermissions", Context.MODE_PRIVATE)
        val allEntries = sharedPrefs.all
        val sitePermissions = allEntries.mapNotNull { (key, value) ->
            (value as? Set<String>)?.let { SitePermission(key, it) }
        }.toMutableList()

        adapter = SitePermissionsAdapter(sitePermissions) { sitePermission ->
            removeSitePermission(sitePermission)
        }
        recyclerView.adapter = adapter
    }

    private fun removeSitePermission(sitePermission: SitePermission) {
        val sharedPrefs = getSharedPreferences("SitePermissions", Context.MODE_PRIVATE)
        sharedPrefs.edit {
            remove(sitePermission.url)
        }
        adapter.remove(sitePermission)
    }
}

data class SitePermission(val url: String, val permissions: Set<String>)

class SitePermissionsAdapter(
    private val sitePermissions: MutableList<SitePermission>,
    private val onRemoveClick: (SitePermission) -> Unit
) : RecyclerView.Adapter<SitePermissionsAdapter.ViewHolder>() {

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val siteUrlTextView: TextView = itemView.findViewById(R.id.site_url_text_view)
        val permissionsTextView: TextView = itemView.findViewById(R.id.permissions_text_view)
        val removeButton: Button = itemView.findViewById(R.id.remove_button)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.site_permission_item, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val sitePermission = sitePermissions[position]
        holder.siteUrlTextView.text = sitePermission.url
        holder.permissionsTextView.text = sitePermission.permissions.joinToString(", ")
        holder.removeButton.setOnClickListener { onRemoveClick(sitePermission) }
    }

    override fun getItemCount(): Int {
        return sitePermissions.size
    }

    fun clear() {
        val size = sitePermissions.size
        sitePermissions.clear()
        notifyItemRangeRemoved(0, size)
    }

    fun remove(sitePermission: SitePermission) {
        val position = sitePermissions.indexOf(sitePermission)
        if (position > -1) {
            sitePermissions.removeAt(position)
            notifyItemRemoved(position)
        }
    }
}
