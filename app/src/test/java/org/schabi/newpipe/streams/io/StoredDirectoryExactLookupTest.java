package org.schabi.newpipe.streams.io;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import androidx.documentfile.provider.DocumentFile;

import org.junit.Test;
import org.mockito.MockedStatic;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A provider may return broader rows than the exact-name query requested. */
public class StoredDirectoryExactLookupTest {
    @Test
    public void prefixSiblingCannotWinBeforeExactChild() {
        final Fixture fixture = new Fixture();
        fixture.rows(new String[]{"clip.mp4.backup", "clip.mp4"},
                new String[]{"wrong", "wanted"});

        try (MockedStatic<DocumentsContract> documents = mockStatic(DocumentsContract.class);
             MockedStatic<DocumentFile> files = mockStatic(DocumentFile.class)) {
            fixture.stub(documents, files);
            assertSame(fixture.wantedFile, StoredDirectoryHelper.findFileSAFHelper(
                    fixture.context, fixture.tree, "clip.mp4"));
        }
        verify(fixture.cursor).close();
    }

    @Test
    public void prefixSiblingAloneDoesNotCountAsExactChild() {
        final Fixture fixture = new Fixture();
        fixture.rows(new String[]{"clip.mp4.backup"}, new String[]{"wrong"});

        try (MockedStatic<DocumentsContract> documents = mockStatic(DocumentsContract.class);
             MockedStatic<DocumentFile> files = mockStatic(DocumentFile.class)) {
            fixture.stub(documents, files);
            assertNull(StoredDirectoryHelper.findFileSAFHelper(
                    fixture.context, fixture.tree, "clip.mp4"));
        }
        verify(fixture.cursor).close();
    }

    @Test
    public void prefixDirectoryCannotBeDeletedAsRequestedFile() throws Exception {
        final Fixture fixture = new Fixture();
        fixture.rows(new String[]{"clip.mp4.backup"}, new String[]{"wrong"});
        when(fixture.wrongFile.exists()).thenReturn(true);
        when(fixture.wrongFile.isDirectory()).thenReturn(true);
        when(fixture.wrongFile.delete()).thenReturn(true);
        when(fixture.tree.createFile(anyString(), eq("clip.mp4")))
                .thenReturn(fixture.wantedFile);
        when(fixture.wantedFile.getUri()).thenReturn(fixture.wantedUri);
        when(fixture.wantedFile.getName()).thenReturn("clip.mp4");
        when(fixture.wantedFile.getType()).thenReturn("video/mp4");

        try (MockedStatic<DocumentsContract> documents = mockStatic(DocumentsContract.class);
             MockedStatic<DocumentFile> files = mockStatic(DocumentFile.class)) {
            fixture.stub(documents, files);
            new StoredFileHelper(fixture.context, fixture.tree,
                    "clip.mp4", "video/mp4", false);
        }
        verify(fixture.wrongFile, never()).delete();
        verify(fixture.tree).createFile(anyString(), eq("clip.mp4"));
    }

    private static final class Fixture {
        final Context context = mock(Context.class);
        final ContentResolver resolver = mock(ContentResolver.class);
        final DocumentFile tree = mock(DocumentFile.class);
        final DocumentFile wantedFile = mock(DocumentFile.class);
        final DocumentFile wrongFile = mock(DocumentFile.class);
        final Uri treeUri = mock(Uri.class);
        final Uri childrenUri = mock(Uri.class);
        final Uri wantedUri = mock(Uri.class);
        final Uri wrongUri = mock(Uri.class);
        final Cursor cursor = mock(Cursor.class);

        void rows(final String[] names, final String[] documentIds) {
            final AtomicInteger row = new AtomicInteger(-1);
            when(cursor.moveToNext()).thenAnswer(call -> row.incrementAndGet() < names.length);
            when(cursor.getString(0)).thenAnswer(call -> names[row.get()]);
            when(cursor.getString(1)).thenAnswer(call -> documentIds[row.get()]);
        }

        void stub(final MockedStatic<DocumentsContract> documents,
                  final MockedStatic<DocumentFile> files) {
            when(context.getContentResolver()).thenReturn(resolver);
            when(tree.canRead()).thenReturn(true);
            when(tree.getUri()).thenReturn(treeUri);
            when(resolver.query(eq(childrenUri), any(String[].class), anyString(),
                    any(String[].class), isNull())).thenReturn(cursor);
            documents.when(() -> DocumentsContract.getDocumentId(treeUri))
                    .thenReturn("parent");
            documents.when(() -> DocumentsContract.buildChildDocumentsUriUsingTree(
                    treeUri, "parent")).thenReturn(childrenUri);
            documents.when(() -> DocumentsContract.buildDocumentUriUsingTree(
                    treeUri, "wanted")).thenReturn(wantedUri);
            documents.when(() -> DocumentsContract.buildDocumentUriUsingTree(
                    treeUri, "wrong")).thenReturn(wrongUri);
            files.when(() -> DocumentFile.fromSingleUri(context, wantedUri))
                    .thenReturn(wantedFile);
            files.when(() -> DocumentFile.fromSingleUri(context, wrongUri))
                    .thenReturn(wrongFile);
        }
    }
}
