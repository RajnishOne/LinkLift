package com.rjnsdev.linklift.app

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

enum class MergeJobState {
    Queued,
    DownloadingVideo,
    DownloadingAudio,
    Muxing,
    Completed,
    Failed,
    Cancelled,
}

data class MergeJobRecord(
    val id: String,
    val title: String,
    val description: String,
    val sourceUrl: String,
    val outputFileName: String,
    val state: MergeJobState,
    val videoBytes: Long,
    val videoTotal: Long,
    val audioBytes: Long,
    val audioTotal: Long,
    val resultUri: String?,
    val errorMessage: String?,
    val updatedAt: Long,
    val mimeType: String,
) {
    val totalBytes: Long? get() {
        val v = videoTotal.takeIf { it > 0L }
        val a = audioTotal.takeIf { it > 0L }
        return when {
            v != null && a != null -> v + a
            v != null -> v
            a != null -> a
            else -> null
        }
    }

    val downloadedBytes: Long get() = videoBytes.coerceAtLeast(0L) + audioBytes.coerceAtLeast(0L)

    val progress: Float get() {
        val total = totalBytes ?: return when (state) {
            MergeJobState.Completed -> 1f
            MergeJobState.Failed, MergeJobState.Cancelled -> 0f
            else -> 0f
        }
        if (total <= 0L) return 0f
        return (downloadedBytes.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    }
}

private val Context.mergeJobDataStore by androidx.datastore.preferences.preferencesDataStore(
    name = "linklift_merge_jobs"
)

private val MERGE_JOBS_KEY = stringPreferencesKey("merge_jobs_v1")

object MergeJobStore {
    private const val UI_EMIT_INTERVAL_MS = 1_000L
    private const val PERSIST_INTERVAL_MS = 8_000L

    private val mutex = Any()
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Always holds the latest in-memory job state (for service snapshot / notifications). */
    private val latestById = ConcurrentHashMap<String, MergeJobRecord>()
    private val lastUiEmitAt = ConcurrentHashMap<String, Long>()
    private val lastPersistAt = ConcurrentHashMap<String, Long>()

    private val _jobs = MutableStateFlow<Map<String, MergeJobRecord>>(emptyMap())
    val jobs: StateFlow<Map<String, MergeJobRecord>> = _jobs.asStateFlow()

    @Volatile
    private var loaded = false

    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(mutex) {
            if (loaded) return@synchronized
            loaded = true
            ioScope.launch {
                runCatching {
                    val prefs = context.applicationContext.mergeJobDataStore.data
                        .catch { emit(emptyPreferences()) }
                        .first()
                    val raw = prefs[MERGE_JOBS_KEY]
                    if (!raw.isNullOrBlank()) {
                        val parsed = parse(raw)
                        if (parsed.isNotEmpty()) {
                            latestById.clear()
                            latestById.putAll(parsed)
                            _jobs.value = parsed
                        }
                    }
                }
            }
        }
    }

    fun upsert(context: Context, record: MergeJobRecord) {
        val sanitized = record.copy(updatedAt = System.currentTimeMillis())
        latestById[sanitized.id] = sanitized
        _jobs.update { current -> current + (sanitized.id to sanitized) }
        lastUiEmitAt[sanitized.id] = System.currentTimeMillis()
        lastPersistAt[sanitized.id] = System.currentTimeMillis()
        persist(context)
    }

    fun update(context: Context, id: String, transform: (MergeJobRecord) -> MergeJobRecord) {
        val existing = latestById[id] ?: _jobs.value[id] ?: return
        val next = transform(existing).copy(id = existing.id, updatedAt = System.currentTimeMillis())
        latestById[id] = next

        if (shouldEmitToUi(existing, next)) {
            lastUiEmitAt[id] = System.currentTimeMillis()
            _jobs.update { current -> current + (id to next) }
        }
        if (shouldPersist(existing, next)) {
            lastPersistAt[id] = System.currentTimeMillis()
            persist(context)
        }
    }

    fun remove(context: Context, ids: Collection<String>) {
        if (ids.isEmpty()) return
        ids.forEach { id ->
            latestById.remove(id)
            lastUiEmitAt.remove(id)
            lastPersistAt.remove(id)
        }
        _jobs.update { current -> current.filterKeys { it !in ids } }
        persist(context)
    }

    fun snapshot(): Map<String, MergeJobRecord> =
        if (latestById.isEmpty()) _jobs.value else latestById.toMap()

    private fun shouldEmitToUi(previous: MergeJobRecord, next: MergeJobRecord): Boolean {
        if (previous.state != next.state) return true
        if (previous.resultUri != next.resultUri) return true
        if (previous.errorMessage != next.errorMessage) return true
        val progressChanged = progressUiPercent(previous) != progressUiPercent(next) ||
            previous.videoBytes != next.videoBytes ||
            previous.audioBytes != next.audioBytes
        if (!progressChanged) return false
        val now = System.currentTimeMillis()
        val lastEmit = lastUiEmitAt[next.id] ?: 0L
        return now - lastEmit >= UI_EMIT_INTERVAL_MS
    }

    private fun shouldPersist(previous: MergeJobRecord, next: MergeJobRecord): Boolean {
        if (previous.state != next.state) return true
        if (previous.resultUri != next.resultUri) return true
        if (previous.errorMessage != next.errorMessage) return true
        val now = System.currentTimeMillis()
        val lastPersist = lastPersistAt[next.id] ?: 0L
        return now - lastPersist >= PERSIST_INTERVAL_MS
    }

    private fun progressUiPercent(record: MergeJobRecord): Int =
        (record.progress * 100f).toInt().coerceIn(0, 100)

    private fun persist(context: Context) {
        val payload = serialize(snapshot())
        ioScope.launch {
            context.applicationContext.mergeJobDataStore.edit { prefs ->
                if (payload.isBlank()) {
                    prefs.remove(MERGE_JOBS_KEY)
                } else {
                    prefs[MERGE_JOBS_KEY] = payload
                }
            }
        }
    }

    private fun serialize(jobs: Map<String, MergeJobRecord>): String {
        if (jobs.isEmpty()) return ""
        val array = JSONArray()
        jobs.values.forEach { job ->
            val obj = JSONObject()
            obj.put("id", job.id)
            obj.put("title", job.title)
            obj.put("description", job.description)
            obj.put("sourceUrl", job.sourceUrl)
            obj.put("outputFileName", job.outputFileName)
            obj.put("state", job.state.name)
            obj.put("videoBytes", job.videoBytes)
            obj.put("videoTotal", job.videoTotal)
            obj.put("audioBytes", job.audioBytes)
            obj.put("audioTotal", job.audioTotal)
            obj.put("resultUri", job.resultUri ?: JSONObject.NULL)
            obj.put("errorMessage", job.errorMessage ?: JSONObject.NULL)
            obj.put("updatedAt", job.updatedAt)
            obj.put("mimeType", job.mimeType)
            array.put(obj)
        }
        return array.toString()
    }

    private fun parse(raw: String): Map<String, MergeJobRecord> {
        return runCatching {
            val array = JSONArray(raw)
            buildMap {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val id = obj.optString("id").takeIf { it.isNotBlank() } ?: continue
                    val state = runCatching { MergeJobState.valueOf(obj.optString("state")) }
                        .getOrDefault(MergeJobState.Failed)
                    val healed = when (state) {
                        // A job that was actively downloading/muxing when the process died is
                        // no longer reachable — surface it as Failed so the user can re-queue.
                        MergeJobState.Queued,
                        MergeJobState.DownloadingVideo,
                        MergeJobState.DownloadingAudio,
                        MergeJobState.Muxing -> MergeJobState.Failed
                        else -> state
                    }
                    put(
                        id,
                        MergeJobRecord(
                            id = id,
                            title = obj.optString("title").ifBlank { "Merge download" },
                            description = obj.optString("description"),
                            sourceUrl = obj.optString("sourceUrl"),
                            outputFileName = obj.optString("outputFileName").ifBlank { "$id.mp4" },
                            state = healed,
                            videoBytes = obj.optLong("videoBytes", 0L),
                            videoTotal = obj.optLong("videoTotal", 0L),
                            audioBytes = obj.optLong("audioBytes", 0L),
                            audioTotal = obj.optLong("audioTotal", 0L),
                            resultUri = obj.optString("resultUri").takeIf { it.isNotBlank() && it != "null" },
                            errorMessage = when (healed) {
                                MergeJobState.Failed -> obj.optString("errorMessage").takeIf { it.isNotBlank() && it != "null" }
                                    ?: "Interrupted by app restart. Retry the download."
                                else -> obj.optString("errorMessage").takeIf { it.isNotBlank() && it != "null" }
                            },
                            updatedAt = obj.optLong("updatedAt", System.currentTimeMillis()),
                            mimeType = obj.optString("mimeType").ifBlank { "video/mp4" },
                        ),
                    )
                }
            }
        }.getOrDefault(emptyMap())
    }
}

