package org.schabi.newpipe.local.playlist

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.schabi.newpipe.database.AppDatabase
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.extractor.stream.StreamType

/** Own disk database only: never replaces or clears the app's NewPipeDatabase singleton. */
class LocalPlaylistPersistenceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var directory: File
    private lateinit var database: AppDatabase

    @Before
    fun openOwnedDatabase() {
        directory = File(context.cacheDir, "playlist-persistence-${UUID.randomUUID()}")
        assertTrue(directory.mkdir())
        database = openDatabase()
    }

    @After
    fun removeOnlyOwnedFixture() {
        if (::database.isInitialized) database.close()
        if (::directory.isInitialized) assertTrue(directory.deleteRecursively())
    }

    @Test
    fun emptyCreationFirstAppendAndReorderSurviveDatabaseReopen() {
        var manager = LocalPlaylistManager(database)
        assertEquals(emptyList<Long>(), manager.createPlaylist("Owned empty list", emptyList()).blockingGet())
        val created = manager.playlists.blockingFirst().single()
        assertEquals(0L, created.streamCount)
        assertEquals(-1L, created.thumbnailStreamId)
        val id = created.uid
        reopen()
        manager = LocalPlaylistManager(database)
        assertEquals(id, manager.playlists.blockingFirst().single().uid)
        assertEquals(0L, manager.playlists.blockingFirst().single().streamCount)

        val tracks = listOf(track("a"), track("b"))
        val inserted = requireNotNull(manager.appendToPlaylist(id, tracks).blockingGet())
        assertEquals(2, inserted.size)
        val ordered = manager.getPlaylistStreams(id).blockingFirst()
        assertEquals(tracks.map { it.url }, ordered.map { it.streamEntity.url })
        manager.updateJoin(id, ordered.map { it.streamId }.reversed()).blockingAwait()
        reopen()
        manager = LocalPlaylistManager(database)
        assertEquals(tracks.map { it.url }.reversed(), manager.getPlaylistStreams(id).blockingFirst().map { it.streamEntity.url })
        assertEquals(2L, manager.playlists.blockingFirst().single().streamCount)
    }

    private fun reopen() {
        database.close()
        database = openDatabase()
    }

    private fun openDatabase() = Room.databaseBuilder(
        context,
        AppDatabase::class.java,
        File(directory, "owned.db").absolutePath
    ).build()

    private fun track(name: String) = StreamEntity(
        serviceId = 0,
        url = "https://example.invalid/owned-fixture/$name",
        title = name,
        streamType = StreamType.AUDIO_STREAM,
        duration = 1,
        uploader = "Generated fixture"
    )
}
