package io.github.thedoctorttv.fakecrash;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class ImageSelectionTest {
    @Test public void sequentialWrapsAndResetStartsAtFirstImage() {
        ImageSelection selection = new ImageSelection();
        List<String> images = Arrays.asList("a", "b");
        assertEquals("a", selection.select(images, true, null));
        // A rejected request does not consume an entry.
        assertEquals("a", selection.select(images, true, null));
        selection.accepted(images, true, null);
        assertEquals("b", selection.select(images, true, null));
        selection.accepted(images, true, null);
        assertEquals("a", selection.select(images, true, null));
        selection.accepted(images, true, null);
        selection.reset();
        assertEquals("a", selection.select(images, true, null));
    }

    @Test public void urlOverrideBypassesEmptyArrayAndDoesNotAdvanceSequence() {
        ImageSelection selection = new ImageSelection();
        String url = "https://example.com/image.png?x=1&y=2";
        assertEquals(url, selection.select(Collections.emptyList(), true, url));
        List<String> images = Arrays.asList("a", "b");
        selection.accepted(images, true, null);
        assertEquals(url, selection.select(images, true, url));
        selection.accepted(images, true, url);
        assertEquals("b", selection.select(images, true, null));
    }

    @Test public void randomSelectionUsesOnlyConfiguredEntries() {
        ImageSelection selection = new ImageSelection();
        List<String> images = Arrays.asList("a", "b", "c");
        for (int i = 0; i < 100; i++) assertTrue(images.contains(selection.select(images, false, null)));
        assertThrows(IllegalArgumentException.class, () -> selection.select(Collections.emptyList(), false, null));
    }

    @Test public void overrideMustBeAnHttpUrl() {
        for (String invalid : Arrays.asList("file:///etc/passwd", "/tmp/image.png", "ftp://example.com/a", "https://", "https://example.com/a b")) {
            assertThrows(IllegalArgumentException.class, () -> ImageSelection.validateUrl(invalid));
        }
        assertEquals("https://example.com/a%20b.png", ImageSelection.validateUrl("HTTPS://example.com/a%20b.png"));
    }
}
