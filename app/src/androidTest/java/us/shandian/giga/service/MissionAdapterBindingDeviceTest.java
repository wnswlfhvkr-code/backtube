package us.shandian.giga.service;

import android.content.Context;
import android.content.ContextWrapper;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Handler;
import android.widget.FrameLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.schabi.newpipe.streams.io.StoredFileHelper;

import java.io.File;
import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.FutureTask;

import us.shandian.giga.get.DownloadMission;
import us.shandian.giga.get.FinishedMission;
import us.shandian.giga.get.sqlite.FinishedMissionStore;
import us.shandian.giga.ui.adapter.MissionAdapter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Real adapter bindings over generated missions and an isolated database directory. */
@RunWith(AndroidJUnit4.class)
public class MissionAdapterBindingDeviceTest {
    @Test
    public void repeatedPendingBindTracksOneHolderAndRecycleClearsIt() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.runOnMain(() -> {
                final RecyclerView.ViewHolder holder = fixture.newHolder();
                fixture.adapter.onBindViewHolder(holder, 1);
                fixture.adapter.onBindViewHolder(holder, 1);
                assertEquals(1, fixture.pendingHolderCount());
                fixture.adapter.onViewRecycled(holder);
                assertEquals(0, fixture.pendingHolderCount());
            });
        }
    }

    @Test
    public void rebindingPendingHolderAsFinishedRemovesStaleTracking() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.runOnMain(() -> {
                final RecyclerView.ViewHolder holder = fixture.newHolder();
                fixture.adapter.onBindViewHolder(holder, 1);
                assertEquals(1, fixture.pendingHolderCount());
                fixture.adapter.onBindViewHolder(holder, 3);
                assertEquals(0, fixture.pendingHolderCount());
                fixture.adapter.onViewRecycled(holder);
            });
        }
    }

    private static final class Fixture implements AutoCloseable {
        final File directory;
        final Context context;
        final DownloadManager manager;
        final MissionAdapter adapter;
        final FrameLayout root;

        Fixture() throws Exception {
            final Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
            directory = new File(target.getCacheDir(), "batch4-adapter-" + UUID.randomUUID());
            assertTrue(directory.mkdir());
            context = new ContextWrapper(target) {
                @Override
                public Context getApplicationContext() {
                    return this;
                }

                @Override
                public File getDatabasePath(final String name) {
                    return new File(directory, name);
                }

                @Override
                public SQLiteDatabase openOrCreateDatabase(final String name, final int mode,
                        final SQLiteDatabase.CursorFactory factory) {
                    return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory);
                }

                @Override
                public SQLiteDatabase openOrCreateDatabase(final String name, final int mode,
                        final SQLiteDatabase.CursorFactory factory,
                        final DatabaseErrorHandler handler) {
                    return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).getPath(),
                            factory, handler);
                }

                @Override
                public File getExternalFilesDir(final String type) {
                    return directory;
                }

                @Override
                public File getFilesDir() {
                    return directory;
                }
            };

            manager = new DownloadManager(context, new Handler(context.getMainLooper()),
                    null, null);
            final File pendingFile = new File(directory, "pending.mp3");
            final File finishedFile = new File(directory, "finished.mp3");
            assertTrue(pendingFile.createNewFile());
            assertTrue(finishedFile.createNewFile());
            final DownloadMission pending = new DownloadMission(
                    new String[]{"https://example.invalid/generated"},
                    new StoredFileHelper(context, null, Uri.fromFile(pendingFile), "test"),
                    'a', null);
            pending.length = 4;
            pending.timestamp = 1;
            final FinishedMission finished = new FinishedMission();
            finished.storage = new StoredFileHelper(context, null,
                    Uri.fromFile(finishedFile), "test");
            finished.kind = 'a';
            finished.length = 4;
            finished.timestamp = 2;
            missions("mMissionsPending").add(pending);
            missions("mMissionsFinished").add(finished);

            final FutureTask<FrameLayout> create = new FutureTask<>(() -> {
                final FrameLayout parent = new FrameLayout(context);
                final MissionAdapter made = new MissionAdapter(context, manager,
                        new TextView(context), parent);
                final PopupMenu controls = new PopupMenu(context, parent);
                made.setMasterButtons(controls.getMenu().add("start"),
                        controls.getMenu().add("pause"));
                made.onPaused();
                adapterHolder = made;
                return parent;
            });
            InstrumentationRegistry.getInstrumentation().runOnMainSync(create);
            root = create.get();
            adapter = adapterHolder;
        }

        private MissionAdapter adapterHolder;

        @SuppressWarnings("unchecked")
        private List<us.shandian.giga.get.Mission> missions(final String fieldName)
                throws ReflectiveOperationException {
            final Field field = DownloadManager.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            return (List<us.shandian.giga.get.Mission>) field.get(manager);
        }

        RecyclerView.ViewHolder newHolder() {
            return adapter.onCreateViewHolder(root, DownloadManager.SPECIAL_NOTHING);
        }

        int pendingHolderCount() throws ReflectiveOperationException {
            final Field field = MissionAdapter.class.getDeclaredField("mPendingDownloadsItems");
            field.setAccessible(true);
            return ((List<?>) field.get(adapter)).size();
        }

        void runOnMain(final ThrowingAction action) throws Exception {
            final FutureTask<Void> task = new FutureTask<>(() -> {
                action.run();
                return null;
            });
            InstrumentationRegistry.getInstrumentation().runOnMainSync(task);
            task.get();
        }

        @Override
        public void close() throws Exception {
            runOnMain(adapter::onDestroy);
            final Field databaseField = DownloadManager.class.getDeclaredField(
                    "mFinishedMissionStore");
            databaseField.setAccessible(true);
            ((FinishedMissionStore) databaseField.get(manager)).close();
            deleteFixture(directory);
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

    private interface ThrowingAction {
        void run() throws Exception;
    }
}
