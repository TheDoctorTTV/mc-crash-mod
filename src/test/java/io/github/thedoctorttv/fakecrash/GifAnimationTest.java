package io.github.thedoctorttv.fakecrash;

import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.*;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class GifAnimationTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private byte[] gif(String disposal, int count) throws Exception {
        byte[] r = {0, (byte)255, 0, 0}, g = {0, 0, (byte)255, 0}, b = {0, 0, 0, (byte)255};
        IndexColorModel palette = new IndexColorModel(8, 4, r, g, b, 0);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
        try (MemoryCacheImageOutputStream stream = new MemoryCacheImageOutputStream(output)) {
            writer.setOutput(stream);
            writer.prepareWriteSequence(null);
            for (int i = 0; i < count; i++) {
                BufferedImage patch = new BufferedImage(i == 0 ? 3 : 1, 1, BufferedImage.TYPE_BYTE_INDEXED, palette);
                for (int x = 0; x < patch.getWidth(); x++) patch.getRaster().setSample(x, 0, 0, i % 3 + 1);
                IIOMetadata metadata = writer.getDefaultImageMetadata(new ImageTypeSpecifier(patch), null);
                IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree("javax_imageio_gif_image_1.0");
                IIOMetadataNode descriptor = (IIOMetadataNode) root.getElementsByTagName("ImageDescriptor").item(0);
                descriptor.setAttribute("imageLeftPosition", i == 2 ? "2" : "0");
                IIOMetadataNode control = (IIOMetadataNode) root.getElementsByTagName("GraphicControlExtension").item(0);
                control.setAttribute("disposalMethod", i == 1 ? disposal : "doNotDispose");
                control.setAttribute("delayTime", i == 0 ? "12" : i == 1 ? "25" : "4");
                control.setAttribute("transparentColorFlag", "TRUE");
                control.setAttribute("transparentColorIndex", "0");
                metadata.setFromTree("javax_imageio_gif_image_1.0", root);
                writer.writeToSequence(new IIOImage(patch, null, metadata), null);
            }
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
        return output.toByteArray();
    }

    private ImageAnimation load(byte[] gif) throws Exception {
        Path file = temporary.getRoot().toPath().resolve("animated.gif");
        Files.write(file, gif);
        return ImageAnimation.decode(ImageLoader.loadAnimation(file.toString(), temporary.getRoot().toPath()));
    }

    private int pixel(ImageAnimation animation, int frame, int x) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(animation.frames[frame])).getRGB(x, 0);
    }

    @Test public void retainsOffsetsDelaysAndLoopsAtCorrectTimes() throws Exception {
        ImageAnimation animation = load(gif("doNotDispose", 3));
        assertEquals(3, animation.frames.length);
        assertArrayEquals(new int[] {120, 250, 40}, animation.delays);
        assertEquals(0xffff0000, pixel(animation, 0, 0));
        assertEquals(0xff00ff00, pixel(animation, 1, 0));
        assertEquals(0xffff0000, pixel(animation, 1, 2));
        assertEquals(0xff00ff00, pixel(animation, 2, 0));
        assertEquals(0xff0000ff, pixel(animation, 2, 2));
        assertEquals(0, animation.frameAt(0));
        assertEquals(0, animation.frameAt(119));
        assertEquals(1, animation.frameAt(120));
        assertEquals(2, animation.frameAt(370));
        assertEquals(0, animation.frameAt(410));
        assertEquals(1, animation.frameAt(530));
    }

    @Test public void restorePreviousRemovesTemporaryPatch() throws Exception {
        ImageAnimation animation = load(gif("restoreToPrevious", 3));
        assertEquals(0xff00ff00, pixel(animation, 1, 0));
        assertEquals(0xffff0000, pixel(animation, 2, 0));
        assertEquals(0xff0000ff, pixel(animation, 2, 2));
    }

    @Test public void restoreBackgroundClearsTransparentRegion() throws Exception {
        ImageAnimation animation = load(gif("restoreToBackgroundColor", 3));
        assertEquals(0, pixel(animation, 2, 0) >>> 24);
        assertEquals(0xffff0000, pixel(animation, 2, 1));
    }

    @Test public void rejectsTooManyFrames() throws Exception {
        byte[] input = gif("doNotDispose", 121);
        assertThrows(IOException.class, () -> load(input));
    }

    @Test public void packetValidationRejectsTruncatedAndExcessiveDecodedData() throws Exception {
        ImageAnimation valid = load(gif("doNotDispose", 3));
        byte[] encoded = valid.encode();
        assertThrows(IOException.class, () -> ImageAnimation.decode(java.util.Arrays.copyOf(encoded, encoded.length - 1)));
        assertThrows(IOException.class, () -> ImageAnimation.decode(new byte[] {0, 0, 0, 121}));
        BufferedImage big = new BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(big, "png", png);
        byte[][] frames = new byte[33][];
        int[] delays = new int[33];
        java.util.Arrays.fill(frames, png.toByteArray());
        java.util.Arrays.fill(delays, 100);
        byte[] excessive = new ImageAnimation(frames, delays).encode();
        assertThrows(IOException.class, () -> ImageAnimation.decode(excessive));
    }

    @Test public void staticImagesStillUseSameTransferFormat() throws Exception {
        Path file = temporary.getRoot().toPath().resolve("still.png");
        ImageIO.write(new BufferedImage(32, 16, BufferedImage.TYPE_INT_RGB), "png", file.toFile());
        ImageAnimation animation = ImageAnimation.decode(ImageLoader.loadAnimation(file.toString(), temporary.getRoot().toPath()));
        assertEquals(1, animation.frames.length);
        assertEquals(0, animation.frameAt(123456));
    }

    @Test public void gifUrlsRetainAnimation() throws Exception {
        byte[] gif = gif("doNotDispose", 3);
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/animated.gif", exchange -> {
            exchange.sendResponseHeaders(200, gif.length);
            exchange.getResponseBody().write(gif);
            exchange.close();
        });
        server.start();
        try {
            ImageAnimation animation = ImageAnimation.decode(ImageLoader.loadAnimation(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/animated.gif", temporary.getRoot().toPath()));
            assertEquals(3, animation.frames.length);
            assertArrayEquals(new int[] {120, 250, 40}, animation.delays);
        } finally {
            server.stop(0);
        }
    }
}
