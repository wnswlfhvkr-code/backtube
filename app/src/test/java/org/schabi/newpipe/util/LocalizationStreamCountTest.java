package org.schabi.newpipe.util;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;

import android.content.Context;

import org.junit.Test;
import org.schabi.newpipe.extractor.ListExtractor;

public class LocalizationStreamCountTest {
    private final Context context = mock(Context.class);

    @Test
    public void missingLegacyCountIsUnknown() {
        final Long missingCount = null;
        assertEquals("", Localization.localizeStreamCountMini(context, missingCount));
    }

    @Test
    public void unknownCountHasNoNumericLabel() {
        assertEquals("", Localization.localizeStreamCountMini(
                context, ListExtractor.ITEM_COUNT_UNKNOWN));
    }

    @Test
    public void knownCountKeepsItsNumericLabel() {
        assertEquals("15", Localization.localizeStreamCountMini(context, 15L));
    }
}
