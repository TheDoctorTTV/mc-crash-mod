package io.github.thedoctorttv.fakecrash;

import com.sun.net.httpserver.HttpServer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class ImageLoaderTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private byte[] image(String format, int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, 0xff22aa44);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, format, output));
        return output.toByteArray();
    }

    @Test public void relativeAndAbsolutePathsPreserveAspectRatio() throws Exception {
        Path base = temporary.getRoot().toPath();
        Path file = base.resolve("image with spaces.png");
        Files.write(file, image("png", 1600, 800));
        byte[] relative = ImageLoader.load(file.getFileName().toString(), base);
        byte[] absolute = ImageLoader.load(file.toString(), base);
        assertArrayEquals(relative, absolute);
        ImageLoader.validateWireImage(relative);
        BufferedImage result = ImageIO.read(new ByteArrayInputStream(relative));
        assertEquals(512, result.getWidth());
        assertEquals(256, result.getHeight());
    }

    @Test public void jpegAndGifBecomePng() throws Exception {
        for (String format : new String[] {"jpg", "gif"}) {
            Path file = temporary.getRoot().toPath().resolve("image." + format);
            Files.write(file, image(format, 30, 60));
            byte[] result = ImageLoader.load(file.toString(), temporary.getRoot().toPath());
            ImageLoader.validateWireImage(result);
            assertEquals(60, ImageIO.read(new ByteArrayInputStream(result)).getHeight());
        }
    }

    @Test public void invalidAndMissingFilesFail() throws Exception {
        Path base = temporary.getRoot().toPath();
        Files.write(base.resolve("not-image"), new byte[] {1, 2, 3});
        assertThrows(IOException.class, () -> ImageLoader.load("not-image", base));
        assertThrows(IOException.class, () -> ImageLoader.load("missing.png", base));
        assertThrows(IOException.class, () -> ImageLoader.load(base.toString(), base));
    }

    @Test public void oversizedFilesAndDimensionsFail() throws Exception {
        Path base = temporary.getRoot().toPath();
        Files.write(base.resolve("large.bin"), new byte[8 * 1024 * 1024 + 1]);
        assertThrows(IOException.class, () -> ImageLoader.load("large.bin", base));
        Files.write(base.resolve("wide.png"), image("png", 4097, 1));
        assertThrows(IOException.class, () -> ImageLoader.load("wide.png", base));
    }

    @Test public void wireHeaderRejectsOversizedOrInvalidImagesBeforeNativeDecode() throws Exception {
        byte[] png = image("png", 32, 32);
        ImageLoader.validateWireImage(png);
        ByteBuffer.wrap(png).putInt(16, 100000);
        assertThrows(IOException.class, () -> ImageLoader.validateWireImage(png));
        assertThrows(IOException.class, () -> ImageLoader.validateWireImage(new byte[32]));
        assertThrows(IOException.class, () -> ImageLoader.validateWireImage(new byte[ImageLoader.MAX_PACKET_BYTES + 1]));
    }

    @Test public void httpImagesAndRedirectsWorkAndErrorsFail() throws Exception {
        byte[] png = image("png", 64, 32);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/image", exchange -> {
            exchange.sendResponseHeaders(200, png.length);
            exchange.getResponseBody().write(png);
            exchange.close();
        });
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "/image");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/loop", exchange -> {
            exchange.getResponseHeaders().add("Location", "/loop");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/file-redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "file:///etc/passwd");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/oversized", exchange -> {
            exchange.sendResponseHeaders(200, 8 * 1024 * 1024 + 1);
            exchange.close();
        });
        server.start();
        try {
            Path base = temporary.getRoot().toPath();
            String root = "http://127.0.0.1:" + server.getAddress().getPort();
            byte[] direct = ImageLoader.load(root + "/image", base);
            ImageLoader.validateWireImage(direct);
            assertArrayEquals(direct, ImageLoader.load(root + "/redirect", base));
            for (String endpoint : new String[] {"/loop", "/file-redirect", "/oversized", "/missing"}) {
                assertThrows(IOException.class, () -> ImageLoader.load(root + endpoint, base));
            }
        } finally {
            server.stop(0);
        }
    }
}
