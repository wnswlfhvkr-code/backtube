package org.schabi.newpipe.player.ui;

import android.content.Context;
import android.content.res.Resources;
import android.util.Log;
import android.view.ViewParent;
import android.view.WindowManager;
import android.widget.RelativeLayout;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.schabi.newpipe.R;
import org.schabi.newpipe.databinding.PlayerBinding;
import org.schabi.newpipe.views.ExpandableSurfaceView;

import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Production size callbacks with only Android window and view boundaries mocked. */
public class PlayerUiSizeBoundaryTest {
    @Test
    public void popupHeightUsesUpperClampedWidthAfterScreenShrinks() throws Exception {
        final PopupFixture fixture = new PopupFixture();
        try (MockedStatic<Log> logging = mockStatic(Log.class)) {
            fixture.ui.changePopupSize(1000);
        }

        assertEquals(400, fixture.params.width);
        assertEquals(225, fixture.params.height);
        verify(fixture.surface).setHeights(225, 225);
    }

    @Test
    public void popupHeightUsesMinimumClampedWidth() throws Exception {
        final PopupFixture fixture = new PopupFixture();
        try (MockedStatic<Log> logging = mockStatic(Log.class)) {
            fixture.ui.changePopupSize(80);
        }

        assertEquals(160, fixture.params.width);
        assertEquals(90, fixture.params.height);
    }

    @Test
    public void popupValidWidthKeepsExistingSixteenToNineSize() throws Exception {
        final PopupFixture fixture = new PopupFixture();
        try (MockedStatic<Log> logging = mockStatic(Log.class)) {
            fixture.ui.changePopupSize(320);
        }

        assertEquals(320, fixture.params.width);
        assertEquals(180, fixture.params.height);
    }

    private static final class PopupFixture {
        final PopupPlayerUi ui = mock(PopupPlayerUi.class, CALLS_REAL_METHODS);
        final Context context = mock(Context.class);
        final PlayerBinding binding = mock(PlayerBinding.class);
        final ExpandableSurfaceView surface = mock(ExpandableSurfaceView.class);
        final WindowManager.LayoutParams params = mock(WindowManager.LayoutParams.class);

        PopupFixture() throws Exception {
            final Resources resources = mock(Resources.class);
            final RelativeLayout root = mock(RelativeLayout.class);
            when(context.getResources()).thenReturn(resources);
            when(resources.getDimension(R.dimen.popup_minimum_width)).thenReturn(160f);
            when(binding.getRoot()).thenReturn(root);
            when(root.getParent()).thenReturn(mock(ViewParent.class));
            set(PlayerUi.class, ui, "context", context);
            set(VideoPlayerUi.class, ui, "binding", binding);
            set(PopupPlayerUi.class, ui, "windowManager", mock(WindowManager.class));
            set(PopupPlayerUi.class, ui, "popupLayoutParams", params);
            set(PopupPlayerUi.class, ui, "screenWidth", 400);
            set(PlayerBinding.class, binding, "surfaceView", surface);
        }
    }

    private static void set(final Class<?> owner, final Object object, final String name,
                            final Object value) throws Exception {
        final Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }
}
