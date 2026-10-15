package org.schabi.newpipe.offline;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import android.util.Log;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;
import org.schabi.newpipe.App;
import org.schabi.newpipe.R;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.util.ExtractorHelper;
import org.schabi.newpipe.util.InfoCache;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Field;

/** Real saved store and metadata routing, with only Android application plumbing mocked. */
public class OfflineStreamInfoRoutingTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();
    private MockedStatic<App> applications;
    private MockedStatic<Log> logs;
    private OfflineStore.Entry entry;
    private OfflineLibrary library;

    @Before
    public void setUp() throws Exception {
        logs = mockStatic(Log.class);
        resetLibrary();
        final App app = mock(App.class);
        when(app.getApplicationContext()).thenReturn(app);
        when(app.getFilesDir()).thenReturn(temporary.getRoot());
        when(app.getString(R.string.offline_saved_copy)).thenReturn("Saved copy");
        applications = mockStatic(App.class);
        applications.when(App::getInstance).thenReturn(app);
        library = OfflineLibrary.get(app);
        entry = library.store().create("Stored local title", "https://example.invalid/origin",
                0, "file:///generated-fixture", "audio/wav");
        library.store().copy(entry.id, new ByteArrayInputStream(new byte[]{1, 2, 3, 4}));
    }

    @After
    public void tearDown() throws Exception {
        applications.close();
        logs.close();
        resetLibrary();
    }

    @Test
    public void internalStreamRequestResolvesSavedMetadataWithoutWebExtraction() {
        final StreamInfo info = ExtractorHelper.getStreamInfo(0, entry.localUrl(), false)
                .blockingGet();
        assertEquals("Stored local title", info.getName());
        assertEquals(entry.localUrl(), info.getUrl());
        assertEquals(0, info.getServiceId());
    }

    @Test
    public void missingSavedRecordFailsLocallyWithoutRequestingOrigin() {
        ExtractorHelper.getStreamInfo(0, "backtube-offline:missing-record", false)
                .test().assertError(IOException.class);
    }

    @Test
    public void unknownInternalIdentityNeverResolvesAnotherEntriesOrigin() throws Exception {
        final String missing = "backtube-offline:missing-origin-alias";
        final OfflineStore.Entry alias = library.store().create("Unrelated saved track",
                missing, 0, "file:///other-generated-fixture", "audio/wav");
        library.store().copy(alias.id, new ByteArrayInputStream(new byte[]{5, 6, 7}));
        ExtractorHelper.getStreamInfo(0, missing, false)
                .test().assertError(IOException.class);
    }

    @Test
    public void deletedSavedFileFailsLocallyWithoutRequestingOrigin() {
        library.store().file(entry).delete();
        ExtractorHelper.getStreamInfo(0, entry.localUrl(), false)
                .test().assertError(IOException.class);
    }

    @Test
    public void storedIdentityDoesNotDependOnCallerServiceSelection() {
        final StreamInfo info = ExtractorHelper.getStreamInfo(-1, entry.localUrl(), true)
                .blockingGet();
        assertEquals(0, info.getServiceId());
        assertEquals("Stored local title", info.getName());
    }

    @Test
    public void internalIdentityDoesNotUseStaleCachedMetadata() {
        final StreamInfo stale = new StreamInfo(0, entry.localUrl(), entry.localUrl(),
                org.schabi.newpipe.extractor.stream.StreamType.AUDIO_STREAM,
                entry.localUrl(), "Stale cached title", 0);
        InfoCache.getInstance().putInfo(0, entry.localUrl(), stale, InfoCache.Type.STREAM);
        final StreamInfo info = ExtractorHelper.getStreamInfo(0, entry.localUrl(), false)
                .blockingGet();
        assertEquals("Stored local title", info.getName());
    }

    private static void resetLibrary() throws Exception {
        final Field singleton = OfflineLibrary.class.getDeclaredField("instance");
        singleton.setAccessible(true);
        singleton.set(null, null);
    }
}
