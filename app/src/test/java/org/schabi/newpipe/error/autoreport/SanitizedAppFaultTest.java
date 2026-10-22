package org.schabi.newpipe.error.autoreport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SanitizedAppFaultTest {
    private static final String APP_FRAME = "\n\tat org.schabi.newpipe.player.Player.play"
            + "(/private/account/secret.java:999)";

    @Test
    public void onlyFixedMetadataLeavesTheSanitizer() {
        final SanitizedAppFault fault = SanitizedAppFault.fromAcra(
                "java.lang.NullPointerException: title=private search=secret token=abc"
                        + " https://example.invalid/account/123" + APP_FRAME, 123, 35).get();
        assertEquals("{\"schema\":1,\"fault\":\"NULL_POINTER\",\"component\":\"PLAYER\","
                + "\"app_version_code\":123,\"android_api\":35}", fault.toJson());
        assertEquals("[App fault] NULL_POINTER / PLAYER", fault.issueTitle());
        assertFalse(fault.issueBody().contains("secret"));
        assertFalse(fault.issueBody().contains("123)"));
        assertFalse(fault.issueBody().contains("https://"));
        assertFalse(fault.issueBody().contains("Player.play"));
    }

    @Test
    public void wrapperDoesNotTurnNetworkProviderOrDataFailureIntoAnAppFault() {
        for (final String type : new String[]{"java.net.SocketTimeoutException",
                "java.io.IOException",
                "org.schabi.newpipe.extractor.exceptions.ExtractionException",
                "java.lang.NumberFormatException", "org.json.JSONException",
                "com.google.android.exoplayer2.PlaybackException", "some.UnknownException"}) {
            assertFalse(type, SanitizedAppFault.fromAcra(
                    "java.lang.NullPointerException: hidden" + APP_FRAME
                            + "\nCaused by: " + type + ": private value" + APP_FRAME,
                    123, 35).isPresent());
        }
    }

    @Test
    public void suppressionsMalformedOversizedAndNonAppReportsFailClosed() {
        for (final String trace : new String[]{null, "", "not a stack trace",
                "java.lang.RuntimeException: unknown fault" + APP_FRAME,
                "java.lang.NullPointerException: hidden\n\tat vendor.library.Task.run(Task.java:1)",
                "java.lang.NullPointerException: hidden" + APP_FRAME
                        + "\n\tSuppressed: java.io.IOException: private path",
                "java.lang.NullPointerException: hidden" + APP_FRAME
                        + "\n\tat org.schabi.newpipe.extractor.SomeParser.read(Parser.java:1)",
                "java.lang.NullPointerException: " + "x".repeat(70000) + APP_FRAME}) {
            assertFalse(SanitizedAppFault.fromAcra(trace, 123, 35).isPresent());
        }
        assertFalse(SanitizedAppFault.fromAcra("java.lang.NullPointerException" + APP_FRAME,
                -1, 35).isPresent());
    }

    @Test
    public void knownWrapperAndMessageChangesHaveTheSameCoarseSignature() {
        final SanitizedAppFault first = SanitizedAppFault.fromAcra(
                "java.lang.RuntimeException: private activity name\n"
                        + "\tat android.app.ActivityThread.run(ActivityThread.java:1)\n"
                        + "Caused by: java.lang.IllegalStateException: private title"
                        + APP_FRAME, 123, 23).get();
        final SanitizedAppFault second = SanitizedAppFault.fromAcra(
                "java.lang.IllegalStateException: different private data" + APP_FRAME,
                123, 23).get();
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertTrue(first.toJson().contains("ILLEGAL_STATE"));
    }

    @Test
    public void fakeFramesAndCausesCannotPromoteNetworkOrUnknownRoots() {
        for (final String type : new String[]{"java.io.IOException", "secret.account.Error123",
                "java.lang.NumberFormatException"}) {
            assertFalse(SanitizedAppFault.fromAcra(type + ": fake trace" + APP_FRAME
                    + "\nCaused by: java.lang.NullPointerException: fake" + APP_FRAME,
                    123, 35).isPresent());
        }
        assertFalse(SanitizedAppFault.fromAcra("java.lang.NullPointerException: fake"
                + APP_FRAME + "\nunstructured private text\nCaused by: java.io.IOException"
                + APP_FRAME, 123, 35).isPresent());
        assertFalse(SanitizedAppFault.fromAcra("java.lang.NullPointerException: fake"
                + APP_FRAME + "\nCaused by: java.lang.RuntimeException: unknown" + APP_FRAME,
                123, 35).isPresent());
    }

    @Test
    public void arbitraryFrameIdentifiersAreNeverCopied() {
        final SanitizedAppFault fault = SanitizedAppFault.fromAcra(
                "java.lang.NullPointerException: PRIVATE_ID\n"
                        + "\tat org.schabi.newpipe.player.PRIVATE_ACCOUNT.PRIVATE_TOKEN"
                        + "(/PRIVATE_PATH:999)", 123, 35).get();
        assertFalse(fault.issueTitle().contains("PRIVATE"));
        assertFalse(fault.issueBody().contains("PRIVATE"));
        assertEquals("[App fault] NULL_POINTER / PLAYER", fault.issueTitle());
    }
}
