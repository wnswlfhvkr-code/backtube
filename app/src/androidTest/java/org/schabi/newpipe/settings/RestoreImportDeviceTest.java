package org.schabi.newpipe.settings;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Bundle;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.schabi.newpipe.R;
import org.schabi.newpipe.settings.export.BackupFileLocator;
import org.schabi.newpipe.settings.export.ImportExportManager;
import org.schabi.newpipe.streams.io.StoredFileHelper;
import us.shandian.giga.io.FileStream;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Real fragment and archive IO; database paths and preferences are isolated test fixtures. */
@RunWith(AndroidJUnit4.class)
public class RestoreImportDeviceTest {
    private SettingsActivity activity;
    private FixtureFragment fragment;
    private File directory;
    private File database;
    private byte[] beforeDatabase;
    private Map<String, ?> beforePreferences;
    private final CountDownLatch release = new CountDownLatch(1);
    private final java.util.concurrent.atomic.AtomicBoolean settingsOffered =
            new java.util.concurrent.atomic.AtomicBoolean();

    public static class FixtureFragment extends BackupRestoreSettingsFragment {
        int finished;
        @Override public void onCreatePreferences(final Bundle state, final String root) {
            setPreferenceScreen(getPreferenceManager().createPreferenceScreen(requireContext()));
            getPreferenceScreen().setTitle("Generated restore test");
        }
        @Override void finishImport(final Uri uri) {
            finished++;
        }
    }

    private static void main(final Runnable action) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(action);
    }

    @Before public void setUp() throws Exception {
        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .setOnAccessibilityEventListener(event -> {
                    if (event.getEventType() == android.view.accessibility.AccessibilityEvent
                            .TYPE_WINDOW_STATE_CHANGED && event.getText().stream().anyMatch(
                                    text -> context.getString(R.string.import_settings)
                                            .contentEquals(text))) {
                        settingsOffered.set(true);
                    }
                });
        directory = Files.createTempDirectory(context.getCacheDir().toPath(), "restore-fixture-")
                .toFile();
        database = new File(directory, "live.db");
        sqlite(database, "old");
        beforeDatabase = Files.readAllBytes(database.toPath());
        activity = (SettingsActivity) InstrumentationRegistry.getInstrumentation()
                .startActivitySync(new Intent(context, SettingsActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        main(() -> {
            fragment = new FixtureFragment();
            activity.getSupportFragmentManager().beginTransaction()
                    .replace(R.id.settings_fragment_holder, fragment).commitNow();
            fragment.defaultPreferences = context.getSharedPreferences(directory.getName(), 0);
            fragment.defaultPreferences.edit().putString("sentinel", "old")
                    .putString("fixture_uri", "old-uri").commit();
            try {
                field("manager", new ImportExportManager(new BackupFileLocator(
                        new ContextWrapper(context) {
                            @Override public File getDatabasePath(final String name) {
                                return database;
                            }
                        })));
                field("importExportDataPathKey", "fixture_uri");
            } catch (final Exception e) {
                throw new AssertionError(e);
            }
        });
        beforePreferences = fragment.defaultPreferences.getAll();
    }

    @After public void tearDown() {
        release.countDown();
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .setOnAccessibilityEventListener(null);
        main(() -> activity.finish());
        fragment.defaultPreferences.edit().clear().commit();
        delete(directory);
    }

    private static void delete(final File file) {
        final File[] children = file.listFiles();
        if (children != null) {
            for (final File child : children) {
                delete(child);
            }
        }
        file.delete();
    }

    private void field(final String name, final Object value) throws Exception {
        final Field field = BackupRestoreSettingsFragment.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(fragment, value);
    }

    private static void sqlite(final File file, final String marker) {
        try (SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(file, null)) {
            db.execSQL("CREATE TABLE fixture(value TEXT)");
            db.execSQL("INSERT INTO fixture VALUES (?)", new Object[]{marker});
        }
    }

    private File archive(final boolean withDatabase, final boolean withPreferences)
            throws Exception {
        final File zip = new File(directory, "backup.zip");
        final File replacement = new File(directory, "replacement.db");
        sqlite(replacement, "new");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip.toPath()))) {
            if (withDatabase) {
                final byte[] data = Files.readAllBytes(replacement.toPath());
                final CRC32 crc = new CRC32();
                crc.update(data);
                final ZipEntry entry = new ZipEntry("newpipe.db");
                entry.setMethod(ZipEntry.STORED);
                entry.setSize(data.length);
                entry.setCompressedSize(data.length);
                entry.setCrc(crc.getValue());
                out.putNextEntry(entry);
                out.write(data);
                out.closeEntry();
            }
            if (withPreferences) {
                out.putNextEntry(new ZipEntry("preferences.json"));
                out.write("{\"sentinel\":\"new\"}"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        return zip;
    }

    private StoredFileHelper source(final File file) {
        return new StoredFileHelper(null, "fixture.zip", "application/zip", null) {
            @Override public FileStream getStream() throws IOException {
                return new FileStream(file);
            }
        };
    }

    private void begin(final StoredFileHelper source) throws Exception {
        final Method method = BackupRestoreSettingsFragment.class.getDeclaredMethod(
                "importDatabase", StoredFileHelper.class, Uri.class);
        method.setAccessible(true);
        final FutureTask<Void> task = new FutureTask<>(() -> {
            method.invoke(fragment, source, Uri.parse("content://generated/backup"));
            return null;
        });
        new android.os.Handler(android.os.Looper.getMainLooper()).post(task);
        task.get(2, TimeUnit.SECONDS);
    }

    private void settle() throws Exception {
        settle(5000);
    }

    private void settle(final long timeoutMillis) throws Exception {
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            final boolean[] busy = {false};
            main(() -> {
                try {
                    final Field f = BackupRestoreSettingsFragment.class
                            .getDeclaredField("importValidationDialog");
                    f.setAccessible(true);
                    final androidx.appcompat.app.AlertDialog dialog =
                            (androidx.appcompat.app.AlertDialog) f.get(fragment);
                    busy[0] = dialog != null && dialog.isShowing();
                } catch (final NoSuchFieldException baseline) {
                    busy[0] = false;
                } catch (final Exception e) {
                    throw new AssertionError(e);
                }
            });
            if (!busy[0]) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("restore validation did not settle");
    }

    private void assertPreserved() throws Exception {
        assertArrayEquals(beforeDatabase, Files.readAllBytes(database.toPath()));
        assertEquals(beforePreferences, fragment.defaultPreferences.getAll());
        assertEquals(0, fragment.finished);
    }

    @Test public void missingDatabaseDoesNotOfferSettingsOrFinish() throws Exception {
        begin(source(archive(false, true)));
        settle();
        Thread.sleep(1000);
        assertTrue("missing DB offered preference import", !settingsOffered.get());
        assertPreserved();
    }

    @Test public void ordinaryTextPreservesDatabaseAndPreferences() throws Exception {
        final File file = new File(directory, "text");
        Files.write(file.toPath(), new byte[]{1, 2, 3, 4});
        begin(source(file));
        settle();
        assertPreserved();
    }

    @Test public void crcFailurePreservesDatabaseAndPreferences() throws Exception {
        final File file = archive(true, true);
        final byte[] bytes = Files.readAllBytes(file.toPath());
        bytes[30 + "newpipe.db".length()] ^= 1;
        Files.write(file.toPath(), bytes);
        begin(source(file));
        settle();
        assertPreserved();
    }

    @Test public void readFailurePreservesDatabaseAndPreferences() throws Exception {
        begin(new StoredFileHelper(null, "fixture.zip", "application/zip", null) {
            @Override public FileStream getStream() throws IOException {
                throw new IOException("generated open failure");
            }
        });
        settle();
        assertPreserved();
    }

    @Test public void closedDatabaseBackupImportsNormally() throws Exception {
        begin(source(archive(true, false)));
        settle();
        assertEquals(1, fragment.finished);
        assertEquals(beforePreferences, fragment.defaultPreferences.getAll());
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(database.toString(), null,
                SQLiteDatabase.OPEN_READONLY);
                android.database.Cursor cursor = db.rawQuery("SELECT value FROM fixture", null)) {
            assertTrue(cursor.moveToFirst());
            assertEquals("new", cursor.getString(0));
        }
    }

    @Test public void cancelDoesNotBlockMainOrContinueFromLateRead() throws Exception {
        final CountDownLatch reading = new CountDownLatch(1);
        final File file = archive(true, false);
        final CountDownLatch closed = new CountDownLatch(1);
        begin(delayedSource(file, reading, closed));
        assertTrue("generated provider did not start reading",
                reading.await(2, TimeUnit.SECONDS));
        main(() -> {
            try {
                final Field f = BackupRestoreSettingsFragment.class
                        .getDeclaredField("importValidationDialog");
                f.setAccessible(true);
                ((androidx.appcompat.app.AlertDialog) f.get(fragment)).cancel();
            } catch (final Exception e) {
                throw new AssertionError(e);
            }
        });
        release.countDown();
        assertTrue(closed.await(2, TimeUnit.SECONDS));
        Thread.sleep(200);
        settle();
        assertPreserved();
    }

    @Test public void removedViewCannotContinueImportAfterLateRead() throws Exception {
        final CountDownLatch reading = new CountDownLatch(1);
        final CountDownLatch closed = new CountDownLatch(1);
        begin(delayedSource(archive(true, false), reading, closed));
        assertTrue("generated provider did not start reading",
                reading.await(2, TimeUnit.SECONDS));
        main(() -> activity.getSupportFragmentManager().beginTransaction()
                .remove(fragment).commitNow());
        release.countDown();
        assertTrue(closed.await(2, TimeUnit.SECONDS));
        Thread.sleep(200);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertPreserved();
    }

    @Test public void timeoutCannotImportFromAnUninterruptibleLateRead() throws Exception {
        final CountDownLatch reading = new CountDownLatch(1);
        final CountDownLatch closed = new CountDownLatch(1);
        begin(delayedSource(archive(true, false), reading, closed));
        assertTrue("generated provider did not start reading",
                reading.await(2, TimeUnit.SECONDS));
        settle(35000);
        assertPreserved();
        release.countDown();
        assertTrue(closed.await(2, TimeUnit.SECONDS));
        Thread.sleep(200);
        assertPreserved();
    }

    @Test public void supersededReadCannotImportAfterNewInvalidSelection() throws Exception {
        final CountDownLatch reading = new CountDownLatch(1);
        final CountDownLatch closed = new CountDownLatch(1);
        begin(delayedSource(archive(true, false), reading, closed));
        assertTrue("generated provider did not start reading",
                reading.await(2, TimeUnit.SECONDS));
        final File text = new File(directory, "new-invalid-selection");
        Files.write(text.toPath(), new byte[]{1, 2, 3});
        begin(source(text));
        settle();
        release.countDown();
        assertTrue(closed.await(2, TimeUnit.SECONDS));
        Thread.sleep(200);
        assertPreserved();
    }

    private StoredFileHelper delayedSource(final File file, final CountDownLatch reading,
                                           final CountDownLatch closed) {
        return new StoredFileHelper(null, "fixture.zip", "application/zip", null) {
            @Override public FileStream getStream() throws IOException {
                return new FileStream(file) {
                    @Override public int read(final byte[] b) throws IOException {
                        return read(b, 0, b.length);
                    }
                    @Override public int read() throws IOException {
                        final byte[] one = new byte[1];
                        return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
                    }
                    @Override public int read(final byte[] b, final int off, final int count)
                            throws IOException {
                        reading.countDown();
                        // A provider may ignore cancellation and eventually return data.
                        while (release.getCount() != 0) {
                            try {
                                release.await(5, TimeUnit.SECONDS);
                            } catch (final InterruptedException ignored) {
                                // Cancellation must still prevent this late result from importing.
                            }
                        }
                        return super.read(b, off, count);
                    }
                    @Override public void close() {
                        super.close();
                        closed.countDown();
                    }
                };
            }
        };
    }
}
