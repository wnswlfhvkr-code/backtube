package us.shandian.giga.get.sqlite;

import android.content.Context;
import android.content.ContextWrapper;
import android.database.Cursor;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteCursor;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** Exercises only isolated, generated downloads databases in app cache. */
@RunWith(AndroidJUnit4.class)
public class FinishedMissionMigrationDeviceTest {
    private static final String OLD_TABLE = "download_missions";

    @Test
    public void versionTwoNullKindPreservesTheOldMission() {
        final File directory = newFixtureDirectory();
        try {
            final File databaseFile = new File(directory, "downloads.db");
            try (SQLiteDatabase database = SQLiteDatabase.openOrCreateDatabase(databaseFile,
                    null)) {
                database.execSQL("CREATE TABLE " + OLD_TABLE + " (location TEXT, name TEXT, "
                        + "url TEXT, bytes_downloaded INTEGER, timestamp INTEGER)");
                database.execSQL("INSERT INTO " + OLD_TABLE + " VALUES "
                        + "('/generated', 'fixture.wav', 'https://example.invalid/a', 42, 1)");
                database.setVersion(2);
            }

            try (FinishedMissionStore store = new FinishedMissionStore(isolatedContext(directory));
                 Cursor rows = store.getWritableDatabase().rawQuery(
                         "SELECT kind, bytes_downloaded FROM finished_missions", null)) {
                assertTrue(rows.moveToFirst());
                assertEquals("?", rows.getString(0));
                assertEquals(42, rows.getLong(1));
                assertEquals(1, rows.getCount());
            }
        } finally {
            deleteFixture(directory);
        }
    }

    @Test
    public void duplicateLegacyRowsAbortMigrationWithoutDroppingOriginals() {
        final File directory = newFixtureDirectory();
        try {
            final File databaseFile = new File(directory, "downloads.db");
            try (SQLiteDatabase database = SQLiteDatabase.openOrCreateDatabase(databaseFile,
                    null)) {
                database.execSQL("CREATE TABLE " + OLD_TABLE + " (location TEXT, name TEXT, "
                        + "url TEXT, bytes_downloaded INTEGER, timestamp INTEGER, kind TEXT)");
                database.execSQL("INSERT INTO " + OLD_TABLE + " VALUES "
                        + "('/generated', 'fixture.wav', 'https://example.invalid/a', 42, 1, 'a')");
                database.execSQL("INSERT INTO " + OLD_TABLE + " VALUES "
                        + "('/generated', 'fixture.wav', 'https://example.invalid/b', 43, 1, 'a')");
                database.setVersion(3);
            }

            try (FinishedMissionStore store =
                    new FinishedMissionStore(isolatedContext(directory))) {
                assertThrows(IllegalStateException.class, store::getWritableDatabase);
            }

            try (SQLiteDatabase original = SQLiteDatabase.openDatabase(databaseFile.getPath(),
                    null, SQLiteDatabase.OPEN_READONLY);
                 Cursor rows = original.rawQuery("SELECT COUNT(*) FROM " + OLD_TABLE, null)) {
                assertTrue(rows.moveToFirst());
                assertEquals(2, rows.getInt(0));
                assertEquals(3, original.getVersion());
            }
        } finally {
            deleteFixture(directory);
        }
    }

    @Test
    public void validVersionThreeMissionMigratesNormally() {
        final File directory = newFixtureDirectory();
        try {
            final File databaseFile = new File(directory, "downloads.db");
            try (SQLiteDatabase database = SQLiteDatabase.openOrCreateDatabase(databaseFile,
                    null)) {
                database.execSQL("CREATE TABLE " + OLD_TABLE + " (location TEXT, name TEXT, "
                        + "url TEXT, bytes_downloaded INTEGER, timestamp INTEGER, kind TEXT)");
                database.execSQL("INSERT INTO " + OLD_TABLE + " VALUES "
                        + "('/generated', 'fixture.wav', 'https://example.invalid/a', 42, 1, 'a')");
                database.setVersion(3);
            }

            try (FinishedMissionStore store = new FinishedMissionStore(isolatedContext(directory));
                 Cursor rows = store.getWritableDatabase().rawQuery(
                         "SELECT kind, bytes_downloaded FROM finished_missions", null)) {
                assertTrue(rows.moveToFirst());
                assertEquals("a", rows.getString(0));
                assertEquals(42, rows.getLong(1));
                assertFalse(rows.moveToNext());
            }
        } finally {
            deleteFixture(directory);
        }
    }

    @Test
    public void emptyFinishedQueryClosesItsCursor() {
        final File directory = newFixtureDirectory();
        try {
            final AtomicInteger opened = new AtomicInteger();
            final AtomicInteger closed = new AtomicInteger();
            final SQLiteDatabase.CursorFactory trackingFactory =
                    (database, driver, editTable, query) -> {
                        opened.incrementAndGet();
                        return new SQLiteCursor(driver, editTable, query) {
                            @Override
                            public void close() {
                                super.close();
                                closed.incrementAndGet();
                            }
                        };
                    };
            try (FinishedMissionStore store = new FinishedMissionStore(
                    isolatedContext(directory, trackingFactory))) {
                store.getWritableDatabase();
                opened.set(0);
                closed.set(0);
                assertTrue(store.loadFinishedMissions().isEmpty());
                assertEquals(1, opened.get());
                assertEquals(1, closed.get());
            }
        } finally {
            deleteFixture(directory);
        }
    }

    private static File newFixtureDirectory() {
        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final File directory = new File(context.getCacheDir(), "migration-" + UUID.randomUUID());
        assertTrue(directory.mkdir());
        return directory;
    }

    private static Context isolatedContext(final File directory) {
        return isolatedContext(directory, null);
    }

    private static Context isolatedContext(final File directory,
            final SQLiteDatabase.CursorFactory trackingFactory) {
        final Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        return new ContextWrapper(target) {
            @Override
            public File getDatabasePath(final String name) {
                return new File(directory, name);
            }

            @Override
            public SQLiteDatabase openOrCreateDatabase(final String name, final int mode,
                    final SQLiteDatabase.CursorFactory factory) {
                return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name),
                        trackingFactory == null ? factory : trackingFactory);
            }

            @Override
            public SQLiteDatabase openOrCreateDatabase(final String name, final int mode,
                    final SQLiteDatabase.CursorFactory factory,
                    final DatabaseErrorHandler errorHandler) {
                return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).getPath(),
                        trackingFactory == null ? factory : trackingFactory, errorHandler);
            }
        };
    }

    private static void deleteFixture(final File file) {
        final File[] children = file.listFiles();
        if (children != null) {
            for (final File child : children) {
                deleteFixture(child);
            }
        }
        assertTrue(file.delete());
    }
}
