package org.schabi.newpipe.offline;

import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModel;

import java.util.ArrayDeque;
import java.util.concurrent.Callable;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/** Owns local import work and undelivered results across Activity recreation, without replay. */
public final class OfflineImportState extends ViewModel {
    enum Result { IMPORTED, STORAGE_FULL, FAILED }

    private final CompositeDisposable work = new CompositeDisposable();
    private final ArrayDeque<Result> results = new ArrayDeque<>();
    private final MutableLiveData<Boolean> changed = new MutableLiveData<>(false);

    void observe(final LifecycleOwner owner, final Observer<Boolean> observer) {
        changed.observe(owner, observer);
    }

    void start(final Callable<OfflineStore.Entry> operation) {
        work.add(Single.fromCallable(operation).subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(entry -> complete(Result.IMPORTED), error -> complete(
                        error instanceof OfflineStore.StorageFullException
                                ? Result.STORAGE_FULL : Result.FAILED)));
    }

    private void complete(final Result result) {
        results.add(result);
        // LiveData signals availability; the queue retains every outcome if signals coalesce.
        changed.setValue(true);
    }

    Result peek() {
        return results.peek();
    }

    void delivered() {
        results.remove();
    }

    @Override protected void onCleared() {
        work.dispose();
        results.clear();
    }
}
