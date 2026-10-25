package org.schabi.newpipe.local.subscription;

import static org.junit.Assert.assertEquals;

import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;

import androidx.test.platform.app.InstrumentationRegistry;

import com.xwray.groupie.GroupAdapter;

import org.junit.Test;
import org.schabi.newpipe.NewPipeDatabase;
import org.schabi.newpipe.databinding.FragmentSubscriptionBinding;
import org.schabi.newpipe.fragments.list.playlist.Batch10TestActivity;
import org.schabi.newpipe.testUtil.TestDatabase;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.concurrent.FutureTask;

/** An old posted result must not mutate the adapter belonging to a replacement view. */
public class SubscriptionGroupPostTest {
    @Test
    public void stalePostIsIgnoredAndCurrentPostStillApplies() throws Exception {
        TestDatabase.Companion.createReplacingNewPipeDatabase();
        final var instrumentation = InstrumentationRegistry.getInstrumentation();
        final Intent intent = new Intent(instrumentation.getTargetContext(),
                Batch10TestActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        final Batch10TestActivity activity = (Batch10TestActivity)
                instrumentation.startActivitySync(intent);
        try {
            final FrameLayout root = new FrameLayout(activity);
            root.setId(View.generateViewId());
            instrumentation.runOnMainSync(() -> activity.setContentView(root));
            final SubscriptionFragment fragment = new SubscriptionFragment();
            instrumentation.runOnMainSync(() -> activity.getSupportFragmentManager()
                    .beginTransaction().add(root.getId(), fragment).commitNow());
            instrumentation.waitForIdleSync();

            final Field bindingField = SubscriptionFragment.class.getDeclaredField("_binding");
            bindingField.setAccessible(true);
            final Field adapterField = SubscriptionFragment.class
                    .getDeclaredField("carouselAdapter");
            adapterField.setAccessible(true);
            final Method handle = SubscriptionFragment.class.getDeclaredMethod(
                    "handleFeedGroups", java.util.List.class, boolean.class);
            handle.setAccessible(true);

            final FutureTask<FragmentSubscriptionBinding> replace = new FutureTask<>(() -> {
                final GroupAdapter<?> adapter = (GroupAdapter<?>) adapterField.get(fragment);
                adapter.clear();
                handle.invoke(fragment, Collections.emptyList(), true);
                // The fragment remains attached, just as it does between view destruction and
                // recreation. Its old RecyclerView is still attached so its posted work runs.
                final FragmentSubscriptionBinding replacement = FragmentSubscriptionBinding
                        .inflate(LayoutInflater.from(activity), root, true);
                bindingField.set(fragment, replacement);
                return replacement;
            });
            instrumentation.runOnMainSync(replace);
            replace.get();
            instrumentation.waitForIdleSync();
            assertEquals(0, ((GroupAdapter<?>) adapterField.get(fragment)).getItemCount());

            final FutureTask<Void> current = new FutureTask<>(() -> {
                handle.invoke(fragment, Collections.emptyList(), false);
                return null;
            });
            instrumentation.runOnMainSync(current);
            current.get();
            instrumentation.waitForIdleSync();
            assertEquals(2, ((GroupAdapter<?>) adapterField.get(fragment)).getItemCount());
        } finally {
            instrumentation.runOnMainSync(activity::finish);
            NewPipeDatabase.close();
        }
    }
}
