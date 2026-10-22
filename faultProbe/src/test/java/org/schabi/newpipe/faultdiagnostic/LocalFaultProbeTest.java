package org.schabi.newpipe.faultdiagnostic;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.schabi.newpipe.error.autoreport.AppFaultOutbox;
import org.schabi.newpipe.error.autoreport.SanitizedAppFault;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * Behavioral tests for the local synthetic fault probe. Only the public controller API and the
 * shared PR7 outbox are exercised; no real crash, network, log or raw input file is involved.
 */
public class LocalFaultProbeTest {
    private static final int PRODUCT_VERSION = 1015;
    private static final int ANDROID_API = 35;
    private static final long NOW = 1_700_000_000_000L;

    private static final String EXPECTED_JSON = "{\"schema\":1,\"fault\":\"NULL_POINTER\","
            + "\"component\":\"PLAYER\",\"app_version_code\":1015,\"android_api\":35}";

    /** Sensitive markers the controller places only in its transient synthetic trace. */
    private static final String[] SENSITIVE_MARKERS = {
            "SYNTHETIC_TOKEN_MARKER",
            "SYNTHETIC_DEVICE_MARKER",
            "SYNTHETIC_PATH_MARKER",
            "synthetic@example.invalid",
            "https://example.invalid/private",
            "example.invalid",
            "rawstacktrace",
            "NullPointerException",
            "org.schabi.newpipe.player",
    };

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private File newDirectory() throws IOException {
        return temporaryFolder.newFolder();
    }

    private static void assertNoSensitiveMarkers(final String text) {
        for (final String marker : SENSITIVE_MARKERS) {
            assertFalse("Leaked marker: " + marker, text.contains(marker));
        }
    }

    @Test
    public void newCaptureIsAddedAndPersistsAcrossFreshOutbox() throws IOException {
        final File directory = newDirectory();
        final LocalFaultProbe probe =
                new LocalFaultProbe(directory, PRODUCT_VERSION, ANDROID_API);

        assertEquals("ADDED", probe.captureSynthetic(NOW));

        final List<SanitizedAppFault> pending = new AppFaultOutbox(directory).pending(NOW + 1);
        assertEquals(1, pending.size());
        assertEquals(EXPECTED_JSON, pending.get(0).toJson());

        final String reopened = new LocalFaultProbe(directory, PRODUCT_VERSION, ANDROID_API)
                .snapshot(NOW + 2);
        assertTrue(reopened.contains("기록 수: 1"));
    }

    @Test
    public void duplicateCaptureIsReportedForSameAndNewInstance() throws IOException {
        final File directory = newDirectory();
        final LocalFaultProbe probe =
                new LocalFaultProbe(directory, PRODUCT_VERSION, ANDROID_API);

        assertEquals("ADDED", probe.captureSynthetic(NOW));
        assertEquals("DUPLICATE", probe.captureSynthetic(NOW + 1000));
        assertEquals("DUPLICATE", new LocalFaultProbe(directory, PRODUCT_VERSION, ANDROID_API)
                .captureSynthetic(NOW + 2000));

        assertEquals(1, new AppFaultOutbox(directory).pending(NOW + 3000).size());
        assertTrue(probe.snapshot(NOW + 3000).contains("기록 수: 1"));
    }

    @Test
    public void snapshotRendersOnlySafeFieldsWithKoreanState() throws IOException {
        final File directory = newDirectory();
        final LocalFaultProbe probe =
                new LocalFaultProbe(directory, PRODUCT_VERSION, ANDROID_API);
        assertEquals("ADDED", probe.captureSynthetic(NOW));

        final String snapshot = probe.snapshot(NOW + 1);
        assertNotNull(snapshot);
        assertTrue(snapshot.contains("\"schema\":1"));
        assertTrue(snapshot.contains("\"fault\":\"NULL_POINTER\""));
        assertTrue(snapshot.contains("\"component\":\"PLAYER\""));
        assertTrue(snapshot.contains("\"app_version_code\":1015"));
        assertTrue(snapshot.contains("\"android_api\":35"));
        assertFalse(snapshot.contains("\"app_version_code\":1,"));
        assertTrue(snapshot.contains("기록 수: 1"));
        assertTrue(snapshot.contains("민감 표식 없음: true"));
        assertNoSensitiveMarkers(snapshot);
    }

    @Test
    public void runtimeApiAndProductVersionAreTakenFromConstructor() throws IOException {
        final File directory = newDirectory();
        final LocalFaultProbe probe = new LocalFaultProbe(directory, PRODUCT_VERSION, 30);
        assertEquals("ADDED", probe.captureSynthetic(NOW));

        final List<SanitizedAppFault> pending = new AppFaultOutbox(directory).pending(NOW + 1);
        assertEquals(1, pending.size());
        assertTrue(pending.get(0).toJson().contains("\"android_api\":30"));
        assertTrue(pending.get(0).toJson().contains("\"app_version_code\":1015"));
    }

    @Test
    public void persistedFilesContainNoSyntheticSensitiveMarkers() throws IOException {
        final File directory = newDirectory();
        final LocalFaultProbe probe =
                new LocalFaultProbe(directory, PRODUCT_VERSION, ANDROID_API);
        assertEquals("ADDED", probe.captureSynthetic(NOW));
        probe.snapshot(NOW + 1);

        final File outbox = new File(directory, "outbox.bin");
        assertTrue(outbox.isFile());
        final File[] files = directory.listFiles();
        assertNotNull(files);
        for (final File file : files) {
            if (!file.isFile()) {
                continue;
            }
            final byte[] bytes = Files.readAllBytes(file.toPath());
            assertNoSensitiveMarkers(new String(bytes, StandardCharsets.ISO_8859_1));
            assertNoSensitiveMarkers(new String(bytes, StandardCharsets.UTF_8));
            assertNoSensitiveMarkers(new String(bytes, StandardCharsets.UTF_16BE));
        }
    }

    @Test
    public void corruptOutboxFailsClosedWithoutResetOrLeak() throws IOException {
        final File directory = newDirectory();
        final File outbox = new File(directory, "outbox.bin");
        final byte[] corrupt = {0x00, 0x13, 0x37, 0x42, 0x7f, 0x01, 0x02};
        Files.write(outbox.toPath(), corrupt);

        final LocalFaultProbe probe =
                new LocalFaultProbe(directory, PRODUCT_VERSION, ANDROID_API);
        assertEquals("ERROR", probe.captureSynthetic(NOW));
        assertEquals("ERROR", probe.captureSynthetic(NOW + 1));
        assertEquals("ERROR", new LocalFaultProbe(directory, PRODUCT_VERSION, ANDROID_API)
                .captureSynthetic(NOW + 2));

        final String snapshot = probe.snapshot(NOW + 3);
        assertTrue(snapshot.contains("ERROR"));
        assertFalse(snapshot.contains("기록 수: 0"));
        assertFalse(snapshot.contains("NULL_POINTER"));
        assertFalse(snapshot.contains("Invalid"));
        assertFalse(snapshot.contains("Exception"));
        assertNoSensitiveMarkers(snapshot);

        assertTrue(outbox.isFile());
        assertArrayEquals(corrupt, Files.readAllBytes(outbox.toPath()));
    }

    @Test
    public void brandNewControllerSnapshotIsEmptyWithoutDeletingFiles() throws IOException {
        final File directory = newDirectory();
        final File unrelated = new File(directory, "keep.txt");
        final byte[] unrelatedBytes = "keep".getBytes(StandardCharsets.UTF_8);
        Files.write(unrelated.toPath(), unrelatedBytes);

        final String snapshot = new LocalFaultProbe(directory, PRODUCT_VERSION, ANDROID_API)
                .snapshot(NOW);
        assertTrue(snapshot.contains("기록 수: 0"));
        assertTrue(snapshot.contains("민감 표식 없음: true"));
        assertFalse(snapshot.contains("NULL_POINTER"));
        assertFalse(snapshot.contains("ERROR"));
        assertNoSensitiveMarkers(snapshot);

        assertTrue(unrelated.isFile());
        assertArrayEquals(unrelatedBytes, Files.readAllBytes(unrelated.toPath()));
        assertTrue(new AppFaultOutbox(directory).pending(NOW + 1).isEmpty());
    }
}
