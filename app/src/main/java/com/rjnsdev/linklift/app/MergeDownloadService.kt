package com.rjnsdev.linklift.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.ProtocolException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.min

class MergeDownloadService : Service() {
    companion object {
        /** New id so devices that already created the old LOW-importance channel get DEFAULT. */
        const val CHANNEL_ID = "linklift_merge_downloads_progress"
        const val SUMMARY_NOTIFICATION_ID = 41010

        const val ACTION_START_MERGE = "com.rjnsdev.linklift.action.MERGE_START"
        const val ACTION_CANCEL_MERGE = "com.rjnsdev.linklift.action.MERGE_CANCEL"

        const val EXTRA_JOB_ID = "job_id"
        const val EXTRA_VIDEO_URL = "video_url"
        const val EXTRA_VIDEO_EXT = "video_ext"
        const val EXTRA_VIDEO_HEADERS = "video_headers"
        const val EXTRA_VIDEO_SIZE = "video_size"
        const val EXTRA_AUDIO_URL = "audio_url"
        const val EXTRA_AUDIO_EXT = "audio_ext"
        const val EXTRA_AUDIO_HEADERS = "audio_headers"
        const val EXTRA_AUDIO_SIZE = "audio_size"
        const val EXTRA_OUTPUT_FILENAME = "output_filename"
        const val EXTRA_TITLE = "title"
        const val EXTRA_DESCRIPTION = "description"
        const val EXTRA_SOURCE_URL = "source_url"
        const val EXTRA_MIME_TYPE = "mime_type"

        private const val DOWNLOAD_MAX_ATTEMPTS = 4
        private const val DOWNLOAD_RETRY_BASE_MS = 1_000L
        private const val DOWNLOAD_RETRY_MAX_MS = 8_000L

        fun enqueue(
            context: Context,
            jobId: String,
            sourceUrl: String,
            title: String,
            description: String,
            outputFileName: String,
            mimeType: String,
            videoUrl: String,
            videoExt: String,
            videoHeaders: Map<String, String>,
            videoSize: Long,
            audioUrl: String,
            audioExt: String,
            audioHeaders: Map<String, String>,
            audioSize: Long,
        ) {
            val intent = Intent(context, MergeDownloadService::class.java).apply {
                action = ACTION_START_MERGE
                putExtra(EXTRA_JOB_ID, jobId)
                putExtra(EXTRA_SOURCE_URL, sourceUrl)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_DESCRIPTION, description)
                putExtra(EXTRA_OUTPUT_FILENAME, outputFileName)
                putExtra(EXTRA_MIME_TYPE, mimeType)
                putExtra(EXTRA_VIDEO_URL, videoUrl)
                putExtra(EXTRA_VIDEO_EXT, videoExt)
                putExtra(EXTRA_VIDEO_SIZE, videoSize)
                putExtra(EXTRA_VIDEO_HEADERS, headersToBundle(videoHeaders))
                putExtra(EXTRA_AUDIO_URL, audioUrl)
                putExtra(EXTRA_AUDIO_EXT, audioExt)
                putExtra(EXTRA_AUDIO_SIZE, audioSize)
                putExtra(EXTRA_AUDIO_HEADERS, headersToBundle(audioHeaders))
            }
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun cancel(context: Context, jobId: String) {
            val intent = Intent(context, MergeDownloadService::class.java).apply {
                action = ACTION_CANCEL_MERGE
                putExtra(EXTRA_JOB_ID, jobId)
            }
            context.startService(intent)
        }

        private fun headersToBundle(headers: Map<String, String>): Bundle {
            val bundle = Bundle()
            headers.forEach { (key, value) -> bundle.putString(key, value) }
            return bundle
        }

        private fun bundleToHeaders(bundle: Bundle?): Map<String, String> {
            if (bundle == null || bundle.isEmpty) return emptyMap()
            return buildMap {
                bundle.keySet().forEach { key ->
                    val value = bundle.getString(key)
                    if (!value.isNullOrBlank()) put(key, value)
                }
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeJobs = mutableMapOf<String, Job>()
    private val jobMutex = Mutex()
    private val notificationManager by lazy {
        getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    }
    private val okHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            // No per-chunk read timeout — large CDN streams can pause briefly on mobile networks.
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        MergeJobStore.ensureLoaded(applicationContext)
        ensureNotificationChannel()
        serviceScope.launch { observeJobsForSummary() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_MERGE -> handleStart(intent)
            ACTION_CANCEL_MERGE -> handleCancel(intent.getStringExtra(EXTRA_JOB_ID))
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun handleStart(intent: Intent) {
        val jobId = intent.getStringExtra(EXTRA_JOB_ID) ?: return
        val videoUrl = intent.getStringExtra(EXTRA_VIDEO_URL).orEmpty()
        val audioUrl = intent.getStringExtra(EXTRA_AUDIO_URL).orEmpty()
        if (videoUrl.isBlank() && audioUrl.isBlank()) return

        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "Merge download" }
        val description = intent.getStringExtra(EXTRA_DESCRIPTION).orEmpty()
        val outputFileName = intent.getStringExtra(EXTRA_OUTPUT_FILENAME).orEmpty().ifBlank { "$jobId.mp4" }
        val mimeType = intent.getStringExtra(EXTRA_MIME_TYPE).orEmpty().ifBlank { "video/mp4" }
        val sourceUrl = intent.getStringExtra(EXTRA_SOURCE_URL).orEmpty()
        val videoExt = intent.getStringExtra(EXTRA_VIDEO_EXT).orEmpty().ifBlank { "mp4" }
        val audioExt = intent.getStringExtra(EXTRA_AUDIO_EXT).orEmpty().ifBlank { "m4a" }
        val videoSize = intent.getLongExtra(EXTRA_VIDEO_SIZE, 0L)
        val audioSize = intent.getLongExtra(EXTRA_AUDIO_SIZE, 0L)
        val videoHeaders = bundleToHeaders(intent.getBundleExtra(EXTRA_VIDEO_HEADERS))
        val audioHeaders = bundleToHeaders(intent.getBundleExtra(EXTRA_AUDIO_HEADERS))

        val initial = MergeJobRecord(
            id = jobId,
            title = title,
            description = description,
            sourceUrl = sourceUrl,
            outputFileName = outputFileName,
            state = MergeJobState.Queued,
            videoBytes = 0L,
            videoTotal = videoSize,
            audioBytes = 0L,
            audioTotal = audioSize,
            resultUri = null,
            errorMessage = null,
            updatedAt = System.currentTimeMillis(),
            mimeType = mimeType,
        )
        MergeJobStore.upsert(applicationContext, initial)
        startForegroundIfNeeded(initial)

        serviceScope.launch {
            jobMutex.withLock {
                if (activeJobs[jobId]?.isActive == true) return@withLock
                val job = serviceScope.launch {
                    runMergeJob(
                        jobId = jobId,
                        title = title,
                        description = description,
                        outputFileName = outputFileName,
                        mimeType = mimeType,
                        sourceUrl = sourceUrl,
                        videoUrl = videoUrl,
                        videoExt = videoExt,
                        videoHeaders = videoHeaders,
                        audioUrl = audioUrl,
                        audioExt = audioExt,
                        audioHeaders = audioHeaders,
                    )
                    onJobFinished(jobId)
                }
                activeJobs[jobId] = job
            }
        }
    }

    private fun handleCancel(jobId: String?) {
        if (jobId.isNullOrBlank()) return
        serviceScope.launch {
            jobMutex.withLock { activeJobs.remove(jobId)?.cancel() }
            MergeJobStore.update(applicationContext, jobId) { record ->
                if (record.state == MergeJobState.Completed) record
                else record.copy(
                    state = MergeJobState.Cancelled,
                    errorMessage = "Cancelled by user",
                )
            }
            onJobFinished(jobId)
        }
    }

    private suspend fun onJobFinished(jobId: String) {
        jobMutex.withLock {
            activeJobs.remove(jobId)
            if (activeJobs.isEmpty()) {
                stopForegroundCompat()
                stopSelf()
            }
        }
    }

    private suspend fun runMergeJob(
        jobId: String,
        title: String,
        description: String,
        outputFileName: String,
        mimeType: String,
        sourceUrl: String,
        videoUrl: String,
        videoExt: String,
        videoHeaders: Map<String, String>,
        audioUrl: String,
        audioExt: String,
        audioHeaders: Map<String, String>,
    ) {
        val tempDir = File(applicationContext.cacheDir, "merge").apply { mkdirs() }
        val videoTemp = File(tempDir, "${jobId}_video.${videoExt}")
        val audioTemp = File(tempDir, "${jobId}_audio.${audioExt}")
        val muxedTemp = File(tempDir, "${jobId}_muxed.${outputExtension(outputFileName, mimeType)}")

        try {
            val hasVideo = videoUrl.isNotBlank()
            val hasAudio = audioUrl.isNotBlank()

            if (hasVideo) {
                updateState(jobId) { it.copy(state = MergeJobState.DownloadingVideo) }
            } else if (hasAudio) {
                updateState(jobId) { it.copy(state = MergeJobState.DownloadingAudio) }
            }

            coroutineScope {
                val videoTask = if (hasVideo) {
                    async {
                        downloadFile(
                            url = videoUrl,
                            headers = videoHeaders,
                            target = videoTemp,
                        ) { bytes, total ->
                            updateState(jobId) {
                                it.copy(
                                    state = if (it.state == MergeJobState.Queued) MergeJobState.DownloadingVideo else it.state,
                                    videoBytes = bytes,
                                    videoTotal = total ?: it.videoTotal,
                                )
                            }
                        }
                    }
                } else null

                val audioTask = if (hasAudio) {
                    async {
                        downloadFile(
                            url = audioUrl,
                            headers = audioHeaders,
                            target = audioTemp,
                        ) { bytes, total ->
                            updateState(jobId) { record ->
                                val nextState = if (record.state == MergeJobState.DownloadingVideo) {
                                    MergeJobState.DownloadingAudio
                                } else record.state
                                record.copy(
                                    state = nextState,
                                    audioBytes = bytes,
                                    audioTotal = total ?: record.audioTotal,
                                )
                            }
                        }
                    }
                } else null

                listOfNotNull(videoTask, audioTask).awaitAll()
            }

            val finalSourceFile = if (hasVideo && hasAudio) {
                updateState(jobId) { it.copy(state = MergeJobState.Muxing) }
                muxFiles(
                    videoFile = videoTemp,
                    audioFile = audioTemp,
                    outputFile = muxedTemp,
                    outputMimeType = mimeType,
                )
                muxedTemp
            } else if (hasVideo) {
                videoTemp
            } else {
                audioTemp
            }

            val finalUri = publishToDownloads(
                source = finalSourceFile,
                fileName = outputFileName,
                mimeType = mimeType,
                description = description,
            )
            updateState(jobId) {
                it.copy(
                    state = MergeJobState.Completed,
                    resultUri = finalUri.toString(),
                    errorMessage = null,
                    videoBytes = if (hasVideo) videoTemp.length().takeIf { len -> len > 0L } ?: it.videoBytes else 0L,
                    audioBytes = if (hasAudio) audioTemp.length().takeIf { len -> len > 0L } ?: it.audioBytes else 0L,
                )
            }
            showCompletionNotification(jobId, title)
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            updateState(jobId) { record ->
                if (record.state == MergeJobState.Completed) record
                else record.copy(
                    state = MergeJobState.Cancelled,
                    errorMessage = record.errorMessage ?: "Cancelled",
                )
            }
            throw cancellation
        } catch (error: Throwable) {
            updateState(jobId) {
                it.copy(
                    state = MergeJobState.Failed,
                    errorMessage = error.message ?: error.javaClass.simpleName,
                )
            }
            showFailureNotification(jobId, title, error.message)

        } finally {
            runCatching { if (videoTemp.exists()) videoTemp.delete() }
            runCatching { if (audioTemp.exists()) audioTemp.delete() }
            runCatching { if (muxedTemp.exists()) muxedTemp.delete() }
        }
    }

    private suspend fun downloadFile(
        url: String,
        headers: Map<String, String>,
        target: File,
        onProgress: (Long, Long?) -> Unit,
    ) {
        var attempt = 0
        var lastError: Throwable? = null
        while (attempt < DOWNLOAD_MAX_ATTEMPTS) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            try {
                downloadFileAttempt(url, headers, target, onProgress)
                return
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                lastError = error
                attempt++
                if (attempt >= DOWNLOAD_MAX_ATTEMPTS || !isTransientDownloadFailure(error)) {
                    throw error
                }
                delay(downloadRetryDelayMs(attempt))
            }
        }
        throw lastError ?: IOException("Download failed after $DOWNLOAD_MAX_ATTEMPTS attempts")
    }

    private suspend fun downloadFileAttempt(
        url: String,
        headers: Map<String, String>,
        target: File,
        onProgress: (Long, Long?) -> Unit,
    ) {
        val resumeFrom = target.takeIf { it.exists() }?.length()?.takeIf { it > 0L } ?: 0L
        val builder = Request.Builder().url(url)
        val isGoogleVideo = url.contains("googlevideo.com")
        if (headers.none { it.key.equals("User-Agent", ignoreCase = true) } || isGoogleVideo) {
            builder.header("User-Agent", linkLiftUserAgent)
        }
        headers.forEach { (name, value) ->
            if (!name.equals("Range", ignoreCase = true)) {
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
                    builder.header(name, value)
                }
            }
        }
        if (resumeFrom > 0L) {
            builder.header("Range", "bytes=$resumeFrom-")
        }
        val response = okHttpClient.newCall(builder.get().build()).execute()
        response.use { resp ->
            if (resp.code == 416 && resumeFrom > 0L) {
                onProgress(resumeFrom, resumeFrom)
                return
            }
            if (!resp.isSuccessful) {
                val retryable = isRetryableHttpCode(resp.code)
                throw IOException(
                    "Download failed: HTTP ${resp.code}${if (retryable) " (retryable)" else ""} for $url",
                )
            }
            val body = resp.body ?: throw IOException("Empty response body for $url")
            val append = resp.code == 206
            if (resumeFrom > 0L && !append) {
                target.delete()
            }
            val total = contentLengthTotal(resp, resumeFrom, body.contentLength())
            FileOutputStream(target, append).use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var read: Int
                    var bytesSoFar = if (append) resumeFrom else 0L
                    var lastReported = 0L
                    while (input.read(buffer).also { read = it } > 0) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        output.write(buffer, 0, read)
                        bytesSoFar += read
                        val now = System.currentTimeMillis()
                        if (now - lastReported >= 250L) {
                            lastReported = now
                            onProgress(bytesSoFar, total)
                        }
                    }
                    onProgress(bytesSoFar, total)
                }
            }
        }
    }

    private fun contentLengthTotal(
        response: Response,
        resumeFrom: Long,
        bodyLength: Long,
    ): Long? {
        val contentRange = response.header("Content-Range")
        if (!contentRange.isNullOrBlank()) {
            val totalPart = contentRange.substringAfter('/', "").trim()
            totalPart.toLongOrNull()?.takeIf { it > 0L }?.let { return it }
        }
        if (bodyLength > 0L) {
            return if (resumeFrom > 0L) resumeFrom + bodyLength else bodyLength
        }
        return null
    }

    private fun downloadRetryDelayMs(attempt: Int): Long {
        val exponent = attempt - 1
        val scaled = DOWNLOAD_RETRY_BASE_MS * (1L shl exponent.coerceAtMost(3))
        return min(scaled, DOWNLOAD_RETRY_MAX_MS)
    }

    private fun isRetryableHttpCode(code: Int): Boolean =
        code == 408 || code == 429 || code in 500..599

    private fun isTransientDownloadFailure(error: Throwable): Boolean {
        var cause: Throwable? = error
        while (cause != null) {
            when (cause) {
                is SocketException,
                is SocketTimeoutException,
                is ProtocolException,
                -> return true
                is IOException -> {
                    val message = cause.message?.lowercase(Locale.ROOT).orEmpty()
                    if (message.contains("connection reset") ||
                        message.contains("connection abort") ||
                        message.contains("software caused connection abort") ||
                        message.contains("broken pipe") ||
                        message.contains("unexpected end of stream") ||
                        message.contains("connection closed") ||
                        message.contains("timeout") ||
                        message.contains("timed out") ||
                        message.contains("(retryable)")
                    ) {
                        return true
                    }
                }
            }
            cause = cause.cause
        }
        return false
    }

    private fun muxFiles(
        videoFile: File,
        audioFile: File,
        outputFile: File,
        outputMimeType: String,
    ) {
        if (outputFile.exists()) outputFile.delete()

        val containerFormat = when {
            outputMimeType.contains("webm", ignoreCase = true) ->
                MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
            else -> MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
        }
        val muxer = MediaMuxer(outputFile.absolutePath, containerFormat)
        val videoExtractor = MediaExtractor().apply { setDataSource(videoFile.absolutePath) }
        val audioExtractor = MediaExtractor().apply { setDataSource(audioFile.absolutePath) }

        var success = false
        try {
            val videoTrack = selectTrack(videoExtractor, "video/") ?: error("No video track in downloaded stream")
            val audioTrack = selectTrack(audioExtractor, "audio/") ?: error("No audio track in downloaded stream")

            videoExtractor.selectTrack(videoTrack.index)
            audioExtractor.selectTrack(audioTrack.index)

            val outVideoIndex = muxer.addTrack(videoTrack.format)
            val outAudioIndex = muxer.addTrack(audioTrack.format)
            muxer.start()

            val bufferSize = pickBufferSize(videoTrack.format) + pickBufferSize(audioTrack.format)
            val buffer = ByteBuffer.allocate(bufferSize.coerceAtLeast(1024 * 1024))
            val info = MediaCodec.BufferInfo()

            copyTrack(videoExtractor, muxer, outVideoIndex, buffer, info)
            copyTrack(audioExtractor, muxer, outAudioIndex, buffer, info)

            success = true
        } finally {
            runCatching { videoExtractor.release() }
            runCatching { audioExtractor.release() }
            runCatching {
                if (success) muxer.stop()
                muxer.release()
            }
            if (!success && outputFile.exists()) outputFile.delete()
        }
    }

    private data class SelectedTrack(val index: Int, val format: MediaFormat)

    private fun selectTrack(extractor: MediaExtractor, mimePrefix: String): SelectedTrack? {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(mimePrefix, ignoreCase = true)) {
                return SelectedTrack(i, format)
            }
        }
        return null
    }

    private fun pickBufferSize(format: MediaFormat): Int {
        return runCatching {
            format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
        }.getOrDefault(256 * 1024)
    }

    private fun copyTrack(
        extractor: MediaExtractor,
        muxer: MediaMuxer,
        muxerTrack: Int,
        buffer: ByteBuffer,
        info: MediaCodec.BufferInfo,
    ) {
        while (true) {
            buffer.clear()
            val sampleSize = extractor.readSampleData(buffer, 0)
            if (sampleSize < 0) break
            info.offset = 0
            info.size = sampleSize
            info.presentationTimeUs = extractor.sampleTime.coerceAtLeast(0L)
            info.flags = extractor.sampleFlags
            muxer.writeSampleData(muxerTrack, buffer, info)
            extractor.advance()
        }
    }

    private fun publishToDownloads(
        source: File,
        fileName: String,
        mimeType: String,
        description: String,
    ): Uri {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            publishViaMediaStore(source, fileName, mimeType, description)
        } else {
            publishViaLegacyFile(source, fileName)
        }
    }

    private fun publishViaMediaStore(
        source: File,
        fileName: String,
        mimeType: String,
        description: String,
    ): Uri {
        val resolver = applicationContext.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/LinkLift")
            if (description.isNotBlank()) {
                put(MediaStore.MediaColumns.TITLE, description.take(120))
            }
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values)
            ?: error("MediaStore refused the Downloads entry for $fileName")
        try {
            resolver.openOutputStream(uri)?.use { out ->
                FileInputStream(source).use { input -> input.copyTo(out) }
            } ?: error("Couldn't open output stream for $fileName")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        } catch (error: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }

    @Suppress("DEPRECATION")
    private fun publishViaLegacyFile(source: File, fileName: String): Uri {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val targetDir = File(dir, "LinkLift").apply { mkdirs() }
        val target = File(targetDir, fileName)
        FileInputStream(source).use { input ->
            FileOutputStream(target).use { out -> input.copyTo(out) }
        }
        return Uri.fromFile(target)
    }

    private fun outputExtension(fileName: String, mimeType: String): String {
        val ext = fileName.substringAfterLast('.', "").ifBlank {
            when {
                mimeType.contains("webm", ignoreCase = true) -> "webm"
                else -> "mp4"
            }
        }
        return ext.lowercase()
    }

    private fun updateState(jobId: String, transform: (MergeJobRecord) -> MergeJobRecord) {
        MergeJobStore.update(applicationContext, jobId, transform)
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val existing = notificationManager.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Merge downloads",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Ongoing progress when LinkLift combines video and audio (1080p+). Appears while the foreground download is active."
            setShowBadge(true)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun startForegroundIfNeeded(record: MergeJobRecord) {
        val notification = buildSummaryNotification(record)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                SUMMARY_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                SUMMARY_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(SUMMARY_NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private suspend fun observeJobsForSummary() {
        var lastFingerprint: Int = 0
        while (serviceScope.isActive) {
            val jobs = MergeJobStore.snapshot()
            val active = jobs.values.filter { it.state in ACTIVE_STATES }
            if (active.isNotEmpty()) {
                val fingerprint = active.sumOf { abs(it.state.ordinal) * 31 + (it.progress * 100).toInt() }
                if (fingerprint != lastFingerprint) {
                    lastFingerprint = fingerprint
                    val first = active.first()
                    runCatching {
                        notificationManager.notify(SUMMARY_NOTIFICATION_ID, buildSummaryNotification(first, active.size))
                    }
                }
            }
            delay(500L)
        }
    }

    private fun buildSummaryNotification(
        record: MergeJobRecord,
        activeCount: Int = 1,
    ): Notification {
        val total = record.totalBytes ?: 0L
        val downloaded = record.downloadedBytes
        val percent = if (total > 0L) ((downloaded.toDouble() / total) * 100).toInt() else 0
        val stateText = when (record.state) {
            MergeJobState.Queued -> "Starting"
            MergeJobState.DownloadingVideo -> "Downloading video"
            MergeJobState.DownloadingAudio -> "Downloading audio"
            MergeJobState.Muxing -> "Combining tracks"
            MergeJobState.Completed -> "Saved"
            MergeJobState.Failed -> "Failed"
            MergeJobState.Cancelled -> "Cancelled"
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(record.title)
            .setContentText(if (activeCount > 1) "$stateText • $activeCount active downloads" else stateText)
            .setOngoing(record.state in ACTIVE_STATES)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)

        when (record.state) {
            MergeJobState.Muxing -> builder.setProgress(0, 0, true)
            MergeJobState.DownloadingVideo,
            MergeJobState.DownloadingAudio -> {
                if (total > 0L) builder.setProgress(100, percent, false)
                else builder.setProgress(0, 0, true)
            }
            else -> Unit
        }
        return builder.build()
    }

    private fun showCompletionNotification(jobId: String, title: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ensureNotificationChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText("Saved to Downloads/LinkLift")
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        notificationManager.notify(completionNotificationIdFor(jobId), notification)
    }

    private fun showFailureNotification(jobId: String, title: String, error: String?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ensureNotificationChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Couldn't finish download")
            .setContentText(error?.take(120) ?: title)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        notificationManager.notify(completionNotificationIdFor(jobId), notification)
    }

    private fun completionNotificationIdFor(jobId: String): Int {
        // Stable but non-colliding id derived from jobId.
        return 41100 + (jobId.hashCode() and 0xFFFF)
    }

    private val ACTIVE_STATES = setOf(
        MergeJobState.Queued,
        MergeJobState.DownloadingVideo,
        MergeJobState.DownloadingAudio,
        MergeJobState.Muxing,
    )
}
