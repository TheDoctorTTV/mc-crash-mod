package io.github.thedoctorttv.fakecrash;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Random;

/** Used only by the server thread; sequence is shared across command senders and targets. */
final class ImageSelection {
    private final Random random = new Random();
    private int next;

    String select(List<String> images, boolean sequential, String override) {
        if (override != null) return validateUrl(override);
        if (images.isEmpty()) throw new IllegalArgumentException("No images configured. Add paths or URLs to config/fakecrash-common.toml or supply a URL in the command.");
        return images.get(sequential ? next % images.size() : random.nextInt(images.size()));
    }

    void accepted(List<String> images, boolean sequential, String override) {
        if (override == null && sequential && !images.isEmpty()) next = (next + 1) % images.size();
    }

    void reset() { next = 0; }

    static String validateUrl(String value) {
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            if (value.length() > 4096 || uri.getHost() == null
                    || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                throw new IllegalArgumentException("Supply a direct HTTP or HTTPS image URL (encode spaces as %20).");
            }
            return scheme.toLowerCase(java.util.Locale.ROOT) + value.substring(scheme.length());
        } catch (URISyntaxException ex) {
            throw new IllegalArgumentException("Invalid image URL; use a direct HTTP/HTTPS URL and encode spaces as %20.");
        }
    }
}
