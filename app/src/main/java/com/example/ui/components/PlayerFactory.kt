package com.example.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.example.network.MediaUrlValidator
import java.io.File

@OptIn(UnstableApi::class)
object PlayerFactory {

    const val DEFAULT_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

    @Volatile
    private var simpleCache: SimpleCache? = null

    @Synchronized
    private fun getCache(context: Context): SimpleCache {
        return simpleCache ?: run {
            val cacheDir = File(context.applicationContext.cacheDir, "exoplayer_media_cache")
            val evictor = LeastRecentlyUsedCacheEvictor(64L * 1024L * 1024L) // 64MB LRU ring cache
            val databaseProvider = StandaloneDatabaseProvider(context.applicationContext)
            SimpleCache(cacheDir, evictor, databaseProvider).also { simpleCache = it }
        }
    }

    /**
     * Pre-warms the MediaCodec renderers, decoder fallback pipelines, and
     * local media cache provider in background during app launch to eliminate startup lag.
     */
    fun prewarmPlayerPipeline(context: Context) {
        try {
            DefaultRenderersFactory(context.applicationContext)
                .setEnableDecoderFallback(true)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            getCache(context)
        } catch (_: Exception) {}
    }

    fun createPlayer(context: Context): ExoPlayer {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 15_000,
                /* maxBufferMs = */ 60_000,
                /* bufferForPlaybackMs = */ 500, // Instant 0.5s click-to-play startup
                /* bufferForPlaybackAfterRebufferMs = */ 1_500
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val renderersFactory = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            .setAllowedVideoJoiningTimeMs(6000)

        // Codec and track selector with VR / 4K / 8K support (no artificial size constraints & exceed capabilities enabled for high-res VR/4096p)
        val trackSelector = DefaultTrackSelector(context).apply {
            parameters = buildUponParameters()
                .clearVideoSizeConstraints()
                .setExceedRendererCapabilitiesIfNecessary(true)
                .setExceedVideoConstraintsIfNecessary(true)
                .setAllowVideoMixedMimeTypeAdaptiveness(true)
                .setAllowVideoNonSeamlessAdaptiveness(true)
                .setAllowMultipleAdaptiveSelections(true)
                .build()
        }

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(androidx.media3.common.C.USAGE_MEDIA)
            .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE)
            .setAllowedCapturePolicy(androidx.media3.common.C.ALLOW_CAPTURE_BY_ALL)
            .build()

        return ExoPlayer.Builder(context, renderersFactory)
            .setTrackSelector(trackSelector)
            .setAudioAttributes(audioAttributes, true)
            .setLoadControl(loadControl)
            .setSeekParameters(SeekParameters.EXACT)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build().apply {
                playWhenReady = true
            }
    }

    fun buildMediaSource(
        context: Context,
        rawUrl: String,
        headers: Map<String, String> = emptyMap(),
        formatHint: String? = null,
        subtitles: List<com.example.network.SubtitleTrack> = emptyList()
    ): MediaSource {
        val url = rawUrl.trim()
        val userAgent = headers.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value ?: DEFAULT_UA

        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(25000)
            .setKeepPostFor302Redirects(true)

        val customHeaders = HashMap<String, String>()
        headers.forEach { (k, v) ->
            if (!k.equals("User-Agent", ignoreCase = true)) customHeaders[k] = v
        }
        if (customHeaders.isNotEmpty()) {
            httpFactory.setDefaultRequestProperties(customHeaders)
        }

        val upstreamFactory = DefaultDataSource.Factory(context, httpFactory)

        // For large cloud debrid and direct file-hoster streams (Gofile, Bunkr, Filester, Pixeldrain),
        // stream directly via upstream factory to preserve session cookies/headers across Range requests
        // and prevent thrashing the 64MB LRU disk cache.
        val isDebridOrDirectCdn = url.contains("real-debrid.com", ignoreCase = true) ||
            url.contains("torbox.app", ignoreCase = true) ||
            url.contains("delivery-", ignoreCase = true) ||
            url.contains("gofile.io", ignoreCase = true) ||
            url.contains("bunkr", ignoreCase = true) ||
            url.contains("filester", ignoreCase = true) ||
            url.contains("pixeldrain.com", ignoreCase = true) ||
            url.contains("cdn.cr", ignoreCase = true) ||
            customHeaders.keys.any { it.equals("Cookie", ignoreCase = true) || it.equals("Authorization", ignoreCase = true) }

        val dataSourceFactory: DataSource.Factory = if (isDebridOrDirectCdn) {
            upstreamFactory
        } else {
            val cache = getCache(context)
            CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(upstreamFactory)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        }

        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
        val mediaItemBuilder = MediaItem.Builder().setUri(url)

        val ext = MediaUrlValidator.mediaExtensionOf(url)
        val resolvedFormat = formatHint?.uppercase()?.trim()

        when {
            ext == "mpd" || url.contains(".mpd", ignoreCase = true) || resolvedFormat == "DASH" ->
                mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_MPD)
            ext == "m3u8" || url.contains(".m3u8", ignoreCase = true) || resolvedFormat == "HLS" ->
                mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
            ext == "mkv" || url.contains(".mkv", ignoreCase = true) || resolvedFormat == "MKV" ->
                mediaItemBuilder.setMimeType(MimeTypes.VIDEO_MATROSKA)
            ext == "webm" || url.contains(".webm", ignoreCase = true) || resolvedFormat == "WEBM" ->
                mediaItemBuilder.setMimeType(MimeTypes.VIDEO_WEBM)
            ext == "ts" || url.contains(".ts", ignoreCase = true) ->
                mediaItemBuilder.setMimeType(MimeTypes.VIDEO_MP2T)
            ext == "mp4" || resolvedFormat == "MP4" ->
                mediaItemBuilder.setMimeType(MimeTypes.VIDEO_MP4)
        }

        if (subtitles.isNotEmpty()) {
            val subtitleConfigs = subtitles.mapIndexed { index, track ->
                val cleanTrackPath = track.url.substringBefore("?").trim()
                val mimeType = when {
                    cleanTrackPath.endsWith(".ass", ignoreCase = true) || cleanTrackPath.endsWith(".ssa", ignoreCase = true) -> MimeTypes.TEXT_SSA
                    cleanTrackPath.endsWith(".vtt", ignoreCase = true) -> MimeTypes.TEXT_VTT
                    cleanTrackPath.endsWith(".ttml", ignoreCase = true) -> MimeTypes.APPLICATION_TTML
                    else -> MimeTypes.APPLICATION_SUBRIP
                }
                MediaItem.SubtitleConfiguration.Builder(Uri.parse(track.url))
                    .setMimeType(mimeType)
                    .setLanguage(track.language.ifEmpty { "en" })
                    .setLabel(track.label.ifEmpty { "English" })
                    .setSelectionFlags(if (index == 0) androidx.media3.common.C.SELECTION_FLAG_DEFAULT else 0)
                    .build()
            }
            mediaItemBuilder.setSubtitleConfigurations(subtitleConfigs)
        }

        return mediaSourceFactory.createMediaSource(mediaItemBuilder.build())
    }

    fun openInExternalPlayer(context: Context, url: String, title: String? = null, headers: Map<String, String> = emptyMap()) {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) {
            Toast.makeText(context, "No stream URL available", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(trimmed), "video/*")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION

                if (!title.isNullOrBlank()) {
                    putExtra("title", title)
                    putExtra(Intent.EXTRA_TITLE, title)
                }

                if (headers.isNotEmpty()) {
                    val bundle = Bundle()
                    headers.forEach { (k, v) -> bundle.putString(k, v) }
                    putExtra("headers", bundle)
                    putExtra("android.media.intent.extra.HTTP_HEADERS", bundle)
                }
            }
            context.startActivity(Intent.createChooser(intent, "Play with external player..."))
        } catch (e: Exception) {
            Toast.makeText(context, "Failed to open external player: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
