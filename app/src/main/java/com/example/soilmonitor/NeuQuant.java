package com.example.soilmonitor;

/**
 * NeuQuant Neural-Net quantization algorithm
 * ------------------------------------------
 * Reduces 24-bit RGB to 8-bit (256 colors).
 * Used by AnimatedGifEncoder to build the palette.
 *
 * Public domain by Anthony Dekker (1994), adapted for Android.
 */
public class NeuQuant {
    protected static final int netsize = 256;   // number of colours used
    protected static final int maxnetpos = netsize - 1;

    protected static final int netbiasshift = 4;    // bias for colour values
    protected static final int intbiasshift = 16;   // bias for fractions
    protected static final int intbias = (1 << intbiasshift);
    protected static final int gammashift = 10;     // gamma = 1024
    protected static final int gamma = (1 << gammashift);
    protected static final int betashift = 10;
    protected static final int beta = intbias >> betashift; // beta = 1/1024
    protected static final int betagamma = intbias << (gammashift - betashift);

    protected static final int initrad = netsize >> 3;          // for 256 cols, radius starts at 32
    protected static final int radiusbiasshift = 6;             // at 32.0 biased by 6 bits
    protected static final int radiusbias = 1 << radiusbiasshift;
    protected static final int initradius = initrad * radiusbias; // and decreases by a factor of 1/30 each cycle
    protected static final int radiusdec = 30;

    protected static final int alphabiasshift = 10;  // alpha starts at 1.0
    protected static final int initalpha = 1 << alphabiasshift;

    protected static final int ncycles = 100; // no. of learning cycles

    protected static final int minpicturebytes = (3 * 503); // 503 is prime4 below

    // four primes near 500—ensures we step through the image in roughly random fashion
    protected static final int prime1 = 499;
    protected static final int prime2 = 491;
    protected static final int prime3 = 487;
    protected static final int prime4 = 503;

    protected byte[] thepicture; // input image array (RGB bytes)
    protected int lengthcount;   // lengthcount = H * W * 3
    protected int samplefac;     // sampling factor 1..30

    // the network itself: [netsize][4] → [r, g, b, index]
    protected int[][] network = new int[netsize][4];

    protected int[] netindex = new int[256]; // for network lookup by green value

    // bias and frequency arrays for learning
    protected int[] bias = new int[netsize];
    protected int[] freq = new int[netsize];
    protected int[] radpower = new int[initrad];

    public NeuQuant(byte[] pixels, int length, int sample) {
        thepicture = pixels;
        lengthcount = length;
        samplefac = Math.max(1, sample);

        // Initialize network: each neuron’s RGB = its index * (256 / netsize)
        for (int i = 0; i < netsize; i++) {
            int[] p = network[i];
            p[0] = p[1] = p[2] = (i << (8 + netbiasshift)) / netsize;
            freq[i] = intbias / netsize; // 1/netsize
            bias[i] = 0;
        }
    }

    /**
     * Main entry point. Learns the palette, builds netindex, and returns a 768-byte color map.
     */
    public byte[] process() {
        learn();
        unbiasnet();
        inxbuild();
        return colorMap();
    }

    // Unbias network so RGB values are in 0..255, and record each neuron’s original index
    protected void unbiasnet() {
        for (int i = 0; i < netsize; i++) {
            int[] p = network[i];
            p[0] >>= netbiasshift;
            p[1] >>= netbiasshift;
            p[2] >>= netbiasshift;
            p[3] = i; // record original index
        }
    }

    // Output the colour map as a byte[] of length 768 (256 × 3)
    protected byte[] colorMap() {
        byte[] map = new byte[3 * netsize];
        int[] index = new int[netsize];
        for (int i = 0; i < netsize; i++) {
            index[network[i][3]] = i;
        }
        int k = 0;
        for (int i = 0; i < netsize; i++) {
            int j = index[i];
            map[k++] = (byte) (network[j][0]);
            map[k++] = (byte) (network[j][1]);
            map[k++] = (byte) (network[j][2]);
        }
        return map;
    }

    // Insertion-sort network by green value and build netindex[0..255] for fast lookup
    protected void inxbuild() {
        int previouscol = 0;
        int startpos = 0;
        for (int i = 0; i < netsize; i++) {
            int[] p = network[i];
            int smallpos = i;
            int smallval = p[1]; // index on green
            for (int j = i + 1; j < netsize; j++) {
                int[] q = network[j];
                if (q[1] < smallval) {
                    smallpos = j;
                    smallval = q[1];
                }
            }
            int[] q = network[smallpos];
            if (i != smallpos) {
                // Swap entries i and smallpos
                int tg, tb, tr, ti;
                tr = q[0]; q[0] = p[0]; p[0] = tr;
                tg = q[1]; q[1] = p[1]; p[1] = tg;
                tb = q[2]; q[2] = p[2]; p[2] = tb;
                ti = q[3]; q[3] = p[3]; p[3] = ti;
            }
            if (smallval != previouscol) {
                netindex[previouscol] = (startpos + i) >> 1;
                for (int j = previouscol + 1; j < smallval; j++) {
                    netindex[j] = i;
                }
                previouscol = smallval;
                startpos = i;
            }
        }
        netindex[previouscol] = (startpos + maxnetpos) >> 1;
        for (int j = previouscol + 1; j < 256; j++) {
            netindex[j] = maxnetpos;
        }
    }

    // Main learning loop: adjusts network over ncycles passes
    protected void learn() {
        int length = lengthcount;
        int alphadec = 30 + ((samplefac - 1) / 3);
        byte[] pix = thepicture;
        int pixp = 0;
        int lim = length;
        int samplepixels = length / (3 * samplefac);
        int delta = samplepixels / ncycles;
        int alpha = initalpha;
        int radius = initradius;

        for (int i = 0; i < netsize; i++) {
            freq[i] = intbias / netsize;
            bias[i] = 0;
        }

        int step;
        if (length < minpicturebytes) {
            samplefac = 1;
            step = 3;
        } else if ((length % prime1) != 0) {
            step = 3 * prime1;
        } else if ((length % prime2) != 0) {
            step = 3 * prime2;
        } else if ((length % prime3) != 0) {
            step = 3 * prime3;
        } else {
            step = 3 * prime4;
        }

        int pos = 0;
        for (int i = 0; i < samplepixels; ) {
            int b = (pix[pos] & 0xFF) << netbiasshift;
            int g = (pix[pos + 1] & 0xFF) << netbiasshift;
            int r = (pix[pos + 2] & 0xFF) << netbiasshift;
            int best = contest(b, g, r);
            altersingle(alpha, best, b, g, r);
            if (radius != 0) alterneigh(radius, best, b, g, r);

            pos += step;
            if (pos >= lim) pos -= length;

            i++;
            if (delta == 0) delta = 1;
            if (i % delta == 0) {
                alpha -= alpha / alphadec;
                radius -= radius / radiusdec;
                if (radius < 1) radius = 1;
            }
        }
    }

    // Find best matching neuron for (b, g, r)
    protected int contest(int b, int g, int r) {
        int bestd = Integer.MAX_VALUE;
        int bestbiasd = bestd;
        int bestpos = -1;
        int bestbiaspos = -1;

        for (int i = 0; i < netsize; i++) {
            int[] n = network[i];
            int dist = Math.abs(n[0] - b) + Math.abs(n[1] - g) + Math.abs(n[2] - r);
            if (dist < bestd) {
                bestd = dist;
                bestpos = i;
            }
            int biasdist = dist - (bias[i] >> (intbiasshift - netbiasshift));
            if (biasdist < bestbiasd) {
                bestbiasd = biasdist;
                bestbiaspos = i;
            }
            freq[i] -= freq[i] >> betashift;
            bias[i] += (freq[i] << (gammashift - betashift));
        }
        freq[bestpos] += beta;
        bias[bestpos] -= betagamma;
        return bestbiaspos;
    }

    // Move neuron i towards (b, g, r) by factor alpha/(1<<alphabiasshift)
    protected void altersingle(int alpha, int pos, int b, int g, int r) {
        int[] n = network[pos];
        n[0] -= (alpha * (n[0] - b)) / initalpha;
        n[1] -= (alpha * (n[1] - g)) / initalpha;
        n[2] -= (alpha * (n[2] - r)) / initalpha;
    }

    // Move neurons in radius around pos towards (b, g, r)
    protected void alterneigh(int rad, int pos, int b, int g, int r) {
        int lo = Math.max(pos - rad, 0);
        int hi = Math.min(pos + rad, netsize - 1);

        for (int j = lo; j <= hi; j++) {
            int[] p = network[j];
            p[0] -= (rad * (p[0] - b)) / (radiusbias * initalpha);
            p[1] -= (rad * (p[1] - g)) / (radiusbias * initalpha);
            p[2] -= (rad * (p[2] - r)) / (radiusbias * initalpha);
        }
    }

    /**
     * Search for nearest palette index (0..255) for color (b, g, r).
     * Uses netindex to speed up lookup by green component.
     */
    public int map(int b, int g, int r) {
        int bestd = Integer.MAX_VALUE;
        int best = -1;
        int i = netindex[g];
        int j = i - 1;

        while ((i < netsize) || (j >= 0)) {
            if (i < netsize) {
                int[] p = network[i];
                int dist = Math.abs(p[1] - g);
                if (dist >= bestd) {
                    i = netsize;
                } else {
                    dist += Math.abs(p[0] - b) + Math.abs(p[2] - r);
                    if (dist < bestd) {
                        bestd = dist;
                        best = i;
                    }
                    i++;
                }
            }
            if (j >= 0) {
                int[] p = network[j];
                int dist = Math.abs(p[1] - g);
                if (dist >= bestd) {
                    j = -1;
                } else {
                    dist += Math.abs(p[0] - b) + Math.abs(p[2] - r);
                    if (dist < bestd) {
                        bestd = dist;
                        best = j;
                    }
                    j--;
                }
            }
        }
        return best;
    }
}
