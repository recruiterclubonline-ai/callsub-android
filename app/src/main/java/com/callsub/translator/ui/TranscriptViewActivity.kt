package com.callsub.translator.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.callsub.translator.R
import com.callsub.translator.storage.TranscriptLogger
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.launch

/**
 * Экран истории звонков: отображает список завершенных разговоров и их детальные стенограммы.
 */
class TranscriptViewActivity : AppCompatActivity() {

    private lateinit var logger: TranscriptLogger
    private lateinit var rvTranscripts: RecyclerView
    private lateinit var tvEmpty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_transcript)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar_transcript)
        toolbar.setNavigationOnClickListener { finish() }

        logger = TranscriptLogger(this)
        rvTranscripts = findViewById(R.id.rv_transcripts)
        tvEmpty = findViewById(R.id.tv_empty_history)

        rvTranscripts.layoutManager = LinearLayoutManager(this)

        loadHistory()
    }

    private fun loadHistory() {
        lifecycleScope.launch {
            val summaries = logger.getCallSummaries()
            if (summaries.isEmpty()) {
                tvEmpty.visibility = View.VISIBLE
                rvTranscripts.visibility = View.GONE
            } else {
                tvEmpty.visibility = View.GONE
                rvTranscripts.visibility = View.VISIBLE
                rvTranscripts.adapter = TranscriptAdapter(summaries) { summary ->
                    showCallDetails(summary)
                }
            }
        }
    }

    private fun showCallDetails(summary: TranscriptLogger.CallSummary) {
        lifecycleScope.launch {
            val entries = logger.loadEntriesForCall(summary.filename)
            val dialogView = LayoutInflater.from(this@TranscriptViewActivity)
                .inflate(R.layout.dialog_transcript_details, null)

            val tvDate = dialogView.findViewById<TextView>(R.id.tv_dialog_date)
            val llMessages = dialogView.findViewById<android.widget.LinearLayout>(R.id.ll_transcript_messages)
            val btnCopy = dialogView.findViewById<android.widget.Button>(R.id.btn_dialog_copy)
            val btnClose = dialogView.findViewById<android.widget.Button>(R.id.btn_dialog_close)

            tvDate.text = summary.formattedDate

            val inflater = LayoutInflater.from(this@TranscriptViewActivity)
            if (entries.isEmpty()) {
                val emptyTv = TextView(this@TranscriptViewActivity).apply {
                    text = "В этом разговоре нет распознанных фраз."
                    setTextColor(getColor(R.color.text_sub))
                    setPadding(16, 32, 16, 32)
                }
                llMessages.addView(emptyTv)
            } else {
                for (entry in entries) {
                    val bubbleView = inflater.inflate(R.layout.item_transcript_bubble, llMessages, false)
                    val tvTime = bubbleView.findViewById<TextView>(R.id.tv_bubble_time)
                    val tvText = bubbleView.findViewById<TextView>(R.id.tv_bubble_text)
                    tvTime.text = entry.time
                    tvText.text = entry.russian
                    llMessages.addView(bubbleView)
                }
            }

            val dialog = AlertDialog.Builder(this@TranscriptViewActivity)
                .setView(dialogView)
                .create()

            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

            val fullTranscriptText = entries.joinToString("\n\n") { "[${it.time}] ${it.russian}" }

            btnCopy.setOnClickListener {
                val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("CallSub Transcript", fullTranscriptText)
                clipboard.setPrimaryClip(clip)
                android.widget.Toast.makeText(this@TranscriptViewActivity, "Текст разговора скопирован!", android.widget.Toast.LENGTH_SHORT).show()
            }

            btnClose.setOnClickListener {
                dialog.dismiss()
            }

            dialog.show()
        }
    }

    class TranscriptAdapter(
        private val items: List<TranscriptLogger.CallSummary>,
        private val onClick: (TranscriptLogger.CallSummary) -> Unit
    ) : RecyclerView.Adapter<TranscriptAdapter.ViewHolder>() {

        class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val tvDate: TextView = v.findViewById(R.id.tv_call_date)
            val tvEntries: TextView = v.findViewById(R.id.tv_call_entries)
            val tvPreview: TextView = v.findViewById(R.id.tv_call_preview)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_transcript_call, parent, false)
            return ViewHolder(v)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.tvDate.text = item.formattedDate
            holder.tvEntries.text = "${item.entriesCount} фраз"
            holder.tvPreview.text = item.preview
            holder.itemView.setOnClickListener { onClick(item) }
        }

        override fun getItemCount(): Int = items.size
    }
}
