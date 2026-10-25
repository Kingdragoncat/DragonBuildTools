package com.dragonbuildtools.converter;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Self-contained pure-Java WebP (RIFF WEBP: VP8L Lossless & VP8 Lossy Intra-frame) decoder.
 * Ensures .webp images from URLs or local files work out-of-the-box without external native libraries.
 */
public final class SimpleWebpDecoder {

    private SimpleWebpDecoder() {
    }

    public static BufferedImage decode(byte[] data) throws IOException {
        if (data == null || data.length < 16) {
            return null;
        }
        if (data[0] != 'R' || data[1] != 'I' || data[2] != 'F' || data[3] != 'F'
                || data[8] != 'W' || data[9] != 'E' || data[10] != 'B' || data[11] != 'P') {
            return null;
        }

        int offset = 12;
        byte[] alphaChunk = null;
        while (offset + 8 <= data.length) {
            String fourCC = new String(data, offset, 4);
            int chunkSize = readLeInt(data, offset + 4);
            int payloadStart = offset + 8;
            if (chunkSize < 0 || payloadStart + chunkSize > data.length) {
                chunkSize = Math.max(0, data.length - payloadStart);
            }
            if ("ALPH".equals(fourCC)) {
                alphaChunk = Arrays.copyOfRange(data, payloadStart, payloadStart + chunkSize);
            } else if ("VP8L".equals(fourCC)) {
                return decodeVp8L(data, payloadStart, chunkSize);
            } else if ("VP8 ".equals(fourCC)) {
                BufferedImage img = decodeVp8(data, payloadStart, chunkSize);
                if (img != null && alphaChunk != null) {
                    applyRawAlphaChunk(img, alphaChunk);
                }
                return img;
            }
            offset = payloadStart + chunkSize + (chunkSize & 1);
        }
        return null;
    }

    private static void applyRawAlphaChunk(BufferedImage img, byte[] alphaChunk) {
        if (alphaChunk.length < 1) {
            return;
        }
        int compression = alphaChunk[0] & 0x03;
        int w = img.getWidth();
        int h = img.getHeight();
        if (compression == 0 && alphaChunk.length >= 1 + w * h) {
            int idx = 1;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int a = alphaChunk[idx++] & 0xFF;
                    int rgb = img.getRGB(x, y) & 0x00FFFFFF;
                    img.setRGB(x, y, (a << 24) | rgb);
                }
            }
        }
    }

    // =========================================================================
    // VP8L (Lossless WebP - RFC 9649) Decoder
    // =========================================================================

    private static final int[] CODE_LENGTH_CODE_ORDER = {
            17, 18, 0, 1, 2, 3, 4, 5, 16, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15
    };

    private static final int[][] DISTANCE_MAP = {
            {0, 1}, {1, 0}, {1, 1}, {-1, 1}, {0, 2}, {2, 0}, {1, 2}, {-1, 2},
            {2, 1}, {-2, 1}, {2, 2}, {-2, 2}, {0, 3}, {3, 0}, {1, 3}, {-1, 3},
            {3, 1}, {-3, 1}, {2, 3}, {-2, 3}, {3, 2}, {-3, 2}, {0, 4}, {4, 0},
            {1, 4}, {-1, 4}, {4, 1}, {-4, 1}, {3, 3}, {-3, 3}, {2, 4}, {-2, 4},
            {4, 2}, {-4, 2}, {0, 5}, {3, 4}, {-3, 4}, {4, 3}, {-4, 3}, {5, 0},
            {1, 5}, {-1, 5}, {5, 1}, {-5, 1}, {2, 5}, {-2, 5}, {5, 2}, {-5, 2},
            {4, 4}, {-4, 4}, {3, 5}, {-3, 5}, {5, 3}, {-5, 3}, {0, 6}, {6, 0},
            {1, 6}, {-1, 6}, {6, 1}, {-6, 1}, {2, 6}, {-2, 6}, {6, 2}, {-6, 2},
            {4, 5}, {-4, 5}, {5, 4}, {-5, 4}, {3, 6}, {-3, 6}, {6, 3}, {-6, 3},
            {0, 7}, {7, 0}, {1, 7}, {-1, 7}, {5, 5}, {-5, 5}, {7, 1}, {-7, 1},
            {4, 6}, {-4, 6}, {6, 4}, {-6, 4}, {2, 7}, {-2, 7}, {7, 2}, {-7, 2},
            {3, 7}, {-3, 7}, {7, 3}, {-7, 3}, {5, 6}, {-5, 6}, {6, 5}, {-6, 5},
            {8, 0}, {4, 7}, {-4, 7}, {7, 4}, {-7, 4}, {8, 1}, {8, 2}, {6, 6},
            {-6, 6}, {8, 3}, {5, 7}, {-5, 7}, {7, 5}, {-7, 5}, {8, 4}, {6, 7},
            {-6, 7}, {7, 6}, {-7, 6}, {8, 5}, {7, 7}, {-7, 7}, {8, 6}, {8, 7}
    };

    private static BufferedImage decodeVp8L(byte[] data, int start, int length) throws IOException {
        if (length < 5 || (data[start] & 0xFF) != 0x2F) {
            return null;
        }
        Vp8LBitReader br = new Vp8LBitReader(data, start + 1, length - 1);
        int width = br.readBits(14) + 1;
        int height = br.readBits(14) + 1;
        br.readBits(1); // alpha_is_used
        int version = br.readBits(3);
        if (version != 0) {
            return null;
        }

        int[] widthRef = {width};
        int[] pixels = decodeVp8LImageStream(br, widthRef, height, true);
        if (pixels == null) {
            return null;
        }
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, width, height, pixels, 0, width);
        return img;
    }

    private static final class Transform {
        int type;
        int bits;
        int xsize;
        int ysize;
        int[] data;
    }

    private static int[] decodeVp8LImageStream(Vp8LBitReader br, int[] widthRef, int height, boolean isTopLevel) {
        int width = widthRef[0];
        java.util.List<Transform> transforms = new java.util.ArrayList<>();

        while (isTopLevel && br.readBits(1) == 1) {
            Transform tr = new Transform();
            tr.type = br.readBits(2);
            tr.xsize = width;
            tr.ysize = height;
            switch (tr.type) {
                case 0: // Predictor
                case 1: // Cross-color
                    tr.bits = br.readBits(3) + 2;
                    int subW = subSampleSize(width, tr.bits);
                    int subH = subSampleSize(height, tr.bits);
                    tr.data = decodeVp8LImageStream(br, new int[]{subW}, subH, false);
                    break;
                case 2: // Subtract green
                    break;
                case 3: // Color indexing (palette)
                    int numColors = br.readBits(8) + 1;
                    int[] palette = decodeVp8LImageStream(br, new int[]{numColors}, 1, false);
                    if (palette == null) {
                        return null;
                    }
                    unfilterPalette(palette);
                    tr.bits = (numColors > 16) ? 0 : (numColors > 4) ? 1 : (numColors > 2) ? 2 : 3;
                    width = subSampleSize(width, tr.bits);
                    tr.data = palette;
                    break;
            }
            transforms.add(tr);
        }

        int colorCacheBits = 0;
        if (br.readBits(1) == 1) {
            colorCacheBits = br.readBits(4);
            if (colorCacheBits < 1 || colorCacheBits > 11) {
                return null;
            }
        }

        int numHuffGroups = 1;
        int huffBits = 0;
        int huffXSize = 1;
        int[] entropyImage = null;
        if (isTopLevel && br.readBits(1) == 1) {
            huffBits = br.readBits(3) + 2;
            huffXSize = subSampleSize(width, huffBits);
            int huffYSize = subSampleSize(height, huffBits);
            entropyImage = decodeVp8LImageStream(br, new int[]{huffXSize}, huffYSize, false);
            if (entropyImage == null) {
                return null;
            }
            int maxGroup = 0;
            for (int i = 0; i < entropyImage.length; i++) {
                int group = (entropyImage[i] >>> 8) & 0xFFFF;
                entropyImage[i] = group;
                if (group > maxGroup) {
                    maxGroup = group;
                }
            }
            numHuffGroups = maxGroup + 1;
        }

        int greenAlphabetSize = 256 + 24 + (colorCacheBits > 0 ? (1 << colorCacheBits) : 0);
        HuffTree[][] huffGroups = new HuffTree[numHuffGroups][5];
        for (int g = 0; g < numHuffGroups; g++) {
            huffGroups[g][0] = readHuffTree(br, greenAlphabetSize);
            huffGroups[g][1] = readHuffTree(br, 256);
            huffGroups[g][2] = readHuffTree(br, 256);
            huffGroups[g][3] = readHuffTree(br, 256);
            huffGroups[g][4] = readHuffTree(br, 40);
            for (int k = 0; k < 5; k++) {
                if (huffGroups[g][k] == null) {
                    return null;
                }
            }
        }

        int[] colorCache = colorCacheBits > 0 ? new int[1 << colorCacheBits] : null;
        int totalPixels = width * height;
        int[] pixels = new int[totalPixels];
        int pos = 0;

        while (pos < totalPixels) {
            int group = 0;
            if (entropyImage != null) {
                int x = pos % width;
                int y = pos / width;
                group = entropyImage[(y >>> huffBits) * huffXSize + (x >>> huffBits)];
            }
            HuffTree[] trees = huffGroups[group];
            int code = trees[0].readSymbol(br);
            if (code < 256) {
                int red = trees[1].readSymbol(br);
                int blue = trees[2].readSymbol(br);
                int alpha = trees[3].readSymbol(br);
                int argb = (alpha << 24) | (red << 16) | (code << 8) | blue;
                pixels[pos++] = argb;
                if (colorCache != null) {
                    colorCache[(0x1e35a7bd * argb) >>> (32 - colorCacheBits)] = argb;
                }
            } else if (code < 256 + 24) {
                int lengthSym = code - 256;
                int lzLength = getCopyValue(br, lengthSym);
                int distSym = trees[4].readSymbol(br);
                int distCode = getCopyValue(br, distSym);
                int dist = planeCodeToDistance(width, distCode);
                if (dist <= 0) {
                    dist = 1;
                }
                for (int k = 0; k < lzLength && pos < totalPixels; k++) {
                    int srcIdx = Math.max(0, pos - dist);
                    int argb = pixels[srcIdx];
                    pixels[pos++] = argb;
                    if (colorCache != null) {
                        colorCache[(0x1e35a7bd * argb) >>> (32 - colorCacheBits)] = argb;
                    }
                }
            } else {
                int cacheIdx = code - 280;
                int argb = (colorCache != null && cacheIdx >= 0 && cacheIdx < colorCache.length) ? colorCache[cacheIdx] : 0xFF000000;
                pixels[pos++] = argb;
            }
        }

        for (int i = transforms.size() - 1; i >= 0; i--) {
            Transform tr = transforms.get(i);
            pixels = inverseTransform(tr, pixels);
        }
        widthRef[0] = width;
        return pixels;
    }

    private static void unfilterPalette(int[] palette) {
        for (int i = 1; i < palette.length; i++) {
            int prev = palette[i - 1];
            int cur = palette[i];
            int a = (((cur >>> 24) & 0xFF) + ((prev >>> 24) & 0xFF)) & 0xFF;
            int r = (((cur >>> 16) & 0xFF) + ((prev >>> 16) & 0xFF)) & 0xFF;
            int g = (((cur >>> 8) & 0xFF) + ((prev >>> 8) & 0xFF)) & 0xFF;
            int b = ((cur & 0xFF) + (prev & 0xFF)) & 0xFF;
            palette[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
    }

    private static int[] inverseTransform(Transform tr, int[] data) {
        switch (tr.type) {
            case 2: { // Subtract green
                for (int i = 0; i < data.length; i++) {
                    int argb = data[i];
                    int g = (argb >>> 8) & 0xFF;
                    int r = (((argb >>> 16) & 0xFF) + g) & 0xFF;
                    int b = ((argb & 0xFF) + g) & 0xFF;
                    data[i] = (argb & 0xFF00FF00) | (r << 16) | b;
                }
                return data;
            }
            case 3: { // Color indexing
                int outW = tr.xsize;
                int outH = tr.ysize;
                int[] out = new int[outW * outH];
                int[] pal = tr.data;
                if (tr.bits == 0) {
                    for (int i = 0; i < out.length && i < data.length; i++) {
                        int idx = (data[i] >>> 8) & 0xFF;
                        out[i] = idx < pal.length ? pal[idx] : 0;
                    }
                } else {
                    int pixelsPerByte = 1 << tr.bits;
                    int countMask = (1 << (8 >> tr.bits)) - 1;
                    int bitsPerPixel = 8 >> tr.bits;
                    int packedW = subSampleSize(outW, tr.bits);
                    for (int y = 0; y < outH; y++) {
                        for (int x = 0; x < outW; x++) {
                            int packedVal = (data[y * packedW + (x >>> tr.bits)] >>> 8) & 0xFF;
                            int idx = (packedVal >>> ((x & (pixelsPerByte - 1)) * bitsPerPixel)) & countMask;
                            out[y * outW + x] = idx < pal.length ? pal[idx] : 0;
                        }
                    }
                }
                return out;
            }
            case 1: { // Cross-color
                int w = tr.xsize;
                int h = tr.ysize;
                int tileW = subSampleSize(w, tr.bits);
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        int c = tr.data[(y >>> tr.bits) * tileW + (x >>> tr.bits)];
                        int gToR = (byte) (c & 0xFF);
                        int gToB = (byte) ((c >>> 8) & 0xFF);
                        int rToB = (byte) ((c >>> 16) & 0xFF);
                        int argb = data[y * w + x];
                        int g = (argb >>> 8) & 0xFF;
                        int r = (argb >>> 16) & 0xFF;
                        int b = argb & 0xFF;
                        r = (r + ((gToR * (byte) g) >> 5)) & 0xFF;
                        b = (b + ((gToB * (byte) g) >> 5)) & 0xFF;
                        b = (b + ((rToB * (byte) r) >> 5)) & 0xFF;
                        data[y * w + x] = (argb & 0xFF00FF00) | (r << 16) | b;
                    }
                }
                return data;
            }
            case 0: { // Predictor
                int w = tr.xsize;
                int h = tr.ysize;
                int tileW = subSampleSize(w, tr.bits);
                data[0] = addPixels(data[0], 0xFF000000);
                for (int x = 1; x < w; x++) {
                    data[x] = addPixels(data[x], data[x - 1]);
                }
                for (int y = 1; y < h; y++) {
                    data[y * w] = addPixels(data[y * w], data[(y - 1) * w]);
                    for (int x = 1; x < w; x++) {
                        int mode = (tr.data[(y >>> tr.bits) * tileW + (x >>> tr.bits)] >>> 8) & 0x0F;
                        int L = data[y * w + x - 1];
                        int T = data[(y - 1) * w + x];
                        int TL = data[(y - 1) * w + x - 1];
                        int TR = (x + 1 < w) ? data[(y - 1) * w + x + 1] : data[y * w];
                        int pred = predictVp8L(mode, L, T, TL, TR);
                        data[y * w + x] = addPixels(data[y * w + x], pred);
                    }
                }
                return data;
            }
            default:
                return data;
        }
    }

    private static int predictVp8L(int mode, int L, int T, int TL, int TR) {
        switch (mode) {
            case 0: return 0xFF000000;
            case 1: return L;
            case 2: return T;
            case 3: return TR;
            case 4: return TL;
            case 5: return avg2(avg2(L, TR), T);
            case 6: return avg2(L, TL);
            case 7: return avg2(L, T);
            case 8: return avg2(TL, T);
            case 9: return avg2(T, TR);
            case 10: return avg2(avg2(L, TL), avg2(T, TR));
            case 11: return selectPredictor(L, T, TL);
            case 12: return clampAddSubtractFull(L, T, TL);
            case 13: return clampAddSubtractHalf(avg2(L, T), TL);
            default: return L;
        }
    }

    private static int addPixels(int a, int b) {
        int alpha = (((a >>> 24) & 0xFF) + ((b >>> 24) & 0xFF)) & 0xFF;
        int red = (((a >>> 16) & 0xFF) + ((b >>> 16) & 0xFF)) & 0xFF;
        int green = (((a >>> 8) & 0xFF) + ((b >>> 8) & 0xFF)) & 0xFF;
        int blue = ((a & 0xFF) + (b & 0xFF)) & 0xFF;
        return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }

    private static int avg2(int a, int b) {
        return (((a ^ b) & 0xFEFEFEFE) >>> 1) + (a & b);
    }

    private static int selectPredictor(int L, int T, int TL) {
        int pAlpha = ((L >>> 24) & 0xFF) + ((T >>> 24) & 0xFF) - ((TL >>> 24) & 0xFF);
        int pRed = ((L >>> 16) & 0xFF) + ((T >>> 16) & 0xFF) - ((TL >>> 16) & 0xFF);
        int pGreen = ((L >>> 8) & 0xFF) + ((T >>> 8) & 0xFF) - ((TL >>> 8) & 0xFF);
        int pBlue = (L & 0xFF) + (T & 0xFF) - (TL & 0xFF);
        int distL = Math.abs(pAlpha - ((L >>> 24) & 0xFF)) + Math.abs(pRed - ((L >>> 16) & 0xFF))
                + Math.abs(pGreen - ((L >>> 8) & 0xFF)) + Math.abs(pBlue - (L & 0xFF));
        int distT = Math.abs(pAlpha - ((T >>> 24) & 0xFF)) + Math.abs(pRed - ((T >>> 16) & 0xFF))
                + Math.abs(pGreen - ((T >>> 8) & 0xFF)) + Math.abs(pBlue - (T & 0xFF));
        return distL < distT ? L : T;
    }

    private static int clampAddSubtractFull(int a, int b, int c) {
        int alpha = clip255(((a >>> 24) & 0xFF) + ((b >>> 24) & 0xFF) - ((c >>> 24) & 0xFF));
        int red = clip255(((a >>> 16) & 0xFF) + ((b >>> 16) & 0xFF) - ((c >>> 16) & 0xFF));
        int green = clip255(((a >>> 8) & 0xFF) + ((b >>> 8) & 0xFF) - ((c >>> 8) & 0xFF));
        int blue = clip255((a & 0xFF) + (b & 0xFF) - (c & 0xFF));
        return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }

    private static int clampAddSubtractHalf(int a, int b) {
        int alpha = clip255(((a >>> 24) & 0xFF) + (((a >>> 24) & 0xFF) - ((b >>> 24) & 0xFF)) / 2);
        int red = clip255(((a >>> 16) & 0xFF) + (((a >>> 16) & 0xFF) - ((b >>> 16) & 0xFF)) / 2);
        int green = clip255(((a >>> 8) & 0xFF) + (((a >>> 8) & 0xFF) - ((b >>> 8) & 0xFF)) / 2);
        int blue = clip255((a & 0xFF) + ((a & 0xFF) - (b & 0xFF)) / 2);
        return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }

    private static int clip255(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    private static int subSampleSize(int size, int samplingBits) {
        return (size + (1 << samplingBits) - 1) >>> samplingBits;
    }

    private static int getCopyValue(Vp8LBitReader br, int sym) {
        if (sym < 4) {
            return sym + 1;
        }
        int extraBits = (sym - 2) >>> 1;
        int offset = (2 + (sym & 1)) << extraBits;
        return offset + br.readBits(extraBits) + 1;
    }

    private static int planeCodeToDistance(int xsize, int planeCode) {
        if (planeCode > 120) {
            return planeCode - 120;
        }
        int[] xy = DISTANCE_MAP[planeCode - 1];
        int dist = xy[0] + xy[1] * xsize;
        return dist < 1 ? 1 : dist;
    }

    private static final class HuffTree {
        int singleSymbol = -1;
        int[] tree; // pairs of left/right child or -(symbol + 1)

        static HuffTree single(int sym) {
            HuffTree ht = new HuffTree();
            ht.singleSymbol = sym;
            return ht;
        }

        static HuffTree fromLengths(int[] codeLengths) {
            int maxLen = 0;
            int nonZeroCount = 0;
            int lastSym = 0;
            for (int i = 0; i < codeLengths.length; i++) {
                int len = codeLengths[i];
                if (len > 0) {
                    nonZeroCount++;
                    lastSym = i;
                    if (len > maxLen) {
                        maxLen = len;
                    }
                }
            }
            if (nonZeroCount == 0) {
                return single(0);
            }
            if (nonZeroCount == 1) {
                return single(lastSym);
            }

            int[] blCount = new int[maxLen + 1];
            for (int len : codeLengths) {
                if (len > 0) {
                    blCount[len]++;
                }
            }
            int[] nextCode = new int[maxLen + 1];
            int code = 0;
            for (int bits = 1; bits <= maxLen; bits++) {
                code = (code + blCount[bits - 1]) << 1;
                nextCode[bits] = code;
            }

            HuffTree ht = new HuffTree();
            ht.tree = new int[codeLengths.length * 4 + 4];
            int nextNode = 1;

            for (int sym = 0; sym < codeLengths.length; sym++) {
                int len = codeLengths[sym];
                if (len > 0) {
                    int c = nextCode[len]++;
                    int node = 0;
                    for (int bit = len - 1; bit >= 0; bit--) {
                        int b = (c >>> bit) & 1;
                        int idx = node * 2 + b;
                        if (bit == 0) {
                            ht.tree[idx] = -(sym + 1);
                        } else {
                            if (ht.tree[idx] == 0) {
                                ht.tree[idx] = nextNode++;
                            }
                            node = ht.tree[idx];
                        }
                    }
                }
            }
            return ht;
        }

        int readSymbol(Vp8LBitReader br) {
            if (singleSymbol >= 0) {
                return singleSymbol;
            }
            int node = 0;
            while (true) {
                int b = br.readBits(1);
                int next = tree[node * 2 + b];
                if (next < 0) {
                    return -next - 1;
                }
                if (next == 0) {
                    return 0;
                }
                node = next;
            }
        }
    }

    private static HuffTree readHuffTree(Vp8LBitReader br, int alphabetSize) {
        int simple = br.readBits(1);
        if (simple == 1) {
            int numSyms = br.readBits(1) + 1;
            int isFirst8Bits = br.readBits(1);
            int sym0 = br.readBits(isFirst8Bits == 1 ? 8 : 1);
            if (numSyms == 1) {
                return HuffTree.single(sym0);
            }
            int sym1 = br.readBits(8);
            int[] lengths = new int[Math.max(alphabetSize, Math.max(sym0, sym1) + 1)];
            lengths[sym0] = 1;
            lengths[sym1] = 1;
            return HuffTree.fromLengths(lengths);
        }

        int numCodeLengthCodes = br.readBits(4) + 4;
        int[] codeLengthCodeLengths = new int[19];
        for (int i = 0; i < numCodeLengthCodes; i++) {
            codeLengthCodeLengths[CODE_LENGTH_CODE_ORDER[i]] = br.readBits(3);
        }
        HuffTree codeLenTree = HuffTree.fromLengths(codeLengthCodeLengths);

        int maxSymbol = alphabetSize;
        if (br.readBits(1) == 1) {
            int lengthNBits = 2 + 2 * br.readBits(3);
            maxSymbol = Math.min(alphabetSize, 2 + br.readBits(lengthNBits));
        }

        int[] codeLengths = new int[alphabetSize];
        int prevCodeLen = 8;
        int symbol = 0;
        while (symbol < alphabetSize && maxSymbol > 0) {
            maxSymbol--;
            int codeLen = codeLenTree.readSymbol(br);
            if (codeLen < 16) {
                codeLengths[symbol++] = codeLen;
                if (codeLen != 0) {
                    prevCodeLen = codeLen;
                }
            } else if (codeLen == 16) {
                int repeat = br.readBits(2) + 3;
                for (int k = 0; k < repeat && symbol < alphabetSize; k++) {
                    codeLengths[symbol++] = prevCodeLen;
                }
            } else if (codeLen == 17) {
                int repeat = br.readBits(3) + 3;
                symbol += repeat;
            } else if (codeLen == 18) {
                int repeat = br.readBits(7) + 11;
                symbol += repeat;
            }
        }
        return HuffTree.fromLengths(codeLengths);
    }

    private static final class Vp8LBitReader {
        private final byte[] data;
        private final int end;
        private int bytePos;
        private long bitBuf;
        private int bitCount;

        Vp8LBitReader(byte[] data, int start, int length) {
            this.data = data;
            this.bytePos = start;
            this.end = Math.min(data.length, start + length);
        }

        int readBits(int n) {
            while (bitCount < n && bytePos < end) {
                bitBuf |= ((long) (data[bytePos++] & 0xFF)) << bitCount;
                bitCount += 8;
            }
            int val = (int) (bitBuf & ((1L << n) - 1L));
            bitBuf >>>= n;
            bitCount = Math.max(0, bitCount - n);
            return val;
        }
    }

    // =========================================================================
    // VP8 (Lossy WebP Intra-frame - RFC 6386) Decoder
    // =========================================================================

    private static final int[] ZIGZAG = {
            0, 1, 4, 8, 5, 2, 3, 6, 9, 12, 13, 10, 7, 11, 14, 15
    };

    private static final int[] COEF_BANDS = {
            0, 1, 2, 3, 6, 4, 5, 6, 6, 6, 6, 6, 6, 6, 6, 7
    };

    private static final int[] DC_QLOOKUP = {
            4, 5, 6, 7, 8, 9, 10, 10, 11, 12, 13, 14, 15, 16, 17, 17,
            18, 19, 20, 20, 21, 21, 22, 22, 23, 23, 24, 25, 25, 26, 27, 28,
            29, 30, 31, 32, 33, 34, 35, 36, 37, 37, 38, 39, 40, 41, 42, 43,
            44, 45, 46, 46, 47, 48, 49, 50, 51, 52, 53, 54, 55, 56, 57, 58,
            59, 60, 61, 62, 63, 64, 65, 66, 67, 68, 69, 70, 71, 72, 73, 74,
            75, 76, 76, 77, 78, 79, 80, 81, 82, 83, 84, 85, 86, 87, 88, 89,
            91, 93, 95, 96, 98, 100, 101, 102, 104, 106, 108, 110, 112, 114, 116, 118,
            122, 124, 126, 128, 130, 132, 134, 136, 138, 140, 143, 145, 148, 151, 154, 157
    };

    private static final int[] AC_QLOOKUP = {
            4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19,
            20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35,
            36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51,
            52, 53, 54, 55, 56, 57, 58, 60, 62, 64, 66, 68, 70, 72, 74, 76,
            78, 80, 82, 84, 86, 88, 90, 92, 94, 96, 98, 100, 102, 104, 106, 108,
            110, 112, 114, 116, 119, 122, 125, 128, 131, 134, 137, 140, 143, 146, 149, 152,
            155, 158, 161, 164, 167, 170, 173, 177, 181, 185, 189, 193, 197, 201, 205, 209,
            213, 217, 221, 225, 229, 234, 239, 245, 249, 254, 259, 264, 269, 274, 279, 284
    };

    private static final int[] KF_YMODE_TREE = {-4, 2, -2, 4, -0, -1, -3};
    private static final int[] KF_YMODE_PROBS = {145, 156, 163, 128};
    private static final int[] KF_UV_MODE_TREE = {-0, 2, -1, 4, -2, -3};
    private static final int[] KF_UV_MODE_PROBS = {142, 114, 183};
    private static final int[] BMODE_TREE = {
            -0, 2, -1, 4, -2, 6, -3, 8, -4, 10, -5, 12, -6, 14, -7, 16, -8, -9
    };

    private static final int[][][][] DEFAULT_COEF_PROBS = initDefaultCoefProbs();

    private static final int[][][][] COEF_UPDATE_PROBS = initCoefUpdateProbs();

    private static final int[][][] KF_BMODE_PROBS = initKfBmodeProbs();

    private static BufferedImage decodeVp8(byte[] data, int start, int length) {
        try {
            if (length < 10) {
                return null;
            }
            int frameTag = (data[start] & 0xFF) | ((data[start + 1] & 0xFF) << 8) | ((data[start + 2] & 0xFF) << 16);
            int keyFrame = frameTag & 1;
            if (keyFrame != 0) {
                return null;
            }
            int firstPartSize = frameTag >>> 5;
            if ((data[start + 3] & 0xFF) != 0x9D || (data[start + 4] & 0xFF) != 0x01 || (data[start + 5] & 0xFF) != 0x2A) {
                return null;
            }
            int width = ((data[start + 6] & 0xFF) | ((data[start + 7] & 0xFF) << 8)) & 0x3FFF;
            int height = ((data[start + 8] & 0xFF) | ((data[start + 9] & 0xFF) << 8)) & 0x3FFF;
            if (width <= 0 || height <= 0 || start + 10 + firstPartSize > data.length) {
                return null;
            }

            Vp8BoolDecoder bd = new Vp8BoolDecoder(data, start + 10, firstPartSize);
            bd.readLiteral(1); // color_space
            bd.readLiteral(1); // clamping_type

            boolean segEnabled = bd.readBit(128) == 1;
            int[] segProb = {255, 255, 255};
            int[] segQp = new int[4];
            boolean segAbsDelta = false;
            if (segEnabled) {
                boolean updateMap = bd.readBit(128) == 1;
                boolean updateData = bd.readBit(128) == 1;
                if (updateData) {
                    segAbsDelta = bd.readBit(128) == 1;
                    for (int i = 0; i < 4; i++) {
                        if (bd.readBit(128) == 1) {
                            int v = bd.readLiteral(7);
                            segQp[i] = bd.readBit(128) == 1 ? -v : v;
                        }
                    }
                    for (int i = 0; i < 4; i++) {
                        if (bd.readBit(128) == 1) {
                            bd.readLiteral(6);
                            bd.readBit(128);
                        }
                    }
                }
                if (updateMap) {
                    for (int i = 0; i < 3; i++) {
                        if (bd.readBit(128) == 1) {
                            segProb[i] = bd.readLiteral(8);
                        }
                    }
                }
            }

            bd.readBit(128); // filter_type
            bd.readLiteral(6); // loop_filter_level
            bd.readLiteral(3); // sharpness_level
            if (bd.readBit(128) == 1) { // loop_filter_adj_enable
                if (bd.readBit(128) == 1) {
                    for (int i = 0; i < 8; i++) {
                        if (bd.readBit(128) == 1) {
                            bd.readLiteral(6);
                            bd.readBit(128);
                        }
                    }
                }
            }

            int log2Partitions = bd.readLiteral(2);
            int numPartitions = 1 << log2Partitions;

            int yAcQi = bd.readLiteral(7);
            int yDcDelta = readQDelta(bd);
            int y2DcDelta = readQDelta(bd);
            int y2AcDelta = readQDelta(bd);
            int uvDcDelta = readQDelta(bd);
            int uvAcDelta = readQDelta(bd);

            bd.readBit(128); // refresh_entropy_probs

            int[][][][] coefProbs = new int[4][8][3][11];
            for (int i = 0; i < 4; i++) {
                for (int j = 0; j < 8; j++) {
                    for (int k = 0; k < 3; k++) {
                        for (int l = 0; l < 11; l++) {
                            int p = DEFAULT_COEF_PROBS[i][j][k][l];
                            if (bd.readBit(COEF_UPDATE_PROBS[i][j][k][l]) == 1) {
                                p = bd.readLiteral(8);
                            }
                            coefProbs[i][j][k][l] = p;
                        }
                    }
                }
            }

            boolean mbNoSkipCoeff = bd.readBit(128) == 1;
            int probSkipFalse = mbNoSkipCoeff ? bd.readLiteral(8) : 0;

            // Set up token partition decoders
            int tokenStart = start + 10 + firstPartSize;
            int tableSize = 3 * (numPartitions - 1);
            Vp8BoolDecoder[] tokenDecoders = new Vp8BoolDecoder[numPartitions];
            int partOffset = tokenStart + tableSize;
            for (int p = 0; p < numPartitions; p++) {
                int pSize;
                if (p < numPartitions - 1) {
                    int off = tokenStart + p * 3;
                    pSize = (data[off] & 0xFF) | ((data[off + 1] & 0xFF) << 8) | ((data[off + 2] & 0xFF) << 16);
                } else {
                    pSize = Math.max(0, (start + length) - partOffset);
                }
                tokenDecoders[p] = new Vp8BoolDecoder(data, partOffset, pSize);
                partOffset += pSize;
            }

            int mbCols = (width + 15) >>> 4;
            int mbRows = (height + 15) >>> 4;
            int strideY = mbCols * 16;
            int strideUV = mbCols * 8;
            int[] planeY = new int[strideY * mbRows * 16];
            int[] planeU = new int[strideUV * mbRows * 8];
            int[] planeV = new int[strideUV * mbRows * 8];

            int[][] topBModes = new int[mbCols][4];
            int[][][] topNz = new int[mbCols][2][4]; // [0]=Y(4), [1]=UV(4) + [0][4]=Y2

            for (int mbY = 0; mbY < mbRows; mbY++) {
                int[] leftBModes = new int[4];
                int[] leftNzY = new int[4];
                int[] leftNzUV = new int[4];
                int leftNzY2 = 0;
                Vp8BoolDecoder td = tokenDecoders[mbY & (numPartitions - 1)];

                for (int mbX = 0; mbX < mbCols; mbX++) {
                    int segId = 0;
                    if (segEnabled) {
                        if (bd.readBit(segProb[0]) == 0) {
                            segId = bd.readBit(segProb[1]);
                        } else {
                            segId = 2 + bd.readBit(segProb[2]);
                        }
                    }
                    int mbSkip = mbNoSkipCoeff ? bd.readBit(probSkipFalse) : 0;
                    int yMode = bd.readTree(KF_YMODE_TREE, KF_YMODE_PROBS);
                    int[][] subModes = new int[4][4];

                    if (yMode == 4) { // B_PRED
                        for (int by = 0; by < 4; by++) {
                            for (int bx = 0; bx < 4; bx++) {
                                int aboveMode = (by == 0) ? topBModes[mbX][bx] : subModes[by - 1][bx];
                                int leftMode = (bx == 0) ? leftBModes[by] : subModes[by][bx - 1];
                                int bMode = bd.readTree(BMODE_TREE, KF_BMODE_PROBS[aboveMode][leftMode]);
                                subModes[by][bx] = bMode;
                            }
                        }
                        for (int i = 0; i < 4; i++) {
                            topBModes[mbX][i] = subModes[3][i];
                            leftBModes[i] = subModes[i][3];
                        }
                    } else {
                        int mapped = (yMode == 0) ? 0 : (yMode == 1) ? 2 : (yMode == 2) ? 3 : 1;
                        for (int i = 0; i < 4; i++) {
                            topBModes[mbX][i] = mapped;
                            leftBModes[i] = mapped;
                        }
                    }

                    int uvMode = bd.readTree(KF_UV_MODE_TREE, KF_UV_MODE_PROBS);

                    int baseQi = yAcQi;
                    if (segEnabled) {
                        baseQi = segAbsDelta ? segQp[segId] : (baseQi + segQp[segId]);
                    }
                    int yDcQ = DC_QLOOKUP[clampQi(baseQi + yDcDelta)];
                    int yAcQ = AC_QLOOKUP[clampQi(baseQi)];
                    int y2DcQ = DC_QLOOKUP[clampQi(baseQi + y2DcDelta)] * 2;
                    int y2AcQ = Math.max(8, AC_QLOOKUP[clampQi(baseQi + y2AcDelta)] * 155 / 100);
                    int uvDcQ = Math.min(132, DC_QLOOKUP[clampQi(baseQi + uvDcDelta)]);
                    int uvAcQ = AC_QLOOKUP[clampQi(baseQi + uvAcDelta)];

                    int[] y2Block = new int[16];
                    int[][] yBlocks = new int[16][16];
                    int[][] uBlocks = new int[4][16];
                    int[][] vBlocks = new int[4][16];

                    if (mbSkip == 0) {
                        if (yMode != 4) {
                            int ctx = leftNzY2 + topNz[mbX][1][3];
                            int nz = decodeBlockCoeffs(td, y2Block, coefProbs[1], 0, ctx, y2DcQ, y2AcQ);
                            leftNzY2 = topNz[mbX][1][3] = nz;
                            iwht4x4(y2Block);
                        }
                        int firstCoeff = (yMode == 4) ? 0 : 1;
                        int planeType = (yMode == 4) ? 3 : 0;
                        for (int by = 0; by < 4; by++) {
                            for (int bx = 0; bx < 4; bx++) {
                                int blkIdx = by * 4 + bx;
                                if (yMode != 4) {
                                    yBlocks[blkIdx][0] = y2Block[blkIdx];
                                }
                                int ctx = leftNzY[by] + topNz[mbX][0][bx];
                                int nz = decodeBlockCoeffs(td, yBlocks[blkIdx], coefProbs[planeType], firstCoeff, ctx, yDcQ, yAcQ);
                                leftNzY[by] = topNz[mbX][0][bx] = nz;
                            }
                        }
                        for (int by = 0; by < 2; by++) {
                            for (int bx = 0; bx < 2; bx++) {
                                int blkIdx = by * 2 + bx;
                                int ctx = leftNzUV[by] + topNz[mbX][1][bx];
                                int nz = decodeBlockCoeffs(td, uBlocks[blkIdx], coefProbs[2], 0, ctx, uvDcQ, uvAcQ);
                                leftNzUV[by] = topNz[mbX][1][bx] = nz;
                            }
                        }
                        for (int by = 0; by < 2; by++) {
                            for (int bx = 0; bx < 2; bx++) {
                                int blkIdx = by * 2 + bx;
                                int ctx = leftNzUV[by + 2] + topNz[mbX][1][bx + 2];
                                int nz = decodeBlockCoeffs(td, vBlocks[blkIdx], coefProbs[2], 0, ctx, uvDcQ, uvAcQ);
                                leftNzUV[by + 2] = topNz[mbX][1][bx + 2] = nz;
                            }
                        }
                    } else {
                        if (yMode != 4) {
                            leftNzY2 = topNz[mbX][1][3] = 0;
                        }
                        for (int i = 0; i < 4; i++) {
                            leftNzY[i] = topNz[mbX][0][i] = 0;
                            leftNzUV[i] = 0;
                        }
                        topNz[mbX][1][0] = topNz[mbX][1][1] = topNz[mbX][1][2] = 0;
                    }

                    // Reconstruct Y macroblock
                    reconstructMbLuma(planeY, strideY, mbX, mbY, yMode, subModes, yBlocks);
                    // Reconstruct U & V macroblocks
                    reconstructMbChroma(planeU, strideUV, mbX, mbY, uvMode, uBlocks);
                    reconstructMbChroma(planeV, strideUV, mbX, mbY, uvMode, vBlocks);
                }
            }

            BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int Y = planeY[y * strideY + x];
                    int U = planeU[(y >>> 1) * strideUV + (x >>> 1)] - 128;
                    int V = planeV[(y >>> 1) * strideUV + (x >>> 1)] - 128;
                    int r = clip255(Y + ((1436 * V) >> 10));
                    int g = clip255(Y - ((352 * U + 731 * V) >> 10));
                    int b = clip255(Y + ((1815 * U) >> 10));
                    img.setRGB(x, y, (0xFF << 24) | (r << 16) | (g << 8) | b);
                }
            }
            return img;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void reconstructMbLuma(int[] plane, int stride, int mbX, int mbY, int yMode, int[][] subModes, int[][] blocks) {
        int x0 = mbX * 16;
        int y0 = mbY * 16;
        if (yMode != 4) {
            predictBlock16x16(plane, stride, x0, y0, yMode);
            for (int by = 0; by < 4; by++) {
                for (int bx = 0; bx < 4; bx++) {
                    idct4x4Add(blocks[by * 4 + bx], plane, stride, x0 + bx * 4, y0 + by * 4);
                }
            }
        } else {
            for (int by = 0; by < 4; by++) {
                for (int bx = 0; bx < 4; bx++) {
                    int sx = x0 + bx * 4;
                    int sy = y0 + by * 4;
                    predictBlock4x4(plane, stride, sx, sy, subModes[by][bx]);
                    idct4x4Add(blocks[by * 4 + bx], plane, stride, sx, sy);
                }
            }
        }
    }

    private static void reconstructMbChroma(int[] plane, int stride, int mbX, int mbY, int uvMode, int[][] blocks) {
        int x0 = mbX * 8;
        int y0 = mbY * 8;
        predictBlock8x8(plane, stride, x0, y0, uvMode);
        for (int by = 0; by < 2; by++) {
            for (int bx = 0; bx < 2; bx++) {
                idct4x4Add(blocks[by * 2 + bx], plane, stride, x0 + bx * 4, y0 + by * 4);
            }
        }
    }

    private static void predictBlock16x16(int[] plane, int stride, int x0, int y0, int mode) {
        predictRegion(plane, stride, x0, y0, 16, mode);
    }

    private static void predictBlock8x8(int[] plane, int stride, int x0, int y0, int mode) {
        predictRegion(plane, stride, x0, y0, 8, mode);
    }

    private static void predictRegion(int[] plane, int stride, int x0, int y0, int size, int mode) {
        boolean hasTop = y0 > 0;
        boolean hasLeft = x0 > 0;
        int dc = 128;
        if (mode == 0) { // DC_PRED
            if (hasTop || hasLeft) {
                int sum = 0;
                int shift = (size == 16) ? 4 : 3;
                if (hasTop && hasLeft) {
                    for (int i = 0; i < size; i++) {
                        sum += plane[(y0 - 1) * stride + x0 + i] + plane[(y0 + i) * stride + x0 - 1];
                    }
                    dc = (sum + size) >>> (shift + 1);
                } else if (hasTop) {
                    for (int i = 0; i < size; i++) sum += plane[(y0 - 1) * stride + x0 + i];
                    dc = (sum + (size >>> 1)) >>> shift;
                } else {
                    for (int i = 0; i < size; i++) sum += plane[(y0 + i) * stride + x0 - 1];
                    dc = (sum + (size >>> 1)) >>> shift;
                }
            }
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    plane[(y0 + y) * stride + x0 + x] = dc;
                }
            }
        } else if (mode == 1) { // V_PRED
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    plane[(y0 + y) * stride + x0 + x] = hasTop ? plane[(y0 - 1) * stride + x0 + x] : 127;
                }
            }
        } else if (mode == 2) { // H_PRED
            for (int y = 0; y < size; y++) {
                int v = hasLeft ? plane[(y0 + y) * stride + x0 - 1] : 129;
                for (int x = 0; x < size; x++) {
                    plane[(y0 + y) * stride + x0 + x] = v;
                }
            }
        } else { // TM_PRED
            int tl = (hasTop && hasLeft) ? plane[(y0 - 1) * stride + x0 - 1] : (hasTop ? 129 : 127);
            for (int y = 0; y < size; y++) {
                int L = hasLeft ? plane[(y0 + y) * stride + x0 - 1] : 129;
                for (int x = 0; x < size; x++) {
                    int T = hasTop ? plane[(y0 - 1) * stride + x0 + x] : 127;
                    plane[(y0 + y) * stride + x0 + x] = clip255(L + T - tl);
                }
            }
        }
    }

    private static void predictBlock4x4(int[] plane, int stride, int x0, int y0, int bMode) {
        boolean hasTop = y0 > 0;
        boolean hasLeft = x0 > 0;
        int tl = (hasTop && hasLeft) ? plane[(y0 - 1) * stride + x0 - 1] : 128;
        int sum = 0;
        for (int i = 0; i < 4; i++) {
            sum += (hasTop ? plane[(y0 - 1) * stride + x0 + i] : 127)
                    + (hasLeft ? plane[(y0 + i) * stride + x0 - 1] : 129);
        }
        int dc = (sum + 4) >>> 3;
        for (int y = 0; y < 4; y++) {
            int L = hasLeft ? plane[(y0 + y) * stride + x0 - 1] : 129;
            for (int x = 0; x < 4; x++) {
                int T = hasTop ? plane[(y0 - 1) * stride + x0 + x] : 127;
                int v;
                switch (bMode) {
                    case 2: v = T; break; // B_VE
                    case 3: v = L; break; // B_HE
                    case 1: v = clip255(L + T - tl); break; // B_TM
                    default: v = dc; break;
                }
                plane[(y0 + y) * stride + x0 + x] = v;
            }
        }
    }

    private static void iwht4x4(int[] block) {
        int[] tmp = new int[16];
        for (int i = 0; i < 4; i++) {
            int a1 = block[i] + block[12 + i];
            int b1 = block[4 + i] + block[8 + i];
            int c1 = block[4 + i] - block[8 + i];
            int d1 = block[i] - block[12 + i];
            tmp[i] = a1 + b1;
            tmp[4 + i] = c1 + d1;
            tmp[8 + i] = a1 - b1;
            tmp[12 + i] = d1 - c1;
        }
        for (int i = 0; i < 4; i++) {
            int off = i * 4;
            int a1 = tmp[off] + tmp[off + 3];
            int b1 = tmp[off + 1] + tmp[off + 2];
            int c1 = tmp[off + 1] - tmp[off + 2];
            int d1 = tmp[off] - tmp[off + 3];
            block[off] = (a1 + b1 + 3) >> 3;
            block[off + 1] = (c1 + d1 + 3) >> 3;
            block[off + 2] = (a1 - b1 + 3) >> 3;
            block[off + 3] = (d1 - c1 + 3) >> 3;
        }
    }

    private static void idct4x4Add(int[] coeffs, int[] plane, int stride, int x0, int y0) {
        final int cospi8sqrt2minus1 = 20091;
        final int sinpi8sqrt2 = 35468;
        int[] tmp = new int[16];
        for (int i = 0; i < 4; i++) {
            int a1 = coeffs[i] + coeffs[8 + i];
            int b1 = coeffs[i] - coeffs[8 + i];
            int t1 = (coeffs[4 + i] * sinpi8sqrt2) >> 16;
            int t2 = coeffs[12 + i] + ((coeffs[12 + i] * cospi8sqrt2minus1) >> 16);
            int c1 = t1 - t2;
            t1 = coeffs[4 + i] + ((coeffs[4 + i] * cospi8sqrt2minus1) >> 16);
            t2 = (coeffs[12 + i] * sinpi8sqrt2) >> 16;
            int d1 = t1 + t2;
            tmp[i * 4] = a1 + d1;
            tmp[i * 4 + 3] = a1 - d1;
            tmp[i * 4 + 1] = b1 + c1;
            tmp[i * 4 + 2] = b1 - c1;
        }
        for (int i = 0; i < 4; i++) {
            int a1 = tmp[i] + tmp[8 + i];
            int b1 = tmp[i] - tmp[8 + i];
            int t1 = (tmp[4 + i] * sinpi8sqrt2) >> 16;
            int t2 = tmp[12 + i] + ((tmp[12 + i] * cospi8sqrt2minus1) >> 16);
            int c1 = t1 - t2;
            t1 = tmp[4 + i] + ((tmp[4 + i] * cospi8sqrt2minus1) >> 16);
            t2 = (tmp[12 + i] * sinpi8sqrt2) >> 16;
            int d1 = t1 + t2;
            int row = (y0 + i) * stride + x0;
            plane[row] = clip255(plane[row] + ((a1 + d1 + 4) >> 3));
            plane[row + 3] = clip255(plane[row + 3] + ((a1 - d1 + 4) >> 3));
            plane[row + 1] = clip255(plane[row + 1] + ((b1 + c1 + 4) >> 3));
            plane[row + 2] = clip255(plane[row + 2] + ((b1 - c1 + 4) >> 3));
        }
    }

    private static final int[][] CAT_PROBS = {
            {159},
            {165, 145},
            {173, 148, 140},
            {176, 155, 140, 135},
            {180, 157, 141, 134, 130},
            {254, 254, 243, 230, 196, 177, 153, 140, 133, 130, 129}
    };

    private static final int[] CAT_BASE = {5, 7, 11, 19, 35, 67};

    private static int decodeBlockCoeffs(
            Vp8BoolDecoder td,
            int[] outBlock,
            int[][][] probs,
            int firstCoeff,
            int ctx,
            int dcQ,
            int acQ
    ) {
        boolean skipZero = false;
        int hasNz = 0;
        for (int i = firstCoeff; i < 16; i++) {
            int[] p = probs[COEF_BANDS[i]][ctx];
            if (!skipZero) {
                if (td.readBit(p[0]) == 0) {
                    break; // EOB
                }
            }
            if (td.readBit(p[1]) == 0) {
                ctx = 0;
                skipZero = true;
                continue;
            }
            int val;
            if (td.readBit(p[2]) == 0) {
                val = 1;
                ctx = 1;
            } else {
                ctx = 2;
                if (td.readBit(p[3]) == 0) {
                    if (td.readBit(p[4]) == 0) {
                        val = 2;
                    } else {
                        val = 3 + td.readBit(p[5]);
                    }
                } else {
                    int cat;
                    if (td.readBit(p[6]) == 0) {
                        cat = (td.readBit(p[7]) == 0) ? 0 : 1;
                    } else {
                        if (td.readBit(p[8]) == 0) {
                            cat = 2 + td.readBit(p[9]);
                        } else {
                            cat = 4 + td.readBit(p[10]);
                        }
                    }
                    int extra = 0;
                    for (int prob : CAT_PROBS[cat]) {
                        extra = (extra << 1) | td.readBit(prob);
                    }
                    val = CAT_BASE[cat] + extra;
                }
            }
            if (td.readBit(128) == 1) {
                val = -val;
            }
            outBlock[ZIGZAG[i]] = val * (i == 0 ? dcQ : acQ);
            hasNz = 1;
            skipZero = false;
        }
        return hasNz;
    }

    private static int readQDelta(Vp8BoolDecoder bd) {
        if (bd.readBit(128) == 1) {
            int mag = bd.readLiteral(4);
            return bd.readBit(128) == 1 ? -mag : mag;
        }
        return 0;
    }

    private static int clampQi(int qi) {
        return qi < 0 ? 0 : (qi > 127 ? 127 : qi);
    }

    private static final class Vp8BoolDecoder {
        private final byte[] data;
        private final int end;
        private int pos;
        private int range = 255;
        private int value;
        private int bitCount;

        Vp8BoolDecoder(byte[] data, int start, int length) {
            this.data = data;
            this.pos = start;
            this.end = Math.min(data.length, start + length);
            this.value = ((pos < end ? (data[pos++] & 0xFF) : 0) << 8)
                    | (pos < end ? (data[pos++] & 0xFF) : 0);
            this.bitCount = 0;
        }

        int readBit(int prob) {
            int split = 1 + (((range - 1) * prob) >>> 8);
            int bigSplit = split << 8;
            int bit;
            if (value >= bigSplit) {
                range -= split;
                value -= bigSplit;
                bit = 1;
            } else {
                range = split;
                bit = 0;
            }
            while (range < 128) {
                value <<= 1;
                range <<= 1;
                if (++bitCount == 8) {
                    bitCount = 0;
                    if (pos < end) {
                        value |= data[pos++] & 0xFF;
                    }
                }
            }
            return bit;
        }

        int readLiteral(int numBits) {
            int v = 0;
            for (int i = numBits - 1; i >= 0; i--) {
                v = (v << 1) | readBit(128);
            }
            return v;
        }

        int readTree(int[] tree, int[] probs) {
            int i = 0;
            while ((i = tree[i + readBit(probs[i >>> 1])]) > 0) {
                // continue traversing tree
            }
            return -i;
        }
    }

    private static int[][][][] initDefaultCoefProbs() {
        int[] flat = {
                128,128,128,128,128,128,128,128,128,128,128,128,128,128,128,128,128,128,128,128,128,128,128,128,128,128,128,128,128,128,128,128,128,
                253,136,254,255,228,219,128,128,128,128,128,189,129,242,255,227,213,254,219,154,128,128,106,126,227,252,214,209,255,255,219,196,157,
                1,98,248,255,236,226,255,255,128,128,128,181,133,238,254,221,234,255,154,128,128,128,78,134,202,247,198,180,255,219,180,152,154,
                1,185,249,255,243,255,128,128,128,128,128,184,150,247,255,236,224,128,128,128,128,128,77,110,216,255,236,230,128,128,128,128,128,
                1,101,251,255,241,255,128,128,128,128,128,170,139,241,252,236,209,255,255,128,128,128,37,116,196,243,228,255,255,255,128,128,128,
                1,204,254,255,245,255,128,128,128,128,128,207,160,250,255,238,128,128,128,128,128,128,102,103,231,255,211,171,128,128,128,128,128,
                1,152,252,255,240,255,128,128,128,128,128,177,135,243,255,234,225,128,128,128,128,128,80,129,211,255,194,224,128,128,128,128,128,
                1,1,128,128,128,128,128,128,128,128,128,1,1,128,128,128,128,128,128,128,128,128,1,1,128,128,128,128,128,128,128,128,128,

                198,35,237,223,193,187,162,160,145,155,62,131,45,198,221,172,176,220,157,152,221,1,68,47,146,208,149,167,221,162,255,223,128,
                1,149,241,255,221,224,255,255,128,128,128,184,141,234,253,222,220,255,199,128,128,128,81,99,181,242,176,190,249,202,255,255,128,
                1,129,232,253,214,197,242,196,255,255,128,99,121,210,250,201,198,255,202,128,128,128,23,91,163,242,170,187,247,210,255,255,128,
                1,200,246,255,234,255,128,128,128,128,128,109,178,241,255,231,245,255,255,128,128,128,44,130,201,253,205,192,255,255,128,128,128,
                1,132,239,251,219,209,255,165,128,128,128,94,136,225,251,218,190,255,255,128,128,128,22,100,174,245,186,161,255,199,128,128,128,
                1,182,249,255,232,235,128,128,128,128,128,124,143,241,255,227,234,128,128,128,128,128,35,77,181,251,193,211,255,205,128,128,128,
                1,157,247,255,236,231,255,255,128,128,128,121,141,235,255,225,227,255,255,128,128,128,45,99,188,251,195,217,255,224,128,128,128,
                1,1,251,255,213,255,128,128,128,128,128,1,1,248,255,255,128,128,128,128,128,128,1,1,255,128,128,128,128,128,128,128,128,

                253,9,248,251,207,208,255,192,128,128,128,175,13,224,243,193,185,249,198,255,255,128,73,17,171,221,161,179,236,167,255,234,128,
                1,95,247,253,212,183,255,255,128,128,128,239,90,244,250,211,209,255,255,128,128,128,155,77,195,248,188,195,255,255,128,128,128,
                1,24,239,251,218,219,255,205,128,128,128,201,51,219,255,196,186,128,128,128,128,128,69,46,190,239,201,218,255,228,128,128,128,
                1,191,251,255,255,128,128,128,128,128,128,223,165,249,255,213,255,128,128,128,128,128,141,124,248,255,255,128,128,128,128,128,128,
                1,16,248,255,255,128,128,128,128,128,128,190,36,230,255,236,255,128,128,128,128,128,149,1,255,128,128,128,128,128,128,128,128,
                1,226,255,128,128,128,128,128,128,128,128,247,192,255,128,128,128,128,128,128,128,128,240,128,255,128,128,128,128,128,128,128,128,
                1,134,252,255,255,128,128,128,128,128,128,213,62,250,255,255,128,128,128,128,128,128,55,93,255,128,128,128,128,128,128,128,128,
                128,128,128,128,128,128,128,128,128,128,128,128,128,128,128,128,128,128,128,128,128,128,128,128,128,128,128,128,128,128,128,128,128,

                202,24,213,235,186,191,220,160,240,175,255,126,38,182,232,169,161,228,174,255,187,128,61,46,138,219,151,178,240,170,255,216,128,
                1,112,230,250,199,191,247,159,255,255,128,166,109,228,252,211,215,255,174,128,128,128,39,77,162,232,172,180,245,178,255,255,128,
                1,52,220,246,198,199,249,220,255,255,128,124,74,191,243,183,193,250,221,255,255,128,24,71,130,219,154,170,243,182,255,255,128,
                1,182,225,249,219,240,255,224,128,128,128,149,150,226,252,216,205,255,171,128,128,128,28,108,170,242,183,194,254,223,255,255,128,
                1,81,230,252,204,203,255,192,128,128,128,123,102,209,247,188,196,255,233,128,128,128,20,95,153,243,164,173,255,203,128,128,128,
                1,222,248,255,216,213,128,128,128,128,128,168,175,246,252,235,205,255,255,128,128,128,47,116,215,255,211,212,255,255,128,128,128,
                1,121,236,253,212,214,255,255,128,128,128,141,84,213,252,201,202,255,219,128,128,128,42,80,160,240,162,185,255,205,128,128,128,
                1,1,255,128,128,128,128,128,128,128,128,1,1,255,128,128,128,128,128,128,128,128,1,1,255,128,128,128,128,128,128,128,128
        };
        int[][][][] out = new int[4][8][3][11];
        int idx = 0;
        for (int i = 0; i < 4; i++)
            for (int j = 0; j < 8; j++)
                for (int k = 0; k < 3; k++)
                    for (int l = 0; l < 11; l++)
                        out[i][j][k][l] = flat[idx++];
        return out;
    }

    private static int[][][][] initCoefUpdateProbs() {
        int[] flat = {
                255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,
                176,246,255,255,255,255,255,255,255,255,255,223,241,252,255,255,255,255,255,255,255,255,249,253,253,255,255,255,255,255,255,255,255,
                255,244,252,255,255,255,255,255,255,255,255,234,254,254,255,255,255,255,255,255,255,255,253,255,255,255,255,255,255,255,255,255,255,
                255,246,254,255,255,255,255,255,255,255,255,239,253,254,255,255,255,255,255,255,255,255,254,255,254,255,255,255,255,255,255,255,255,
                255,248,254,255,255,255,255,255,255,255,255,251,255,254,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,
                255,253,254,255,255,255,255,255,255,255,255,251,254,254,255,255,255,255,255,255,255,255,254,255,254,255,255,255,255,255,255,255,255,
                255,254,253,255,254,255,255,255,255,255,255,250,255,254,255,254,255,255,255,255,255,255,254,255,255,255,255,255,255,255,255,255,255,
                255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,

                217,255,255,255,255,255,255,255,255,255,255,225,252,241,253,255,255,254,255,255,255,255,234,250,241,250,253,255,253,254,255,255,255,
                255,254,255,255,255,255,255,255,255,255,255,223,254,254,255,255,255,255,255,255,255,255,238,253,254,254,255,255,255,255,255,255,255,
                255,248,254,255,255,255,255,255,255,255,255,249,254,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,
                255,253,255,255,255,255,255,255,255,255,255,247,254,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,
                255,253,254,255,255,255,255,255,255,255,255,252,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,
                255,254,254,255,255,255,255,255,255,255,255,253,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,
                255,254,253,255,255,255,255,255,255,255,255,250,255,255,255,255,255,255,255,255,255,255,254,255,255,255,255,255,255,255,255,255,255,
                255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,

                186,251,250,255,255,255,255,255,255,255,255,234,251,244,254,255,255,255,255,255,255,255,251,251,243,253,254,255,254,255,255,255,255,
                255,253,254,255,255,255,255,255,255,255,255,236,253,254,255,255,255,255,255,255,255,255,251,253,253,254,254,255,255,255,255,255,255,
                255,254,254,255,255,255,255,255,255,255,255,254,254,254,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,
                255,254,255,255,255,255,255,255,255,255,255,254,254,255,255,255,255,255,255,255,255,255,254,255,255,255,255,255,255,255,255,255,255,
                255,255,255,255,255,255,255,255,255,255,255,254,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,
                255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,
                255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,
                255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,

                248,255,255,255,255,255,255,255,255,255,255,250,254,252,254,255,255,255,255,255,255,255,248,254,249,253,255,255,255,255,255,255,255,
                255,253,253,255,255,255,255,255,255,255,255,246,253,253,255,255,255,255,255,255,255,255,252,254,251,254,254,255,255,255,255,255,255,
                255,254,252,255,255,255,255,255,255,255,255,248,254,253,255,255,255,255,255,255,255,255,253,255,254,254,255,255,255,255,255,255,255,
                255,251,254,255,255,255,255,255,255,255,255,245,251,254,255,255,255,255,255,255,255,255,253,253,254,255,255,255,255,255,255,255,255,
                255,251,253,255,255,255,255,255,255,255,255,252,253,254,255,255,255,255,255,255,255,255,255,254,255,255,255,255,255,255,255,255,255,
                255,252,255,255,255,255,255,255,255,255,255,249,255,254,255,255,255,255,255,255,255,255,255,255,254,255,255,255,255,255,255,255,255,
                255,255,253,255,255,255,255,255,255,255,255,250,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,
                255,255,255,255,255,255,255,255,255,255,255,254,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255
        };
        int[][][][] out = new int[4][8][3][11];
        int idx = 0;
        for (int i = 0; i < 4; i++)
            for (int j = 0; j < 8; j++)
                for (int k = 0; k < 3; k++)
                    for (int l = 0; l < 11; l++)
                        out[i][j][k][l] = flat[idx++];
        return out;
    }

    private static int[][][] initKfBmodeProbs() {
        int[] flat = {
                231,120,48,89,115,113,120,152,112, 152,179,64,126,170,118,46,70,95, 175,69,143,80,85,82,72,155,103, 56,58,10,171,218,189,17,13,152, 144,71,10,38,171,213,144,34,26, 114,26,17,163,44,195,21,10,173, 121,24,80,195,26,62,44,64,85, 170,46,55,19,136,160,33,206,71, 63,20,8,114,114,208,12,9,226, 81,40,11,96,182,84,29,16,36,
                134,183,89,137,98,101,106,165,148, 72,187,100,130,157,111,32,75,80, 66,102,167,99,74,62,40,234,128, 41,53,9,178,241,141,26,8,107, 104,79,12,27,217,255,87,17,7, 74,43,26,146,73,166,49,23,157, 65,38,105,160,51,52,31,115,128, 87,68,71,44,114,51,15,186,23, 47,41,14,110,182,183,21,17,194, 66,45,25,102,197,189,23,18,22,
                88,88,147,150,42,46,45,196,205, 43,97,183,117,85,38,35,179,61, 39,53,200,87,26,21,43,232,171, 56,34,51,104,114,102,29,93,77, 107,54,32,26,51,1,81,43,31, 39,28,85,171,58,165,90,98,64, 34,22,116,206,23,34,43,166,73, 68,25,106,22,64,171,36,225,114, 34,19,21,102,132,188,16,76,124, 62,18,78,95,85,57,50,48,51,
                193,101,35,159,215,111,89,46,111, 60,148,31,172,219,228,21,18,111, 112,113,77,85,179,255,38,120,114, 40,42,1,196,245,209,10,25,109, 100,80,8,43,154,1,51,26,71, 88,43,29,140,166,213,37,43,154, 61,63,30,155,67,45,68,1,209, 142,78,78,16,255,128,34,197,171, 41,40,5,102,211,183,4,1,221, 51,50,17,168,209,192,23,25,82,
                125,98,42,88,104,85,117,175,82, 95,84,53,89,128,100,113,101,45, 75,79,123,47,51,128,81,171,1, 57,17,5,71,102,57,53,41,49, 115,21,2,10,102,255,166,23,6, 38,33,13,121,57,73,26,1,85, 41,10,67,138,77,110,90,47,114, 101,29,16,10,85,128,101,196,26, 57,18,10,102,102,213,34,20,43, 117,20,15,36,163,128,68,1,26,
                138,31,36,171,27,166,38,44,229, 67,87,58,169,82,115,26,59,179, 63,59,90,180,59,166,93,73,154, 40,40,21,116,143,209,34,39,175, 57,46,22,24,128,1,54,17,37, 47,15,16,183,34,223,49,45,183, 46,17,33,183,6,98,15,32,183, 65,32,73,115,28,128,23,128,205, 40,3,9,115,51,192,18,6,223, 87,37,9,115,59,77,64,21,47,
                104,55,44,218,9,54,53,130,226, 64,90,70,205,40,41,23,26,57, 54,57,112,184,5,41,38,166,213, 30,34,26,133,152,116,10,32,134, 75,32,12,51,192,255,160,43,51, 39,19,53,221,26,114,32,73,255, 31,9,65,234,2,15,1,118,73, 88,31,35,67,102,85,55,186,85, 56,21,23,111,59,205,45,37,192, 55,38,70,124,73,102,1,34,98,
                102,61,71,37,34,53,31,243,192, 69,60,71,38,73,119,28,222,37, 68,45,128,34,1,47,11,245,171, 62,17,19,70,146,85,55,62,70, 75,15,9,9,64,255,184,119,16, 37,43,37,154,100,163,85,160,1, 63,9,92,136,28,64,32,201,85, 86,6,28,5,64,255,25,248,1, 56,8,17,132,137,255,55,116,128, 58,15,20,82,135,57,26,121,40,
                164,50,31,137,154,133,25,35,218, 51,103,44,131,131,123,31,6,158, 86,40,64,135,148,224,45,183,128, 22,26,17,131,240,154,14,1,209, 83,12,13,54,192,255,68,47,28, 45,16,21,91,64,222,7,1,197, 56,21,39,155,60,138,23,102,213, 85,26,85,85,128,128,32,146,171, 18,11,7,63,144,171,4,4,246, 35,27,10,146,174,171,12,26,128,
                190,80,35,99,180,80,126,54,45, 85,126,47,87,176,51,41,20,32, 101,75,128,139,118,146,116,128,85, 56,41,15,176,236,85,37,9,62, 146,36,19,30,171,255,97,27,20, 71,30,17,119,118,255,17,18,138, 101,38,60,138,55,70,43,26,142, 138,45,61,62,219,1,81,188,64, 32,41,20,117,151,142,20,21,163, 112,19,12,61,195,128,48,4,24
        };
        int[][][] out = new int[10][10][9];
        int idx = 0;
        for (int i = 0; i < 10; i++)
            for (int j = 0; j < 10; j++)
                for (int k = 0; k < 9; k++)
                    out[i][j][k] = flat[idx++];
        return out;
    }

    private static int readLeInt(byte[] b, int off) {
        return (b[off] & 0xFF)
                | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16)
                | ((b[off + 3] & 0xFF) << 24);
    }
}
