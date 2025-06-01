package com.example.soilmonitor;

import java.io.IOException;
import java.io.OutputStream;

/**
 * LZWEncoder.java
 *
 * GIF image compression logic used by AnimatedGifEncoder.
 * Public domain implementation from the android-gif-encoder project.
 */
public class LZWEncoder {
    private static final int EOF = -1;

    private int imgW, imgH;
    private byte[] pixAry;
    private int initCodeSize;
    private int remaining;
    private int curPixel;

    // GIFCOMPR.C - GIF image compression routines
    private static final int BITS = 12;
    private static final int HSIZE = 5003; // 80% occupancy

    private int n_bits;           // number of bits/code
    private int maxbits = BITS;   // user-settable max bits/code
    private int maxcode;          // maximum code, given n_bits
    private int maxmaxcode = 1 << BITS; // should never generate this code

    private int[] htab = new int[HSIZE];
    private int[] codetab = new int[HSIZE];
    private int hsize = HSIZE; // for dynamic table sizing

    private int free_ent = 0; // first unused entry

    private boolean clear_flg = false;

    // output
    private int cur_accum = 0;
    private int cur_bits = 0;

    private byte[] buffer = new byte[256];
    private int a_count;

    public LZWEncoder(int width, int height, byte[] pixels, int colorDepth) {
        imgW = width;
        imgH = height;
        pixAry = pixels;
        initCodeSize = Math.max(2, colorDepth);
    }

    private void char_out(byte c, OutputStream outs) throws IOException {
        buffer[a_count++] = c;
        if (a_count >= 254) flush_char(outs);
    }

    private void cl_block(OutputStream outs) throws IOException {
        cl_hash(hsize);
        free_ent = (1 << initCodeSize);
        clear_flg = true;
        output(initCodeSize, outs);
    }

    private void cl_hash(int hsize) {
        for (int i = 0; i < hsize; i++) {
            htab[i] = -1;
        }
    }

    public void encode(OutputStream os) throws IOException {
        os.write(initCodeSize); // write “initial code size”
        remaining = imgW * imgH;
        curPixel = 0;
        compress(initCodeSize + 1, os); // compress and write pixel data
        os.write(0); // write block terminator
    }

    private void compress(int init_bits, OutputStream outs) throws IOException {
        int fcode;
        int i;
        int c;
        int ent;
        int disp;
        int hsize_reg;
        int hshift;

        n_bits = init_bits;
        maxcode = maxCode(n_bits);

        int clearCode = 1 << (init_bits - 1);
        int EOFCode = clearCode + 1;
        free_ent = clearCode + 2;
        clear_flg = false;

        ent = nextPixel();

        hshift = 0;
        for (fcode = hsize; fcode < 65536; fcode *= 2) {
            hshift++;
        }
        hshift = 8 - hshift;

        hsize_reg = hsize;
        cl_hash(hsize_reg);

        output(clearCode, outs);

        outer_loop:
        while ((c = nextPixel()) != EOF) {
            fcode = (c << maxbits) + ent;
            i = (c << hshift) ^ ent; // xor hashing

            if (htab[i] == fcode) {
                ent = codetab[i];
                continue;
            } else if (htab[i] >= 0) {
                disp = hsize_reg - i;
                if (i == 0) disp = 1;
                do {
                    i -= disp;
                    if (i < 0) i += hsize_reg;
                    if (htab[i] == fcode) {
                        ent = codetab[i];
                        continue outer_loop;
                    }
                } while (htab[i] >= 0);
            }

            output(ent, outs);
            ent = c;
            if (free_ent < maxmaxcode) {
                codetab[i] = free_ent++;
                htab[i] = fcode;
            } else {
                cl_block(outs);
            }
        }

        output(ent, outs);
        output(EOFCode, outs);
        while (cur_bits > 0) {
            char_out((byte) (cur_accum & 0xFF), outs);
            cur_accum >>= 8;
            cur_bits -= 8;
        }
        flush_char(outs);
    }

    private int nextPixel() {
        if (remaining == 0) return EOF;
        remaining--;
        byte pix = pixAry[curPixel++];
        return pix & 0xFF;
    }

    private void output(int code, OutputStream outs) throws IOException {
        cur_accum &= 0xFFFFFFFF;
        if (cur_bits > 0) cur_accum |= (code << cur_bits);
        else cur_accum = code;

        cur_bits += n_bits;
        while (cur_bits >= 8) {
            char_out((byte) (cur_accum & 0xFF), outs);
            cur_accum >>= 8;
            cur_bits -= 8;
        }

        if (free_ent > maxcode || clear_flg) {
            if (clear_flg) {
                n_bits = initCodeSize;
                maxcode = maxCode(n_bits);
                clear_flg = false;
            } else {
                n_bits++;
                if (n_bits == maxbits) maxcode = maxmaxcode;
                else maxcode = maxCode(n_bits);
            }
        }

        if (code == EOF) {
            flush_char(outs);
        }
    }

    private void flush_char(OutputStream outs) throws IOException {
        if (a_count > 0) {
            outs.write(a_count);
            outs.write(buffer, 0, a_count);
            a_count = 0;
        }
    }

    private int maxCode(int n_bits) {
        return (1 << n_bits) - 1;
    }
}
