package org.schabi.newpipe.local.bookmark;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.view.View;
import android.widget.EditText;

import androidx.appcompat.app.AlertDialog;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.schabi.newpipe.MainActivity;
import org.schabi.newpipe.NewPipeDatabase;
import org.schabi.newpipe.R;
import org.schabi.newpipe.database.AppDatabase;
import org.schabi.newpipe.local.dialog.PlaylistCreationDialog;
import org.schabi.newpipe.local.playlist.LocalPlaylistManager;

import java.util.UUID;
import java.util.concurrent.FutureTask;
import java.util.function.Supplier;

/** Future device fixture: only its unique generated playlist is written or removed. */
@RunWith(AndroidJUnit4.class)
public class PersonalPlaylistEntryTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final String ownedName = "Entry fixture " + UUID.randomUUID();
    private MainActivity host;
    private AppDatabase database;

    @After public void cleanUpOnlyOwnedPlaylist() throws Exception {
        if (host != null) {
            main(() -> {
                host.finish();
                return null;
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        }
        if (database != null) {
            for (final var entry : new LocalPlaylistManager(database)
                    .getPlaylists().blockingFirst()) {
                if (ownedName.equals(entry.getOrderingName())) {
                    database.playlistDAO().deletePlaylist(entry.getUid());
                }
            }
        }
    }

    @Test public void standaloneEntryCancelsWithoutWritingThenCreatesVisibleEmptyList()
            throws Exception {
        database = NewPipeDatabase.getInstance(context);
        final Activity activity = InstrumentationRegistry.getInstrumentation().startActivitySync(
                new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        host = (MainActivity) activity;
        main(() -> {
            host.getSupportFragmentManager().beginTransaction()
                    .replace(R.id.fragment_holder, new BookmarkFragment()).commitNow();
            final View entry = host.findViewById(R.id.create_personal_playlist);
            assertNotNull(entry);
            assertTrue("creation is available without selecting a track", entry.isShown());
            final float density = host.getResources().getDisplayMetrics().density;
            assertTrue(entry.getMinimumHeight() >= Math.round(48 * density));
            return null;
        });
        openDialog(false);
        assertEquals(0, ownedCount());
        openDialog(true);
        final long timeout = SystemClock.uptimeMillis() + 10_000;
        while (ownedCount() == 0 && SystemClock.uptimeMillis() < timeout) {
            SystemClock.sleep(50);
        }
        assertEquals(1, ownedCount());
        final var created = new LocalPlaylistManager(database).getPlaylists().blockingFirst()
                .stream().filter(entry -> ownedName.equals(entry.getOrderingName()))
                .findFirst().orElseThrow();
        assertEquals(0, created.getStreamCount());
        assertEquals(Long.valueOf(-1), created.getThumbnailStreamId());
    }

    private void openDialog(final boolean confirm) throws Exception {
        main(() -> {
            host.findViewById(R.id.create_personal_playlist).performClick();
            host.getSupportFragmentManager().executePendingTransactions();
            final PlaylistCreationDialog fragment = (PlaylistCreationDialog)
                    host.getSupportFragmentManager().findFragmentByTag(
                            PlaylistCreationDialog.class.getSimpleName());
            assertNotNull(fragment);
            assertTrue("direct creation receives an empty non-null selection",
                    fragment.getStreamEntities().isEmpty());
            final AlertDialog dialog = (AlertDialog) fragment.requireDialog();
            final EditText name = dialog.findViewById(R.id.dialogEditText);
            assertNotNull(name);
            name.setText(ownedName);
            dialog.getButton(confirm ? AlertDialog.BUTTON_POSITIVE : AlertDialog.BUTTON_NEGATIVE)
                    .performClick();
            host.getSupportFragmentManager().executePendingTransactions();
            return null;
        });
    }

    private long ownedCount() {
        return new LocalPlaylistManager(database).getPlaylists().blockingFirst().stream()
                .filter(entry -> ownedName.equals(entry.getOrderingName())).count();
    }

    private static <T> T main(final Supplier<T> work) throws Exception {
        final FutureTask<T> task = new FutureTask<>(work::get);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(task);
        return task.get();
    }
}
