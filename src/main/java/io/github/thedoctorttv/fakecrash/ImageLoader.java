package io.github.thedoctorttv.fakecrash;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.ByteBuffer;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;

/** Runs on a bounded worker pool, never the Minecraft tick thread. */
public final class ImageLoader {
    public static final int MAX_PACKET_BYTES = 512 * 1024;
    private static final int MAX_INPUT_BYTES = 8 * 1024 * 1024;
    private static final int MAX_DIMENSION = 4096;
    private static final int DISPLAY_DIMENSION = 512;

    private ImageLoader() { }

    public static void validateWireImage(byte[] png) throws IOException {
        if (png.length < 33 || png.length > MAX_PACKET_BYTES) throw new IOException("Invalid image packet size.");
        ByteBuffer header = ByteBuffer.wrap(png);
        if (header.getLong() != 0x89504e470d0a1a0aL || header.getInt() != 13 || header.getInt() != 0x49484452) {
            throw new IOException("Expected a PNG image.");
        }
        int width = header.getInt();
        int height = header.getInt();
        if (width < 1 || height < 1 || width > DISPLAY_DIMENSION || height > DISPLAY_DIMENSION) {
            throw new IOException("Received image dimensions exceed the display limit.");
        }
    }

    public static byte[] load(String source, Path gameDirectory) throws IOException {
        return normalize(readSource(source, gameDirectory, System.nanoTime() + 15_000_000_000L));
    }

    public static byte[] loadAnimation(String source, Path gameDirectory) throws IOException {
        long deadline = System.nanoTime() + 15_000_000_000L;
        byte[] input = readSource(source, gameDirectory, deadline);
        if (input.length >= 6 && input[0] == 'G' && input[1] == 'I' && input[2] == 'F') {
            return GifDecoder.decode(input, deadline);
        }
        return new ImageAnimation(new byte[][] { normalize(input) }, new int[] {100}).encode();
    }

    private static byte[] readSource(String source, Path gameDirectory, long deadline) throws IOException {
        byte[] input;
        if (source.startsWith("http://") || source.startsWith("https://")) {
            input = download(new URL(source), deadline);
        } else {
            Path path = Paths.get(source);
            if (!path.isAbsolute()) path = gameDirectory.resolve(path);
            if (!Files.isRegularFile(path)) throw new IOException("Image file does not exist or is not a regular file.");
            if (Files.size(path) > MAX_INPUT_BYTES) throw new IOException("Image exceeds 8 MiB.");
            try (InputStream stream = Files.newInputStream(path)) {
                input = readBounded(stream, deadline);
            }
        }
        return input;
    }

    private static byte[] download(URL initial, long deadline) throws IOException {
        URL url = initial;
        for (int redirects = 0; redirects <= 3; redirects++) {
            checkDeadline(deadline);
            if (!url.getProtocol().equals("http") && !url.getProtocol().equals("https")) {
                throw new IOException("Only HTTP and HTTPS image URLs are supported.");
            }
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(4000);
            connection.setReadTimeout(4000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "ped/0.4.0");
            try {
                int status = connection.getResponseCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("Image redirect has no destination.");
                    url = new URL(url, location);
                    continue;
                }
                if (status != 200) throw new IOException("Image URL returned HTTP " + status + ".");
                if (connection.getContentLengthLong() > MAX_INPUT_BYTES) throw new IOException("Image exceeds 8 MiB.");
                try (InputStream stream = connection.getInputStream()) {
                    return readBounded(stream, deadline);
                }
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("Too many image URL redirects.");
    }

    private static byte[] readBounded(InputStream stream, long deadline) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = stream.read(buffer)) != -1) {
            checkDeadline(deadline);
            if (output.size() + count > MAX_INPUT_BYTES) throw new IOException("Image exceeds 8 MiB.");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static void checkDeadline(long deadline) throws IOException {
        if (Thread.currentThread().isInterrupted() || System.nanoTime() > deadline) {
            throw new IOException("Image loading timed out or was cancelled.");
        }
    }

    private static byte[] normalize(byte[] input) throws IOException {
        try (ImageInputStream stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(input))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw new IOException("Not a supported image (use PNG, JPEG, or GIF).");
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > MAX_DIMENSION || height > MAX_DIMENSION) {
                    throw new IOException("Image dimensions must be between 1 and 4096 pixels.");
                }
                BufferedImage image = reader.read(0);
                byte[] png = resize(image, DISPLAY_DIMENSION);
                if (png.length > MAX_PACKET_BYTES - 12) png = resize(image, 256);
                if (png.length > MAX_PACKET_BYTES - 12) throw new IOException("Encoded image is too large.");
                return png;
            } finally {
                reader.dispose();
            }
        }
    }

    private static byte[] resize(BufferedImage input, int maximum) throws IOException {
        double scale = Math.min(1.0, (double) maximum / Math.max(input.getWidth(), input.getHeight()));
        int width = Math.max(1, (int) Math.round(input.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(input.getHeight() * scale));
        BufferedImage output = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = output.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(input, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        if (!ImageIO.write(output, "png", bytes)) throw new IOException("PNG encoder unavailable.");
        return bytes.toByteArray();
    }
}
