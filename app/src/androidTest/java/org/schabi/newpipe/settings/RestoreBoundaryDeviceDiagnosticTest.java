package org.schabi.newpipe.settings;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.ContextWrapper;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.room.Room;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.schabi.newpipe.database.AppDatabase;
import org.schabi.newpipe.settings.export.BackupFileLocator;
import org.schabi.newpipe.settings.export.ImportExportManager;
import org.schabi.newpipe.streams.io.StoredFileHelper;
import org.schabi.newpipe.util.ZipHelper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import us.shandian.giga.io.FileStream;

/**
 * Test-owned Room files only. These diagnostics establish the current R1 risk and show that
 * archive staging can leave the old database untouched. They do not prove an exclusive restore
 * owner or a safe live-file swap.
 */
@RunWith(AndroidJUnit4.class)
public class RestoreBoundaryDeviceDiagnosticTest {
    private Context context;
    private File directory;
    private File liveFile;
    private AppDatabase liveRoom;

    @Before
    public void createOwnedDatabase() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        directory = Files.createTempDirectory(context.getCacheDir().toPath(), "r1-owned-")
                .toFile();
        liveFile = new File(directory, "owned-live.db");
        liveRoom = openRoom(liveFile);
        addMarker(liveRoom, "old", false);
        assertEquals("old", readMarker(liveRoom));
    }

    @After
    public void removeOnlyOwnedFixture() {
        if (liveRoom != null) {
            liveRoom.close();
        }
        if (directory != null) {
            deleteRecursively(directory);
        }
    }

    @Test
    public void secondArchiveReadFailureDamagesActiveOwnedRoomDatabaseFile() throws Exception {
        final File archive = createArchiveWithClosedRoomDatabase();
        final byte[] before = Files.readAllBytes(liveFile.toPath());
        final StoredFileHelper source = secondReadFailureSource(archive);
        assertTrue("generated backup must pass R2 preflight", ZipHelper.isValidZipFile(source));
        assertThrows(IOException.class, () -> manager().extractDb(source));
        assertFalse("R1 risk did not reproduce: live path stayed unchanged",
                Arrays.equals(before, Files.readAllBytes(liveFile.toPath())));
    }

    @Test
    public void closedRoomBackupCanBeStagedAndOpenedWithoutTouchingLiveFile()
            throws Exception {
        final File archive = createArchiveWithClosedRoomDatabase();
        final byte[] before = Files.readAllBytes(liveFile.toPath());
        final File stage = new File(directory, "owned-stage.db");
        final StoredFileHelper source = new StoredFileHelper(null, "owned.zip",
                "application/zip", null) {
            @Override
            public FileStream getStream() throws IOException {
                return new FileStream(archive);
            }
        };
        assertTrue(ZipHelper.isValidZipFile(source));
        assertTrue(ZipHelper.extractFileFromZip(source, "newpipe.db", stage.toPath()));
        final AppDatabase stagedRoom = openRoom(stage);
        try {
            assertEquals("new", readMarker(stagedRoom));
        } finally {
            stagedRoom.close();
        }
        assertEquals("old", readMarker(liveRoom));
        assertArrayEquals(before, Files.readAllBytes(liveFile.toPath()));
    }

    @Test
    public void secondReadFailureIntoStageLeavesActiveRoomDatabaseUnchanged()
            throws Exception {
        final File archive = createArchiveWithClosedRoomDatabase();
        final byte[] before = Files.readAllBytes(liveFile.toPath());
        final File stage = new File(directory, "failed-stage.db");
        final StoredFileHelper source = secondReadFailureSource(archive);
        assertTrue(ZipHelper.isValidZipFile(source));

        assertThrows(IOException.class,
                () -> ZipHelper.extractFileFromZip(source, "newpipe.db", stage.toPath()));

        assertEquals("old", readMarker(liveRoom));
        assertArrayEquals(before, Files.readAllBytes(liveFile.toPath()));
    }

    @Test
    public void mainFileSnapshotOmitsCommittedRowStillInWal() throws Exception {
        final File walFile = new File(directory, "wal-owned.db");
        try (SQLiteDatabase database = SQLiteDatabase.openOrCreateDatabase(walFile, null)) {
            assertTrue(database.enableWriteAheadLogging());
            database.execSQL("CREATE TABLE r1_fixture(value TEXT NOT NULL)");
            database.execSQL("INSERT INTO r1_fixture(value) VALUES ('old')");
        }

        try (SQLiteDatabase database = SQLiteDatabase.openDatabase(walFile.toString(), null,
                SQLiteDatabase.OPEN_READWRITE)) {
            assertTrue(database.enableWriteAheadLogging());
            try (Cursor checkpoint = database.rawQuery("PRAGMA wal_autocheckpoint=0", null)) {
                assertTrue(checkpoint.moveToFirst());
            }
            database.execSQL("UPDATE r1_fixture SET value='new'");
            try (Cursor cursor = database.rawQuery("SELECT value FROM r1_fixture", null)) {
                assertTrue(cursor.moveToFirst());
                assertEquals("new", cursor.getString(0));
            }
            assertTrue(new File(directory, "wal-owned.db-wal").length() > 0);

            final File mainOnly = new File(directory, "main-only.db");
            Files.copy(walFile.toPath(), mainOnly.toPath());
            try (SQLiteDatabase snapshot = SQLiteDatabase.openDatabase(mainOnly.toString(),
                    null, SQLiteDatabase.OPEN_READONLY);
                    Cursor cursor = snapshot.rawQuery("SELECT value FROM r1_fixture", null)) {
                assertTrue(cursor.moveToFirst());
                assertEquals("old", cursor.getString(0));
            }
        }
    }

    private AppDatabase openRoom(final File path) {
        return Room.databaseBuilder(context, AppDatabase.class, path.getAbsolutePath()).build();
    }

    private static void addMarker(final AppDatabase room, final String marker,
                                  final boolean addRandomPayload) {
        room.getOpenHelper().getWritableDatabase()
                .execSQL("CREATE TABLE r1_fixture(value TEXT NOT NULL, padding BLOB)");
        final byte[] padding;
        if (addRandomPayload) {
            padding = new byte[128 * 1024];
            new Random(1).nextBytes(padding);
        } else {
            padding = new byte[0];
        }
        room.getOpenHelper().getWritableDatabase().execSQL(
                "INSERT INTO r1_fixture(value, padding) VALUES (?, ?)",
                new Object[]{marker, padding});
    }

    private static String readMarker(final AppDatabase room) {
        try (Cursor cursor = room.query("SELECT value FROM r1_fixture", null)) {
            assertTrue(cursor.moveToFirst());
            return cursor.getString(0);
        }
    }

    private File createArchiveWithClosedRoomDatabase() throws Exception {
        final File candidate = new File(directory, "owned-candidate.db");
        final AppDatabase room = openRoom(candidate);
        try {
            addMarker(room, "new", true);
        } finally {
            room.close();
        }
        final File archive = new File(directory, "owned-backup.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(archive.toPath()))) {
            out.putNextEntry(new ZipEntry("newpipe.db"));
            out.write(Files.readAllBytes(candidate.toPath()));
            out.closeEntry();
        }
        assertTrue("generated archive must exceed injected read limit",
                archive.length() > 16 * 1024);
        return archive;
    }

    private StoredFileHelper secondReadFailureSource(final File archive) {
        final AtomicInteger opens = new AtomicInteger();
        return new StoredFileHelper(null, "owned.zip", "application/zip", null) {
            @Override
            public FileStream getStream() throws IOException {
                if (opens.getAndIncrement() == 0) {
                    return new FileStream(archive);
                }
                return new FileStream(archive) {
                    private int consumed;

                    @Override
                    public int read(final byte[] b) throws IOException {
                        if (consumed > 8192) {
                            throw new IOException("generated second-read failure");
                        }
                        final int result = super.read(b);
                        consumed += Math.max(result, 0);
                        return result;
                    }

                    @Override
                    public int read(final byte[] b, final int offset, final int count)
                            throws IOException {
                        if (consumed > 8192) {
                            throw new IOException("generated second-read failure");
                        }
                        final int result = super.read(b, offset, count);
                        consumed += Math.max(result, 0);
                        return result;
                    }
                };
            }
        };
    }

    private ImportExportManager manager() {
        return new ImportExportManager(new BackupFileLocator(new ContextWrapper(context) {
            @Override
            public File getDatabasePath(final String name) {
                return liveFile;
            }
        }));
    }

    private static void deleteRecursively(final File file) {
        final File[] children = file.listFiles();
        if (children != null) {
            for (final File child : children) {
                deleteRecursively(child);
            }
        }
        file.delete();
    }
}
