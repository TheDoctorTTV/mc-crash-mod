package io.github.thedoctorttv.fakecrash;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.*;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.w3c.dom.Node;

/** Composites partial GIF frames and disposal operations before sending PNG frames. */
final class GifDecoder {
    static byte[] decode(byte[] input, long deadline) throws IOException {
        // Retry at lower resolution if PNG frames exceed the single-packet budget.
        for (int size : new int[] {256, 128, 64}) {
            try {
                return decodeAtSize(input, size, deadline).encode();
            } catch (TransferLimit ex) {
                if (size == 64) throw new IOException("GIF exceeds transfer limit; use a shorter or simpler GIF.");
            }
        }
        throw new IOException("Could not decode GIF.");
    }

    private static ImageAnimation decodeAtSize(byte[] input, int maximum, long deadline) throws IOException {
        try (MemoryCacheImageInputStream stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(input))) {
            ImageReader reader = ImageIO.getImageReadersByFormatName("gif").next();
            try {
                reader.setInput(stream, false, false);
                int count = reader.getNumImages(true);
                if (count < 1 || count > ImageAnimation.MAX_FRAMES) throw new IOException("GIF must contain 1–120 frames.");
                Node metadata = reader.getStreamMetadata().getAsTree("javax_imageio_gif_stream_1.0");
                Node logical = child(metadata, "LogicalScreenDescriptor");
                int sourceWidth = number(logical, "logicalScreenWidth");
                int sourceHeight = number(logical, "logicalScreenHeight");
                if (sourceWidth < 1 || sourceHeight < 1 || sourceWidth > 4096 || sourceHeight > 4096) {
                    throw new IOException("GIF dimensions must be between 1 and 4096 pixels.");
                }
                double scale = Math.min(1.0, (double) maximum / Math.max(sourceWidth, sourceHeight));
                int width = Math.max(1, (int) Math.round(sourceWidth * scale));
                int height = Math.max(1, (int) Math.round(sourceHeight * scale));
                BufferedImage canvas = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
                byte[][] frames = new byte[count][];
                int[] delays = new int[count];
                long decodedPixels = 0;
                int encodedBytes = 4 + count * 8;
                int background = background(metadata);
                for (int i = 0; i < count; i++) {
                    if (Thread.currentThread().isInterrupted() || System.nanoTime() > deadline) throw new IOException("GIF loading timed out.");
                    Node frameMeta = reader.getImageMetadata(i).getAsTree("javax_imageio_gif_image_1.0");
                    Node descriptor = child(frameMeta, "ImageDescriptor");
                    Node control = child(frameMeta, "GraphicControlExtension");
                    int x = number(descriptor, "imageLeftPosition"), y = number(descriptor, "imageTopPosition");
                    int w = reader.getWidth(i), h = reader.getHeight(i);
                    if (w < 1 || h < 1 || x + w > sourceWidth || y + h > sourceHeight) throw new IOException("GIF frame lies outside canvas.");
                    decodedPixels += (long) w * h;
                    if (decodedPixels > 256_000_000L) throw new IOException("GIF is too complex; reduce its dimensions or frame count.");
                    boolean transparent = control != null && "TRUE".equalsIgnoreCase(attribute(control, "transparentColorFlag"));
                    String disposal = control == null ? "none" : attribute(control, "disposalMethod");
                    int delay = control == null ? 0 : number(control, "delayTime") * 10;
                    delays[i] = delay == 0 ? 100 : Math.max(20, delay);
                    if (i == 0 && !transparent) fill(canvas, 0, 0, width, height, background);
                    BufferedImage previous = null;
                    if ("restoreToPrevious".equals(disposal)) {
                        previous = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
                        Graphics2D copy = previous.createGraphics();
                        copy.drawImage(canvas, 0, 0, null);
                        copy.dispose();
                    }
                    BufferedImage patch = reader.read(i);
                    int left = (int) Math.round(x * scale), top = (int) Math.round(y * scale);
                    int right = (int) Math.round((x + w) * scale), bottom = (int) Math.round((y + h) * scale);
                    Graphics2D graphics = canvas.createGraphics();
                    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    graphics.drawImage(patch, left, top, right - left, bottom - top, null);
                    graphics.dispose();
                    ByteArrayOutputStream png = new ByteArrayOutputStream();
                    ImageIO.write(canvas, "png", png);
                    frames[i] = png.toByteArray();
                    encodedBytes += frames[i].length;
                    if (encodedBytes > ImageLoader.MAX_PACKET_BYTES) throw new TransferLimit();
                    if ("restoreToBackgroundColor".equals(disposal)) {
                        fill(canvas, left, top, right - left, bottom - top, transparent ? 0 : background);
                    } else if (previous != null) {
                        canvas = previous;
                    }
                }
                return new ImageAnimation(frames, delays);
            } finally {
                reader.dispose();
            }
        }
    }

    private static void fill(BufferedImage image, int x, int y, int w, int h, int color) {
        Graphics2D graphics = image.createGraphics();
        graphics.setComposite(AlphaComposite.Src);
        graphics.setColor(new Color(color, true));
        graphics.fillRect(x, y, w, h);
        graphics.dispose();
    }

    private static int background(Node metadata) {
        Node table = child(metadata, "GlobalColorTable");
        if (table == null) return 0;
        int index = number(table, "backgroundColorIndex");
        for (Node node = table.getFirstChild(); node != null; node = node.getNextSibling()) {
            if ("ColorTableEntry".equals(node.getNodeName()) && number(node, "index") == index) {
                return 0xff000000 | number(node, "red") << 16 | number(node, "green") << 8 | number(node, "blue");
            }
        }
        return 0;
    }

    private static Node child(Node node, String name) {
        for (Node c = node.getFirstChild(); c != null; c = c.getNextSibling()) if (name.equals(c.getNodeName())) return c;
        return null;
    }
    private static String attribute(Node node, String name) { return node.getAttributes().getNamedItem(name).getNodeValue(); }
    private static int number(Node node, String name) { return Integer.parseInt(attribute(node, name)); }
    private static final class TransferLimit extends IOException { }
}
