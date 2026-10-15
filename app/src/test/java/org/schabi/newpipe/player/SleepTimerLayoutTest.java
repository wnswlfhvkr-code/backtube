package org.schabi.newpipe.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.File;

import javax.xml.parsers.DocumentBuilderFactory;

/** Resource contract checks only; these do not replace Android rendering tests. */
public class SleepTimerLayoutTest {
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";
    private static final String[] LAYOUTS = {"layout", "layout-large-land"};

    @Test
    public void detailTimerReplacesRedundantBackgroundActionInBothLayouts() throws Exception {
        for (final String layout : LAYOUTS) {
            final Document document = read(layout + "/fragment_video_detail.xml");
            final Element timer = find(document, "detail_controls_sleep_timer");
            assertNotNull(layout + " is missing the detail sleep timer action", timer);
            assertEquals("true", timer.getAttributeNS(ANDROID, "clickable"));
            assertEquals("true", timer.getAttributeNS(ANDROID, "focusable"));
            assertEquals("@string/personal_sleep_timer_title",
                    timer.getAttributeNS(ANDROID, "contentDescription"));
            assertEquals(find(document, "detail_control_panel"), timer.getParentNode());
            assertNull("redundant Background action must be removed", find(document,
                    "detail_controls_background"));
            for (final String action : new String[]{"playlist_append", "popup", "download"}) {
                assertNotNull("existing action must remain", find(document,
                        "detail_controls_" + action));
            }
        }
    }

    @Test
    public void narrowScreensCanScrollActionsWithoutShrinkingTouchTargets() throws Exception {
        for (final String layout : LAYOUTS) {
            final Document document = read(layout + "/fragment_video_detail.xml");
            final Element panel = find(document, "detail_control_panel");
            final Element viewport = (Element) panel.getParentNode();
            assertEquals("HorizontalScrollView", viewport.getTagName());
            assertEquals("true", viewport.getAttributeNS(ANDROID, "fillViewport"));
            assertEquals("wrap_content", panel.getAttributeNS(ANDROID, "layout_width"));
            final Element timer = find(document, "detail_controls_sleep_timer");
            assertNotNull(timer);
            assertEquals("@dimen/detail_control_width",
                    timer.getAttributeNS(ANDROID, "layout_width"));
            assertEquals("wrap_content", timer.getAttributeNS(ANDROID, "layout_height"));
            assertEquals("@dimen/detail_control_height",
                    timer.getAttributeNS(ANDROID, "minHeight"));
        }
        final Document dimensions = read("values/dimens.xml");
        final NodeList values = dimensions.getElementsByTagName("dimen");
        for (int index = 0; index < values.getLength(); index++) {
            final Element value = (Element) values.item(index);
            if (value.getAttribute("name").equals("detail_control_width")
                    || value.getAttribute("name").equals("detail_control_height")) {
                assertTrue(Float.parseFloat(value.getTextContent().replace("dp", "")) >= 48);
            }
        }
    }

    @Test
    public void timerNamingAndDurationUnitsAreConciseInEnglishAndKorean() throws Exception {
        for (final String folder : new String[]{"values", "values-ko"}) {
            final Document document = read(folder + "/personal_playback_strings.xml");
            final NodeList strings = document.getElementsByTagName("string");
            final java.util.Map<String, String> values = new java.util.HashMap<>();
            for (int i = 0; i < strings.getLength(); i++) {
                final Element item = (Element) strings.item(i);
                values.put(item.getAttribute("name"), item.getTextContent());
            }
            for (final String key : new String[]{"title", "off", "remaining", "custom"}) {
                final String label = values.get("personal_sleep_timer_" + key);
                org.junit.Assert.assertFalse(label.toLowerCase(java.util.Locale.ROOT)
                        .contains("sleep"));
                org.junit.Assert.assertFalse(label.contains("취침"));
            }
            assertEquals(folder.equals("values") ? "hours" : "시간",
                    values.get("personal_timer_hours"));
            assertEquals(folder.equals("values") ? "minutes" : "분",
                    values.get("personal_timer_minutes"));
        }
    }

    private static Document read(final String path) throws Exception {
        final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(new File("src/main/res", path));
    }

    private static Element find(final Document document, final String id) {
        final NodeList nodes = document.getElementsByTagName("*");
        for (int index = 0; index < nodes.getLength(); index++) {
            final Element node = (Element) nodes.item(index);
            if (node.getAttributeNS(ANDROID, "id").equals("@+id/" + id)) {
                return node;
            }
        }
        return null;
    }
}
