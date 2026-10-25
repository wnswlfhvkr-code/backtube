package org.schabi.newpipe.settings.preferencesearch;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.schabi.newpipe.R;
import org.xmlpull.v1.XmlPullParserException;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Uses a compiled app XML resource and an isolated test preference namespace. */
public class PreferenceParserResourceDeviceTest {
    @Test
    public void closesNativeResourceParserAfterEarlyFailure() {
        final Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final Resources appResources = base.getResources();
        final AtomicInteger closeCalls = new AtomicInteger();
        final String preferencesName = "parser-device-test-" + UUID.randomUUID();
        final Resources wrappedResources = new Resources(appResources.getAssets(),
                appResources.getDisplayMetrics(), appResources.getConfiguration()) {
            @Override
            public XmlResourceParser getXml(final int id) {
                final XmlResourceParser nativeParser = appResources.getXml(id);
                return (XmlResourceParser) Proxy.newProxyInstance(
                        XmlResourceParser.class.getClassLoader(),
                        new Class<?>[]{XmlResourceParser.class}, (proxy, method, args) -> {
                            if (method.getName().equals("getEventType")) {
                                throw new XmlPullParserException("synthetic early failure");
                            }
                            if (method.getName().equals("close")) {
                                closeCalls.incrementAndGet();
                            }
                            try {
                                return method.invoke(nativeParser, args);
                            } catch (final InvocationTargetException e) {
                                throw e.getCause();
                            }
                        });
            }
        };
        final Context isolated = new ContextWrapper(base) {
            @Override
            public Resources getResources() {
                return wrappedResources;
            }

            @Override
            public SharedPreferences getSharedPreferences(final String name, final int mode) {
                return base.getSharedPreferences(preferencesName, MODE_PRIVATE);
            }
        };
        try {
            new PreferenceParser(isolated, new PreferenceSearchConfiguration())
                    .parse(R.xml.video_audio_settings);
            assertEquals(1, closeCalls.get());
        } finally {
            base.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
                    .edit().clear().commit();
        }
    }
}
