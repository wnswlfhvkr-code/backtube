package org.schabi.newpipe.offline;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

/** Generated metadata and bytes only; registration is the real production boundary. */
public class OfflineSaveDeduplicationTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();
    private static final String ORIGINAL = "https://www.youtube.com/watch?v=abcdefghijk";

    @Test public void concurrentEquivalentDownloadsAllocateOneRecordAndOneFile() throws Exception {
        final File directory = temporary.newFolder();
        final OfflineStore store = new OfflineStore(directory, 1000);
        final ExecutorService pool = Executors.newFixedThreadPool(6);
        final CyclicBarrier start = new CyclicBarrier(6);
        final List<Future<OfflineStore.Entry>> results = new ArrayList<>();
        try {
            for (int i = 0; i < 6; i++) {
                final String origin = i % 2 == 0 ? ORIGINAL
                        : "https://youtu.be/abcdefghijk?t=45&si=generated";
                results.add(pool.submit(() -> {
                    start.await();
                    return store.beginDownload("Generated video", origin, 0, "video/mp4");
                }));
            }
            final String id = results.get(0).get().id;
            for (final Future<OfflineStore.Entry> result : results) {
                assertEquals("all requests must share the registered ownership",
                        id, result.get().id);
            }
            assertEquals(1, store.list().size());
            assertEquals(1, directory.listFiles((dir, name) -> name.endsWith(".media")).length);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test public void completedSaveAndLocalIdentityDoNotAllocateAnotherFile() throws Exception {
        final OfflineStore store = new OfflineStore(temporary.newFolder(), 1000);
        final OfflineStore.Entry first = store.beginDownload("Generated", ORIGINAL, 0, "audio/mp4");
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(store.file(first))) {
            output.write(new byte[]{1, 2, 3});
        }
        store.finishDownload(first.id);
        assertEquals(first.id, store.beginDownload("Again", ORIGINAL + "&t=2", 0,
                "audio/webm").id);
        assertEquals(first.id, store.beginDownload("Local", first.localUrl(), 0, "audio/mp4").id);
        assertEquals(3, store.file(first).length());
        assertEquals(1, store.list().size());
    }

    @Test public void repeatedLocalImportSharesPendingAndCompletedRegistration() throws Exception {
        final OfflineStore store = new OfflineStore(temporary.newFolder(), 1000);
        final OfflineStore.Entry first = store.create("Generated", ORIGINAL, 0,
                "file:///generated/input.wav", "audio/wav");
        assertEquals(first.id, store.create("Again", ORIGINAL + "&list=generated", 0,
                "file:///generated/input.wav", "audio/wav").id);
        store.copy(first.id, new ByteArrayInputStream(new byte[]{3, 4}));
        assertEquals(first.id, store.create("Completed", first.localUrl(), 0,
                "file:///generated/input.wav", "audio/wav").id);
        assertEquals(1, store.list().size());
    }

    @Test public void failureAndCancellationRetryKeepTheSameRegistration() throws Exception {
        final OfflineStore store = new OfflineStore(temporary.newFolder(), 1000);
        final OfflineStore.Entry failed = store.beginDownload(
                "Generated", ORIGINAL, 0, "audio/mp4");
        store.fail(failed.id);
        assertEquals(failed.id, store.beginDownload("Retry", ORIGINAL, 0, "audio/mp4").id);
        final OfflineStore.Entry interrupted = store.create("Generated local", "", 0,
                "file:///generated/interrupt.wav", "audio/wav");
        store.interrupt(interrupted.id);
        assertEquals(interrupted.id, store.create("Retry local", "", 0,
                "file:///generated/interrupt.wav", "audio/wav").id);
        assertEquals(2, store.list().size());
    }

    @Test public void dispatchClaimIsAtomicAndReleasedForFailedDispatch() throws Exception {
        final OfflineStore store = new OfflineStore(temporary.newFolder(), 1000);
        final OfflineStore.Entry entry = store.beginDownload("Generated", ORIGINAL, 0, "audio/mp4");
        final ExecutorService pool = Executors.newFixedThreadPool(6);
        final CyclicBarrier start = new CyclicBarrier(6);
        final List<Future<Boolean>> claims = new ArrayList<>();
        try {
            for (int i = 0; i < 6; i++) {
                claims.add(pool.submit(() -> {
                    start.await();
                    return store.claimDownload(entry.id);
                }));
            }
            int owners = 0;
            for (final Future<Boolean> claim : claims) {
                owners += claim.get() ? 1 : 0;
            }
            assertEquals(1, owners);
            store.releaseDownloadClaim(entry.id);
            org.junit.Assert.assertTrue(store.claimDownload(entry.id));
            store.fail(entry.id);
            assertEquals(entry.id, store.beginDownload("Retry", ORIGINAL, 0, "audio/mp4").id);
            org.junit.Assert.assertTrue(store.claimDownload(entry.id));
            assertEquals(1, store.list().size());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test public void completedDuplicateBypassesCapacityAndCannotClaimWriting() throws Exception {
        final OfflineStore store = new OfflineStore(temporary.newFolder(), 3);
        final OfflineStore.Entry entry = store.create("Generated", ORIGINAL, 0,
                "file:///generated/input.wav", "audio/wav");
        store.copy(entry.id, new ByteArrayInputStream(new byte[]{1, 2, 3}));
        assertEquals(entry.id, store.beginDownload("Again", ORIGINAL, 0, "audio/mp4", 1000).id);
        org.junit.Assert.assertFalse(store.claimDownload(entry.id));
        assertEquals(3, store.usedBytes());
    }

    @Test public void federatedCanonicalUrlsRetainTheInstanceHost() throws Exception {
        final OfflineStore store = new OfflineStore(temporary.newFolder(), 1000);
        final int service = org.schabi.newpipe.extractor.ServiceList.PeerTube.getServiceId();
        final String path = "/videos/watch/aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
        final OfflineStore.Entry entry = store.beginDownload("One",
                "https://one.example.invalid" + path, service, "video/mp4");
        assertEquals(entry.id, store.beginDownload("Same",
                "https://one.example.invalid" + path + "?start=30", service, "video/webm").id);
        assertNotEquals(entry.id, store.beginDownload("Other host",
                "https://two.example.invalid" + path, service, "video/mp4").id);
    }

    @Test public void legacyDuplicateRecordsAndBytesRemainAndReadyWinsOverFailure()
            throws Exception {
        final File directory = temporary.newFolder();
        OfflineStore store = new OfflineStore(directory, 1000);
        final OfflineStore.Entry first = store.create("Generated", ORIGINAL, 0,
                "file:///generated/input.wav", "audio/wav");
        store.copy(first.id, new ByteArrayInputStream(new byte[]{1, 2, 3}));
        final java.util.Properties properties = new java.util.Properties();
        final File firstMetadata = new File(directory, first.id + ".properties");
        try (java.io.InputStream input = new java.io.FileInputStream(firstMetadata)) {
            properties.load(input);
        }
        final String duplicate = java.util.UUID.randomUUID().toString();
        properties.setProperty("id", duplicate);
        try (java.io.OutputStream output = new java.io.FileOutputStream(
                new File(directory, duplicate + ".properties"))) {
            properties.store(output, "generated old duplicate");
        }
        java.nio.file.Files.copy(store.file(first).toPath(),
                new File(directory, duplicate + ".media").toPath());
        properties.setProperty("id", first.id);
        properties.setProperty("state", "FAILED");
        try (java.io.OutputStream output = new java.io.FileOutputStream(firstMetadata)) {
            properties.store(output, "generated old failed record");
        }
        store = new OfflineStore(directory, 1000);
        store.move(first.id, -1);
        final List<String> order = store.list().stream().map(entry -> entry.id)
                .collect(java.util.stream.Collectors.toList());
        assertEquals(duplicate, store.beginDownload("Again", ORIGINAL, 0, "audio/mp4").id);
        assertEquals(order, store.list().stream().map(entry -> entry.id)
                .collect(java.util.stream.Collectors.toList()));
        assertEquals(2, store.list().size());
        assertEquals(6, store.usedBytes());
        assertEquals(OfflineStore.State.FAILED, store.get(first.id).state);
    }

    @Test public void identityMatchingNeverCallsNetworkResolvingProviderFactories()
            throws Exception {
        final OfflineStore store = new OfflineStore(temporary.newFolder(), 1000);
        try (org.mockito.MockedStatic<org.schabi.newpipe.extractor.NewPipe> extractor =
                     org.mockito.Mockito.mockStatic(org.schabi.newpipe.extractor.NewPipe.class,
                             org.mockito.Mockito.CALLS_REAL_METHODS)) {
            extractor.when(org.schabi.newpipe.extractor.NewPipe::getDownloader).thenReturn(
                    org.mockito.Mockito.mock(
                            org.schabi.newpipe.extractor.downloader.Downloader.class));
            store.beginDownload("SoundCloud generated", "https://soundcloud.com/generated/track",
                    org.schabi.newpipe.extractor.ServiceList.SoundCloud.getServiceId(),
                    "audio/mp4");
            store.beginDownload("Bandcamp generated",
                    "https://generated.example.invalid/track/song",
                    org.schabi.newpipe.extractor.ServiceList.Bandcamp.getServiceId(),
                    "audio/mp4");
            extractor.verify(org.schabi.newpipe.extractor.NewPipe::getDownloader,
                    org.mockito.Mockito.never());
        }
    }

    @Test public void failedLocalReselectionKeepsIdentityAndUsesTheNewGrantedSource()
            throws Exception {
        final OfflineStore store = new OfflineStore(temporary.newFolder(), 1000);
        final OfflineStore.Entry entry = store.create("Generated", ORIGINAL, 0,
                "file:///generated/unavailable.wav", "audio/wav");
        store.fail(entry.id);
        final OfflineStore.Entry retry = store.create("Reselected", ORIGINAL, 0,
                "file:///generated/reselected.wav", "audio/wav");
        assertEquals(entry.id, retry.id);
        assertEquals("file:///generated/reselected.wav", retry.source);
        assertEquals(1, store.list().size());
    }

    @Test public void identityPreservesServiceContentAndAudioVideoVariants() throws Exception {
        final OfflineStore store = new OfflineStore(temporary.newFolder(), 1000);
        final OfflineStore.Entry first = store.beginDownload("Generated", ORIGINAL, 0, "audio/mp4");
        assertNotEquals(first.id, store.beginDownload("Video", ORIGINAL, 0, "video/mp4").id);
        assertNotEquals(first.id, store.beginDownload("Other service",
                ORIGINAL, 1, "audio/mp4").id);
        assertNotEquals(first.id, store.beginDownload("Other video",
                "https://www.youtube.com/watch?v=lmnopqrstuv", 0, "audio/mp4").id);
        final OfflineStore.Entry unknown = store.beginDownload("Unknown",
                "https://example.invalid/watch?id=one", 0, "audio/mp4");
        assertNotEquals(unknown.id, store.beginDownload("Other unknown",
                "https://example.invalid/watch?id=two", 0, "audio/mp4").id);
        final OfflineStore.Entry empty = store.create("Local one", "", 0,
                "file:///generated/one.wav", "audio/wav");
        assertNotEquals(empty.id, store.create("Local two", "", 0,
                "file:///generated/two.wav", "audio/wav").id);
    }
}
