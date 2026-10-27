package org.schabi.newpipe.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.schabi.newpipe.R;
import org.schabi.newpipe.database.subscription.NotificationMode;
import org.schabi.newpipe.databinding.FragmentInstanceListBinding;
import org.schabi.newpipe.settings.notifications.NotificationModeConfigAdapter;
import org.schabi.newpipe.settings.notifications.SubscriptionItem;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.disposables.Disposable;

/** Android view and adapter checks with synthetic rows and no external requests. */
public class SettingsBoundaryBatch16DeviceTest {
    @Test
    public void peerTubeViewDestroyCancelsOwnedRequestAndAllowsNewViewRequest() throws Exception {
        onMain(() -> {
            final PeertubeInstanceListFragment fragment = new PeertubeInstanceListFragment();
            setField(fragment, "binding", FragmentInstanceListBinding.inflate(
                    LayoutInflater.from(themedContext())));
            final CompositeDisposable requests = field(fragment, "disposables");
            final Disposable pending = Disposable.empty();
            assertTrue(requests.add(pending));

            fragment.onDestroyView();

            assertNull(field(fragment, "binding"));
            assertTrue(pending.isDisposed());
            assertFalse(requests.isDisposed());
            assertTrue(requests.add(Disposable.empty()));
            return null;
        });
    }

    @Test
    public void peerTubeLateDialogWithoutViewCannotStartRequest() throws Exception {
        onMain(() -> {
            final PeertubeInstanceListFragment fragment = new PeertubeInstanceListFragment();
            final Method add = PeertubeInstanceListFragment.class
                    .getDeclaredMethod("addInstance", String.class);
            add.setAccessible(true);
            add.invoke(fragment, "https://fixture.invalid");
            assertEquals(0, ((CompositeDisposable) field(fragment, "disposables")).size());
            return null;
        });
    }

    @Test
    public void notificationHolderIgnoresNoPositionButKeepsValidClick() throws Exception {
        onMain(() -> {
            final AtomicInteger selectedPosition = new AtomicInteger(Integer.MIN_VALUE);
            final AtomicInteger selectedMode = new AtomicInteger(Integer.MIN_VALUE);
            final NotificationModeConfigAdapter adapter = new NotificationModeConfigAdapter(
                    (position, mode) -> {
                        selectedPosition.set(position);
                        selectedMode.set(mode);
                    });
            final Context context = themedContext();
            final RecyclerView list = new RecyclerView(context);
            list.setLayoutManager(new LinearLayoutManager(context));
            list.setAdapter(adapter);
            final RecyclerView.ViewHolder unbound = adapter.onCreateViewHolder(list, 0);

            assertEquals(RecyclerView.NO_POSITION, unbound.getBindingAdapterPosition());
            unbound.itemView.performClick();
            assertEquals(Integer.MIN_VALUE, selectedPosition.get());

            adapter.submitList(List.of(new SubscriptionItem(17L, "example",
                    NotificationMode.DISABLED, 7, "https://fixture.invalid/channel")));
            list.measure(View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY));
            list.layout(0, 0, 500, 500);
            final RecyclerView.ViewHolder bound = list.findViewHolderForAdapterPosition(0);
            assertNotNull(bound);
            bound.itemView.performClick();
            assertEquals(0, selectedPosition.get());
            assertEquals(NotificationMode.ENABLED, selectedMode.get());
            return null;
        });
    }

    private static Context themedContext() {
        return new ContextThemeWrapper(InstrumentationRegistry.getInstrumentation()
                .getTargetContext(), R.style.LightTheme);
    }

    private static <T> T onMain(final Callable<T> action) throws Exception {
        final FutureTask<T> task = new FutureTask<>(action);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(task);
        return task.get(10, TimeUnit.SECONDS);
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(final Object owner, final String name) throws Exception {
        final Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(owner);
    }

    private static void setField(final Object owner, final String name, final Object value)
            throws Exception {
        final Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(owner, value);
    }
}
