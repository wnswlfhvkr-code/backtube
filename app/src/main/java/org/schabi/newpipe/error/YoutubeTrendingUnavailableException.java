package org.schabi.newpipe.error;

import org.schabi.newpipe.extractor.exceptions.ContentNotSupportedException;

/** The general YouTube Trending page was retired; category charts are separate kiosks. */
public final class YoutubeTrendingUnavailableException extends ContentNotSupportedException {
    public YoutubeTrendingUnavailableException() {
        super("YouTube retired the general Trending page");
    }
}
