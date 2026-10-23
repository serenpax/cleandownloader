package com.cleantube.app.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class HistoryRecord(
    val key: String,
    val title: String,
    val label: String,
    val displayName: String,
    val uri: String,
    val isVideo: Boolean,
    val sizeBytes: Long,
    val timeMillis: Long,
)

/** 下载历史：内存 StateFlow + 本地 JSON 文件（最多保留200条） */
object HistoryStore {

    private const val MAX_ITEMS = 200
    private lateinit var file: File
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _items = MutableStateFlow<List<HistoryRecord>>(emptyList())
    val items: StateFlow<List<HistoryRecord>> = _items.asStateFlow()

    fun init(context: Context) {
        if (::file.isInitialized) return
        file = File(context.applicationContext.filesDir, "history.json")
        io.launch { load() }
    }

    fun add(record: HistoryRecord) {
        _items.update { (listOf(record) + it.filterNot { r -> r.key == record.key }).take(MAX_ITEMS) }
        saveAsync()
    }

    fun remove(key: String) {
        _items.update { list -> list.filterNot { it.key == key } }
        saveAsync()
    }

    fun clear() {
        _items.value = emptyList()
        saveAsync()
    }

    private fun load() {
        if (!file.exists()) return
        val loaded = runCatching {
            val array = JSONArray(file.readText())
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                HistoryRecord(
                    key = o.optString("key"),
                    title = o.optString("title"),
                    label = o.optString("label"),
                    displayName = o.optString("displayName"),
                    uri = o.optString("uri"),
                    isVideo = o.optBoolean("isVideo", true),
                    sizeBytes = o.optLong("size", 0),
                    timeMillis = o.optLong("time", 0),
                )
            }
        }.getOrDefault(emptyList())
        // 防止启动加载与新下载写入竞争时丢数据
        _items.update { current -> if (current.isEmpty()) loaded else current }
    }

    private fun saveAsync() {
        io.launch {
            val snapshot = _items.value
            runCatching {
                val array = JSONArray()
                snapshot.forEach { r ->
                    array.put(
                        JSONObject().apply {
                            put("key", r.key)
                            put("title", r.title)
                            put("label", r.label)
                            put("displayName", r.displayName)
                            put("uri", r.uri)
                            put("isVideo", r.isVideo)
                            put("size", r.sizeBytes)
                            put("time", r.timeMillis)
                        }
                    )
                }
                file.writeText(array.toString())
            }
        }
    }
}
