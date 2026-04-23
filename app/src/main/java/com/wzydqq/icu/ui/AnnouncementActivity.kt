package com.wzydqq.icu.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.wzydqq.icu.AnnouncementManager
import com.wzydqq.icu.NoticeItem
import com.wzydqq.icu.R
import com.wzydqq.icu.databinding.ActivityAnnouncementBinding
import kotlinx.coroutines.launch

class AnnouncementActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAnnouncementBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAnnouncementBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        loadAnnouncements()
    }

    private fun loadAnnouncements() {
        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val config = AnnouncementManager.fetchConfig()
                ?: AnnouncementManager.getCachedConfig()

            binding.progressBar.visibility = View.GONE

            if (config != null && config.notices.isNotEmpty()) {
                binding.recyclerView.adapter = NoticeAdapter(config.notices)
                binding.tvEmpty.visibility = View.GONE
            } else {
                binding.tvEmpty.visibility = View.VISIBLE
                binding.tvEmpty.text = "暂无公告"
            }
        }
    }

    class NoticeAdapter(private val items: List<NoticeItem>) :
        RecyclerView.Adapter<NoticeAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvTitle: TextView = view.findViewById(R.id.tvNoticeTitle)
            val tvContent: TextView = view.findViewById(R.id.tvNoticeContent)
            val tvTime: TextView = view.findViewById(R.id.tvNoticeTime)
            val badge: TextView = view.findViewById(R.id.tvBadge)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_notice, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.tvTitle.text = item.title
            holder.tvContent.text = item.content
            holder.tvTime.text = item.time
            if (item.important) {
                holder.badge.visibility = View.VISIBLE
                holder.badge.text = "重要"
            } else {
                holder.badge.visibility = View.GONE
            }
        }

        override fun getItemCount() = items.size
    }
}
