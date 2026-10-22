package org.schabi.newpipe.offline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import coil3.BitmapImage
import coil3.imageLoader
import coil3.memory.MemoryCache
import coil3.request.Disposable
import coil3.request.ImageRequest
import coil3.request.error
import coil3.request.placeholder
import coil3.request.target
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.Properties
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.BooleanSupplier
import kotlin.math.max
import okio.buffer
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.util.InfoCache

/** Optional, bounded artwork. No URL fetcher, extractor, or media-file writer is used here. */
object OfflineThumbnails {
    private const val EDGE = OfflineThumbnailFiles.MAX_EDGE
    private const val CACHE_BUDGET = 32L * 1024 * 1024
    private const val MAX_CACHED_SOURCE = 4L * 1024 * 1024
    private val worker = ThreadPoolExecutor(1, 1, 20, TimeUnit.SECONDS, ArrayBlockingQueue(32))
    private val misses = object : LinkedHashMap<String, Long>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > 64
    }

    private fun submit(work: () -> Unit): FutureTask<Unit>? {
        val task = FutureTask { work() }
        return try {
            worker.execute(task)
            task
        } catch (_: RejectedExecutionException) {
            null
        }
    }

    /** Copy an already cached cover while the save is being downloaded, before cache eviction. */
    @JvmStatic
    fun remember(context: Context, store: OfflineStore, entry: OfflineStore.Entry, images: List<Image>) {
        val urls = images.take(8).map { it.url }.filter { it.length <= 2048 }
        submit {
            optional {
                if (!sameSource(store.get(entry.id), entry)) return@optional
                val bitmap = cachedSafely(context.applicationContext, urls) ?: return@optional
                try {
                    val dir = OfflineThumbnailFiles.directory(store, entry)
                    if (!dir.isDirectory && !dir.mkdirs()) return@optional
                    tidy(store, dir, File(dir, "${entry.id}.preview"))
                    val preview = File(dir, "${entry.id}.preview")
                    synchronized(store) {
                        if (!sameSource(store.get(entry.id), entry)) return@optional
                        writeBitmap(bitmap, preview)
                        val identity = Properties().apply {
                            setProperty("origin", entry.origin)
                            setProperty("mime", entry.mime)
                            setProperty("service", entry.serviceId.toString())
                        }
                        File(dir, "${entry.id}.identity").outputStream().use { identity.store(it, null) }
                    }
                    store.get(entry.id)?.let { misses.remove(OfflineThumbnailFiles.file(store, it).absolutePath) }
                    prepare(context.applicationContext, store, entry.id)
                } finally {
                    bitmap.recycle()
                }
            }
        }
    }

    /** Resolve optional artwork asynchronously; the media source never waits for this worker. */
    @JvmStatic
    fun schedule(
        context: Context,
        store: OfflineStore,
        entry: OfflineStore.Entry,
        ready: java.util.function.Consumer<List<Image>>
    ) {
        submit {
            optional { prepare(context.applicationContext, store, entry.id) }
            optional { ready.accept(OfflineThumbnailFiles.images(store, store.get(entry.id))) }
        }
    }

    /** The owner cancels on every rebind/recycle; both callback and Coil request are guarded. */
    @JvmStatic
    fun bind(
        context: Context,
        store: OfflineStore,
        entry: OfflineStore.Entry,
        target: ImageView,
        current: BooleanSupplier
    ): Runnable {
        val cancelled = AtomicBoolean()
        val handler = Handler(Looper.getMainLooper())
        var request: Disposable? = null
        var complete: Runnable? = null
        val task = submit {
            optional { prepare(context.applicationContext, store, entry.id) }
            val images = try {
                OfflineThumbnailFiles.images(store, store.get(entry.id))
            } catch (_: IOException) {
                emptyList()
            }
            val callback = Runnable {
                if (!cancelled.get() && current.asBoolean) {
                    request = context.imageLoader.enqueue(
                        ImageRequest.Builder(context).data(images.firstOrNull()?.url)
                            .size(EDGE, EDGE).placeholder(R.drawable.placeholder_thumbnail_video)
                            .error(R.drawable.placeholder_thumbnail_video).target(target).build()
                    )
                }
            }
            complete = callback
            if (!cancelled.get()) handler.post(callback)
        }
        return Runnable {
            cancelled.set(true)
            if (task != null) {
                task.cancel(false)
                worker.remove(task)
            }
            complete?.let { handler.removeCallbacks(it) }
            request?.dispose()
        }
    }

    private fun sameSource(current: OfflineStore.Entry?, expected: OfflineStore.Entry) = current != null && current.origin == expected.origin && current.mime == expected.mime &&
        current.serviceId == expected.serviceId

    // Only the single bounded worker calls this method or accesses misses.
    private fun prepare(context: Context, store: OfflineStore, id: String) {
        val entry = store.get(id) ?: return
        if (entry.state != OfflineStore.State.READY) return
        val output = OfflineThumbnailFiles.file(store, entry)
        if (OfflineThumbnailFiles.images(store, entry).isNotEmpty()) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(output.absolutePath, bounds)
            if (bounds.outWidth in 1..EDGE && bounds.outHeight in 1..EDGE) return
            output.delete()
        }
        val key = output.absolutePath
        if ((misses[key] ?: 0) > System.currentTimeMillis()) return
        misses[key] = System.currentTimeMillis() + 60_000
        val dir = output.parentFile ?: return
        if (!dir.isDirectory && !dir.mkdirs()) return
        tidy(store, dir, output)
        val preview = File(dir, "$id.preview")
        val identityFile = File(dir, "$id.identity")
        if (preview.isFile && preview.length() in 1..OfflineThumbnailFiles.MAX_IMAGE_BYTES &&
            identityFile.isFile && identityFile.length() <= 8192
        ) {
            val identity = Properties()
            optional { identityFile.inputStream().use { identity.load(it) } }
            if (identity.getProperty("origin") == entry.origin &&
                identity.getProperty("mime") == entry.mime &&
                identity.getProperty("service") == entry.serviceId.toString()
            ) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(preview.absolutePath, bounds)
                synchronized(store) {
                    val current = store.get(id)
                    if (sameSource(current, entry) && current?.state == OfflineStore.State.READY &&
                        OfflineThumbnailFiles.file(store, current) == output &&
                        bounds.outWidth in 1..EDGE && bounds.outHeight in 1..EDGE && preview.renameTo(output)
                    ) {
                        return
                    }
                }
            }
        }
        val urls = mutableListOf<String>()
        val info = InfoCache.getInstance().getFromKey(entry.serviceId, entry.origin, InfoCache.Type.STREAM)
        if (info is StreamInfo) urls.addAll(info.thumbnails.take(8).map { it.url })
        var bitmap = cachedSafely(context, urls)
        if (bitmap == null && entry.origin.isNotEmpty()) {
            optional {
                NewPipeDatabase.getInstance(context).streamDAO()
                    .getStream(entry.serviceId.toLong(), entry.origin).blockingFirst()
                    .firstOrNull()?.thumbnailUrl?.let { bitmap = cachedSafely(context, listOf(it)) }
            }
        }
        val artwork = bitmap ?: frame(store, entry) ?: return
        try {
            // Deletion/retry during extraction must not publish artwork for a new generation.
            synchronized(store) {
                val current = store.get(id)
                if (sameSource(current, entry) && current?.state == OfflineStore.State.READY &&
                    OfflineThumbnailFiles.file(store, current) == output
                ) {
                    writeBitmap(artwork, output)
                }
            }
        } finally {
            artwork.recycle()
        }
    }

    private fun cachedSafely(context: Context, urls: List<String>): Bitmap? {
        for (url in urls.distinct().take(8)) {
            try {
                cached(context, listOf(url))?.let { return it }
            } catch (_: IOException) { /* Try the next cached candidate, then owned media. */ } catch (_: RuntimeException) { /* An invalid image must not prevent frame extraction. */ }
        }
        return null
    }

    private fun cached(context: Context, urls: List<String>): Bitmap? {
        if (urls.isEmpty()) return null
        val loader = context.imageLoader
        for (url in urls.distinct().take(8)) {
            val memory = loader.memoryCache?.get(MemoryCache.Key(url))?.image as? BitmapImage
            if (memory != null) return scaled(memory.bitmap)
            val cache = loader.diskCache ?: continue
            cache.openSnapshot(url)?.use { snapshot ->
                val size = cache.fileSystem.metadata(snapshot.data).size ?: 0
                if (size !in 1..MAX_CACHED_SOURCE) return@use
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                cache.fileSystem.source(snapshot.data).buffer().inputStream().use {
                    BitmapFactory.decodeStream(it, null, bounds)
                }
                if (bounds.outWidth !in 1..16384 || bounds.outHeight !in 1..16384) return@use
                val options = BitmapFactory.Options().apply { inSampleSize = 1 }
                while (max(bounds.outWidth, bounds.outHeight) / options.inSampleSize > EDGE * 2) {
                    options.inSampleSize *= 2
                }
                cache.fileSystem.source(snapshot.data).buffer().inputStream().use {
                    val decoded = BitmapFactory.decodeStream(it, null, options) ?: return@use
                    try {
                        return scaled(decoded)
                    } finally {
                        decoded.recycle()
                    }
                }
            }
        }
        return null
    }

    private fun scaled(bitmap: Bitmap): Bitmap {
        val factor = minOf(1.0, EDGE.toDouble() / max(bitmap.width, bitmap.height))
        val result = Bitmap.createScaledBitmap(
            bitmap,
            max(1, (bitmap.width * factor).toInt()),
            max(1, (bitmap.height * factor).toInt()),
            true
        )
        // Memory-cache ownership stays with Coil, even if scaling returned the input bitmap.
        return if (result === bitmap) bitmap.copy(Bitmap.Config.ARGB_8888, false) else result
    }

    private fun frame(store: OfflineStore, entry: OfflineStore.Entry): Bitmap? {
        if (!OfflineThumbnailFiles.isVideo(entry)) return null
        val media = store.retain(entry.id)
        try {
            val metadata = MediaMetadataRetriever()
            try {
                metadata.setDataSource(media.absolutePath)
                val width = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val height = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                if (width !in 1..8192 || height !in 1..8192) return null
                // Pre-27 has no scaled API: refuse large/unknown frames before any decode.
                if (Build.VERSION.SDK_INT < 27 && width.toLong() * height > 480_000) return null
                val ratio = minOf(1.0, EDGE.toDouble() / max(width, height))
                val frame = if (Build.VERSION.SDK_INT >= 27) {
                    metadata.getScaledFrameAtTime(
                        0,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        max(1, (width * ratio).toInt()),
                        max(1, (height * ratio).toInt())
                    )
                } else {
                    metadata.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }
                return frame?.let {
                    try {
                        scaled(it)
                    } finally {
                        it.recycle()
                    }
                }
            } finally {
                metadata.release()
            }
        } finally {
            store.release(entry.id)
        }
    }

    private fun writeBitmap(bitmap: Bitmap, output: File) {
        val bytes = ByteArrayOutputStream().use {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it)) return
            it.toByteArray()
        }
        if (bytes.isEmpty() || bytes.size > OfflineThumbnailFiles.MAX_IMAGE_BYTES) return
        val part = File(output.parentFile, output.name + ".part")
        try {
            part.outputStream().use { it.write(bytes) }
            if (!part.renameTo(output)) throw IOException("Artwork unavailable")
        } finally {
            part.delete()
        }
    }

    private fun tidy(store: OfflineStore, dir: File, keep: File) {
        val ids = store.list().map { it.id }.toSet()
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        files.filter { file -> ids.none { file.name.startsWith(it + "-") || file.name.startsWith(it + ".") } }
            .forEach { it.delete() }
        val images = files.filter { it.exists() && it != keep }.sortedBy { it.lastModified() }
        var total = images.sumOf { it.length() }
        for (file in images) {
            if (total < CACHE_BUDGET - OfflineThumbnailFiles.MAX_IMAGE_BYTES) break
            val size = file.length()
            if (file.delete()) total -= size
        }
    }

    private inline fun optional(work: () -> Unit) {
        try {
            work()
        } catch (_: IOException) {
            // A missing cover must never fail saving or playback. No URLs/errors are logged.
        } catch (_: RuntimeException) {
            // Unsupported images/codecs retain the placeholder.
        }
    }
}
