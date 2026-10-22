package org.schabi.newpipe.offline

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.drawable.toBitmap
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.imageLoader
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import okio.buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.R
import org.schabi.newpipe.player.SavedPlaybackTestSupport.RejectNetwork
import org.schabi.newpipe.player.playqueue.OfflinePlayQueue

/** Generated media and solid-colour images only. Device radios are disabled by the runner. */
@RunWith(AndroidJUnit4::class)
class OfflineThumbnailsDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private lateinit var library: OfflineLibrary
    private val created = mutableListOf<String>()
    private var activity: Activity? = null

    @Before fun setup() {
        library = OfflineLibrary.get(context)
    }

    @After fun cleanup() {
        main { activity?.finish() }
        created.forEach { id ->
            library.store().delete(id)
            val dir = File(context.filesDir, "offline/thumbnails")
            dir.listFiles()?.filter { it.name.startsWith(id) }?.forEach { it.delete() }
        }
    }

    @Test fun cachedArtPersistsAndActualShelfShowsDistinctVideoAudioCoversAndLabels() {
        val video = save(true)
        val audio = save(false)
        val videoCover = cacheCover(Color.RED)
        val audioCover = cacheCover(Color.BLUE)
        RejectNetwork().use { network ->
            OfflineThumbnails.remember(context, library.store(), video, videoCover)
            OfflineThumbnails.remember(context, library.store(), audio, audioCover)
            assertTrue(
                waitFor {
                    OfflineThumbnailFiles.images(library.store(), video).isNotEmpty() &&
                        OfflineThumbnailFiles.images(library.store(), audio).isNotEmpty()
                }
            )
            val reopened = OfflineStore(File(context.filesDir, "offline"), OfflineStore.DEFAULT_LIMIT)
            val all = OfflinePlayQueue(reopened, "Saved copy")
            for (entry in listOf(video, audio)) {
                val item = all.streams.first { it.url == entry.localUrl() }
                assertEquals(1, item.thumbnails.size)
                assertTrue(item.thumbnails[0].url.startsWith("file:"))
                assertEquals(
                    item.thumbnails[0].url,
                    OfflineMediaTag(item, reopened).maybeStreamInfo.get().thumbnails[0].url
                )
            }
            activity = instrumentation.startActivitySync(
                Intent(context, OfflineLibraryActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            assertTrue(waitFor { main { rowColour(video.id) == Color.RED && rowColour(audio.id) == Color.BLUE } })
            main {
                assertTrue(rowDetails(video.id).contains(activity!!.getString(R.string.offline_video_play)))
                assertTrue(rowDetails(audio.id).contains(activity!!.getString(R.string.offline_audio_play)))
            }
            library.store().move(audio.id, -1)
            assertTrue(
                waitFor {
                    main {
                        val rows = activity!!.findViewById<RecyclerView>(R.id.offline_saved_rows)
                        val adapter = rows.adapter as OfflineShelfAdapter
                        adapter.positionOf(audio.id) < adapter.positionOf(video.id) &&
                            rowColour(video.id) == Color.RED && rowColour(audio.id) == Color.BLUE
                    }
                }
            )
            instrumentation.uiAutomation.takeScreenshot()?.let { screenshot ->
                File(context.cacheDir, "offline-thumbnails-generated-ui.png").outputStream().use {
                    screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                screenshot.recycle()
            }
            val korean = context.createConfigurationContext(
                Configuration(context.resources.configuration).apply {
                    setLocale(Locale.KOREAN)
                }
            )
            assertEquals("영상 저장본 재생", korean.getString(R.string.offline_video_play))
            assertEquals("오디오 저장본 재생", korean.getString(R.string.offline_audio_play))
            assertEquals(0, network.requests.get())
        }
    }

    @Test fun legacyVideoWithoutImageExtractsLocalFrameAndKeepsMediaBytesAndOrder() {
        val video = save(true)
        val media = library.store().file(video)
        val before = media.readBytes()
        val order = library.store().list().map { it.id }
        // Malformed preview metadata must not suppress the valid owned-video fallback.
        val dir = OfflineThumbnailFiles.directory(library.store(), video).apply { mkdirs() }
        File(dir, "${video.id}.preview").writeBytes(byteArrayOf(1, 2, 3))
        File(dir, "${video.id}.identity").writeText("origin=\\uQQQQ\n")
        val ready = CountDownLatch(1)
        RejectNetwork().use { network ->
            OfflineThumbnails.schedule(context, library.store(), video) { ready.countDown() }
            assertTrue(ready.await(30, TimeUnit.SECONDS))
            val image = OfflineThumbnailFiles.file(library.store(), video)
            assertTrue(image.isFile)
            val decoded = android.graphics.BitmapFactory.decodeFile(image.absolutePath)
            assertTrue(decoded.width in 1..320 && decoded.height in 1..320)
            decoded.recycle()
            assertTrue(before.contentEquals(media.readBytes()))
            assertEquals(order, library.store().list().map { it.id })
            assertEquals(0, network.requests.get())
        }
    }

    @Test fun corruptMediaAndAudioWithoutCoverKeepPlaceholderWithoutLosingSave() {
        val bad = library.store().create(
            "Generated invalid media",
            "",
            0,
            "file:///generated/${UUID.randomUUID()}",
            "video/webm"
        )
        created.add(bad.id)
        library.store().copy(bad.id, byteArrayOf(1, 2, 3).inputStream())
        val audio = save(false)
        for (entry in listOf(library.store().get(bad.id), audio)) {
            val ready = CountDownLatch(1)
            OfflineThumbnails.schedule(context, library.store(), entry) { ready.countDown() }
            assertTrue(ready.await(30, TimeUnit.SECONDS))
            assertTrue(OfflineThumbnailFiles.images(library.store(), entry).isEmpty())
            assertEquals(OfflineStore.State.READY, library.store().get(entry.id).state)
            assertFalse(library.queue(entry).isEmpty)
        }
    }

    @Test fun roomCachedCoverIsTriedAfterEightInfoCacheMisses() {
        val origin = "https://example.invalid/generated/${UUID.randomUUID()}"
        val audio = save(false, origin)
        val cover = cacheCover(Color.GREEN)
        val info = org.schabi.newpipe.extractor.stream.StreamInfo(
            0,
            origin,
            origin,
            org.schabi.newpipe.extractor.stream.StreamType.AUDIO_STREAM,
            origin,
            "Generated audio",
            0
        )
        info.thumbnails = (1..8).map {
            org.schabi.newpipe.extractor.Image(
                "https://example.invalid/uncached/$it/${UUID.randomUUID()}.jpg",
                45,
                80,
                org.schabi.newpipe.extractor.Image.ResolutionLevel.LOW
            )
        }
        val cache = org.schabi.newpipe.util.InfoCache.getInstance()
        val dao = org.schabi.newpipe.NewPipeDatabase.getInstance(context).streamDAO()
        val entity = org.schabi.newpipe.database.stream.model.StreamEntity(
            serviceId = 0,
            url = origin,
            title = "Generated audio",
            streamType = info.streamType,
            duration = 3,
            uploader = "Generated",
            thumbnailUrl = cover[0].url
        )
        entity.uid = dao.insert(entity)
        cache.putInfo(0, origin, info, org.schabi.newpipe.util.InfoCache.Type.STREAM)
        try {
            val ready = CountDownLatch(1)
            OfflineThumbnails.schedule(context, library.store(), audio) { ready.countDown() }
            assertTrue(ready.await(30, TimeUnit.SECONDS))
            assertEquals(
                "Room cached cover was skipped after eight misses",
                1,
                OfflineThumbnailFiles.images(library.store(), audio).size
            )
        } finally {
            cache.removeInfo(0, origin, org.schabi.newpipe.util.InfoCache.Type.STREAM)
            dao.delete(entity)
        }
    }

    @Test fun saturatedArtworkWorkerNeverDelaysOwnedMediaSourceCreation() {
        val audio = save(false)
        val field = OfflineThumbnails::class.java.getDeclaredField("worker").apply {
            isAccessible = true
        }
        val worker = field.get(null) as java.util.concurrent.ThreadPoolExecutor
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        worker.execute {
            entered.countDown()
            release.await(30, TimeUnit.SECONDS)
        }
        assertTrue(entered.await(30, TimeUnit.SECONDS))
        try {
            repeat(40) { OfflineThumbnails.schedule(context, library.store(), audio) { } }
            val start = System.nanoTime()
            assertTrue(library.source(library.queue(audio).item) != null)
            assertTrue(
                "artwork backlog delayed media source",
                System.nanoTime() - start < TimeUnit.MILLISECONDS.toNanos(1500)
            )
            assertTrue(worker.queue.size <= 32)
        } finally {
            release.countDown()
        }
    }

    private fun save(video: Boolean, origin: String = ""): OfflineStore.Entry {
        val asset = if (video) "offline-generated-av.webm" else "offline-generated-audio.m4a"
        val entry = library.store().create(
            if (video) "Generated saved video" else "Generated saved audio",
            origin,
            0,
            "file:///generated/${UUID.randomUUID()}/$asset",
            if (video) "video/webm" else "audio/mp4"
        )
        created.add(entry.id)
        instrumentation.context.assets.open(asset).use { library.store().copy(entry.id, it) }
        return library.store().get(entry.id)
    }

    private fun cacheCover(colour: Int): List<org.schabi.newpipe.extractor.Image> {
        val url = "https://example.invalid/generated-cover/${UUID.randomUUID()}.jpg"
        val cache = context.imageLoader.diskCache!!
        val editor = cache.openEditor(url)!!
        val bitmap = Bitmap.createBitmap(80, 45, Bitmap.Config.ARGB_8888).apply { eraseColor(colour) }
        cache.fileSystem.sink(editor.data).buffer().outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
        editor.commit()
        return listOf(
            org.schabi.newpipe.extractor.Image(
                url,
                45,
                80,
                org.schabi.newpipe.extractor.Image.ResolutionLevel.LOW
            )
        )
    }

    private fun row(id: String): RecyclerView.ViewHolder? {
        val rows = activity!!.findViewById<RecyclerView>(R.id.offline_saved_rows)
        val adapter = rows.adapter as OfflineShelfAdapter
        return rows.findViewHolderForAdapterPosition(adapter.positionOf(id))
    }

    private fun rowDetails(id: String) = row(id)!!.itemView.findViewById<TextView>(R.id.itemAdditionalDetails).text.toString()

    private fun rowColour(id: String): Int? {
        val image = row(id)?.itemView?.findViewById<ImageView>(R.id.itemThumbnailView) ?: return null
        // Coil 3 wraps BitmapImage in ImageDrawable, rather than Android's BitmapDrawable.
        return image.drawable?.toBitmap(1, 1)?.getPixel(0, 0)?.let {
            // JPEG quantisation may change each channel by a few units.
            when {
                Color.red(it) > 240 && Color.blue(it) < 10 -> Color.RED
                Color.blue(it) > 240 && Color.red(it) < 10 -> Color.BLUE
                else -> it
            }
        }
    }

    private fun waitFor(condition: () -> Boolean): Boolean {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (System.nanoTime() < until) {
            if (condition()) return true
            Thread.sleep(50)
        }
        return false
    }

    private fun <T> main(block: () -> T): T {
        val task = FutureTask { block() }
        instrumentation.runOnMainSync(task)
        return task.get()
    }
}
