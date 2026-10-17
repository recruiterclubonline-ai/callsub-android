package com.callsub.translator.storage

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Логирует реплики и субтитры звонков в локальные файлы истории.
 * Позволяет пользователю после завершения разговора прочитать адреса, даты и важные детали.
 */
class TranscriptLogger(private val context: Context) {

    private val gson = Gson()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())
    private val displayTimeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private var currentSessionFile: File? = null

    data class TranscriptEntry(
        val time: String,
        val german: String,
        val russian: String
    )

    data class CallSummary(
        val filename: String,
        val formattedDate: String,
        val preview: String,
        val entriesCount: Int
    )

    fun startNewSession() {
        try {
            val historyDir = getHistoryDir()
            val filename = "call_${dateFormat.format(Date())}.jsonl"
            currentSessionFile = File(historyDir, filename)
            Log.i(TAG, "Сессия логирования начата: $filename")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка создания файла лога: ${e.message}")
        }
    }

    suspend fun logEntry(germanText: String, russianText: String) = withContext(Dispatchers.IO) {
        val file = currentSessionFile ?: return@withContext
        if (germanText.isBlank() && russianText.isBlank()) return@withContext

        try {
            val entry = TranscriptEntry(
                time = displayTimeFormat.format(Date()),
                german = germanText.trim(),
                russian = russianText.trim()
            )
            val line = gson.toJson(entry) + "\n"
            file.appendText(line)
        } catch (e: Exception) {
            Log.w(TAG, "Ошибка записи строки лога: ${e.message}")
        }
    }

    suspend fun getCallSummaries(): List<CallSummary> = withContext(Dispatchers.IO) {
        val historyDir = getHistoryDir()
        val files = historyDir.listFiles { f -> f.extension == "jsonl" } ?: return@withContext emptyList()

        return@withContext files.sortedByDescending { it.lastModified() }.map { file ->
            val lines = file.readLines()
            val firstLine = lines.firstOrNull()
            val preview = if (firstLine != null) {
                try {
                    val entry = gson.fromJson(firstLine, TranscriptEntry::class.java)
                    entry.russian
                } catch (_: Exception) {
                    "Разговор без текста"
                }
            } else "Пустой разговор"

            val dateStr = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(file.lastModified()))

            CallSummary(
                filename = file.name,
                formattedDate = dateStr,
                preview = preview,
                entriesCount = lines.size
            )
        }
    }

    suspend fun loadEntriesForCall(filename: String): List<TranscriptEntry> = withContext(Dispatchers.IO) {
        val file = File(getHistoryDir(), filename)
        if (!file.exists()) return@withContext emptyList()

        val raw = file.readLines().mapNotNull { line ->
            try {
                gson.fromJson(line, TranscriptEntry::class.java)
            } catch (_: Exception) {
                null
            }
        }

        val merged = mutableListOf<TranscriptEntry>()
        for (item in raw) {
            val last = merged.lastOrNull()
            val text = item.russian.trim()
            if (text.isBlank()) continue

            // Если фраза является продолжением мысли (начинается с союза, со строчной буквы или времени)
            val isContinuation = last != null && (
                text.startsWith("и ", ignoreCase = true) ||
                text.startsWith("а ", ignoreCase = true) ||
                text.startsWith("в ", ignoreCase = true) ||
                text.startsWith("на ", ignoreCase = true) ||
                text.startsWith("секунд", ignoreCase = true) ||
                text.startsWith("минут", ignoreCase = true) ||
                (text.firstOrNull()?.isLowerCase() == true)
            )

            if (isContinuation && last != null) {
                merged[merged.size - 1] = last.copy(
                    russian = "${last.russian} $text".trim()
                )
            } else {
                merged.add(item)
            }
        }
        return@withContext merged
    }

    private fun getHistoryDir(): File {
        val dir = File(context.filesDir, "call_transcripts")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    companion object {
        private const val TAG = "TranscriptLogger"
    }
}
