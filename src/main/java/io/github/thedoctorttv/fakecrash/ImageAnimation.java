package io.github.thedoctorttv.fakecrash;

import java.io.*;
import java.nio.ByteBuffer;

/** Bounded frame bundle shared by the server decoder and client renderer. */
final class ImageAnimation {
    static final int MAX_FRAMES = 120;
    final byte[][] frames;
    final int[] delays;

    ImageAnimation(byte[][] frames, int[] delays) {
        this.frames = frames;
        this.delays = delays;
    }

    byte[] encode() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(frames.length);
        for (int i = 0; i < frames.length; i++) {
            out.writeInt(delays[i]);
            out.writeInt(frames[i].length);
            out.write(frames[i]);
            if (bytes.size() > ImageLoader.MAX_PACKET_BYTES) throw new IOException("Animation exceeds transfer limit; use a smaller GIF.");
        }
        return bytes.toByteArray();
    }

    static ImageAnimation decode(byte[] bytes) throws IOException {
        if (bytes.length > ImageLoader.MAX_PACKET_BYTES) throw new IOException("Animation packet too large.");
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        int count = in.readInt();
        if (count < 1 || count > MAX_FRAMES) throw new IOException("Invalid animation frame count.");
        byte[][] frames = new byte[count][];
        int[] delays = new int[count];
        long pixels = 0;
        int width = -1, height = -1;
        for (int i = 0; i < count; i++) {
            delays[i] = in.readInt();
            int length = in.readInt();
            if (delays[i] < 20 || delays[i] > 655350 || length < 33 || length > in.available()) {
                throw new IOException("Invalid animation frame.");
            }
            frames[i] = new byte[length];
            in.readFully(frames[i]);
            ImageLoader.validateWireImage(frames[i]);
            ByteBuffer png = ByteBuffer.wrap(frames[i]);
            int w = png.getInt(16), h = png.getInt(20);
            if (i > 0 && (w != width || h != height)) throw new IOException("Animation frame sizes differ.");
            width = w; height = h;
            pixels += (long) w * h;
            if (pixels > 8 * 1024 * 1024) throw new IOException("Animation exceeds decoded memory limit.");
        }
        if (in.available() != 0) throw new IOException("Unexpected animation data.");
        return new ImageAnimation(frames, delays);
    }

    int frameAt(long elapsedMillis) {
        long total = 0;
        for (int delay : delays) total += delay;
        long position = Math.max(0, elapsedMillis) % total;
        for (int i = 0; i < delays.length; i++) {
            if (position < delays[i]) return i;
            position -= delays[i];
        }
        return 0;
    }
}
