package org.schabi.newpipe.fragments.list.kiosk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import android.content.Context;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.schabi.newpipe.error.ErrorInfo;
import org.schabi.newpipe.error.UserAction;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.exceptions.ContentNotSupportedException;
import org.schabi.newpipe.extractor.localization.ContentCountry;
import org.schabi.newpipe.util.ExtractorHelper;
import org.schabi.newpipe.util.Localization;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.Disposable;

public class RetiredTrendingTest {
    @Test public void retiredYouTubeGeneralTrendingIsUnavailableBeforeExtraction()
            throws Exception {
        final AtomicInteger requests = new AtomicInteger();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        try (MockedStatic<Localization> locales = mockStatic(Localization.class);
             MockedStatic<ExtractorHelper> extraction = mockStatic(ExtractorHelper.class)) {
            locales.when(() -> Localization.getPreferredContentCountry(
                    org.mockito.ArgumentMatchers.any())).thenReturn(new ContentCountry("KR"));
            final KioskFragment fragment = fragment(ServiceList.YouTube.getServiceId(), "Trending");
            extraction.when(() -> ExtractorHelper.getKioskInfo(0,
                    "https://www.youtube.com/feed/trending", false)).thenAnswer(call -> {
                        requests.incrementAndGet();
                        return Single.error(new java.io.IOException("Generated unavailable route"));
                    });
            final Disposable request = fragment.loadResult(false)
                    .subscribe(info -> { }, failure::set);
            request.dispose();
            org.junit.Assert.assertTrue("Retired feature must have explicit unsupported state",
                    failure.get() instanceof ContentNotSupportedException);
            org.junit.Assert.assertEquals(0, requests.get());
            final ErrorInfo error = new ErrorInfo(failure.get(), UserAction.REQUESTED_KIOSK,
                    "Generated public kiosk", 0, "https://www.youtube.com/feed/trending");
            assertFalse(error.isRetryable());
            assertFalse(error.isReportable());
            assertNull(error.getOpenInBrowserUrl());
        }
    }

    @Test public void categoryChartsAndOtherProvidersStillUseTheirExtractor() throws Exception {
        for (final int serviceId : new int[]{ServiceList.YouTube.getServiceId(),
                ServiceList.PeerTube.getServiceId()}) {
            try (MockedStatic<Localization> locales = mockStatic(Localization.class);
                 MockedStatic<ExtractorHelper> extraction = mockStatic(ExtractorHelper.class)) {
                locales.when(() -> Localization.getPreferredContentCountry(
                        org.mockito.ArgumentMatchers.any())).thenReturn(new ContentCountry("KR"));
                final org.schabi.newpipe.extractor.kiosk.KioskInfo info =
                        mock(org.schabi.newpipe.extractor.kiosk.KioskInfo.class);
                final String id = serviceId == 0 ? "trending_music" : "Trending";
                final KioskFragment fragment = fragment(serviceId, id);
                extraction.when(() -> ExtractorHelper.getKioskInfo(serviceId,
                        "https://www.youtube.com/feed/trending", false))
                        .thenReturn(Single.just(info));
                org.junit.Assert.assertSame(info, fragment.loadResult(false).blockingGet());
                extraction.verify(() -> ExtractorHelper.getKioskInfo(serviceId,
                        "https://www.youtube.com/feed/trending", false));
            }
        }
    }

    private static KioskFragment fragment(final int serviceId, final String kioskId)
            throws Exception {
        final Context context = mock(Context.class);
        final KioskFragment fragment = new KioskFragment() {
            @Override public Context getContext() {
                return context;
            }
        };
        fragment.kioskId = kioskId;
        final Field service = org.schabi.newpipe.fragments.list.BaseListInfoFragment.class
                .getDeclaredField("serviceId");
        service.setAccessible(true);
        service.set(fragment, serviceId);
        final Field url = org.schabi.newpipe.fragments.list.BaseListInfoFragment.class
                .getDeclaredField("url");
        url.setAccessible(true);
        url.set(fragment, "https://www.youtube.com/feed/trending");
        return fragment;
    }
}
