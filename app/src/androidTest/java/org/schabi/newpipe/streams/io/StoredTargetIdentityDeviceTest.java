package org.schabi.newpipe.streams.io;

import android.content.Context;
import android.net.Uri;

import androidx.documentfile.provider.DocumentFile;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Identity cases use generated names and an isolated app-cache file. */
@RunWith(AndroidJUnit4.class)
public class StoredTargetIdentityDeviceTest {
    @Test
    public void invalidPlaceholdersWithSameNameButDifferentParentsAreDistinct() {
        final StoredFileHelper first = new StoredFileHelper(
                Uri.parse("file:///generated/one"), "clip.mp4", "video/mp4", "video");
        final StoredFileHelper second = new StoredFileHelper(
                Uri.parse("file:///generated/two"), "clip.mp4", "video/mp4", "video");
        assertFalse(first.equals(second));
    }

    @Test
    public void invalidPlaceholdersWithinSameParentKeepLegacyIdentity() {
        final StoredFileHelper first = new StoredFileHelper(
                Uri.parse("file:///generated/one"), "Clip.MP4", "video/mp4", "video");
        final StoredFileHelper second = new StoredFileHelper(
                Uri.parse("file:///generated/one"), "clip.mp4", "VIDEO/MP4", "other-tag");
        assertTrue(first.equals(second));
    }

    @Test
    public void validDirectFileIdentityIgnoresOptionalParentMetadata() throws Exception {
        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final File file = File.createTempFile("batch4-identity-", ".mp4", context.getCacheDir());
        try {
            try (FileOutputStream output = new FileOutputStream(file)) {
                output.write(new byte[]{1, 2, 3});
            }
            final Uri path = Uri.fromFile(file);
            final StoredFileHelper first = new StoredFileHelper(context, null, path, "video");
            final StoredFileHelper second = new StoredFileHelper(context,
                    Uri.fromFile(file.getParentFile()), path, "other-tag");
            assertTrue(first.equals(second));
            assertTrue(file.isFile());
            assertTrue(file.length() == 3);
        } finally {
            assertTrue(file.delete());
        }
    }

    @Test
    public void sameDocumentIdAtDifferentProvidersIsDistinct() throws Exception {
        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final StoredFileHelper first = syntheticSafHandle(context,
                Uri.parse("content://provider.one/document/shared"),
                Uri.parse("content://provider.one/tree/parent"));
        final StoredFileHelper second = syntheticSafHandle(context,
                Uri.parse("content://provider.two/document/shared"),
                Uri.parse("content://provider.two/tree/parent"));
        assertFalse(first.equals(second));
    }

    @Test
    public void sameValidSafDocumentIgnoresParentAlias() throws Exception {
        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final Uri document = Uri.parse("content://provider.one/document/shared");
        final StoredFileHelper first = syntheticSafHandle(context, document,
                Uri.parse("content://provider.one/tree/old"));
        final StoredFileHelper second = syntheticSafHandle(context, document,
                Uri.parse("content://provider.one/tree/new"));
        assertTrue(first.equals(second));
    }

    private static StoredFileHelper syntheticSafHandle(final Context context,
            final Uri document, final Uri parent) throws ReflectiveOperationException {
        final StoredFileHelper handle = new StoredFileHelper(parent,
                "clip.mp4", "video/mp4", "test");
        handle.source = document.toString();
        final Field documentField = StoredFileHelper.class.getDeclaredField("docFile");
        documentField.setAccessible(true);
        documentField.set(handle, DocumentFile.fromSingleUri(context, document));
        return handle;
    }
}
