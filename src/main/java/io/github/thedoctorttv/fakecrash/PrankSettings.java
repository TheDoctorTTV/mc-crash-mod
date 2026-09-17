package io.github.thedoctorttv.fakecrash;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable settings snapshot: a failed reload cannot partially change live settings. */
final class PrankSettings {
    final List<String> images;
    final boolean sequential;
    final int imageSeconds;

    private PrankSettings(List<String> images, boolean sequential, int imageSeconds) {
        this.images = Collections.unmodifiableList(new ArrayList<>(images));
        this.sequential = sequential;
        this.imageSeconds = imageSeconds;
    }

    static PrankSettings read(Path path) throws IOException {
        if (!Files.isRegularFile(path)) throw new IOException("Config file does not exist: " + path);
        try (CommentedFileConfig file = CommentedFileConfig.of(path)) {
            file.load();
            Object entries = file.get("images");
            if (!(entries instanceof List)) throw new IOException("images must be an array of paths or URLs.");
            List<String> images = new ArrayList<>();
            for (Object entry : (List<?>) entries) {
                if (!(entry instanceof String) || ((String) entry).trim().isEmpty() || ((String) entry).length() > 4096) {
                    throw new IOException("Every images entry must be a nonempty string of at most 4096 characters.");
                }
                images.add((String) entry);
            }
            Object mode = file.getOrElse("imageSelection", "random");
            if (!"random".equals(mode) && !"sequential".equals(mode)) {
                throw new IOException("imageSelection must be \"random\" or \"sequential\".");
            }
            Object seconds = file.get("imageSeconds");
            if (!(seconds instanceof Integer || seconds instanceof Long)
                    || ((Number) seconds).longValue() < 1 || ((Number) seconds).longValue() > 15) {
                throw new IOException("imageSeconds must be an integer from 1 to 15.");
            }
            return new PrankSettings(images, "sequential".equals(mode), ((Number) seconds).intValue());
        } catch (RuntimeException ex) {
            throw new IOException("Could not parse config: " + ex.getMessage(), ex);
        }
    }
}
