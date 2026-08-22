package com.rjnsdev.linklift.app

import android.app.Application
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.os.SystemClock
import android.util.Patterns
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.rjnsdev.linklift.app.util.CookieHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

private const val instagramWebAppId = "936619743392459"
private const val instagramReelGraphQlDocId = "24368985919464652"

private data class ExtractedPageMedia(
    val mediaUrl: String,
    val mimeType: String?,
    val kind: MediaKind,
    val title: String?,
    val description: String,
    val platformLabel: String,
)

class LinkLiftViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    private val okHttpClient = OkHttpClient.Builder().followRedirects(true).followSslRedirects(true).build()
    private val downloadManager: DownloadManager? = runCatching {
        appContext.getSystemService(DownloadManager::class.java)
    }.getOrNull()

    private var analysisJob: Job? = null
    /** Active batch-download coroutine that walks the selected playlist/bulk entries. */
    private var batchProcessingJob: Job? = null

    private val _uiState = MutableStateFlow(
        LinkLiftUiState(
            settings = UserPreferences(
                downloadLocation = defaultDownloadLocation(),
            ),
            downloadServiceAvailable = downloadManager != null,
            isYouTubeAvailable = RemoteConfigHelper.isYouTubeAvailable,
            isSoundCloudAvailable = RemoteConfigHelper.isSoundCloudAvailable,
            isImgurAvailable = RemoteConfigHelper.isImgurAvailable,
        )
    )
    val uiState: StateFlow<LinkLiftUiState> = _uiState.asStateFlow()

    init {
        updateRemoteConfigFlags()
        viewModelScope.launch(Dispatchers.IO) {
            RemoteConfigHelper.fetchConfig(okHttpClient) {
                updateRemoteConfigFlags()
            }
        }

        observePreferences()

        MergeJobStore.ensureLoaded(appContext)
        observeMergeJobs()
        if (downloadManager == null) {
            _uiState.update {
                it.copy(message = "Download service is unavailable on this device right now.")
            }
        } else {
            viewModelScope.launch(Dispatchers.IO) {
                while (isActive) {
                    runCatching { refreshDownloads() }
                    delay(2_000)
                }
            }
        }
    }

    private fun observeMergeJobs() {
        viewModelScope.launch {
            MergeJobStore.jobs
                .distinctUntilChanged { old, new -> mergeJobsUiFingerprint(old) == mergeJobsUiFingerprint(new) }
                .collect { jobs ->
                    val hasFailed403 = jobs.values.any { job ->
                        job.state == MergeJobState.Failed && (job.errorMessage?.contains("403") == true || job.errorMessage?.contains("YouTube", ignoreCase = true) == true)
                    }
                    val hasCookies = CookieHelper.hasValidCookies(appContext)
                    _uiState.update { current ->
                        val merged = mergeDownloadsList(
                            base = current.downloads.filter { it.id >= 0 },
                            mergeJobs = jobs.values.toList(),
                            previous = current.downloads,
                        )
                        val shouldPrompt = RemoteConfigHelper.isYouTubeAvailable && hasFailed403 && !hasCookies && !current.showYouTubeAuthPrompt
                        val next = if (merged == current.downloads) current else current.copy(downloads = merged)
                        if (shouldPrompt) {
                            next.copy(
                                showYouTubeAuthPrompt = true,
                                youTubeAuthPromptReason = "A YouTube download was blocked by stream protection (HTTP 403). Sign in or import cookies to download this video.",
                            )
                        } else next
                    }
                }
        }
    }

    private fun mergeJobsUiFingerprint(jobs: Map<String, MergeJobRecord>): Int {
        var fingerprint = jobs.size
        jobs.values.forEach { job ->
            fingerprint = 31 * fingerprint + job.id.hashCode()
            fingerprint = 31 * fingerprint + job.state.ordinal
            fingerprint = 31 * fingerprint + (job.progress * 100f).toInt()
            fingerprint = 31 * fingerprint + (job.resultUri?.hashCode() ?: 0)
            fingerprint = 31 * fingerprint + (job.errorMessage?.hashCode() ?: 0)
        }
        return fingerprint
    }

    private fun mergeDownloadsList(
        base: List<DownloadEntry>,
        mergeJobs: List<MergeJobRecord>,
        previous: List<DownloadEntry> = emptyList(),
    ): List<DownloadEntry> {
        val previousById = previous.associateBy { it.id }
        val mergeEntries = mergeJobs.map { job ->
            val next = job.toDownloadEntry()
            val prev = previousById[next.id]
            if (prev != null && prev.hasSameDownloadIdentity(next)) {
                prev.copy(
                    description = next.description,
                    state = next.state,
                    progress = next.progress,
                    downloadedBytes = next.downloadedBytes,
                    totalBytes = next.totalBytes,
                    localUri = next.localUri,
                    updatedAt = next.updatedAt,
                )
            } else {
                next
            }
        }
        return (base + mergeEntries).sortedByDescending { it.updatedAt }
    }

    private fun DownloadEntry.hasSameDownloadIdentity(other: DownloadEntry): Boolean =
        id == other.id &&
            title == other.title &&
            kind == other.kind &&
            mimeType == other.mimeType &&
            localUri == other.localUri &&
            sourceUrl == other.sourceUrl

    fun onUrlChanged(value: String) {
        _uiState.update { it.copy(inputUrl = value) }
    }

    fun openScreen(screen: AppScreen) {
        _uiState.update { it.copy(currentScreen = screen) }
    }

    fun backFromPreview() {
        _uiState.update {
            it.copy(
                currentScreen = AppScreen.Home,
                selectedPreviewIndex = 0,
                selectedItemIndices = emptySet(),
            )
        }
    }

    fun backFromBatch() {
        if (uiState.value.batchProgress?.isActive == true) {
            _uiState.update { it.copy(message = "Wait for the batch to finish before going back.") }
            return
        }
        _uiState.update {
            it.copy(
                currentScreen = AppScreen.Home,
                batch = null,
                selectedBatchIndices = emptySet(),
                batchProgress = null,
            )
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    fun showMessage(message: String) {
        _uiState.update { it.copy(message = message) }
    }

    fun selectFormat(itemIndex: Int, formatId: String) {
        _uiState.update { state ->
            val preview = state.preview ?: return@update state
            val item = preview.items.getOrNull(itemIndex) ?: return@update state
            if (item.formats.none { it.formatId == formatId }) return@update state
            val updated = state.selectedFormatIdsByItem.toMutableMap()
            updated[itemIndex] = formatId
            state.copy(selectedFormatIdsByItem = updated)
        }
    }

    fun setAudioOnlyFilter(enabled: Boolean) {
        _uiState.update { state ->
            val preview = state.preview ?: return@update state.copy(showAudioOnly = enabled)
            val newSelections = preview.items.mapIndexed { index, item ->
                val available = if (enabled) item.formats.filter { it.isAudioOnly } else item.formats
                val current = state.selectedFormatIdsByItem[index]
                val selected = available.firstOrNull { it.formatId == current }
                    ?: chooseFormatForPreset(available, state.settings.preferredQuality)
                index to selected?.formatId
            }
            val filtered = newSelections.mapNotNull { (idx, id) -> id?.let { idx to it } }.toMap()
            state.copy(showAudioOnly = enabled, selectedFormatIdsByItem = filtered)
        }
    }

    fun selectPreviewItem(index: Int) {
        _uiState.update { state ->
            val preview = state.preview ?: return@update state
            val boundedIndex = index.coerceIn(0, preview.items.lastIndex.coerceAtLeast(0))
            state.copy(selectedPreviewIndex = boundedIndex)
        }
    }

    fun handleSharedText(sharedText: String?): Boolean {
        val text = sharedText?.trim().orEmpty()
        if (text.isBlank()) return false

        val urls = extractAllUrls(text)
        if (urls.isEmpty()) {
            _uiState.update {
                it.copy(
                    currentScreen = AppScreen.Home,
                    message = "Couldn't find a link in what was shared.",
                )
            }
            return true
        }

        val disabledMessage = RemoteConfigHelper.getDisabledPlatformMessageForAny(urls)
        if (disabledMessage != null) {
            _uiState.update {
                it.copy(
                    currentScreen = AppScreen.Home,
                    message = "Downloads from this source are not supported."
                )
            }
            return true
        }

        _uiState.update {
            it.copy(
                currentScreen = AppScreen.Home,
                inputUrl = if (urls.size == 1) urls.first() else urls.joinToString("\n"),
                message = if (urls.size > 1) {
                    "Received ${urls.size} links. Preparing your batch..."
                } else {
                    "Link received. Checking it now..."
                },
            )
        }
        analyzeInput(rawInput = if (urls.size == 1) urls.first() else urls.joinToString("\n"))
        return true
    }

    fun analyzeLink() {
        val rawInput = uiState.value.inputUrl.trim()
        if (rawInput.isBlank()) {
            _uiState.update { it.copy(message = "Paste a link to continue.") }
            return
        }
        analyzeInput(rawInput = rawInput)
    }

    private fun analyzeInput(rawInput: String) {
        val urls = extractAllUrls(rawInput)

        val disabledMessage = RemoteConfigHelper.getDisabledPlatformMessageForAny(urls)
        if (disabledMessage != null) {
            _uiState.update {
                it.copy(
                    currentScreen = AppScreen.Home,
                    message = "Downloads from this source are not supported."
                )

            }
            return
        }

        when {
            urls.isEmpty() -> {
                _uiState.update {
                    it.copy(message = "No valid links found in what you pasted.")
                }
            }
            urls.size > 1 -> {
                presentBulkBatch(rawInput, urls)
            }
            looksLikePlaylistUrl(urls.first()) -> {
                resolveAsPlaylist(rawUrl = urls.first())
            }
            else -> {
                analyzeLink(rawUrl = urls.first())
            }
        }
    }

    private fun presentBulkBatch(rawInput: String, urls: List<String>) {
        val cappedUrls = urls.take(BATCH_MAX_ITEMS)
        val entries = cappedUrls.map { url ->
            LinkBatchEntry(
                sourceUrl = url,
                title = shortenUrlForDisplay(url),
                host = normalizedHost(url),
            )
        }
        val batch = LinkBatch(
            source = BatchSource.BulkPaste,
            sourceUrl = rawInput,
            label = "Bulk paste",
            title = "${entries.size} links pasted",
            entries = entries,
            totalCount = urls.size,
            returnedCount = entries.size,
            isTruncated = urls.size > entries.size,
        )
        showBatchPreview(batch)
    }

    private fun resolveAsPlaylist(rawUrl: String) {
        analysisJob?.cancel()
        analysisJob = viewModelScope.launch(Dispatchers.IO) {
            val startedAt = SystemClock.elapsedRealtime()
            _uiState.update {
                it.copy(
                    currentScreen = AppScreen.Processing,
                    message = null,
                    analysisStartedAt = startedAt,
                    analysisLatencyMs = null,
                )
            }

            try {
                val batch = inspectAsPlaylist(rawUrl)
                ensureActive()
                val latency = SystemClock.elapsedRealtime() - startedAt
                if (batch == null) {
                    analyzeLink(rawUrl = rawUrl)
                    return@launch
                }
                _uiState.update {
                    it.copy(
                        currentScreen = AppScreen.Batch,
                        batch = batch,
                        selectedBatchIndices = defaultBatchSelection(batch),
                        batchProgress = null,
                        preview = null,
                        analysisLatencyMs = latency,
                    )
                }
            } catch (cancellation: CancellationException) {
                // The UI state is reset by cancelAnalysis(); just bail.
                throw cancellation
            } catch (error: Throwable) {
                val isYt = isYouTubeUrl(rawUrl)
                val hasCookies = CookieHelper.hasValidCookies(appContext)
                val errMsg = error.message.orEmpty()
                val needsYouTubeAuth = RemoteConfigHelper.isYouTubeAvailable && isYt && (!hasCookies || errMsg.contains("bot", ignoreCase = true) || errMsg.contains("Sign in", ignoreCase = true) || errMsg.contains("403") || errMsg.contains("cookies", ignoreCase = true))

                _uiState.update {
                    it.copy(
                        currentScreen = AppScreen.Home,
                        analysisLatencyMs = null,
                        message = error.message ?: "Unable to read that playlist.",
                        showYouTubeAuthPrompt = needsYouTubeAuth,
                        youTubeAuthPromptReason = if (needsYouTubeAuth) {
                            "YouTube playlist extraction was blocked by bot protection. Sign in with YouTube or import cookies to proceed."
                        } else null,
                    )
                }
            }
        }
    }

    private fun showBatchPreview(batch: LinkBatch) {
        _uiState.update {
            it.copy(
                currentScreen = AppScreen.Batch,
                batch = batch,
                selectedBatchIndices = defaultBatchSelection(batch),
                batchProgress = null,
                preview = null,
                analysisStartedAt = null,
                analysisLatencyMs = null,
            )
        }
    }

    private fun defaultBatchSelection(batch: LinkBatch): Set<Int> {
        return if (batch.entries.size > BATCH_AUTO_SELECT_LIMIT) {
            emptySet()
        } else {
            batch.entries.indices.toSet()
        }
    }

    private fun analyzeLink(rawUrl: String) {
        val disabledMessage = RemoteConfigHelper.getDisabledPlatformMessage(rawUrl)
        if (disabledMessage != null) {
            _uiState.update {
                it.copy(
                    currentScreen = AppScreen.Home,
                    message = "Downloads from this source are not supported."
                )
            }
            return
        }

        analysisJob?.cancel()
        analysisJob = viewModelScope.launch(Dispatchers.IO) {
            val startedAt = SystemClock.elapsedRealtime()
            _uiState.update {
                it.copy(
                    currentScreen = AppScreen.Processing,
                    message = null,
                    analysisStartedAt = startedAt,
                    analysisLatencyMs = null,
                )
            }

            try {
                val preview = if (isInstagramHost(rawUrl)) {
                    inspectInstagramLink(url = rawUrl)
                } else {
                    resolveWithPythonOrFallback(rawUrl)
                }
                ensureActive()
                val latency = SystemClock.elapsedRealtime() - startedAt
                _uiState.update { current ->
                    val preset = current.settings.preferredQuality
                    val initialSelections = preview.items.mapIndexedNotNull { index, item ->
                        chooseFormatForPreset(item.formats, preset)?.let { fmt ->
                            index to fmt.formatId
                        }
                    }.toMap()
                    current.copy(
                        currentScreen = AppScreen.Preview,
                        preview = preview,
                        selectedPreviewIndex = 0,
                        selectedItemIndices = preview.items.indices.toSet(),
                        selectedFormatIdsByItem = initialSelections,
                        showAudioOnly = false,
                        batch = null,
                        selectedBatchIndices = emptySet(),
                        batchProgress = null,
                        analysisLatencyMs = latency,
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                val isYt = isYouTubeUrl(rawUrl)
                val hasCookies = CookieHelper.hasValidCookies(appContext)
                val errMsg = error.message.orEmpty()
                val needsYouTubeAuth = RemoteConfigHelper.isYouTubeAvailable && isYt && (!hasCookies || errMsg.contains("bot", ignoreCase = true) || errMsg.contains("Sign in", ignoreCase = true) || errMsg.contains("403") || errMsg.contains("cookies", ignoreCase = true) || errMsg.contains("reloaded", ignoreCase = true))

                _uiState.update {
                    it.copy(
                        currentScreen = AppScreen.Home,
                        preview = null,
                        analysisLatencyMs = null,
                        message = error.message ?: "Unable to analyze that link.",
                        showYouTubeAuthPrompt = needsYouTubeAuth,
                        youTubeAuthPromptReason = if (needsYouTubeAuth) {
                            "YouTube requires authentication or session cookies to analyze and download this video without bot blocks."
                        } else null,
                    )
                }
            }
        }
    }

    /**
     * Cancel an in-flight link analysis (Processing screen). Safe to call when
     * no analysis is running. Resets the UI back to the Home screen. The
     * underlying yt-dlp / HTTP work may continue briefly on its IO thread
     * because Python calls aren't truly interruptible, but its result is
     * discarded and the user is unblocked immediately.
     */
    fun cancelAnalysis() {
        val job = analysisJob
        analysisJob = null
        if (job == null || !job.isActive) {
            // Nothing in flight — still make sure we're not stranded on the
            // Processing screen (e.g. analysis completed but UI is mid-flight).
            if (_uiState.value.currentScreen == AppScreen.Processing) {
                _uiState.update {
                    it.copy(
                        currentScreen = AppScreen.Home,
                        analysisStartedAt = null,
                        analysisLatencyMs = null,
                    )
                }
            }
            return
        }
        job.cancel()
        _uiState.update {
            it.copy(
                currentScreen = AppScreen.Home,
                preview = null,
                analysisStartedAt = null,
                analysisLatencyMs = null,
                message = "Analysis cancelled.",
            )
        }
    }

    fun queueDownload() {
        val state = uiState.value
        val preview = state.preview ?: run {
            _uiState.update { it.copy(message = "Analyze a link before downloading.") }
            return
        }
        val activeIndex = state.selectedPreviewIndex
        val selectedItem = selectedPreviewItem(preview, activeIndex)
        val format = selectedItem.formats.firstOrNull { it.formatId == state.selectedFormatIdsByItem[activeIndex] }

        viewModelScope.launch(Dispatchers.IO) {
            val sourceUrl = preview.sourceUrl
            var refreshedFormat = format
            var refreshedItem = selectedItem

            if (shouldRefreshUrlBeforeDownload(sourceUrl)) {
                _uiState.update { it.copy(message = "Refreshing download link...") }
                val newPreview = runCatching {
                    if (isInstagramHost(sourceUrl)) {
                        inspectInstagramLink(sourceUrl)
                    } else {
                        resolveWithPythonOrFallback(sourceUrl)
                    }
                }.getOrNull()

                if (newPreview != null) {
                    val newItem = selectedPreviewItem(newPreview, activeIndex)
                    val newFormat = newItem.formats.firstOrNull { it.formatId == format?.formatId }
                        ?: newItem.formats.firstOrNull { it.label == format?.label }
                        ?: newItem.formats.firstOrNull()
                    if (newFormat != null) {
                        refreshedFormat = newFormat
                        refreshedItem = newItem
                    }
                }
            }

            val finalFormat = refreshedFormat
            val finalItem = refreshedItem

            val useMergeService = finalFormat != null && shouldDownloadWithMergeService(finalFormat, sourceUrl)

            if (useMergeService) {
                enqueueMergeJob(
                    sourceUrl = sourceUrl,
                    item = finalItem,
                    format = finalFormat,
                )
                _uiState.update {
                    it.copy(
                        currentScreen = AppScreen.Downloads,
                        message = "Download queued in ${defaultDownloadLocation()}",
                    )
                }
                return@launch
            }

            val resolved = resolveItemForDownload(item = finalItem, formatId = finalFormat?.formatId)
            val manager = downloadManager ?: run {
                _uiState.update {
                    it.copy(message = "Download service is unavailable on this device.")
                }
                return@launch
            }

            runCatching {
                enqueuePreviewItems(
                    manager = manager,
                    sourceUrl = sourceUrl,
                    items = listOf(resolved),
                    formats = listOf(finalFormat),
                )
            }.onSuccess {
                _uiState.update {
                    it.copy(
                        currentScreen = AppScreen.Downloads,
                        message = "Download queued in ${defaultDownloadLocation()}",
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(message = error.message ?: "Unable to start the download.")
                }
            }
        }
    }

    private fun enqueueMergeJob(
        sourceUrl: String,
        item: PreviewMediaItem,
        format: MediaFormat,
    ) {
        val audioFormat = format.mergeAudio // might be null
        val outputExt = if (format.isAudioOnly) {
            format.ext
        } else when {
            format.ext.equals("webm", ignoreCase = true) -> "webm"
            else -> "mp4"
        }
        val outputMime = if (format.isAudioOnly) {
            format.mimeType
        } else if (outputExt == "webm") {
            "video/webm"
        } else {
            "video/mp4"
        }
        val baseName = item.fileName.substringBeforeLast('.', item.fileName)
        val sanitizedBase = baseName.replace(Regex("""[\\/:*?"<>|]"""), "_")
        val resolutionTag = format.height?.let { "_${it}p" } ?: ""
        val outputFileName = "${sanitizedBase}${resolutionTag}.$outputExt"
        val jobId = "merge-${System.currentTimeMillis()}-${(0..9999).random()}"

        MergeDownloadService.enqueue(
            context = appContext,
            jobId = jobId,
            sourceUrl = sourceUrl,
            title = item.title,
            description = format.label,
            outputFileName = outputFileName,
            mimeType = outputMime,
            videoUrl = if (format.isAudioOnly) "" else format.mediaUrl,
            videoExt = format.ext,
            videoHeaders = format.httpHeaders,
            videoSize = if (format.isAudioOnly) 0L else (format.fileSizeBytes?.let {
                val audioSize = audioFormat?.fileSizeBytes ?: 0L
                if (audioSize > 0L && it > audioSize) it - audioSize else it
            } ?: 0L),
            audioUrl = audioFormat?.mediaUrl ?: (if (format.isAudioOnly) format.mediaUrl else ""),
            audioExt = audioFormat?.ext ?: (if (format.isAudioOnly) format.ext else ""),
            audioHeaders = audioFormat?.httpHeaders ?: (if (format.isAudioOnly) format.httpHeaders else emptyMap()),
            audioSize = audioFormat?.fileSizeBytes ?: (if (format.isAudioOnly) (format.fileSizeBytes ?: 0L) else 0L),
        )

        val actionLabel = if (format.requiresMerge) "Merging" else "Downloading"
        _uiState.update {
            it.copy(
                currentScreen = AppScreen.Downloads,
                message = "$actionLabel ${format.label} • saving to ${defaultDownloadLocation()}",
            )
        }
    }

    fun queueAllDownloads() {
        val preview = uiState.value.preview ?: run {
            _uiState.update { it.copy(message = "Analyze a link before downloading.") }
            return
        }
        val manager = downloadManager ?: run {
            _uiState.update {
                it.copy(message = "Download service is unavailable on this device.")
            }
            return
        }
        if (preview.items.size <= 1) {
            queueDownload()
            return
        }

        val state = uiState.value
        val selectedIndices = state.selectedItemIndices
            .filter { it in preview.items.indices }
            .toSortedSet()
        val indices = if (selectedIndices.isEmpty()) preview.items.indices.toList() else selectedIndices.toList()

        data class StandardQueue(val item: PreviewMediaItem, val format: MediaFormat?)
        data class MergeQueue(val item: PreviewMediaItem, val format: MediaFormat)

        val standard = mutableListOf<StandardQueue>()
        val merges = mutableListOf<MergeQueue>()
        indices.forEach { idx ->
            val item = preview.items.getOrNull(idx) ?: return@forEach
            val formatId = state.selectedFormatIdsByItem[idx]
            val format = item.formats.firstOrNull { it.formatId == formatId }
            if (format?.requiresMerge == true) {
                merges += MergeQueue(item, format)
            } else {
                standard += StandardQueue(
                    item = resolveItemForDownload(item, formatId),
                    format = format,
                )
            }
        }

        if (standard.isEmpty() && merges.isEmpty()) {
            _uiState.update { it.copy(message = "Select at least one item to download.") }
            return
        }

        merges.forEach { (item, format) ->
            enqueueMergeJob(
                sourceUrl = preview.sourceUrl,
                item = item,
                format = format,
            )
        }

        if (standard.isEmpty()) {
            _uiState.update {
                it.copy(
                    currentScreen = AppScreen.Downloads,
                    message = "Merging ${merges.size} item${if (merges.size == 1) "" else "s"} • saving to ${defaultDownloadLocation()}",
                )
            }
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                enqueuePreviewItems(
                    manager = manager,
                    sourceUrl = preview.sourceUrl,
                    items = standard.map { it.item },
                    formats = standard.map { it.format },
                )
            }.onSuccess {
                val totalQueued = standard.size + merges.size
                _uiState.update {
                    it.copy(
                        currentScreen = AppScreen.Downloads,
                        message = "$totalQueued download${if (totalQueued == 1) "" else "s"} queued in ${defaultDownloadLocation()}",
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(message = error.message ?: "Unable to start all downloads.")
                }
            }
        }
    }

    fun toggleCarouselItem(index: Int) {
        _uiState.update { state ->
            val preview = state.preview ?: return@update state
            if (index !in preview.items.indices) return@update state
            val current = state.selectedItemIndices
            val updated = if (index in current) current - index else current + index
            state.copy(selectedItemIndices = updated)
        }
    }

    fun selectAllCarouselItems() {
        _uiState.update { state ->
            val preview = state.preview ?: return@update state
            state.copy(selectedItemIndices = preview.items.indices.toSet())
        }
    }

    fun clearCarouselSelection() {
        _uiState.update { it.copy(selectedItemIndices = emptySet()) }
    }

    fun toggleBatchEntry(index: Int) {
        _uiState.update { state ->
            val batch = state.batch ?: return@update state
            if (index !in batch.entries.indices) return@update state
            val current = state.selectedBatchIndices
            val updated = if (index in current) current - index else current + index
            state.copy(selectedBatchIndices = updated)
        }
    }

    fun selectAllBatchEntries() {
        _uiState.update { state ->
            val batch = state.batch ?: return@update state
            state.copy(selectedBatchIndices = batch.entries.indices.toSet())
        }
    }

    fun clearBatchSelection() {
        _uiState.update { it.copy(selectedBatchIndices = emptySet()) }
    }

    fun cancelBatch() {
        // Tearing down the batch screen also stops any in-flight processing.
        batchProcessingJob?.cancel()
        batchProcessingJob = null
        _uiState.update {
            it.copy(
                currentScreen = AppScreen.Home,
                batch = null,
                selectedBatchIndices = emptySet(),
                batchProgress = null,
            )
        }
    }

    /**
     * Stop walking a batch mid-flight. Already-queued downloads keep running
     * (they're owned by DownloadManager / MergeDownloadService). The Cancel
     * button on each download entry is the way to stop those individually.
     */
    fun cancelBatchProcessing() {
        val job = batchProcessingJob
        if (job?.isActive != true) {
            cancelBatch()
            return
        }
        job.cancel()
        batchProcessingJob = null
        val progress = _uiState.value.batchProgress
        val processed = progress?.processed ?: 0
        val total = progress?.total ?: 0
        _uiState.update {
            it.copy(
                currentScreen = AppScreen.Home,
                batch = null,
                selectedBatchIndices = emptySet(),
                batchProgress = null,
                message = if (total > 0) {
                    "Batch cancelled after $processed of $total. Already-queued downloads continue."
                } else {
                    "Batch cancelled."
                },
            )
        }
    }

    fun downloadSelectedBatchEntries() {
        val state = uiState.value
        val batch = state.batch ?: run {
            _uiState.update { it.copy(message = "No batch ready to download.") }
            return
        }
        val manager = downloadManager ?: run {
            _uiState.update {
                it.copy(message = "Download service is unavailable on this device.")
            }
            return
        }
        if (state.batchProgress?.isActive == true) {
            _uiState.update { it.copy(message = "Batch download is already in progress.") }
            return
        }

        val selectedEntries = state.selectedBatchIndices
            .sorted()
            .mapNotNull { batch.entries.getOrNull(it) }
        if (selectedEntries.isEmpty()) {
            _uiState.update { it.copy(message = "Select at least one item to download.") }
            return
        }

        batchProcessingJob?.cancel()
        batchProcessingJob = viewModelScope.launch(Dispatchers.IO) {
            _uiState.update {
                it.copy(
                    batchProgress = BatchProgress(
                        total = selectedEntries.size,
                        processed = 0,
                        succeeded = 0,
                        failed = 0,
                        currentTitle = selectedEntries.firstOrNull()?.title,
                    ),
                )
            }

            var succeeded = 0
            var failed = 0
            try {
                selectedEntries.forEachIndexed { index, entry ->
                    ensureActive()
                _uiState.update { current ->
                    val existing = current.batchProgress ?: BatchProgress(
                        total = selectedEntries.size,
                        processed = 0,
                        succeeded = 0,
                        failed = 0,
                    )
                    current.copy(
                        batchProgress = existing.copy(
                            currentTitle = entry.title,
                        ),
                    )
                }

                val previewResult = runCatching {
                    if (isInstagramHost(entry.sourceUrl)) {
                        inspectInstagramLink(url = entry.sourceUrl)
                    } else {
                        resolveWithPythonOrFallback(entry.sourceUrl)
                    }
                }.getOrNull()
                ensureActive()

                val enqueueOk = previewResult?.let { preview ->
                    val primary = preview.items.firstOrNull() ?: return@let null
                    val preset = uiState.value.settings.preferredQuality
                    val chosen = chooseFormatForPreset(primary.formats, preset)
                    val useMergeService = chosen != null && shouldDownloadWithMergeService(chosen, entry.sourceUrl)
                    if (useMergeService) {
                        runCatching {
                            enqueueMergeJob(
                                sourceUrl = entry.sourceUrl,
                                item = primary,
                                format = chosen,
                            )
                        }.isSuccess
                    } else {
                        val resolved = resolveItemForDownload(primary, chosen?.formatId)
                        runCatching {
                            enqueuePreviewItems(
                                manager = manager,
                                sourceUrl = entry.sourceUrl,
                                items = listOf(resolved),
                                formats = listOf(chosen),
                            )
                        }.isSuccess
                    }
                } ?: false

                if (enqueueOk) succeeded++ else failed++

                _uiState.update { current ->
                    val existing = current.batchProgress ?: BatchProgress(
                        total = selectedEntries.size,
                        processed = 0,
                        succeeded = 0,
                        failed = 0,
                    )
                    current.copy(
                        batchProgress = existing.copy(
                            processed = index + 1,
                            succeeded = succeeded,
                            failed = failed,
                            currentTitle = entry.title,
                        ),
                    )
                }
                }
            } catch (cancellation: CancellationException) {
                // cancelBatchProcessing() already cleared batchProgress/batch
                // and surfaced a snackbar message — just bail.
                throw cancellation
            }

            batchProcessingJob = null
            _uiState.update {
                it.copy(
                    currentScreen = AppScreen.Downloads,
                    batchProgress = null,
                    batch = null,
                    selectedBatchIndices = emptySet(),
                    message = buildString {
                        append("Queued $succeeded of ${selectedEntries.size}")
                        if (failed > 0) append(" • $failed failed")
                        append(" • Saved to ${defaultDownloadLocation()}")
                    },
                )
            }
        }
    }

    private fun inspectAsPlaylist(rawUrl: String): LinkBatch? {
        if (!URLUtil.isNetworkUrl(rawUrl)) return null
        return runCatching {
            ensurePythonStarted()
            val python = Python.getInstance()
            val module = python.getModule("generic_media_resolver")
            val cookiePath = CookieHelper.getCookieFilePath(appContext)
            val payload = module.callAttr("resolve_playlist", rawUrl, cookiePath, BATCH_MAX_ITEMS).toString()
            buildPlaylistBatchFromPayload(rawUrl, payload)
        }.getOrNull()
    }

    private fun buildPlaylistBatchFromPayload(rawUrl: String, payload: String): LinkBatch? {
        val json = JSONObject(payload)
        if (!json.optBoolean("is_playlist", false)) return null

        val rawEntries = json.optJSONArray("entries") ?: return null
        if (rawEntries.length() == 0) return null

        val entries = buildList {
            for (i in 0 until rawEntries.length()) {
                val obj = rawEntries.optJSONObject(i) ?: continue
                val entryUrl = obj.optString("source_url").takeIf { it.isNotBlank() } ?: continue
                val title = obj.optString("title").takeIf { it.isNotBlank() } ?: shortenUrlForDisplay(entryUrl)
                val durationMs = obj.opt("duration_ms").let { value ->
                    when (value) {
                        is Number -> value.toLong().takeIf { it > 0L }
                        is String -> value.toLongOrNull()?.takeIf { it > 0L }
                        else -> null
                    }
                }
                val uploader = obj.optString("uploader").orEmpty()
                add(
                    LinkBatchEntry(
                        sourceUrl = entryUrl,
                        title = title,
                        uploader = uploader,
                        host = normalizedHost(entryUrl),
                        durationMs = durationMs,
                    )
                )
            }
        }
        if (entries.isEmpty()) return null

        val platformLabel = platformLabelFromResolver(json.optString("platform"))
        val totalCount = json.optInt("total_count", entries.size).coerceAtLeast(entries.size)
        val returnedCount = entries.size
        val truncated = json.optBoolean("truncated", totalCount > returnedCount)

        return LinkBatch(
            source = BatchSource.Playlist,
            sourceUrl = rawUrl,
            label = if (platformLabel.isBlank()) "Playlist" else "$platformLabel playlist",
            title = json.optString("title").ifBlank { "Playlist" },
            uploader = json.optString("uploader").orEmpty(),
            entries = entries,
            totalCount = totalCount,
            returnedCount = returnedCount,
            isTruncated = truncated,
        )
    }

    private fun chooseFormatForPreset(
        formats: List<MediaFormat>,
        preset: QualityPreset,
    ): MediaFormat? {
        if (formats.isEmpty()) return null
        val videoFormats = formats.filter { !it.isAudioOnly }
        val pool = if (videoFormats.isNotEmpty()) videoFormats else formats
        return when (preset) {
            QualityPreset.BestQuality -> pool.maxByOrNull { format ->
                (format.height ?: 0) * 100_000L + (format.tbr ?: 0)
            } ?: pool.first()
            QualityPreset.SmallestFile -> {
                val withSize = pool.filter { it.fileSizeBytes != null && it.fileSizeBytes > 0 }
                if (withSize.isNotEmpty()) {
                    withSize.minByOrNull { it.fileSizeBytes ?: Long.MAX_VALUE }
                } else {
                    pool.minByOrNull { (it.height ?: Int.MAX_VALUE) * 100_000L + (it.tbr ?: Int.MAX_VALUE) }
                }
            }
            QualityPreset.AskEveryTime -> pool.maxByOrNull { format ->
                (format.height ?: 0) * 100_000L + (format.tbr ?: 0)
            } ?: pool.first()
        }
    }

    private fun resolveItemForDownload(
        item: PreviewMediaItem,
        formatId: String?,
    ): PreviewMediaItem {
        val selected = item.formats.firstOrNull { it.formatId == formatId }
            ?: item.formats.firstOrNull { !it.isAudioOnly }
            ?: item.formats.firstOrNull()
            ?: return item

        val baseFileName = item.fileName.substringBeforeLast(".", item.fileName)
        val newFileName = "$baseFileName.${selected.ext}"
        return item.copy(
            resolvedUrl = selected.mediaUrl,
            mimeType = selected.mimeType,
            kind = selected.kind,
            fileSizeBytes = selected.fileSizeBytes ?: item.fileSizeBytes,
            fileName = newFileName,
        )
    }

    private fun shortenUrlForDisplay(url: String): String {
        val cleaned = url.removePrefix("https://").removePrefix("http://").removePrefix("www.")
        return if (cleaned.length > 80) cleaned.take(77) + "..." else cleaned
    }

    private suspend fun enqueuePreviewItems(
        manager: DownloadManager,
        sourceUrl: String,
        items: List<PreviewMediaItem>,
        formats: List<MediaFormat?> = emptyList(),
    ) {
        items.forEachIndexed { index, item ->
            val request = DownloadManager.Request(Uri.parse(item.resolvedUrl))
                .setTitle(item.title)
                .setDescription(
                    if (items.size > 1) {
                        "${item.description} • Item ${index + 1} of ${items.size}"
                    } else {
                        item.description
                    }
                )
                .setMimeType(item.mimeType)
                .setNotificationVisibility(
                    if (uiState.value.settings.completionNotifications) {
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                    } else {
                        DownloadManager.Request.VISIBILITY_VISIBLE
                    }
                )
                .setAllowedOverMetered(!uiState.value.settings.wifiOnly)
                .setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    "LinkLift/${item.fileName}",
                )

            val headers = formats.getOrNull(index)?.httpHeaders.orEmpty()
            val isGoogleVideo = item.resolvedUrl.contains("googlevideo.com")
            val extractionUserAgent = headers.entries
                .firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value
            val effectiveUserAgent = extractionUserAgent ?: linkLiftUserAgent

            if (headers.isNotEmpty()) {
                headers.forEach { (name, value) ->
                    val shouldSkip = isGoogleVideo && (
                        name.equals("User-Agent", ignoreCase = true) ||
                        name.equals("Accept", ignoreCase = true) ||
                        name.equals("Accept-Language", ignoreCase = true) ||
                        name.equals("Sec-Fetch-Mode", ignoreCase = true) ||
                        name.equals("Sec-Fetch-User", ignoreCase = true) ||
                        name.equals("Sec-Fetch-Site", ignoreCase = true) ||
                        name.equals("Sec-Fetch-Dest", ignoreCase = true)
                    )
                    if (!shouldSkip) {
                        runCatching { request.addRequestHeader(name, value) }
                    }
                }
                runCatching { request.addRequestHeader("User-Agent", effectiveUserAgent) }
            } else {
                runCatching { request.addRequestHeader("User-Agent", effectiveUserAgent) }
            }

            val downloadId = manager.enqueue(request)
            rememberTrackedDownload(downloadId = downloadId, sourceUrl = sourceUrl)
        }
        refreshDownloads()
    }

    fun updateWifiOnly(enabled: Boolean) {
        viewModelScope.launch {
            appContext.linkLiftDataStore.edit { prefs ->
                prefs[PreferenceKeys.wifiOnly] = enabled
            }
        }
    }

    fun updateCompletionNotifications(enabled: Boolean) {
        viewModelScope.launch {
            appContext.linkLiftDataStore.edit { prefs ->
                prefs[PreferenceKeys.completionNotifications] = enabled
            }
        }
    }

    fun updatePreferredQuality(preset: QualityPreset) {
        viewModelScope.launch {
            appContext.linkLiftDataStore.edit { prefs ->
                prefs[PreferenceKeys.preferredQuality] = preset.storageKey
            }
        }
    }

    /**
     * Cancel an in-flight or queued download. Removes the partial file (for
     * system DownloadManager downloads) or aborts the merge service worker
     * (for merge jobs) and clears the entry from the tracked list.
     */
    fun cancelDownload(downloadId: Long) {
        viewModelScope.launch {
            if (isMergeJobDownloadId(downloadId)) {
                val mergeJob = MergeJobStore.snapshot().values
                    .firstOrNull { mergeJobToDownloadId(it.id) == downloadId }
                if (mergeJob != null) {
                    MergeDownloadService.cancel(appContext, mergeJob.id)
                    MergeJobStore.remove(appContext, setOf(mergeJob.id))
                }
            } else {
                runCatching { downloadManager?.remove(downloadId) }
                appContext.linkLiftDataStore.edit { prefs ->
                    val currentIds = prefs[PreferenceKeys.downloadIds]?.toMutableSet() ?: mutableSetOf()
                    currentIds.remove(downloadId.toString())
                    prefs[PreferenceKeys.downloadIds] = currentIds

                    val sourceUrls = parseDownloadSourceMap(prefs[PreferenceKeys.downloadSourceUrls])
                    sourceUrls.remove(downloadId)
                    prefs[PreferenceKeys.downloadSourceUrls] = serializeDownloadSourceMap(sourceUrls)
                }
            }
            refreshDownloads()
            _uiState.update { it.copy(message = "Download cancelled.") }
        }
    }

    fun removeTrackedDownloads(downloadIds: Set<Long>) {
        if (downloadIds.isEmpty()) return
        val (mergeIds, systemIds) = downloadIds.partition { isMergeJobDownloadId(it) }
        viewModelScope.launch {
            if (systemIds.isNotEmpty()) {
                val systemSet = systemIds.toSet()
                appContext.linkLiftDataStore.edit { prefs ->
                    val currentIds = prefs[PreferenceKeys.downloadIds]?.toMutableSet() ?: mutableSetOf()
                    currentIds.removeAll(systemSet.map(Long::toString).toSet())
                    prefs[PreferenceKeys.downloadIds] = currentIds

                    val sourceUrls = parseDownloadSourceMap(prefs[PreferenceKeys.downloadSourceUrls])
                    systemSet.forEach(sourceUrls::remove)
                    prefs[PreferenceKeys.downloadSourceUrls] = serializeDownloadSourceMap(sourceUrls)
                }
            }
            if (mergeIds.isNotEmpty()) {
                val mergeIdSet = mergeIds.toSet()
                val toRemove = MergeJobStore.snapshot()
                    .filterValues { mergeJobToDownloadId(it.id) in mergeIdSet }
                    .keys
                if (toRemove.isNotEmpty()) {
                    MergeJobStore.remove(appContext, toRemove)
                    toRemove.forEach { MergeDownloadService.cancel(appContext, it) }
                }
            }
            refreshDownloads()
            _uiState.update {
                it.copy(
                    message = "Removed ${downloadIds.size} tracked item${if (downloadIds.size == 1) "" else "s"}. Files remain in ${defaultDownloadLocation()}.",
                )
            }
        }
    }

    fun regenerateTrackedDownload(sourceUrl: String?) {
        if (sourceUrl.isNullOrBlank()) {
            _uiState.update {
                it.copy(
                    message = "This file is no longer on your device, and LinkLift doesn't have the original link to regenerate it.",
                )
            }
            return
        }

        _uiState.update { it.copy(inputUrl = sourceUrl) }
        analyzeLink(rawUrl = sourceUrl)
    }

    private fun observePreferences() {
        viewModelScope.launch {
            appContext.linkLiftDataStore.data
                .catch { emit(emptyPreferences()) }
                .map { prefs ->
                    UserPreferences(
                        wifiOnly = prefs[PreferenceKeys.wifiOnly] ?: false,
                        completionNotifications = prefs[PreferenceKeys.completionNotifications] ?: true,
                        preferredQuality = QualityPreset.fromStorageKey(prefs[PreferenceKeys.preferredQuality]),
                        downloadLocation = defaultDownloadLocation(),
                        hasYouTubeCookies = CookieHelper.hasValidCookies(appContext),
                        youtubeCookiesLastModified = CookieHelper.getCookiesLastModified(appContext),
                    )
                }
                .collect { settings ->
                    _uiState.update { it.copy(settings = settings) }
                }
        }
    }

    private suspend fun inspectMediaUrl(url: String): MediaPreview {
        require(URLUtil.isNetworkUrl(url)) {
            "Paste a valid link to continue."
        }

        val response = fetchHeaders(url)
        response.use { result ->
            val finalUrl = result.request.url.toString()
            val mimeType = result.header("Content-Type")?.substringBefore(";").orEmpty()
            val kind = MediaKind.fromMimeType(mimeType, finalUrl)

            if (kind != MediaKind.Unknown) {
                val sizeFromHeader = result.header("Content-Length")?.toLongOrNull()
                    ?.takeIf { it > 0L }
                return buildMediaPreview(
                    sourceUrl = url,
                    resolvedUrl = finalUrl,
                    mimeType = mimeType,
                    fileSizeBytes = sizeFromHeader,
                    explicitKind = kind,
                    hostLabel = normalizedHost(finalUrl).ifBlank { "Direct source" },
                    isDirectLink = true,
                )
            }

            if (isLikelyHtml(mimeType, finalUrl)) {
                return inspectHtmlPage(sourceUrl = url, pageUrl = finalUrl)
            }

            throw IllegalArgumentException(
                "This link isn't supported yet. Try a direct file link or a public post link."
            )
        }
    }

    private suspend fun inspectInstagramLink(url: String): MediaPreview {
        inspectInstagramWithInstaloader(url)?.let { return it }

        return runCatching { inspectMediaUrl(url) }
            .getOrElse {
                throw IllegalArgumentException(
                    "Couldn't read this Instagram link. Try another public post or reel link."
                )
            }
    }

    private suspend fun resolveWithPythonOrFallback(url: String): MediaPreview {
        val pythonHandled = shouldTryPythonResolver(url)
        if (!pythonHandled) {
            return inspectMediaUrl(url)
        }

        // For platforms that go through yt-dlp (TikTok, etc.) the
        // HTML scraping fallback can only ever produce a broken preview.
        // Surface yt-dlp's actual error instead so the user sees
        // something actionable like "Sign in to confirm you're not a bot."
        return runCatching { invokePythonResolver(url) }
            .getOrElse { error ->
                val message = error.message?.takeIf { it.isNotBlank() }
                    ?: "yt-dlp couldn't read this link."
                throw IllegalArgumentException(yt_dlpFailureMessage(url, message))
            }
            ?: throw IllegalArgumentException(
                "yt-dlp returned no playable streams for this link. The video may be private, age-restricted, or region-locked."
            )
    }

    private fun invokePythonResolver(url: String): MediaPreview? {
        ensurePythonStarted()
        val python = Python.getInstance()
        val module = python.getModule("generic_media_resolver")
        val cookiePath = CookieHelper.getCookieFilePath(appContext)
        val payload = module.callAttr("resolve_url", url, cookiePath).toString()
        return buildPreviewFromPythonPayload(
            sourceUrl = url,
            payload = payload,
            defaultDescription = "Resolved with yt-dlp from a public media link",
        )
    }

    @Suppress("FunctionName")
    private fun yt_dlpFailureMessage(url: String, message: String): String {
        var text = message.trim()
        for (prefix in listOf("java.lang.IllegalArgumentException:", "IllegalArgumentException:", "ValueError:", "Exception:")) {
            if (text.startsWith(prefix, ignoreCase = true)) {
                text = text.substring(prefix.length).trim()
            }
        }
        val cleaned = text.trimEnd('.').take(160)
        val isYt = isYouTubeUrl(url)
        val hasCookies = CookieHelper.hasValidCookies(appContext)
        if (isYt && !hasCookies && (
            cleaned.contains("bot", ignoreCase = true) ||
            cleaned.contains("Sign in", ignoreCase = true) ||
            cleaned.contains("Forbidden", ignoreCase = true) ||
            cleaned.contains("403", ignoreCase = true) ||
            cleaned.contains("reloaded", ignoreCase = true) ||
            cleaned.contains("format is not available", ignoreCase = true)
        )) {
            return "YouTube bot protection blocked extraction. Sign in or import cookies in Settings to download."
        }
        return "This link could not be processed: $cleaned. Try another link or update the app."
    }

    private fun inspectInstagramWithInstaloader(url: String): MediaPreview? {
        val shortcode = instagramShortcode(url) ?: return null
        return runCatching {
            ensurePythonStarted()
            val python = Python.getInstance()
            val module = python.getModule("instagram_resolver")
            val payload = module.callAttr("resolve_instagram_shortcode", shortcode).toString()
            buildPreviewFromPythonPayload(
                sourceUrl = url,
                payload = payload,
                defaultDescription = instagramDescription(url),
                hostLabelOverride = "Instagram",
            )
        }.getOrNull()
    }

    private fun ensurePythonStarted() {
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(appContext))
        }
    }

    private suspend fun inspectHtmlPage(
        sourceUrl: String,
        pageUrl: String,
    ): MediaPreview {
        val pageResponse = fetchPage(pageUrl)
        pageResponse.use { response ->
            require(response.isSuccessful) {
                "Couldn't open this page."
            }

            val finalPageUrl = response.request.url.toString()
            val html = response.body?.string().orEmpty()
            require(html.isNotBlank()) {
                "This page returned empty content."
            }

            val initialExtracted = extractMediaFromHtml(finalPageUrl, html)
            val extracted = if (isInstagramReelUrl(finalPageUrl) && initialExtracted?.kind != MediaKind.Video) {
                extractInstagramReelWithFallbacks(finalPageUrl, html)
                    ?: initialExtracted?.takeIf { it.kind == MediaKind.Video }
            } else {
                initialExtracted
            }
                ?: throw IllegalArgumentException(
                    "This link isn't supported yet. Try a direct file link or a public post link from a supported app."
                )

            val mediaResponse = fetchHeaders(extracted.mediaUrl)
            mediaResponse.use { mediaResult ->
                val finalMediaUrl = mediaResult.request.url.toString()
                val mediaMimeType = mediaResult.header("Content-Type")?.substringBefore(";")
                    .orEmpty()
                    .ifBlank { extracted.mimeType.orEmpty() }
                val sizeFromHeader = mediaResult.header("Content-Length")?.toLongOrNull()
                    ?.takeIf { it > 0L }

                return buildMediaPreview(
                    sourceUrl = sourceUrl,
                    resolvedUrl = finalMediaUrl,
                    mimeType = mediaMimeType,
                    fileSizeBytes = sizeFromHeader,
                    explicitKind = extracted.kind,
                    titleHint = extracted.title,
                    descriptionHint = extracted.description,
                    hostLabel = extracted.platformLabel,
                    isDirectLink = false,
                )
            }
        }
    }

    private fun buildPreviewFromExtractedMedia(
        sourceUrl: String,
        extracted: ExtractedPageMedia,
    ): MediaPreview {
        val mediaResponse = fetchHeaders(extracted.mediaUrl)
        mediaResponse.use { mediaResult ->
            val finalMediaUrl = mediaResult.request.url.toString()
            val mediaMimeType = mediaResult.header("Content-Type")?.substringBefore(";")
                .orEmpty()
                .ifBlank { extracted.mimeType.orEmpty() }
            val sizeFromHeader = mediaResult.header("Content-Length")?.toLongOrNull()
                ?.takeIf { it > 0L }

            return buildMediaPreview(
                sourceUrl = sourceUrl,
                resolvedUrl = finalMediaUrl,
                mimeType = mediaMimeType,
                fileSizeBytes = sizeFromHeader,
                explicitKind = extracted.kind,
                titleHint = extracted.title,
                descriptionHint = extracted.description,
                hostLabel = extracted.platformLabel,
                isDirectLink = false,
            )
        }
    }

    private fun buildPreviewFromPythonPayload(
        sourceUrl: String,
        payload: String,
        defaultDescription: String,
        hostLabelOverride: String? = null,
    ): MediaPreview? {
        val json = JSONObject(payload)
        val platformLabel = hostLabelOverride ?: platformLabelFromResolver(json.optString("platform"))
        val items = extractPreviewItemsFromPythonJson(
            sourceUrl = sourceUrl,
            json = json,
            platformLabel = platformLabel,
            defaultDescription = defaultDescription,
        )
        if (items.isEmpty()) return null
        val normalizedItems = if (items.size > 1) {
            items.mapIndexed { index, item ->
                item.copy(fileName = multiItemFileName(item.fileName, index))
            }
        } else {
            items
        }

        return buildMediaPreviewFromItems(
            sourceUrl = sourceUrl,
            hostLabel = platformLabel,
            isDirectLink = false,
            items = normalizedItems,
            titleHint = json.optString("title").ifBlank { normalizedItems.first().title },
            descriptionHint = json.optString("description").ifBlank {
                if (normalizedItems.size > 1) {
                    "$defaultDescription • ${normalizedItems.size} items found"
                } else {
                    defaultDescription
                }
            },
        )
    }

    private fun extractPreviewItemsFromPythonJson(
        sourceUrl: String,
        json: JSONObject,
        platformLabel: String,
        defaultDescription: String,
    ): List<PreviewMediaItem> {
        val rawItems = json.optJSONArray("items")
        val items = mutableListOf<PreviewMediaItem>()

        if (rawItems != null && rawItems.length() > 0) {
            for (index in 0 until rawItems.length()) {
                val rawItem = rawItems.optJSONObject(index) ?: continue
                val item = previewItemFromJson(
                    sourceUrl = sourceUrl,
                    platformLabel = platformLabel,
                    json = rawItem,
                    fallbackTitle = json.optString("title").ifBlank { null },
                    fallbackDescription = rawItem.optString("description").ifBlank {
                        "${json.optString("description").ifBlank { defaultDescription }} • Item ${index + 1}"
                    },
                )
                if (item != null) items += item
            }
        }

        if (items.isNotEmpty()) return items

        previewItemFromJson(
            sourceUrl = sourceUrl,
            platformLabel = platformLabel,
            json = json,
            fallbackTitle = json.optString("title").ifBlank { null },
            fallbackDescription = json.optString("description").ifBlank { defaultDescription },
        )?.let { items += it }

        return items
    }

    private fun previewItemFromJson(
        sourceUrl: String,
        platformLabel: String,
        json: JSONObject,
        fallbackTitle: String?,
        fallbackDescription: String,
    ): PreviewMediaItem? {
        val parsedFormats = parseMediaFormats(json.optJSONArray("formats"))
        if (parsedFormats.isNotEmpty()) {
            return previewItemFromFormatsPayload(
                json = json,
                formats = parsedFormats,
                fallbackTitle = fallbackTitle,
                fallbackDescription = fallbackDescription,
            )
        }

        val kind = when (json.optString("kind").lowercase(Locale.ROOT)) {
            "video" -> MediaKind.Video
            "image" -> MediaKind.Image
            "audio" -> MediaKind.Audio
            else -> MediaKind.Unknown
        }
        val extracted = buildExtractedPageMedia(
            pageUrl = sourceUrl,
            rawMediaUrl = json.optString("media_url"),
            mimeType = json.optString("mime_type").ifBlank { null },
            kindHint = kind,
            title = json.optString("title").ifBlank { fallbackTitle },
            description = json.optString("description").ifBlank { fallbackDescription },
            platformLabel = platformLabel,
        ) ?: return null

        return previewItemFromExtractedMedia(
            extracted = extracted,
            sourceUrl = sourceUrl,
            isDirectLink = false,
        )
    }

    private fun previewItemFromFormatsPayload(
        json: JSONObject,
        formats: List<MediaFormat>,
        fallbackTitle: String?,
        fallbackDescription: String,
    ): PreviewMediaItem {
        val primary = formats.first()
        // Default to the highest quality video stream available (e.g. 1080p, 1440p, 4K).
        val previewFormat = formats.firstOrNull { !it.isAudioOnly } ?: primary
        val title = json.optString("title").ifBlank { fallbackTitle }?.takeIf { it.isNotBlank() }
            ?: primary.label
        val description = json.optString("description").ifBlank { fallbackDescription }
            ?: fallbackDescription
        val durationMs = json.opt("duration_ms").let { value ->
            when (value) {
                is Number -> value.toLong().takeIf { it > 0L }
                is String -> value.toLongOrNull()?.takeIf { it > 0L }
                else -> null
            }
        }
        val resolution = primary.width?.let { width ->
            primary.height?.let { height -> "${width}x$height" }
        } ?: primary.height?.let { "${it}p" }
        val baseFileName = bestFileName(previewFormat.mediaUrl, previewFormat.mimeType)
        val fileName = if (baseFileName.endsWith(".${previewFormat.ext}", ignoreCase = true)) {
            baseFileName
        } else {
            "${baseFileName.substringBeforeLast(".", baseFileName)}.${previewFormat.ext}"
        }
        return PreviewMediaItem(
            resolvedUrl = previewFormat.mediaUrl,
            title = sanitizedPageTitle(title) ?: title,
            description = description,
            mimeType = previewFormat.mimeType,
            kind = previewFormat.kind,
            fileSizeBytes = primary.fileSizeBytes,
            durationMs = durationMs,
            resolution = resolution,
            fileName = fileName,
            formats = formats,
        )
    }

    private fun parseMediaFormats(array: org.json.JSONArray?): List<MediaFormat> {
        if (array == null || array.length() == 0) return emptyList()
        val result = mutableListOf<MediaFormat>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val format = parseSingleMediaFormat(obj, fallbackIndex = i) ?: continue
            result += format
        }
        return result
    }

    private fun parseSingleMediaFormat(obj: JSONObject, fallbackIndex: Int): MediaFormat? {
        val mediaUrl = obj.optString("media_url").takeIf { it.isNotBlank() } ?: return null
        val kindStr = obj.optString("kind").lowercase(Locale.ROOT)
        val kind = when (kindStr) {
            "audio" -> MediaKind.Audio
            "video" -> MediaKind.Video
            "image" -> MediaKind.Image
            else -> MediaKind.Video
        }
        val ext = obj.optString("ext").ifBlank { if (kind == MediaKind.Audio) "m4a" else "mp4" }
        val mimeType = obj.optString("mime_type").ifBlank {
            if (kind == MediaKind.Audio) "audio/$ext" else "video/$ext"
        }
        val headersObj = obj.optJSONObject("http_headers")
        val headers = if (headersObj == null) emptyMap() else buildMap {
            headersObj.keys().forEach { key ->
                val value = headersObj.optString(key)
                if (value.isNotBlank()) put(key, value)
            }
        }
        val mergeAudioObj = obj.optJSONObject("merge_audio")
        val mergeAudio = mergeAudioObj?.let { parseSingleMediaFormat(it, fallbackIndex = -1) }

        return MediaFormat(
            formatId = obj.optString("format_id").ifBlank {
                if (fallbackIndex >= 0) "fmt_$fallbackIndex" else "fmt_pair"
            },
            label = obj.optString("label").ifBlank {
                if (kind == MediaKind.Audio) "Audio ${ext.uppercase(Locale.ROOT)}" else "Video"
            },
            mediaUrl = mediaUrl,
            mimeType = mimeType,
            kind = kind,
            ext = ext,
            height = obj.optIntOrNull("height"),
            width = obj.optIntOrNull("width"),
            fps = obj.optIntOrNull("fps"),
            abr = obj.optIntOrNull("abr"),
            tbr = obj.optIntOrNull("tbr"),
            fileSizeBytes = obj.optLongOrNull("filesize"),
            fileSizeApprox = obj.optBoolean("filesize_approx", false),
            isAudioOnly = obj.optBoolean("is_audio_only", kind == MediaKind.Audio),
            isProgressive = obj.optBoolean(
                "is_progressive",
                !obj.optBoolean("is_audio_only", false) && !obj.optBoolean("is_video_only", false),
            ),
            isVideoOnly = obj.optBoolean("is_video_only", false),
            vcodec = obj.optString("vcodec").takeIf { it.isNotBlank() },
            acodec = obj.optString("acodec").takeIf { it.isNotBlank() },
            httpHeaders = headers,
            mergeAudio = mergeAudio,
        )
    }

    private fun previewItemFromExtractedMedia(
        extracted: ExtractedPageMedia,
        sourceUrl: String,
        isDirectLink: Boolean,
    ): PreviewMediaItem {
        val mediaResponse = fetchHeaders(extracted.mediaUrl)
        mediaResponse.use { mediaResult ->
            val finalMediaUrl = mediaResult.request.url.toString()
            val mediaMimeType = mediaResult.header("Content-Type")?.substringBefore(";")
                .orEmpty()
                .ifBlank { extracted.mimeType.orEmpty() }
            val sizeFromHeader = mediaResult.header("Content-Length")?.toLongOrNull()
                ?.takeIf { it > 0L }
            val sourceLabel = extracted.platformLabel.ifBlank {
                normalizedHost(sourceUrl).ifBlank { "Web source" }
            }

            return createPreviewMediaItem(
                resolvedUrl = finalMediaUrl,
                mimeType = mediaMimeType,
                explicitKind = extracted.kind,
                fileSizeBytes = sizeFromHeader,
                titleHint = extracted.title,
                descriptionHint = extracted.description,
                sourceLabel = sourceLabel,
                isDirectLink = isDirectLink,
            )
        }
    }

    private fun buildMediaPreviewFromItems(
        sourceUrl: String,
        hostLabel: String,
        isDirectLink: Boolean,
        items: List<PreviewMediaItem>,
        titleHint: String? = null,
        descriptionHint: String? = null,
    ): MediaPreview {
        require(items.isNotEmpty()) { "At least one media item is required." }
        val primaryItem = items.first()
        val previewTitle = sanitizedPageTitle(firstNonBlank(titleHint, primaryItem.title)) ?: primaryItem.title
        val previewDescription = descriptionHint
            ?: if (items.size > 1) {
                "${primaryItem.description} • ${items.size} items found"
            } else {
                primaryItem.description
            }

        return MediaPreview(
            sourceUrl = sourceUrl,
            resolvedUrl = primaryItem.resolvedUrl,
            title = previewTitle,
            description = previewDescription,
            mimeType = primaryItem.mimeType,
            kind = primaryItem.kind,
            host = hostLabel,
            fileSizeBytes = primaryItem.fileSizeBytes,
            durationMs = primaryItem.durationMs,
            resolution = primaryItem.resolution,
            fileName = primaryItem.fileName,
            isDirectLink = isDirectLink,
            items = items,
        )
    }

    private fun buildMediaPreview(
        sourceUrl: String,
        resolvedUrl: String,
        mimeType: String,
        fileSizeBytes: Long?,
        explicitKind: MediaKind,
        hostLabel: String,
        isDirectLink: Boolean,
        titleHint: String? = null,
        descriptionHint: String? = null,
    ): MediaPreview {
        val kind = explicitKind.takeIf { it != MediaKind.Unknown }
            ?: MediaKind.fromMimeType(mimeType, resolvedUrl)
        require(kind != MediaKind.Unknown) {
            "Unable to determine the media type for this source."
        }

        val safeMimeType = mimeType.ifBlank { fallbackMimeType(resolvedUrl, kind) }
        val sourceLabel = hostLabel.ifBlank { normalizedHost(sourceUrl).ifBlank { "Web source" } }
        val item = createPreviewMediaItem(
            resolvedUrl = resolvedUrl,
            mimeType = safeMimeType,
            explicitKind = kind,
            fileSizeBytes = fileSizeBytes,
            titleHint = titleHint,
            descriptionHint = descriptionHint,
            sourceLabel = sourceLabel,
            isDirectLink = isDirectLink,
        )

        return buildMediaPreviewFromItems(
            sourceUrl = sourceUrl,
            hostLabel = sourceLabel,
            isDirectLink = isDirectLink,
            items = listOf(item),
            titleHint = item.title,
            descriptionHint = item.description,
        )
    }

    private fun createPreviewMediaItem(
        resolvedUrl: String,
        mimeType: String,
        explicitKind: MediaKind,
        fileSizeBytes: Long?,
        titleHint: String?,
        descriptionHint: String?,
        sourceLabel: String,
        isDirectLink: Boolean,
    ): PreviewMediaItem {
        val kind = explicitKind.takeIf { it != MediaKind.Unknown }
            ?: MediaKind.fromMimeType(mimeType, resolvedUrl)
        val metadata = extractRemoteMetadata(resolvedUrl, kind)
        val fileName = bestFileName(resolvedUrl, mimeType)
        val fallbackTitle = fileName.substringBeforeLast(".")
            .replace('-', ' ')
            .replace('_', ' ')
            .trim()
            .ifBlank { "Media download" }
        val description = descriptionHint ?: when (kind) {
            MediaKind.Video -> if (isDirectLink) {
                "Ready to download this direct video file"
            } else {
                "Ready to download from $sourceLabel"
            }
            MediaKind.Audio -> if (isDirectLink) {
                "Ready to download this direct audio file"
            } else {
                "Ready to download from $sourceLabel"
            }
            MediaKind.Image -> if (isDirectLink) {
                "Ready to download this direct image file"
            } else {
                "Ready to download from $sourceLabel"
            }
            MediaKind.Unknown -> "Ready to download"
        }

        return PreviewMediaItem(
            resolvedUrl = resolvedUrl,
            title = sanitizedPageTitle(firstNonBlank(titleHint, metadata.title, fallbackTitle)) ?: "Media download",
            description = description,
            mimeType = mimeType,
            kind = kind,
            fileSizeBytes = metadata.fileSizeBytes ?: fileSizeBytes,
            durationMs = metadata.durationMs,
            resolution = metadata.resolution,
            fileName = fileName,
        )
    }

    private fun requestBuilder(url: String): Request.Builder {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", linkLiftUserAgent)
            .header("Accept-Language", "en-US,en;q=0.9")
        if (isInstagramHost(url)) {
            builder
                .header("X-IG-App-ID", instagramWebAppId)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Accept", "*/*")
        }
        return builder
    }

    private fun fetchHeaders(url: String): okhttp3.Response {
        val headRequest = requestBuilder(url).head().build()
        val headResponse = okHttpClient.newCall(headRequest).execute()
        val headMime = headResponse.header("Content-Type").orEmpty()
        if (headResponse.isSuccessful && !headMime.contains("text/html", ignoreCase = true)) {
            return headResponse
        }

        headResponse.close()
        val getRequest = requestBuilder(url)
            .get()
            .header("Range", "bytes=0-1")
            .build()
        return okHttpClient.newCall(getRequest).execute()
    }

    private fun fetchPage(
        url: String,
        referer: String? = null,
    ): okhttp3.Response {
        val requestBuilder = requestBuilder(url)
        referer?.takeIf { it.isNotBlank() }?.let { requestBuilder.header("Referer", it) }
        val request = requestBuilder.get().build()
        return okHttpClient.newCall(request).execute()
    }

    private fun fallbackMimeType(url: String, kind: MediaKind): String {
        val extension = MimeTypeMap.getFileExtensionFromUrl(url).lowercase(Locale.ROOT)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: when (kind) {
                MediaKind.Video -> "video/*"
                MediaKind.Audio -> "audio/*"
                MediaKind.Image -> "image/*"
                MediaKind.Unknown -> "application/octet-stream"
            }
    }

    private fun extractMediaFromHtml(
        pageUrl: String,
        html: String,
    ): ExtractedPageMedia? {
        val document = Jsoup.parse(html, pageUrl)
        val pageTitle = sanitizedPageTitle(
            firstNonBlank(
                metaContent(document, "meta[property=og:title]"),
                metaContent(document, "meta[name=twitter:title]"),
                document.title(),
            )
        )
        val pageHost = normalizedHost(pageUrl)

        return when {
            pageHost.contains("reddit.com") || pageHost == "redd.it" ->
                extractRedditMedia(pageUrl, html, pageTitle)
            pageHost.contains("instagram.com") || pageHost == "instagr.am" ->
                extractInstagramMedia(pageUrl, html, pageTitle)
            pageHost.contains("pinterest.") || pageHost == "pin.it" || pageHost.contains("pinimg.com") ->
                extractPinterestMedia(pageUrl, html, pageTitle)
            else -> null
        }
            ?: extractStructuredMedia(document, pageUrl, pageTitle)
            ?: extractEmbeddedMedia(document, pageUrl, pageTitle)
            ?: extractGenericMediaRegex(pageUrl, html, pageTitle)
    }

    private fun extractStructuredMedia(
        document: org.jsoup.nodes.Document,
        pageUrl: String,
        pageTitle: String?,
    ): ExtractedPageMedia? {
        val hostLabel = platformLabel(normalizedHost(pageUrl))

        val videoUrl = firstNonBlank(
            metaContent(document, "meta[property=og:video:secure_url]"),
            metaContent(document, "meta[property=og:video:url]"),
            metaContent(document, "meta[property=og:video]"),
            metaContent(document, "meta[name=twitter:player:stream]"),
            metaContent(document, "meta[property=twitter:player:stream]"),
        )
        if (videoUrl != null) {
            return buildExtractedPageMedia(
                pageUrl = pageUrl,
                rawMediaUrl = videoUrl,
                mimeType = firstNonBlank(
                    metaContent(document, "meta[property=og:video:type]"),
                    "video/*",
                ),
                kindHint = MediaKind.Video,
                title = pageTitle,
                description = "Ready to download from this page",
                platformLabel = hostLabel,
            )
        }

        val audioUrl = firstNonBlank(
            metaContent(document, "meta[property=og:audio:secure_url]"),
            metaContent(document, "meta[property=og:audio]"),
        )
        if (audioUrl != null) {
            return buildExtractedPageMedia(
                pageUrl = pageUrl,
                rawMediaUrl = audioUrl,
                mimeType = "audio/*",
                kindHint = MediaKind.Audio,
                title = pageTitle,
                description = "Ready to download from this page",
                platformLabel = hostLabel,
            )
        }

        val contentUrl = firstNonBlank(metaContent(document, "meta[itemprop=contentUrl]"))
        if (contentUrl != null) {
            val normalizedUrl = normalizeCandidateUrl(pageUrl, contentUrl)
            val inferredKind = MediaKind.fromMimeType("", normalizedUrl)
            if (inferredKind == MediaKind.Video || inferredKind == MediaKind.Audio) {
                return buildExtractedPageMedia(
                    pageUrl = pageUrl,
                    rawMediaUrl = contentUrl,
                    mimeType = null,
                    kindHint = inferredKind,
                    title = pageTitle,
                    description = "Ready to download from this page",
                    platformLabel = hostLabel,
                )
            }
        }

        return null
    }

    private fun extractEmbeddedMedia(
        document: org.jsoup.nodes.Document,
        pageUrl: String,
        pageTitle: String?,
    ): ExtractedPageMedia? {
        val videoUrl = document.select("video[src], video source[src]")
            .firstNotNullOfOrNull { element -> element.absUrl("src").takeIf { it.isNotBlank() } }
        if (videoUrl != null) {
            return buildExtractedPageMedia(
                pageUrl = pageUrl,
                rawMediaUrl = videoUrl,
                mimeType = null,
                kindHint = MediaKind.Video,
                title = pageTitle,
                description = "Ready to download from this page",
                platformLabel = platformLabel(normalizedHost(pageUrl)),
            )
        }

        val audioUrl = document.select("audio[src], audio source[src]")
            .firstNotNullOfOrNull { element -> element.absUrl("src").takeIf { it.isNotBlank() } }
        if (audioUrl != null) {
            return buildExtractedPageMedia(
                pageUrl = pageUrl,
                rawMediaUrl = audioUrl,
                mimeType = null,
                kindHint = MediaKind.Audio,
                title = pageTitle,
                description = "Ready to download from this page",
                platformLabel = platformLabel(normalizedHost(pageUrl)),
            )
        }

        return null
    }

    private fun extractRedditMedia(
        pageUrl: String,
        html: String,
        pageTitle: String?,
    ): ExtractedPageMedia? {
        val normalizedHtml = normalizeEscapedHtml(html)
        val videoRegexes = listOf(
            Regex("""https://v\.redd\.it/[^"'\\s<>()]+/DASH_[^"'\\s<>()]+\.mp4(?:\?[^"'\\s<>()]*)?""", RegexOption.IGNORE_CASE),
            Regex("""https://v\.redd\.it/[^"'\\s<>()]+/HLSPlaylist\.m3u8(?:\?[^"'\\s<>()]*)?""", RegexOption.IGNORE_CASE),
        )
        videoRegexes.forEach { regex ->
            val match = regex.find(normalizedHtml)
            if (match != null) {
                return buildExtractedPageMedia(
                    pageUrl = pageUrl,
                    rawMediaUrl = match.value,
                    mimeType = null,
                    kindHint = MediaKind.Video,
                    title = pageTitle,
                    description = "From a public Reddit post",
                    platformLabel = "Reddit",
                )
            }
        }

        val imageMatch = Regex(
            """https://(?:i|preview)\.redd\.it/[^"'\\s<>()]+?\.(?:jpg|jpeg|png|webp|gif)(?:\?[^"'\\s<>()]*)?""",
            RegexOption.IGNORE_CASE,
        ).find(normalizedHtml)
        if (imageMatch != null) {
            return buildExtractedPageMedia(
                pageUrl = pageUrl,
                rawMediaUrl = imageMatch.value,
                mimeType = null,
                kindHint = MediaKind.Image,
                title = pageTitle,
                description = "From a public Reddit post",
                platformLabel = "Reddit",
            )
        }

        return null
    }

    private fun extractPinterestMedia(
        pageUrl: String,
        html: String,
        pageTitle: String?,
    ): ExtractedPageMedia? {
        val normalizedHtml = normalizeEscapedHtml(html)
        val document = Jsoup.parse(html, pageUrl)

        val structuredVideo = firstNonBlank(
            metaContent(document, "meta[property=og:video:secure_url]"),
            metaContent(document, "meta[property=og:video:url]"),
            metaContent(document, "meta[property=og:video]"),
            matchFirst(normalizedHtml, listOf(
                Regex("""https://v\d+\.pinimg\.com/videos/[^"'\\s<>()]+?\.mp4(?:\?[^"'\\s<>()]*)?""", RegexOption.IGNORE_CASE),
                Regex("""https://v\d+\.pinimg\.com/videos/mc/[^"'\\s<>()]+?\.mp4(?:\?[^"'\\s<>()]*)?""", RegexOption.IGNORE_CASE),
            )),
        )
        if (structuredVideo != null) {
            return buildExtractedPageMedia(
                pageUrl = pageUrl,
                rawMediaUrl = structuredVideo,
                mimeType = "video/*",
                kindHint = MediaKind.Video,
                title = pageTitle,
                description = "From a public Pinterest post",
                platformLabel = "Pinterest",
            )
        }

        val structuredImage = firstNonBlank(
            metaContent(document, "meta[property=og:image:secure_url]"),
            metaContent(document, "meta[property=og:image]"),
            metaContent(document, "meta[name=twitter:image]"),
        )?.takeUnless { candidate ->
            candidate.contains("236x", ignoreCase = true) || candidate.contains("75x75", ignoreCase = true)
        }
        if (structuredImage != null) {
            return buildExtractedPageMedia(
                pageUrl = pageUrl,
                rawMediaUrl = structuredImage,
                mimeType = "image/*",
                kindHint = MediaKind.Image,
                title = pageTitle,
                description = "From a public Pinterest post",
                platformLabel = "Pinterest",
            )
        }

        val videoMatch = Regex(
            """https://v\d+\.pinimg\.com/videos/[^"'\\s<>()]+?\.mp4(?:\?[^"'\\s<>()]*)?""",
            RegexOption.IGNORE_CASE,
        ).find(normalizedHtml)
        if (videoMatch != null) {
            return buildExtractedPageMedia(
                pageUrl = pageUrl,
                rawMediaUrl = videoMatch.value,
                mimeType = null,
                kindHint = MediaKind.Video,
                title = pageTitle,
                description = "From a public Pinterest post",
                platformLabel = "Pinterest",
            )
        }

        val imageMatch = Regex(
            """https://i\.pinimg\.com/[^"'\\s<>()]+?\.(?:jpg|jpeg|png|webp)(?:\?[^"'\\s<>()]*)?""",
            RegexOption.IGNORE_CASE,
        ).find(normalizedHtml)
        if (imageMatch != null) {
            return buildExtractedPageMedia(
                pageUrl = pageUrl,
                rawMediaUrl = imageMatch.value,
                mimeType = null,
                kindHint = MediaKind.Image,
                title = pageTitle,
                description = "From a public Pinterest post",
                platformLabel = "Pinterest",
            )
        }

        return null
    }

    private fun extractInstagramMedia(
        pageUrl: String,
        html: String,
        pageTitle: String?,
    ): ExtractedPageMedia? {
        val normalizedHtml = normalizeEscapedHtml(html)
        val document = Jsoup.parse(html, pageUrl)

        val videoCandidate = firstNonBlank(
            metaContent(document, "meta[property=og:video:secure_url]"),
            metaContent(document, "meta[property=og:video:url]"),
            metaContent(document, "meta[property=og:video]"),
            metaContent(document, "meta[name=twitter:player:stream]"),
            matchFirst(normalizedHtml, listOf(
                Regex(""""xdt_api__v1__media__shortcode__web_info":\{.*?"video_versions":\[\{.*?"url":"(https://[^"]+)"""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
                Regex(""""xdt_shortcode_media":\{.*?"video_versions":\[\{.*?"url":"(https://[^"]+)"""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
                Regex(""""video_versions":\[\{.*?"url":"(https://[^"]+)"""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
                Regex(""""browser_native_hd_url":"(https://[^"]+)"""", RegexOption.IGNORE_CASE),
                Regex(""""browser_native_sd_url":"(https://[^"]+)"""", RegexOption.IGNORE_CASE),
                Regex(""""playback_video_uri":"(https://[^"]+)"""", RegexOption.IGNORE_CASE),
                Regex(""""progressive_download_url":"(https://[^"]+)"""", RegexOption.IGNORE_CASE),
                Regex(""""video_url":"(https://[^"]+)"""", RegexOption.IGNORE_CASE),
                Regex(""""contentUrl":"(https://[^"]+)"""", RegexOption.IGNORE_CASE),
                Regex("""https://scontent[^"'\\s<>()]+?\.mp4(?:\?[^"'\\s<>()]*)?""", RegexOption.IGNORE_CASE),
            )),
        )
        if (videoCandidate != null) {
            return buildExtractedPageMedia(
                pageUrl = pageUrl,
                rawMediaUrl = videoCandidate,
                mimeType = "video/*",
                kindHint = MediaKind.Video,
                title = pageTitle,
                description = instagramDescription(pageUrl),
                platformLabel = "Instagram",
            )
        }

        val imageCandidate = firstNonBlank(
            metaContent(document, "meta[property=og:image:secure_url]"),
            metaContent(document, "meta[property=og:image]"),
            metaContent(document, "meta[name=twitter:image]"),
            matchFirst(normalizedHtml, listOf(
                Regex(""""display_url":"(https://[^"]+)"""", RegexOption.IGNORE_CASE),
                Regex(""""display_resources":\[\{"src":"(https://[^"]+)"""", RegexOption.IGNORE_CASE),
                Regex(""""image_versions2":\{"candidates":\[\{"url":"(https://[^"]+)"""", RegexOption.IGNORE_CASE),
                Regex("""https://scontent[^"'\\s<>()]+?\.(?:jpg|jpeg|png|webp)(?:\?[^"'\\s<>()]*)?""", RegexOption.IGNORE_CASE),
            )),
        )
        if (imageCandidate != null) {
            return buildExtractedPageMedia(
                pageUrl = pageUrl,
                rawMediaUrl = imageCandidate,
                mimeType = "image/*",
                kindHint = MediaKind.Image,
                title = pageTitle,
                description = instagramDescription(pageUrl),
                platformLabel = "Instagram",
            )
        }

        return null
    }

    private suspend fun extractInstagramReelWithFallbacks(
        pageUrl: String,
        initialHtml: String,
    ): ExtractedPageMedia? {
        val pageTitle = instagramTitleFromHtml(pageUrl, initialHtml)

        extractInstagramMedia(pageUrl, initialHtml, pageTitle)
            ?.takeIf { it.kind == MediaKind.Video }
            ?.let { return it }

        val shortcode = instagramShortcode(pageUrl) ?: return null
        val fallbackUrls = listOf(
            "https://www.instagram.com/reel/$shortcode/embed",
            "https://www.instagram.com/reel/$shortcode/embed/captioned/",
            "https://www.instagram.com/reel/$shortcode/?__a=1&__d=dis",
            "https://www.instagram.com/reel/$shortcode/?__a=1&__d=www",
        )

        fallbackUrls.forEach { fallbackUrl ->
            val extracted = runCatching {
                fetchPage(fallbackUrl, referer = pageUrl).use { response ->
                    if (!response.isSuccessful) return@use null
                    val body = response.body?.string().orEmpty()
                    if (body.isBlank()) return@use null
                    extractInstagramMedia(
                        pageUrl = response.request.url.toString(),
                        html = body,
                        pageTitle = pageTitle,
                    )?.takeIf { it.kind == MediaKind.Video }
                }
            }.getOrNull()

            if (extracted != null) return extracted
        }

        extractInstagramReelViaGraphQl(pageUrl, shortcode, pageTitle)?.let { return it }

        return null
    }

    private fun extractInstagramReelViaGraphQl(
        pageUrl: String,
        shortcode: String,
        pageTitle: String?,
    ): ExtractedPageMedia? {
        val body = FormBody.Builder()
            .add("variables", """{"shortcode":"$shortcode"}""")
            .add("doc_id", instagramReelGraphQlDocId)
            .build()

        val request = requestBuilder("https://www.instagram.com/graphql/query/")
            .header("Referer", pageUrl)
            .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .post(body)
            .build()

        val responseBody = runCatching {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body?.string().orEmpty()
            }
        }.getOrNull().orEmpty()

        if (responseBody.isBlank()) return null

        return extractInstagramMedia(
            pageUrl = pageUrl,
            html = responseBody,
            pageTitle = pageTitle,
        )?.takeIf { it.kind == MediaKind.Video }
    }

    private fun extractGenericMediaRegex(
        pageUrl: String,
        html: String,
        pageTitle: String?,
    ): ExtractedPageMedia? {
        val normalizedHtml = normalizeEscapedHtml(html)
        val match = Regex(
            """https://[^"'\\s<>()]+?\.(?:mp4|webm|m3u8|mp3|m4a|aac|wav)(?:\?[^"'\\s<>()]*)?""",
            RegexOption.IGNORE_CASE,
        ).find(normalizedHtml) ?: return null
        val mediaUrl = normalizeCandidateUrl(pageUrl, match.value)
        val kind = MediaKind.fromMimeType("", mediaUrl)
        if (kind != MediaKind.Video && kind != MediaKind.Audio) {
            return null
        }

        return buildExtractedPageMedia(
            pageUrl = pageUrl,
            rawMediaUrl = match.value,
            mimeType = null,
            kindHint = kind,
            title = pageTitle,
            description = "Ready to download from this page",
            platformLabel = platformLabel(normalizedHost(pageUrl)),
        )
    }

    private fun buildExtractedPageMedia(
        pageUrl: String,
        rawMediaUrl: String,
        mimeType: String?,
        kindHint: MediaKind,
        title: String?,
        description: String,
        platformLabel: String,
    ): ExtractedPageMedia? {
        val mediaUrl = normalizeCandidateUrl(pageUrl, rawMediaUrl)
        if (mediaUrl.isBlank()) return null

        val inferredKind = kindHint.takeIf { it != MediaKind.Unknown }
            ?: MediaKind.fromMimeType(mimeType, mediaUrl)
        if (inferredKind == MediaKind.Unknown) return null

        return ExtractedPageMedia(
            mediaUrl = mediaUrl,
            mimeType = mimeType?.takeIf { it.isNotBlank() } ?: fallbackMimeType(mediaUrl, inferredKind),
            kind = inferredKind,
            title = title,
            description = description,
            platformLabel = platformLabel,
        )
    }

    private fun metaContent(
        document: org.jsoup.nodes.Document,
        selector: String,
    ): String? {
        return document.selectFirst(selector)
            ?.attr("content")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun firstNonBlank(vararg values: String?): String? {
        return values.firstOrNull { !it.isNullOrBlank() }?.trim()
    }

    private fun matchFirst(
        html: String,
        regexes: List<Regex>,
    ): String? {
        regexes.forEach { regex ->
            val match = regex.find(html) ?: return@forEach
            val value = match.groupValues.getOrNull(1)
                ?.takeIf { it.isNotBlank() }
                ?: match.value
            return value
        }
        return null
    }

    private fun normalizeEscapedHtml(html: String): String {
        return html
            .replace("\\u002F", "/")
            .replace("\\u002f", "/")
            .replace("\\/", "/")
            .replace("\\u003A", ":")
            .replace("\\u003a", ":")
            .replace("\\u0026", "&")
            .replace("\\u0026amp;", "&")
            .replace("&amp;", "&")
    }

    private fun normalizeCandidateUrl(
        pageUrl: String,
        rawMediaUrl: String,
    ): String {
        var cleaned = rawMediaUrl
            .trim()
            .trim('"', '\'')
            .replace("\\u002F", "/")
            .replace("\\u002f", "/")
            .replace("\\/", "/")
            .replace("\\u003A", ":")
            .replace("\\u003a", ":")
            .replace("\\u0026", "&")
            .replace("\\u0026amp;", "&")
            .replace("&amp;", "&")

        if (cleaned.startsWith("//")) {
            cleaned = "${Uri.parse(pageUrl).scheme ?: "https"}:$cleaned"
        }

        return runCatching { URI(pageUrl).resolve(cleaned).toString() }
            .getOrDefault(cleaned)
    }

    private fun stripQueryParameters(url: String): String {
        return runCatching {
            val uri = URI(url)
            URI(uri.scheme, uri.authority, uri.path, null, null).toString()
        }.getOrDefault(url.substringBefore("?"))
    }

    private fun normalizedHost(url: String): String {
        val host = Uri.parse(url).host.orEmpty().lowercase(Locale.ROOT).removePrefix("www.")
        return if (host.contains("youtube") || host == "youtu.be") "Video Link" else host
    }

    private fun platformLabel(host: String): String {
        return when {
            host.contains("youtube") || host == "youtu.be" || host == "Video Link" -> "Video Link"
            host.contains("instagram") || host == "instagr.am" -> "Instagram"
            host.contains("reddit") || host == "redd.it" -> "Reddit"
            host.contains("pinterest") || host.contains("pinimg") || host == "pin.it" -> "Pinterest"
            host.contains("twitch") -> "Twitch"
            host.contains("streamable") -> "Streamable"
            host.contains("imgur") -> "Imgur"
            host.contains("soundcloud") -> "SoundCloud"
            host.contains("dailymotion") || host == "dai.ly" -> "Dailymotion"
            host.contains("rumble") -> "Rumble"
            host.contains("drive.google") -> "Google Drive"
            host.isBlank() -> "Web page"
            else -> host.substringBefore(".").replaceFirstChar { char ->
                if (char.isLowerCase()) char.titlecase(Locale.ROOT) else char.toString()
            }
        }
    }

    private fun sanitizedPageTitle(title: String?): String? {
        val cleaned = title
            ?.trim()
            ?.replace(Regex("""\s+[|\-–]\s+(Instagram|Reddit|Pinterest)$""", RegexOption.IGNORE_CASE), "")
            ?.replace(Regex("""\s+[|\-–]\s+Watch on Pinterest$""", RegexOption.IGNORE_CASE), "")
        return cleaned?.takeIf { it.isNotBlank() }
    }

    private fun instagramDescription(pageUrl: String): String {
        val lowered = pageUrl.lowercase(Locale.ROOT)
        return when {
            "/reel/" in lowered -> "From a public Instagram reel"
            "/reels/" in lowered -> "From a public Instagram reel"
            "/p/" in lowered -> "From a public Instagram post"
            else -> "From a public Instagram page"
        }
    }

    private fun isInstagramReelUrl(pageUrl: String): Boolean {
        val lowered = pageUrl.lowercase(Locale.ROOT)
        return "instagram.com" in lowered && ("/reel/" in lowered || "/reels/" in lowered)
    }

    private fun isInstagramHost(url: String): Boolean {
        val host = Uri.parse(url).host.orEmpty().lowercase(Locale.ROOT)
        return host.contains("instagram.com") || host == "instagr.am"
    }

    private fun shouldTryPythonResolver(url: String): Boolean = isYtDlpSupportedHost(url)

    private fun platformLabelFromResolver(platform: String?): String {
        val normalized = platform.orEmpty().trim()
        if (normalized.isBlank()) return "Link"
        return when {
            normalized.contains("youtube", ignoreCase = true) -> "Video Link"
            normalized.equals("tiktok", ignoreCase = true) -> "TikTok"
            normalized.equals("twitter", ignoreCase = true) || normalized.equals("x", ignoreCase = true) -> "X"
            normalized.equals("facebook", ignoreCase = true) -> "Facebook"
            normalized.equals("vimeo", ignoreCase = true) -> "Vimeo"
            normalized.equals("reddit", ignoreCase = true) -> "Reddit"
            normalized.equals("pinterest", ignoreCase = true) -> "Pinterest"
            normalized.contains("twitch", ignoreCase = true) -> "Twitch"
            normalized.contains("streamable", ignoreCase = true) -> "Streamable"
            normalized.contains("imgur", ignoreCase = true) -> "Imgur"
            normalized.contains("soundcloud", ignoreCase = true) -> "SoundCloud"
            normalized.contains("dailymotion", ignoreCase = true) -> "Dailymotion"
            normalized.contains("rumble", ignoreCase = true) -> "Rumble"
            normalized.contains("googledrive", ignoreCase = true) -> "Google Drive"
            else -> normalized.replaceFirstChar { char ->
                if (char.isLowerCase()) char.titlecase(Locale.ROOT) else char.toString()
            }
        }
    }

    private fun instagramShortcode(pageUrl: String): String? {
        val segments = Uri.parse(pageUrl).pathSegments
        val mediaIndex = segments.indexOfFirst {
            it.equals("reel", ignoreCase = true) ||
                it.equals("reels", ignoreCase = true) ||
                it.equals("p", ignoreCase = true)
        }
        if (mediaIndex == -1) return null
        return segments.getOrNull(mediaIndex + 1)?.takeIf { it.isNotBlank() }
    }

    private fun instagramTitleFromHtml(
        pageUrl: String,
        html: String,
    ): String? {
        val document = Jsoup.parse(html, pageUrl)
        return sanitizedPageTitle(
            firstNonBlank(
                metaContent(document, "meta[property=og:title]"),
                metaContent(document, "meta[name=twitter:title]"),
                document.title(),
            )
        )
    }

    private fun isLikelyHtml(
        mimeType: String,
        url: String,
    ): Boolean {
        return mimeType.isBlank() ||
            mimeType.contains("text/html", ignoreCase = true) ||
            (mimeType.contains("application/xhtml+xml", ignoreCase = true) && !url.endsWith(".xhtml"))
    }

    private fun extractRemoteMetadata(url: String, kind: MediaKind): RemoteMetadata {
        return when (kind) {
            MediaKind.Image -> RemoteMetadata()
            MediaKind.Audio,
            MediaKind.Video,
            -> runCatching {
                val retriever = android.media.MediaMetadataRetriever()
                retriever.setDataSource(url, emptyMap())
                val title = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE)
                val durationMs = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()
                val width = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?.toIntOrNull()
                val height = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toIntOrNull()
                retriever.release()
                RemoteMetadata(
                    title = title,
                    durationMs = durationMs,
                    resolution = if (width != null && height != null) "${width}x$height" else null,
                )
            }.getOrDefault(RemoteMetadata())
            MediaKind.Unknown -> RemoteMetadata()
        }
    }

    private suspend fun rememberTrackedDownload(downloadId: Long, sourceUrl: String) {
        appContext.linkLiftDataStore.edit { prefs ->
            val currentIds = prefs[PreferenceKeys.downloadIds]?.toMutableSet() ?: mutableSetOf()
            currentIds += downloadId.toString()
            prefs[PreferenceKeys.downloadIds] = currentIds

            val sourceUrls = parseDownloadSourceMap(prefs[PreferenceKeys.downloadSourceUrls])
            sourceUrls[downloadId] = sourceUrl
            prefs[PreferenceKeys.downloadSourceUrls] = serializeDownloadSourceMap(sourceUrls)
        }
    }

    private suspend fun refreshDownloads() {
        val manager = downloadManager ?: run {
            _uiState.update { current ->
                current.copy(
                    downloads = mergeDownloadsList(
                        emptyList(),
                        MergeJobStore.snapshot().values.toList(),
                        previous = current.downloads,
                    ),
                    downloadServiceAvailable = false,
                )
            }
            return
        }
        val ids = readTrackedIds()
        val sourceUrls = readTrackedSourceUrls()
        val systemDownloads = if (ids.isEmpty()) {
            emptyList()
        } else {
            val query = DownloadManager.Query().setFilterById(*ids.toLongArray())
            val cursor = manager.query(query)
            buildList {
                cursor.use {
                    while (it.moveToNext()) {
                        val downloadId = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))
                        add(it.toDownloadEntry(sourceUrl = sourceUrls[downloadId]))
                    }
                }
            }
        }

        _uiState.update { current ->
            current.copy(
                downloads = mergeDownloadsList(
                    systemDownloads,
                    MergeJobStore.snapshot().values.toList(),
                    previous = current.downloads,
                ),
                downloadServiceAvailable = true,
            )
        }
    }

    private suspend fun readTrackedIds(): Set<Long> {
        return appContext.linkLiftDataStore.data
            .catch { emit(emptyPreferences()) }
            .map { prefs -> prefs[PreferenceKeys.downloadIds].orEmpty() }
            .map { ids -> ids.mapNotNull { it.toString().toLongOrNull() }.toSet() }
            .first()
    }

    private suspend fun readTrackedSourceUrls(): Map<Long, String> {
        return appContext.linkLiftDataStore.data
            .catch { emit(emptyPreferences()) }
            .map { prefs -> parseDownloadSourceMap(prefs[PreferenceKeys.downloadSourceUrls]) }
            .first()
    }

    private fun parseDownloadSourceMap(raw: String?): MutableMap<Long, String> {
        if (raw.isNullOrBlank()) return mutableMapOf()

        return runCatching {
            val json = JSONObject(raw)
            buildMap {
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    key.toLongOrNull()?.let { id ->
                        json.optString(key).takeIf(String::isNotBlank)?.let { url ->
                            put(id, url)
                        }
                    }
                }
            }.toMutableMap()
        }.getOrElse { mutableMapOf() }
    }

    private fun serializeDownloadSourceMap(sourceUrls: Map<Long, String>): String {
        return JSONObject().apply {
            sourceUrls.forEach { (id, url) ->
                if (url.isNotBlank()) {
                    put(id.toString(), url)
                }
            }
        }.toString()
    }

    private fun Cursor.toDownloadEntry(sourceUrl: String?): DownloadEntry {
        val id = getLong(getColumnIndexOrThrow(DownloadManager.COLUMN_ID))
        val title = getString(getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)).orEmpty()
        val description = getString(getColumnIndexOrThrow(DownloadManager.COLUMN_DESCRIPTION)).orEmpty()
        val mimeType = getString(getColumnIndexOrThrow(DownloadManager.COLUMN_MEDIA_TYPE))
        val localUri = getString(getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
        val downloadedBytes = getLong(getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
        val totalBytesRaw = getLong(getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
        val totalBytes = totalBytesRaw.takeIf { it > 0L }
        val status = getInt(getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
        val reason = getInt(getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
        val updatedAt = runCatching {
            getLong(getColumnIndexOrThrow(DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP))
        }.getOrDefault(System.currentTimeMillis())

        val state = when (status) {
            DownloadManager.STATUS_PENDING -> DownloadState.Queued
            DownloadManager.STATUS_RUNNING -> DownloadState.Downloading
            DownloadManager.STATUS_PAUSED -> DownloadState.Paused
            DownloadManager.STATUS_SUCCESSFUL -> DownloadState.Completed
            else -> DownloadState.Failed
        }

        return DownloadEntry(
            id = id,
            title = title.ifBlank { "Download $id" },
            description = if (description.isBlank()) downloadStatusDescription(status, reason) else description,
            mimeType = mimeType,
            kind = MediaKind.fromMimeType(mimeType, localUri.orEmpty()),
            state = state,
            progress = if (totalBytes != null && totalBytes > 0) {
                (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            },
            downloadedBytes = downloadedBytes,
            totalBytes = totalBytes,
            localUri = localUri,
            sourceUrl = sourceUrl,
            updatedAt = updatedAt,
            dmFailureCode = if (state == DownloadState.Failed) reason else null,
        )
    }

    private fun downloadStatusDescription(status: Int, reason: Int): String {
        return when (status) {
            DownloadManager.STATUS_PENDING -> "Waiting for network availability"
            DownloadManager.STATUS_RUNNING -> "Downloading now"
            DownloadManager.STATUS_PAUSED -> "Paused by system or network policy"
            DownloadManager.STATUS_SUCCESSFUL -> "Saved to ${defaultDownloadLocation()}"
            else -> "Download failed (reason $reason)"
        }
    }

    private fun bestFileName(url: String, mimeType: String): String {
        val guessed = URLUtil.guessFileName(url, null, mimeType)
        if (guessed.isNotBlank()) return guessed
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType).orEmpty()
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return buildString {
            append("linklift_")
            append(timestamp)
            if (extension.isNotBlank()) {
                append(".")
                append(extension)
            }
        }
    }

    private fun defaultDownloadLocation(): String = "Downloads/LinkLift"

    fun refreshCookieStatus() {
        val hasCookies = CookieHelper.hasValidCookies(appContext)
        val lastMod = CookieHelper.getCookiesLastModified(appContext)
        _uiState.update { state ->
            state.copy(
                settings = state.settings.copy(
                    hasYouTubeCookies = hasCookies,
                    youtubeCookiesLastModified = lastMod,
                )
            )
        }
    }

    fun importCookiesFromUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = CookieHelper.importFromUri(appContext, uri)
            result.fold(
                onSuccess = { count ->
                    refreshCookieStatus()
                    _uiState.update {
                        it.copy(message = "Successfully imported $count cookies!")
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(message = "Failed to import cookies: ${error.message}")
                    }
                },
            )
        }
    }

    fun importCookiesFromText(text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = CookieHelper.importFromText(appContext, text)
            result.fold(
                onSuccess = { count ->
                    refreshCookieStatus()
                    _uiState.update {
                        it.copy(message = "Successfully saved $count cookies!")
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(message = "Failed to save cookies: ${error.message}")
                    }
                },
            )
        }
    }

    fun clearYouTubeCookies() {
        CookieHelper.clearCookies(appContext)
        refreshCookieStatus()
        _uiState.update {
            it.copy(message = "YouTube cookies cleared.")
        }
    }

    fun promptYouTubeAuth(reason: String? = null) {
        if (!RemoteConfigHelper.isYouTubeAvailable) return
        _uiState.update {
            it.copy(
                showYouTubeAuthPrompt = true,
                youTubeAuthPromptReason = reason ?: "YouTube authentication is required to access or download this stream.",
            )
        }
    }

    fun dismissYouTubeAuthPrompt() {
        _uiState.update {
            it.copy(
                showYouTubeAuthPrompt = false,
                youTubeAuthPromptReason = null,
            )
        }
    }

    private fun updateRemoteConfigFlags() {
        _uiState.update { current ->
            current.copy(
                isYouTubeAvailable = RemoteConfigHelper.isYouTubeAvailable,
                isSoundCloudAvailable = RemoteConfigHelper.isSoundCloudAvailable,
                isImgurAvailable = RemoteConfigHelper.isImgurAvailable,
                showYouTubeAuthPrompt = if (!RemoteConfigHelper.isYouTubeAvailable) false else current.showYouTubeAuthPrompt,
                youTubeAuthPromptReason = if (!RemoteConfigHelper.isYouTubeAvailable) null else current.youTubeAuthPromptReason,
            )
        }
    }
}

private data class RemoteMetadata(
    val title: String? = null,
    val fileSizeBytes: Long? = null,
    val durationMs: Long? = null,
    val resolution: String? = null,
)
