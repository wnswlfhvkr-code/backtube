package org.schabi.newpipe.settings.preferencesearch;

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.util.Log;

import androidx.preference.PreferenceManager;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.util.Collections;

/** The parser returned by Resources.getXml belongs to each parse call. */
public class PreferenceParserCloseTest {
    private static final int XML_ID = 42;

    @Test
    public void closesParserAfterNormalEndDocument() throws Exception {
        final Fixture fixture = new Fixture();
        when(fixture.parser.getEventType()).thenReturn(XmlPullParser.END_DOCUMENT);
        fixture.parse();
        verify(fixture.parser).close();
    }

    @Test
    public void closesParserAfterFeatureFailure() throws Exception {
        final Fixture fixture = new Fixture();
        doThrow(new XmlPullParserException("synthetic feature failure"))
                .when(fixture.parser).setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
        fixture.parse();
        verify(fixture.parser).close();
    }

    @Test
    public void closesParserAfterNextFailure() throws Exception {
        final Fixture fixture = new Fixture();
        when(fixture.parser.getEventType()).thenReturn(XmlPullParser.TEXT);
        doThrow(new XmlPullParserException("synthetic next failure"))
                .when(fixture.parser).next();
        fixture.parse();
        verify(fixture.parser).close();
    }

    private static final class Fixture {
        private final Context context = mock(Context.class);
        private final Resources resources = mock(Resources.class);
        private final SharedPreferences preferences = mock(SharedPreferences.class);
        private final XmlResourceParser parser = mock(XmlResourceParser.class);

        private Fixture() {
            when(context.getResources()).thenReturn(resources);
            when(resources.getXml(XML_ID)).thenReturn(parser);
            when(preferences.getAll()).thenReturn(Collections.emptyMap());
        }

        private void parse() {
            try (MockedStatic<PreferenceManager> preferenceManager =
                         mockStatic(PreferenceManager.class);
                 MockedStatic<Log> ignoredLog = mockStatic(Log.class)) {
                preferenceManager.when(() -> PreferenceManager.getDefaultSharedPreferences(context))
                        .thenReturn(preferences);
                assertTrue(new PreferenceParser(context, new PreferenceSearchConfiguration())
                        .parse(XML_ID).isEmpty());
            }
        }
    }
}
