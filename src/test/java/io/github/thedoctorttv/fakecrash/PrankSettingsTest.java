package io.github.thedoctorttv.fakecrash;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class PrankSettingsTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private Path write(String text) throws IOException {
        Path path = temporary.getRoot().toPath().resolve("config.toml");
        Files.write(path, text.getBytes(StandardCharsets.UTF_8));
        return path;
    }

    @Test public void oldConfigDefaultsToRandomAndReloadReadsFreshValues() throws Exception {
        Path path = write("images = ['one.png']\nimageSeconds = 3\n");
        PrankSettings original = PrankSettings.read(path);
        assertFalse(original.sequential);
        write("images = ['two.png', 'https://example.com/a.png']\nimageSeconds = 7\nimageSelection = 'sequential'\n");
        PrankSettings fresh = PrankSettings.read(path);
        assertTrue(fresh.sequential);
        assertEquals(7, fresh.imageSeconds);
        assertEquals(2, fresh.images.size());
        assertEquals("one.png", original.images.get(0));
        assertThrows(UnsupportedOperationException.class, () -> fresh.images.add("x"));
    }

    @Test public void invalidReloadCannotMutateExistingSnapshotOrRewriteFile() throws Exception {
        PrankSettings original = PrankSettings.read(write("images = ['one.png']\nimageSeconds = 3\n"));
        for (String invalid : new String[] {
                "images = [", "images = [123]\nimageSeconds = 3",
                "images = []\nimageSeconds = 0", "images = []\nimageSeconds = 2.5",
                "images = []\nimageSeconds = 3\nimageSelection = 'bad'"}) {
            Path path = write(invalid);
            assertThrows(IOException.class, () -> PrankSettings.read(path));
            assertEquals(invalid, new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
            assertEquals("one.png", original.images.get(0));
        }
    }
}
