package com.dragonbuildtools.demo;

import com.dragonbuildtools.animation.VideoAndGifEngine;
import com.dragonbuildtools.converter.HeightmapTerrainGenerator;
import com.dragonbuildtools.converter.ImagePixelArtConverter;
import com.dragonbuildtools.converter.Model3DVoxelizer;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;

/**
 * Generates zero-setup starter demo assets into {@code plugins/DragonBuildTools/} on first startup
 * so users can test 3D models, wing-flapping animations, curved GIF screens, heightmaps, and pixel art immediately.
 */
public final class DemoAssetGenerator {

    public static final String DEMO_DRAGON_MODEL = "dragon_statue.obj";
    public static final String DEMO_KNOT_MODEL = "crystal_knot.obj";
    public static final String DEMO_SYNTHWAVE_GIF = "synthwave_sunset.gif";
    public static final String DEMO_ALPINE_HEIGHTMAP = "alpine_island.png";
    public static final String DEMO_CREST_IMAGE = "dragon_crest.png";

    private DemoAssetGenerator() {
    }

    /**
     * Ensures starter demo files exist in the plugin's asset directories without overwriting user modifications.
     */
    public static int ensureStarterAssets(boolean overwrite) {
        int created = 0;
        try {
            File modelsDir = Model3DVoxelizer.getModelsDirectory();
            File dragonObj = new File(modelsDir, DEMO_DRAGON_MODEL);
            if (overwrite || !dragonObj.exists()) {
                writeDragonModel(modelsDir);
                created++;
            }
            File knotObj = new File(modelsDir, DEMO_KNOT_MODEL);
            if (overwrite || !knotObj.exists()) {
                writeCrystalKnotModel(modelsDir);
                created++;
            }

            File videosDir = VideoAndGifEngine.getVideosDirectory();
            File synthGif = new File(videosDir, DEMO_SYNTHWAVE_GIF);
            if (overwrite || !synthGif.exists()) {
                writeSynthwaveGif(synthGif);
                created++;
            }

            File hmapDir = HeightmapTerrainGenerator.getHeightmapsDirectory();
            File alpinePng = new File(hmapDir, DEMO_ALPINE_HEIGHTMAP);
            if (overwrite || !alpinePng.exists()) {
                writeAlpineHeightmap(alpinePng);
                created++;
            }

            File imgDir = ImagePixelArtConverter.getImagesDirectory();
            File crestPng = new File(imgDir, DEMO_CREST_IMAGE);
            if (overwrite || !crestPng.exists()) {
                writeDragonCrestImage(crestPng);
                created++;
            }
        } catch (IOException ignored) {
        }
        return created;
    }

    public static void writeDragonModel(File modelsDir) throws IOException {
        if (!modelsDir.exists()) {
            modelsDir.mkdirs();
        }
        File mtlFile = new File(modelsDir, "dragon_statue.mtl");
        File objFile = new File(modelsDir, DEMO_DRAGON_MODEL);

        try (BufferedWriter mw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(mtlFile), StandardCharsets.UTF_8))) {
            mw.write("newmtl crimson_scales\nKd 0.7200 0.1400 0.1100\n\n");
            mw.write("newmtl dark_obsidian\nKd 0.1400 0.1200 0.1700\n\n");
            mw.write("newmtl gold_trim\nKd 0.9400 0.7600 0.2200\n\n");
            mw.write("newmtl wing_membrane\nKd 0.8800 0.3600 0.1200\n\n");
        }

        ObjBuilder b = new ObjBuilder();
        // Spine, neck, and tail tube along Z (-11 tail -> +8 head)
        List<double[]> spinePts = new ArrayList<>();
        List<Double> spineRad = new ArrayList<>();
        double[][] spineSpec = {
                {0, 3.8, -12.0, 0.35},
                {0, 3.2, -9.0, 0.65},
                {0, 3.0, -6.0, 1.10},
                {0, 3.4, -3.0, 1.75},
                {0, 3.8, 0.0, 2.25},
                {0, 4.1, 2.8, 2.05},
                {0, 5.4, 4.8, 1.35},
                {0, 7.2, 6.2, 1.15},
                {0, 8.2, 7.6, 1.35},
                {0, 7.9, 9.6, 0.85}
        };
        for (double[] s : spineSpec) {
            spinePts.add(new double[]{s[0], s[1], s[2]});
            spineRad.add(s[3]);
        }
        b.addTube("crimson_scales", spinePts, spineRad, 8);

        // Golden underbelly ridge
        b.addBox("gold_trim", -1.2, 2.0, -2.5, 1.2, 3.3, 3.2);

        // Four sturdy legs
        for (int sx : new int[]{-1, 1}) {
            for (double lz : new double[]{-2.2, 2.2}) {
                List<double[]> leg = List.of(
                        new double[]{sx * 1.6, 3.4, lz},
                        new double[]{sx * 2.1, 1.7, lz + 0.3},
                        new double[]{sx * 2.0, 0.0, lz + 0.6}
                );
                b.addTube("crimson_scales", leg, List.of(0.85, 0.65, 0.75), 6);
                b.addBox("dark_obsidian", sx * 2.0 - 0.6, 0.0, lz + 0.2, sx * 2.0 + 0.6, 0.5, lz + 1.5);
            }
        }

        // Twin swept-back obsidian horns & dorsal spikes
        for (int sx : new int[]{-1, 1}) {
            List<double[]> horn = List.of(
                    new double[]{sx * 0.65, 8.6, 7.4},
                    new double[]{sx * 1.35, 9.8, 5.8},
                    new double[]{sx * 1.85, 10.7, 4.1}
            );
            b.addTube("gold_trim", horn, List.of(0.42, 0.26, 0.08), 6);
        }
        for (double sz = -7.5; sz <= 4.5; sz += 2.0) {
            double sy = sz > 2.5 ? 5.2 : 4.2;
            b.addPyramid("gold_trim", 0.0, sy, sz, 0.45, 1.35);
        }

        // Wide articulated wings extending out to X = +/- 14
        for (int sx : new int[]{-1, 1}) {
            List<double[]> wingArm = List.of(
                    new double[]{sx * 1.5, 4.6, 1.8},
                    new double[]{sx * 5.5, 6.8, 2.8},
                    new double[]{sx * 10.2, 7.6, 1.4},
                    new double[]{sx * 14.0, 6.2, -1.2}
            );
            b.addTube("dark_obsidian", wingArm, List.of(0.55, 0.45, 0.35, 0.20), 6);

            // Thick wing membrane panels so voxelizer captures clean sloped wing surfaces
            double[][] lead = {
                    {sx * 1.5, 4.5, 1.8},
                    {sx * 5.5, 6.7, 2.8},
                    {sx * 10.2, 7.5, 1.4},
                    {sx * 14.0, 6.1, -1.2}
            };
            double[][] trail = {
                    {sx * 1.6, 3.8, -2.6},
                    {sx * 5.2, 5.1, -2.4},
                    {sx * 9.5, 5.6, -3.2},
                    {sx * 13.0, 4.8, -4.4}
            };
            for (int i = 0; i < lead.length - 1; i++) {
                b.addThickQuad("wing_membrane", lead[i], lead[i + 1], trail[i + 1], trail[i], 0.55);
            }
        }

        b.write(objFile, "dragon_statue.mtl");
    }

    public static void writeCrystalKnotModel(File modelsDir) throws IOException {
        if (!modelsDir.exists()) {
            modelsDir.mkdirs();
        }
        File mtlFile = new File(modelsDir, "crystal_knot.mtl");
        File objFile = new File(modelsDir, DEMO_KNOT_MODEL);

        try (BufferedWriter mw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(mtlFile), StandardCharsets.UTF_8))) {
            mw.write("newmtl prismarine_band\nKd 0.3600 0.6800 0.6200\n\n");
            mw.write("newmtl amethyst_band\nKd 0.5400 0.3800 0.7600\n\n");
            mw.write("newmtl quartz_band\nKd 0.9100 0.8900 0.8600\n\n");
        }

        ObjBuilder b = new ObjBuilder();
        int steps = 84;
        int sides = 10;
        double tubeR = 1.65;
        String[] mats = {"prismarine_band", "amethyst_band", "quartz_band"};

        double[][] ringPrev = null;
        double[][] ringFirst = null;
        for (int i = 0; i <= steps; i++) {
            double t = (2.0 * Math.PI * i) / steps;
            double[] p = torusKnotPoint(t);
            double[] pNext = torusKnotPoint(t + 0.01);
            double[] tang = normalize(sub(pNext, p));
            double[] up = Math.abs(tang[1]) > 0.9 ? new double[]{1, 0, 0} : new double[]{0, 1, 0};
            double[] n1 = normalize(cross(tang, up));
            double[] n2 = normalize(cross(tang, n1));

            double[][] ring = new double[sides][3];
            for (int j = 0; j < sides; j++) {
                double a = (2.0 * Math.PI * j) / sides;
                double ca = Math.cos(a) * tubeR;
                double sa = Math.sin(a) * tubeR;
                ring[j] = new double[]{
                        p[0] + n1[0] * ca + n2[0] * sa,
                        p[1] + n1[1] * ca + n2[1] * sa + 9.0,
                        p[2] + n1[2] * ca + n2[2] * sa
                };
            }
            if (i == 0) {
                ringFirst = ring;
            } else {
                String mat = mats[(i / 7) % mats.length];
                double[][] cur = (i == steps) ? ringFirst : ring;
                for (int j = 0; j < sides; j++) {
                    int jn = (j + 1) % sides;
                    b.addQuad(mat, ringPrev[j], ringPrev[jn], cur[jn], cur[j]);
                }
            }
            ringPrev = ring;
        }
        b.write(objFile, "crystal_knot.mtl");
    }

    private static double[] torusKnotPoint(double t) {
        double r = 5.5 + 2.2 * Math.cos(3.0 * t);
        return new double[]{
                r * Math.cos(2.0 * t),
                2.8 * Math.sin(3.0 * t),
                r * Math.sin(2.0 * t)
        };
    }

    public static void writeSynthwaveGif(File outGif) throws IOException {
        if (outGif.getParentFile() != null && !outGif.getParentFile().exists()) {
            outGif.getParentFile().mkdirs();
        }
        int w = 96;
        int h = 54;
        int frameCount = 20;

        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("gif");
        if (!writers.hasNext()) {
            return;
        }
        ImageWriter writer = writers.next();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(outGif)) {
            writer.setOutput(ios);
            writer.prepareWriteSequence(null);

            ImageWriteParam params = writer.getDefaultWriteParam();
            ImageTypeSpecifier typeSpec = ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_INT_ARGB);

            for (int f = 0; f < frameCount; f++) {
                BufferedImage img = renderSynthwaveFrame(w, h, f, frameCount);
                IIOMetadata metadata = writer.getDefaultImageMetadata(typeSpec, params);
                configureGifMetadata(metadata, 10, f == 0);
                writer.writeToSequence(new IIOImage(img, null, metadata), params);
            }
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
    }

    private static BufferedImage renderSynthwaveFrame(int w, int h, int frame, int totalFrames) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int horizon = (int) (h * 0.56);
        // Sky gradient
        g.setPaint(new GradientPaint(0, 0, new Color(18, 10, 46), 0, horizon, new Color(196, 36, 122)));
        g.fillRect(0, 0, w, horizon);

        // Glowing sun with animated scanlines
        int sunR = (int) (h * 0.30);
        int sunCx = w / 2;
        int sunCy = horizon - 2;
        g.setPaint(new GradientPaint(0, sunCy - sunR, new Color(255, 226, 70), 0, sunCy + sunR / 2, new Color(255, 62, 130)));
        g.fillOval(sunCx - sunR, sunCy - sunR, sunR * 2, sunR * 2);

        g.setColor(new Color(45, 14, 68));
        int stripeShift = (frame * 2) % 6;
        for (int sy = sunCy - 2; sy < horizon; sy += 5) {
            g.fillRect(sunCx - sunR, sy + (stripeShift / 2), sunR * 2, 2);
        }

        // Mountain silhouette
        Path2D mountains = new Path2D.Double();
        mountains.moveTo(0, horizon);
        for (int x = 0; x <= w; x += 6) {
            double mh = 4 + 7 * Math.abs(Math.sin(x * 0.09 + 0.5)) * Math.sin(x * 0.03 + 1.1);
            mountains.lineTo(x, horizon - Math.max(2, mh));
        }
        mountains.lineTo(w, horizon);
        mountains.closePath();
        g.setColor(new Color(22, 12, 42));
        g.fill(mountains);

        // Ground grid
        g.setColor(new Color(16, 8, 34));
        g.fillRect(0, horizon, w, h - horizon);

        g.setStroke(new BasicStroke(1.2f));
        g.setColor(new Color(0, 232, 255));
        for (int i = -6; i <= 6; i++) {
            int topX = sunCx + i * 4;
            int botX = sunCx + i * 20;
            g.drawLine(topX, horizon, botX, h);
        }

        double phase = (double) frame / totalFrames;
        g.setColor(new Color(255, 52, 185));
        for (int r = 0; r < 6; r++) {
            double t = (r + phase) / 6.0;
            int y = horizon + (int) (Math.pow(t, 1.8) * (h - horizon));
            if (y >= horizon && y < h) {
                g.drawLine(0, y, w, y);
            }
        }
        g.dispose();
        return img;
    }

    private static void configureGifMetadata(IIOMetadata metadata, int delayHundredthsSec, boolean firstFrame) {
        try {
            String format = metadata.getNativeMetadataFormatName();
            IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree(format);

            IIOMetadataNode gce = getOrCreateChild(root, "GraphicControlExtension");
            gce.setAttribute("disposalMethod", "none");
            gce.setAttribute("userInputFlag", "FALSE");
            gce.setAttribute("transparentColorFlag", "FALSE");
            gce.setAttribute("delayTime", Integer.toString(delayHundredthsSec));
            gce.setAttribute("transparentColorIndex", "0");

            if (firstFrame) {
                IIOMetadataNode appExtensions = getOrCreateChild(root, "ApplicationExtensions");
                IIOMetadataNode netscape = new IIOMetadataNode("ApplicationExtension");
                netscape.setAttribute("applicationID", "NETSCAPE");
                netscape.setAttribute("authenticationCode", "2.0");
                netscape.setUserObject(new byte[]{0x1, 0x0, 0x0});
                appExtensions.appendChild(netscape);
            }

            metadata.setFromTree(format, root);
        } catch (Throwable ignored) {
        }
    }

    private static IIOMetadataNode getOrCreateChild(IIOMetadataNode parent, String name) {
        for (int i = 0; i < parent.getLength(); i++) {
            if (parent.item(i).getNodeName().equalsIgnoreCase(name)) {
                return (IIOMetadataNode) parent.item(i);
            }
        }
        IIOMetadataNode node = new IIOMetadataNode(name);
        parent.appendChild(node);
        return node;
    }

    public static void writeAlpineHeightmap(File outPng) throws IOException {
        if (outPng.getParentFile() != null && !outPng.getParentFile().exists()) {
            outPng.getParentFile().mkdirs();
        }
        int size = 128;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                double nx = (x - size * 0.5) / (size * 0.45);
                double nz = (z - size * 0.5) / (size * 0.45);
                double radial = Math.sqrt(nx * nx + nz * nz);
                double islandMask = Math.max(0.0, 1.0 - Math.pow(radial, 2.1));

                double peak1 = Math.exp(-((nx + 0.22) * (nx + 0.22) + (nz + 0.12) * (nz + 0.12)) * 5.8);
                double peak2 = 0.82 * Math.exp(-((nx - 0.26) * (nx - 0.26) + (nz - 0.18) * (nz - 0.18)) * 6.5);
                double ridge = 0.45 * Math.exp(-Math.pow(nx * 0.8 - nz * 0.9, 2) * 8.0) * islandMask;
                double detail = 0.08 * Math.sin(x * 0.21) * Math.cos(z * 0.19)
                        + 0.05 * Math.sin((x + z) * 0.37);

                double h = Math.max(0.0, Math.min(1.0, (0.20 + 0.72 * Math.max(peak1, peak2) + ridge + detail) * islandMask));
                int v = (int) Math.round(h * 255.0);
                int argb = (255 << 24) | (v << 16) | (v << 8) | v;
                img.setRGB(x, z, argb);
            }
        }
        ImageIO.write(img, "png", outPng);
    }

    public static void writeDragonCrestImage(File outPng) throws IOException {
        if (outPng.getParentFile() != null && !outPng.getParentFile().exists()) {
            outPng.getParentFile().mkdirs();
        }
        int s = 64;
        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        g.setColor(new Color(18, 18, 22));
        g.fillOval(2, 2, s - 4, s - 4);
        g.setColor(new Color(230, 92, 0));
        g.setStroke(new BasicStroke(4.0f));
        g.drawOval(6, 6, s - 12, s - 12);

        Path2D head = new Path2D.Double();
        head.moveTo(22, 48);
        head.lineTo(30, 34);
        head.lineTo(18, 35);
        head.lineTo(16, 28);
        head.lineTo(30, 20);
        head.lineTo(46, 12);
        head.lineTo(40, 23);
        head.lineTo(50, 18);
        head.lineTo(42, 30);
        head.lineTo(44, 44);
        head.closePath();
        g.fill(head);

        g.setColor(Color.WHITE);
        g.fillOval(25, 25, 6, 4);
        g.dispose();
        ImageIO.write(img, "png", outPng);
    }

    private static double[] sub(double[] a, double[] b) {
        return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[]{
                a[1] * b[2] - a[2] * b[1],
                a[2] * b[0] - a[0] * b[2],
                a[0] * b[1] - a[1] * b[0]
        };
    }

    private static double[] normalize(double[] v) {
        double len = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        if (len < 1e-9) {
            return new double[]{0, 1, 0};
        }
        return new double[]{v[0] / len, v[1] / len, v[2] / len};
    }

    private static final class ObjBuilder {
        private final List<double[]> vertices = new ArrayList<>();
        private final List<String> lines = new ArrayList<>();
        private String currentMtl = null;

        private void useMtl(String mtl) {
            if (!mtl.equals(currentMtl)) {
                lines.add("usemtl " + mtl);
                currentMtl = mtl;
            }
        }

        private int addVert(double[] p) {
            vertices.add(p);
            return vertices.size();
        }

        void addQuad(String mtl, double[] a, double[] b, double[] c, double[] d) {
            useMtl(mtl);
            int ia = addVert(a);
            int ib = addVert(b);
            int ic = addVert(c);
            int id = addVert(d);
            lines.add("f " + ia + " " + ib + " " + ic + " " + id);
        }

        void addTri(String mtl, double[] a, double[] b, double[] c) {
            useMtl(mtl);
            int ia = addVert(a);
            int ib = addVert(b);
            int ic = addVert(c);
            lines.add("f " + ia + " " + ib + " " + ic);
        }

        void addBox(String mtl, double x0, double y0, double z0, double x1, double y1, double z1) {
            addQuad(mtl, new double[]{x0, y0, z0}, new double[]{x0, y0, z1}, new double[]{x0, y1, z1}, new double[]{x0, y1, z0});
            addQuad(mtl, new double[]{x1, y0, z1}, new double[]{x1, y0, z0}, new double[]{x1, y1, z0}, new double[]{x1, y1, z1});
            addQuad(mtl, new double[]{x0, y0, z0}, new double[]{x1, y0, z0}, new double[]{x1, y0, z1}, new double[]{x0, y0, z1});
            addQuad(mtl, new double[]{x0, y1, z1}, new double[]{x1, y1, z1}, new double[]{x1, y1, z0}, new double[]{x0, y1, z0});
            addQuad(mtl, new double[]{x1, y0, z0}, new double[]{x0, y0, z0}, new double[]{x0, y1, z0}, new double[]{x1, y1, z0});
            addQuad(mtl, new double[]{x0, y0, z1}, new double[]{x1, y0, z1}, new double[]{x1, y1, z1}, new double[]{x0, y1, z1});
        }

        void addPyramid(String mtl, double cx, double baseY, double cz, double halfBase, double height) {
            double[] top = {cx, baseY + height, cz};
            double[] p0 = {cx - halfBase, baseY, cz - halfBase};
            double[] p1 = {cx + halfBase, baseY, cz - halfBase};
            double[] p2 = {cx + halfBase, baseY, cz + halfBase};
            double[] p3 = {cx - halfBase, baseY, cz + halfBase};
            addQuad(mtl, p0, p1, p2, p3);
            addTri(mtl, p0, p1, top);
            addTri(mtl, p1, p2, top);
            addTri(mtl, p2, p3, top);
            addTri(mtl, p3, p0, top);
        }

        void addThickQuad(String mtl, double[] a, double[] b, double[] c, double[] d, double thickness) {
            double h = thickness * 0.5;
            double[] at = {a[0], a[1] + h, a[2]};
            double[] bt = {b[0], b[1] + h, b[2]};
            double[] ct = {c[0], c[1] + h, c[2]};
            double[] dt = {d[0], d[1] + h, d[2]};
            double[] ab = {a[0], a[1] - h, a[2]};
            double[] bb = {b[0], b[1] - h, b[2]};
            double[] cb = {c[0], c[1] - h, c[2]};
            double[] db = {d[0], d[1] - h, d[2]};
            addQuad(mtl, at, bt, ct, dt);
            addQuad(mtl, db, cb, bb, ab);
            addQuad(mtl, ab, bb, bt, at);
            addQuad(mtl, bb, cb, ct, bt);
            addQuad(mtl, cb, db, dt, ct);
            addQuad(mtl, db, ab, at, dt);
        }

        void addTube(String mtl, List<double[]> centers, List<Double> radii, int sides) {
            double[][] prevRing = null;
            for (int i = 0; i < centers.size(); i++) {
                double[] c = centers.get(i);
                double r = radii.get(i);
                double[] dir;
                if (i < centers.size() - 1) {
                    dir = normalize(sub(centers.get(i + 1), c));
                } else {
                    dir = normalize(sub(c, centers.get(i - 1)));
                }
                double[] up = Math.abs(dir[1]) > 0.9 ? new double[]{1, 0, 0} : new double[]{0, 1, 0};
                double[] n1 = normalize(cross(dir, up));
                double[] n2 = normalize(cross(dir, n1));

                double[][] ring = new double[sides][3];
                for (int s = 0; s < sides; s++) {
                    double a = (2.0 * Math.PI * s) / sides;
                    double ca = Math.cos(a) * r;
                    double sa = Math.sin(a) * r;
                    ring[s] = new double[]{
                            c[0] + n1[0] * ca + n2[0] * sa,
                            c[1] + n1[1] * ca + n2[1] * sa,
                            c[2] + n1[2] * ca + n2[2] * sa
                    };
                }
                if (prevRing != null) {
                    for (int s = 0; s < sides; s++) {
                        int sn = (s + 1) % sides;
                        addQuad(mtl, prevRing[s], prevRing[sn], ring[sn], ring[s]);
                    }
                }
                prevRing = ring;
            }
        }

        void write(File objFile, String mtlName) throws IOException {
            try (BufferedWriter ow = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(objFile), StandardCharsets.UTF_8))) {
                ow.write("# Dragon Build Tools Built-in Starter Model\n");
                ow.write("mtllib " + mtlName + "\n\n");
                for (double[] v : vertices) {
                    ow.write(String.format(Locale.US, "v %.4f %.4f %.4f\n", v[0], v[1], v[2]));
                }
                ow.write("\n");
                for (String ln : lines) {
                    ow.write(ln + "\n");
                }
            }
        }
    }
}
