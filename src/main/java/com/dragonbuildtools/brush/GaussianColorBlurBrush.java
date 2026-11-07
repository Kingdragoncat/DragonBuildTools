package com.dragonbuildtools.brush;

import com.dragonbuildtools.util.VanillaBlockAppearanceResolver;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.MaxChangedBlocksException;
import com.sk89q.worldedit.command.tool.brush.Brush;
import com.sk89q.worldedit.function.pattern.Pattern;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockType;
import java.util.HashSet;
import java.util.Set;

/**
 * 3D Gaussian Color Blur Brush ({@code //gblur} / {@code /dbr gblur}).
 *
 * <p>Computes a true 3D isotropic Gaussian color convolution over blocks inside the brush sphere
 * (ignoring air/liquids/plants via normalized separable 3-pass filtering) and maps each block to
 * the closest perceptual color while strictly preserving sub-block classes and state properties
 * (stairs keep their exact facing/half/corner shape, slabs keep top/bottom/double, walls/fences/panes
 * keep directional connections, trapdoors/gates/buttons/plates keep orientation and open/closed state).
 */
public final class GaussianColorBlurBrush implements Brush {

    private static final double MAX_BRUSH_RADIUS = 40.0;

    private final double sigma;
    private final double strength;
    private final boolean localPaletteOnly;
    private final boolean hardEdge;

    public GaussianColorBlurBrush(double sigma, double strength, boolean localPaletteOnly, boolean hardEdge) {
        this.sigma = Math.max(0.35, Math.min(6.0, sigma));
        this.strength = Math.max(0.05, Math.min(1.0, strength));
        this.localPaletteOnly = localPaletteOnly;
        this.hardEdge = hardEdge;
    }

    public double getSigma() {
        return sigma;
    }

    public double getStrength() {
        return strength;
    }

    public boolean isLocalPaletteOnly() {
        return localPaletteOnly;
    }

    public boolean isHardEdge() {
        return hardEdge;
    }

    @Override
    public void build(EditSession editSession, BlockVector3 position, Pattern pattern, double size)
            throws MaxChangedBlocksException {
        double radius = Math.max(1.0, Math.min(MAX_BRUSH_RADIUS, size));
        int rCeil = (int) Math.ceil(radius);
        int kernelRadius = computeKernelRadius(sigma);
        int pad = rCeil + kernelRadius;

        int cx = position.x();
        int cy = position.y();
        int cz = position.z();

        int worldMinY = editSession.getMinY();
        int worldMaxY = editSession.getMaxY();

        int minX = cx - pad;
        int maxX = cx + pad;
        int minY = Math.max(worldMinY, cy - pad);
        int maxY = Math.min(worldMaxY, cy + pad);
        int minZ = cz - pad;
        int maxZ = cz + pad;

        if (minY > maxY) {
            return;
        }

        int nx = maxX - minX + 1;
        int ny = maxY - minY + 1;
        int nz = maxZ - minZ + 1;
        int total = nx * ny * nz;

        BlockState[] snapshot = new BlockState[total];
        float[] origR = new float[total];
        float[] origG = new float[total];
        float[] origB = new float[total];
        float[] wr = new float[total];
        float[] wg = new float[total];
        float[] wb = new float[total];
        float[] ww = new float[total];

        Set<String> observedNames = localPaletteOnly ? new HashSet<>() : null;
        double rSq = radius * radius;
        boolean anySolidInSphere = false;

        // 1. Read immutable 3D snapshot (sphere + Gaussian kernel halo) before modifying any blocks
        for (int y = 0; y < ny; y++) {
            int wy = minY + y;
            int dy = wy - cy;
            for (int z = 0; z < nz; z++) {
                int wz = minZ + z;
                int dz = wz - cz;
                int rowBase = (y * nz + z) * nx;
                for (int x = 0; x < nx; x++) {
                    int wx = minX + x;
                    int dx = wx - cx;
                    int idx = rowBase + x;

                    BlockState st = editSession.getBlock(wx, wy, wz);
                    snapshot[idx] = st;

                    float w = (float) SubBlockRecolorUtil.getConvolutionWeight(st);
                    if (w > 0.0f) {
                        BlockType bt = st.getBlockType();
                        int[] rgb = VanillaBlockAppearanceResolver.getAverageRgb(bt);
                        origR[idx] = rgb[0];
                        origG[idx] = rgb[1];
                        origB[idx] = rgb[2];
                        wr[idx] = rgb[0] * w;
                        wg[idx] = rgb[1] * w;
                        wb[idx] = rgb[2] * w;
                        ww[idx] = w;

                        double distSq = (double) dx * dx + (double) dy * dy + (double) dz * dz;
                        if (distSq <= rSq) {
                            anySolidInSphere = true;
                            if (observedNames != null) {
                                observedNames.add(VanillaBlockAppearanceResolver.cleanBlockName(bt.id()));
                            }
                        }
                    }
                }
            }
        }

        if (!anySolidInSphere) {
            return;
        }

        Set<String> expandedLocalPalette = localPaletteOnly
                ? SubBlockRecolorUtil.expandLocalPaletteFamilies(observedNames)
                : null;

        // 2. Separable 3-pass 1D Gaussian convolution (exact 3D isotropic normalized Gaussian)
        convolve3DNormalizedGaussian(wr, wg, wb, ww, nx, ny, nz, sigma);

        // 3. Apply blurred colors inside the brush sphere while preserving exact sub-block geometry
        int sphereMinX = Math.max(0, (cx - rCeil) - minX);
        int sphereMaxX = Math.min(nx - 1, (cx + rCeil) - minX);
        int sphereMinY = Math.max(0, (cy - rCeil) - minY);
        int sphereMaxY = Math.min(ny - 1, (cy + rCeil) - minY);
        int sphereMinZ = Math.max(0, (cz - rCeil) - minZ);
        int sphereMaxZ = Math.min(nz - 1, (cz + rCeil) - minZ);

        for (int y = sphereMinY; y <= sphereMaxY; y++) {
            int wy = minY + y;
            int dy = wy - cy;
            for (int z = sphereMinZ; z <= sphereMaxZ; z++) {
                int wz = minZ + z;
                int dz = wz - cz;
                int dyzSq = dy * dy + dz * dz;
                if (dyzSq > rSq) {
                    continue;
                }
                int rowBase = (y * nz + z) * nx;
                for (int x = sphereMinX; x <= sphereMaxX; x++) {
                    int wx = minX + x;
                    int dx = wx - cx;
                    double distSq = dyzSq + dx * dx;
                    if (distSq > rSq) {
                        continue;
                    }

                    int idx = rowBase + x;
                    BlockState origState = snapshot[idx];
                    float blurredWeight = ww[idx];
                    if (origState == null || blurredWeight <= 1e-5f || SubBlockRecolorUtil.getConvolutionWeight(origState) <= 0.0) {
                        continue;
                    }

                    double bR = wr[idx] / blurredWeight;
                    double bG = wg[idx] / blurredWeight;
                    double bB = wb[idx] / blurredWeight;

                    double blend = strength;
                    if (!hardEdge && radius >= 2.5) {
                        double dist = Math.sqrt(distSq);
                        double featherStart = radius * 0.72;
                        if (dist > featherStart) {
                            double t = Math.max(0.0, Math.min(1.0, (radius - dist) / (radius - featherStart)));
                            double smoothstep = t * t * (3.0 - 2.0 * t);
                            blend *= smoothstep;
                        }
                    }

                    if (blend <= 0.02) {
                        continue;
                    }

                    int targetR = clamp255((int) Math.round(origR[idx] + (bR - origR[idx]) * blend));
                    int targetG = clamp255((int) Math.round(origG[idx] + (bG - origG[idx]) * blend));
                    int targetB = clamp255((int) Math.round(origB[idx] + (bB - origB[idx]) * blend));

                    BlockState recolored = SubBlockRecolorUtil.recolorPreservingSubBlock(
                            origState, targetR, targetG, targetB, expandedLocalPalette
                    );
                    if (recolored != null && !recolored.equals(origState)) {
                        editSession.setBlock(wx, wy, wz, recolored);
                    }
                }
            }
        }
    }

    public static int computeKernelRadius(double sigma) {
        double s = Math.max(0.35, Math.min(6.0, sigma));
        return Math.max(1, Math.min(12, (int) Math.ceil(s * 2.5)));
    }

    /**
     * In-place separable 3-pass 1D Gaussian convolution over weighted {@code (w*R, w*G, w*B, w)} channels.
     * Produces the exact 3D isotropic Gaussian integral while completely ignoring zero-weight air/empty cells.
     */
    public static void convolve3DNormalizedGaussian(
            float[] wr,
            float[] wg,
            float[] wb,
            float[] ww,
            int nx,
            int ny,
            int nz,
            double sigma
    ) {
        int k = computeKernelRadius(sigma);
        double clampedSigma = Math.max(0.35, Math.min(6.0, sigma));
        double invTwoSigmaSq = 1.0 / (2.0 * clampedSigma * clampedSigma);

        float[] kernel = new float[2 * k + 1];
        float kSum = 0.0f;
        for (int i = -k; i <= k; i++) {
            float g = (float) Math.exp(-(i * i) * invTwoSigmaSq);
            kernel[i + k] = g;
            kSum += g;
        }
        for (int i = 0; i < kernel.length; i++) {
            kernel[i] /= kSum;
        }

        int total = nx * ny * nz;
        float[] tmpR = new float[total];
        float[] tmpG = new float[total];
        float[] tmpB = new float[total];
        float[] tmpW = new float[total];

        // Pass 1: Convolve along X (stride = 1)
        for (int y = 0; y < ny; y++) {
            for (int z = 0; z < nz; z++) {
                int row = (y * nz + z) * nx;
                for (int x = 0; x < nx; x++) {
                    float sr = 0, sg = 0, sb = 0, sw = 0;
                    int dMin = Math.max(-k, -x);
                    int dMax = Math.min(k, nx - 1 - x);
                    for (int d = dMin; d <= dMax; d++) {
                        float kv = kernel[d + k];
                        int si = row + x + d;
                        float w = ww[si];
                        if (w > 0.0f) {
                            sr += wr[si] * kv;
                            sg += wg[si] * kv;
                            sb += wb[si] * kv;
                            sw += w * kv;
                        }
                    }
                    int di = row + x;
                    tmpR[di] = sr;
                    tmpG[di] = sg;
                    tmpB[di] = sb;
                    tmpW[di] = sw;
                }
            }
        }

        // Pass 2: Convolve along Z (stride = nx) into wr/wg/wb/ww
        for (int y = 0; y < ny; y++) {
            int yBase = y * nz * nx;
            for (int z = 0; z < nz; z++) {
                int dMin = Math.max(-k, -z);
                int dMax = Math.min(k, nz - 1 - z);
                int dstRow = yBase + z * nx;
                for (int x = 0; x < nx; x++) {
                    float sr = 0, sg = 0, sb = 0, sw = 0;
                    for (int d = dMin; d <= dMax; d++) {
                        float kv = kernel[d + k];
                        int si = yBase + (z + d) * nx + x;
                        float w = tmpW[si];
                        if (w > 0.0f) {
                            sr += tmpR[si] * kv;
                            sg += tmpG[si] * kv;
                            sb += tmpB[si] * kv;
                            sw += w * kv;
                        }
                    }
                    int di = dstRow + x;
                    wr[di] = sr;
                    wg[di] = sg;
                    wb[di] = sb;
                    ww[di] = sw;
                }
            }
        }

        // Pass 3: Convolve along Y (stride = nz * nx) from wr->tmp, then copy back
        int yStride = nz * nx;
        for (int y = 0; y < ny; y++) {
            int dMin = Math.max(-k, -y);
            int dMax = Math.min(k, ny - 1 - y);
            int dstSlice = y * yStride;
            for (int z = 0; z < nz; z++) {
                int zOff = z * nx;
                for (int x = 0; x < nx; x++) {
                    float sr = 0, sg = 0, sb = 0, sw = 0;
                    int xz = zOff + x;
                    for (int d = dMin; d <= dMax; d++) {
                        float kv = kernel[d + k];
                        int si = (y + d) * yStride + xz;
                        float w = ww[si];
                        if (w > 0.0f) {
                            sr += wr[si] * kv;
                            sg += wg[si] * kv;
                            sb += wb[si] * kv;
                            sw += w * kv;
                        }
                    }
                    int di = dstSlice + xz;
                    tmpR[di] = sr;
                    tmpG[di] = sg;
                    tmpB[di] = sb;
                    tmpW[di] = sw;
                }
            }
        }

        System.arraycopy(tmpR, 0, wr, 0, total);
        System.arraycopy(tmpG, 0, wg, 0, total);
        System.arraycopy(tmpB, 0, wb, 0, total);
        System.arraycopy(tmpW, 0, ww, 0, total);
    }

    private static int clamp255(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }
}
