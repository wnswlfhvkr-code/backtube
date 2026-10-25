package org.schabi.newpipe.fragments.list.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.widget.EditText;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.schabi.newpipe.R;
import org.schabi.newpipe.databinding.FragmentSearchBinding;
import org.schabi.newpipe.info_list.InfoListAdapter;
import org.schabi.newpipe.local.history.HistoryRecordManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;

import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.disposables.Disposable;

/** Exercises the real search submission order without requesting provider results. */
public class SearchHistoryLifecycleDeviceTest {
    @Test
    public void latestHistorySubscriptionSurvivesItsOwnSearchAndNextSearchCancelsIt()
            throws Exception {
        onMain(() -> {
            final Context context = new ContextThemeWrapper(
                    InstrumentationRegistry.getInstrumentation().getTargetContext(),
                    R.style.LightTheme);
            final FakeHistoryManager history = new FakeHistoryManager(context);
            final TestSearchFragment fragment = fragment(context, history);

            invokeSearch(fragment, "query A");
            assertEquals(1, history.disposed.size());
            assertFalse(history.disposed.get(0).get());

            final AtomicBoolean oldResultsDisposed = new AtomicBoolean();
            set(fragment, "searchDisposable", new Disposable() {
                @Override
                public void dispose() {
                    oldResultsDisposed.set(true);
                }

                @Override
                public boolean isDisposed() {
                    return oldResultsDisposed.get();
                }
            });
            invokeSearch(fragment, "");
            assertFalse(oldResultsDisposed.get());

            invokeSearch(fragment, "query B");
            assertEquals(2, history.disposed.size());
            assertTrue(history.disposed.get(0).get());
            assertFalse(history.disposed.get(1).get());
            assertTrue(oldResultsDisposed.get());
        });
    }

    @Test
    public void urlSubmissionCancelsPreviousKeywordWorkBeforeRouting() throws Exception {
        onMain(() -> {
            final Context context = new ContextThemeWrapper(
                    InstrumentationRegistry.getInstrumentation().getTargetContext(),
                    R.style.LightTheme);
            final FakeHistoryManager history = new FakeHistoryManager(context);
            final TestSearchFragment fragment = fragment(context, history);
            invokeSearch(fragment, "query A");
            final AtomicBoolean oldResultsDisposed = new AtomicBoolean();
            set(fragment, "searchDisposable", trackedDisposable(oldResultsDisposed));

            invokeSearch(fragment, "https://www.youtube.com/watch?v=abc123xyz01");
            ((CompositeDisposable) get(fragment, "disposables")).clear();

            assertTrue(oldResultsDisposed.get());
            assertTrue(history.disposed.get(0).get());
            assertEquals(1, history.disposed.size());
            assertEquals(1, fragment.keywordLoads);
        });
    }

    private static TestSearchFragment fragment(final Context context,
                                               final FakeHistoryManager history) {
        final TestSearchFragment fragment = new TestSearchFragment(context);
        set(fragment, "searchBinding", FragmentSearchBinding.inflate(
                LayoutInflater.from(context)));
        set(fragment, "searchEditText", new EditText(context));
        set(fragment, "historyRecordManager", history);
        return fragment;
    }

    private static Disposable trackedDisposable(final AtomicBoolean disposed) {
        return new Disposable() {
            @Override
            public void dispose() {
                disposed.set(true);
            }

            @Override
            public boolean isDisposed() {
                return disposed.get();
            }
        };
    }

    private static class TestSearchFragment extends SearchFragment {
        int keywordLoads;

        TestSearchFragment(final Context context) {
            infoListAdapter = new InfoListAdapter(context);
        }

        @Override
        public void showLoading() {
            // The URL branch only needs its subscription lifecycle here.
        }

        @Override
        public void startLoading(final boolean forceLoad) {
            // Keep the real startLoading cleanup contract, while suppressing provider search.
            keywordLoads++;
            ((CompositeDisposable) get(this, "disposables")).clear();
        }
    }

    private static class FakeHistoryManager extends HistoryRecordManager {
        final List<AtomicBoolean> disposed = new ArrayList<>();

        FakeHistoryManager(final Context context) {
            super(context);
        }

        @Override
        public Maybe<Long> onSearched(final int serviceId, final String search) {
            final AtomicBoolean marker = new AtomicBoolean();
            disposed.add(marker);
            return Maybe.<Long>never().doOnDispose(() -> marker.set(true));
        }
    }

    private static void invokeSearch(final SearchFragment fragment, final String query) {
        try {
            final Method search = SearchFragment.class.getDeclaredMethod("search", String.class,
                    String[].class, String.class);
            search.setAccessible(true);
            search.invoke(fragment, query, new String[0], "");
        } catch (final ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void set(final Object target, final String name, final Object value) {
        try {
            final Field field = SearchFragment.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (final ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static Object get(final Object target, final String name) {
        try {
            final Field field = SearchFragment.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (final ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void onMain(final Runnable action) throws Exception {
        final FutureTask<Void> task = new FutureTask<>(() -> {
            action.run();
            return null;
        });
        InstrumentationRegistry.getInstrumentation().runOnMainSync(task);
        task.get();
    }
}
