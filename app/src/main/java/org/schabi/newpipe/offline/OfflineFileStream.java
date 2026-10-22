package org.schabi.newpipe.offline;

import java.io.File;
import java.io.IOException;

import us.shandian.giga.io.FileStream;

/** Enforces the shared byte budget before Giga grows an app-owned output file. */
public final class OfflineFileStream extends FileStream {
    private final OfflineStore store;
    private final String id;

    public OfflineFileStream(final File file, final OfflineStore store, final String id)
            throws IOException {
        super(file);
        this.store = store;
        this.id = id;
    }

    @Override public void write(final byte value) throws IOException {
        synchronized (store) {
            store.checkGrowth(id, source.getFilePointer() + 1);
            super.write(value);
        }
    }

    @Override public void write(final byte[] buffer) throws IOException {
        write(buffer, 0, buffer.length);
    }

    @Override public void write(final byte[] buffer, final int offset, final int count)
            throws IOException {
        synchronized (store) {
            store.checkGrowth(id, source.getFilePointer() + count);
            super.write(buffer, offset, count);
        }
    }

    @Override public void setLength(final long length) throws IOException {
        synchronized (store) {
            store.checkGrowth(id, length);
            super.setLength(length);
        }
    }
}
