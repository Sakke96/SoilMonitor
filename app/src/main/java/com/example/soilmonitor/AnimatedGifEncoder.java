package com.example.soilmonitor;

import android.graphics.Bitmap;
import java.io.IOException;
import java.io.OutputStream;

/**
 * AnimatedGifEncoder.java
 * Public domain code adapted for Android.
 *
 * Usage:
 *   AnimatedGifEncoder encoder = new AnimatedGifEncoder();
 *   encoder.start(outputStream);
 *   encoder.setDelay(frameDelayInMilliseconds);
 *   encoder.setRepeat(0); // loop forever
 *   for (Bitmap bmp : bitmapList) {
 *       encoder.addFrame(bmp);
 *   }
 *   encoder.finish();
 */
public class AnimatedGifEncoder {
    protected int width;
    protected int height;
    protected OutputStream out;
    protected int repeat = -1;      // no repeat
    protected int delay = 0;        // frame delay (hundredths)
    protected boolean started = false;  // ready to output frames
    protected byte[] pixels;        // BGR byte array from frame
    protected byte[] indexedPixels; // converted frame indexed to palette
    protected int colorDepth;       // number of bit planes
    protected byte[] colorTab;      // palette
    protected boolean[] usedEntry = new boolean[256]; // used colors
    protected int palSize = 7;      // color table size (bits-1)
    protected int dispose = -1;     // disposal code (-1 = use default)
    protected boolean closeStream = false; // close stream when finished
    protected boolean firstFrame = true;
    protected NeuQuant nq;
    protected int sample = 10;      // default sample interval for quantizer

    public void setDelay(int ms) {
        delay = Math.max(1, ms / 10);
    }

    public void setRepeat(int iter) {
        repeat = iter;
    }

    /**
     * Start writing GIF header.
     * @param os OutputStream to write to
     * @return true if started successfully
     */
    public boolean start(OutputStream os) {
        if (os == null) return false;
        started = true;
        out = os;
        try {
            writeString("GIF89a"); // header
        } catch (IOException e) {
            return false;
        }
        return true;
    }

    /**
     * Add a frame to the GIF. Bitmap must use ARGB_8888.
     * @param im Bitmap to add
     * @return true on success
     */
    public boolean addFrame(Bitmap im) {
        if (!started) return false;
        try {
            int w = im.getWidth();
            int h = im.getHeight();
            width = w;
            height = h;
            int[] pixelsInt = new int[w * h];
            im.getPixels(pixelsInt, 0, w, 0, 0, w, h);

            pixels = new byte[w * h * 3];
            int count = 0;
            for (int i = 0; i < pixelsInt.length; i++) {
                int argb = pixelsInt[i];
                pixels[count++] = (byte) (argb & 0xFF);           // B
                pixels[count++] = (byte) ((argb >> 8) & 0xFF);    // G
                pixels[count++] = (byte) ((argb >> 16) & 0xFF);   // R
            }

            analyzePixels();

            if (firstFrame) {
                writeLSD();
                writePalette();
                if (repeat >= 0) {
                    writeNetscapeExt();
                }
            }

            writeGraphicCtrlExt();
            writeImageDesc();
            if (!firstFrame) {
                writePalette();
            }

            writePixels();

            firstFrame = false;
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Finish the GIF file. Writes trailer byte and closes stream.
     * @return true on success
     */
    public boolean finish() {
        if (!started) return false;
        try {
            out.write(0x3B); // GIF trailer
            out.flush();
            if (closeStream) {
                out.close();
            }
        } catch (IOException e) {
            return false;
        }
        started = false;
        return true;
    }

    // ────────────── Internal helper methods ──────────────

    private void analyzePixels() {
        int len = width * height;
        indexedPixels = new byte[len];
        NeuQuant nq = new NeuQuant(pixels, pixels.length, sample);
        byte[] nqPalette = nq.process();
        colorTab = new byte[256 * 3];
        for (int i = 0; i < 256; i++) {
            int idx = i * 3;
            colorTab[i * 3 + 0] = nqPalette[idx + 2]; // B
            colorTab[i * 3 + 1] = nqPalette[idx + 1]; // G
            colorTab[i * 3 + 2] = nqPalette[idx + 0]; // R
        }
        for (int i = 0, px = 0; i < len; i++) {
            int b = pixels[px++] & 0xFF;
            int g = pixels[px++] & 0xFF;
            int r = pixels[px++] & 0xFF;
            int index = nq.map(r, g, b);
            indexedPixels[i] = (byte) index;
            usedEntry[index] = true;
        }
        pixels = null;
        colorDepth = 8;
        palSize = 7;
    }

    private void writeLSD() throws IOException {
        writeShort(width);
        writeShort(height);
        out.write(0x80 | palSize);
        out.write(0);
        out.write(0);
    }

    private void writePalette() throws IOException {
        out.write(colorTab);
        int n = (3 * 256) - colorTab.length;
        for (int i = 0; i < n; i++) {
            out.write(0);
        }
    }

    private void writeNetscapeExt() throws IOException {
        out.write(0x21);
        out.write(0xFF);
        out.write(11);
        writeString("NETSCAPE2.0");
        out.write(3);
        out.write(1);
        writeShort(repeat);
        out.write(0);
    }

    private void writeGraphicCtrlExt() throws IOException {
        out.write(0x21);
        out.write(0xF9);
        out.write(4);
        out.write(0);
        writeShort(delay);
        out.write(dispose & 0x7);
        out.write(0);
        out.write(0);
    }

    private void writeImageDesc() throws IOException {
        out.write(0x2C);
        writeShort(0);
        writeShort(0);
        writeShort(width);
        writeShort(height);
        out.write(0);
    }

    private void writePixels() throws IOException {
        LZWEncoder encoder = new LZWEncoder(width, height, indexedPixels, colorDepth);
        encoder.encode(out);
    }

    private void writeShort(int value) throws IOException {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
    }

    private void writeString(String s) throws IOException {
        for (int i = 0; i < s.length(); i++) {
            out.write((byte) s.charAt(i));
        }
    }
}