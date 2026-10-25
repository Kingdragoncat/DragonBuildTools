package com.dragonbuildtools.converter;

import com.dragonbuildtools.DragonBuildToolsPlugin;
import com.dragonbuildtools.util.BlockColorMapper;
import com.dragonbuildtools.util.CardinalDirectionUtil;



import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.MaxChangedBlocksException;
import com.sk89q.worldedit.entity.Player;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.block.BaseBlock;
import com.sk89q.worldedit.world.block.BlockStateHolder;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.Paint;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.GeneralPath;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import javax.imageio.ImageIO;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Converts 2D images across all popular raster and vector formats
 * (PNG, JPEG/JPG, SVG/SVGZ, GIF, BMP, WEBP, TGA, PPM/PGM, ICO, TIFF)
 * into Minecraft block pixel art in-game.
 */
public final class ImagePixelArtConverter {

    private static final String[] SUPPORTED_EXTENSIONS = {
            ".png", ".jpg", ".jpeg", ".svg", ".svgz", ".gif", ".bmp", ".webp", ".tga", ".ppm", ".ico", ".tiff", ".tif"
    };

    private static final Map<String, Integer> CSS_NAMED_COLORS = new HashMap<>();

    static {
        initNamedColors();
    }

    private ImagePixelArtConverter() {
    }

    public static File getImagesDirectory() {
        File base = (DragonBuildToolsPlugin.getInstance() != null) ? DragonBuildToolsPlugin.getInstance().getDataFolder() : new File(".");
        File dir = new File(base, "images");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    /**
     * Resolves a local file inside {@code plugins/DragonBuildTools/images/} by exact name or automatic extension matching.
     */
    public static File resolveLocalImageFile(String source) {
        File dir = getImagesDirectory();
        File direct = new File(dir, source);
        if (direct.exists() && direct.isFile()) {
            return direct;
        }
        for (String ext : SUPPORTED_EXTENSIONS) {
            File candidate = new File(dir, source + ext);
            if (candidate.exists() && candidate.isFile()) {
                return candidate;
            }
        }
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isFile() && f.getName().equalsIgnoreCase(source)) {
                    return f;
                }
                String lower = f.getName().toLowerCase(Locale.ROOT);
                for (String ext : SUPPORTED_EXTENSIONS) {
                    if (lower.equals((source + ext).toLowerCase(Locale.ROOT))) {
                        return f;
                    }
                }
            }
        }
        File rawFile = new File(source);
        if (rawFile.exists() && rawFile.isFile()) {
            return rawFile;
        }
        return null;
    }

    public static BufferedImage loadImage(String source) throws IOException {
        return loadImage(source, 0, 0);
    }

    /**
     * Loads an image from a local file path, filename in {@code plugins/DragonBuildTools/images/}, or HTTP/HTTPS URL.
     */
    public static BufferedImage loadImage(String source, int preferredRenderWidth, int preferredRenderHeight) throws IOException {
        byte[] data;
        String hintName = source.toLowerCase(Locale.ROOT);
        if (source.startsWith("http://") || source.startsWith("https://")) {
            data = downloadBytes(source, 32 * 1024 * 1024);
        } else if (source.startsWith("data:image/")) {
            int comma = source.indexOf(',');
            if (comma < 0) {
                throw new IOException("Invalid data URI image.");
            }
            String meta = source.substring(0, comma).toLowerCase(Locale.ROOT);
            if (meta.contains("svg")) {
                hintName = "inline.svg";
            }
            String payload = source.substring(comma + 1).trim();
            if (meta.contains(";base64")) {
                data = Base64.getDecoder().decode(payload);
            } else {
                data = java.net.URLDecoder.decode(payload, StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8);
            }
        } else {
            File file = resolveLocalImageFile(source);
            if (file == null || !file.exists()) {
                throw new IOException("Image file not found: " + source + " (Place images in plugins/DragonBuildTools/images/ or use a URL)");
            }
            hintName = file.getName().toLowerCase(Locale.ROOT);
            try (InputStream in = new FileInputStream(file)) {
                data = readAllBytes(in, 32 * 1024 * 1024);
            }
        }
        return decodeImageBytes(data, hintName, preferredRenderWidth, preferredRenderHeight);
    }

    public static BufferedImage decodeImageBytes(byte[] data, String hintName) throws IOException {
        return decodeImageBytes(data, hintName, 0, 0);
    }

    /**
     * Decodes raw image bytes across SVG/SVGZ, PNG, JPEG, GIF, BMP, WEBP, TGA, PPM/PGM, and ICO formats.
     */
    public static BufferedImage decodeImageBytes(byte[] data, String hintName, int preferredWidth, int preferredHeight) throws IOException {
        if (data == null || data.length == 0) {
            throw new IOException("Empty image data.");
        }

        // Check GZIP magic (0x1F 0x8B) for .svgz
        if (data.length >= 2 && (data[0] & 0xFF) == 0x1F && (data[1] & 0xFF) == 0x8B) {
            try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(data))) {
                data = readAllBytes(gzip, 32 * 1024 * 1024);
                hintName = "decompressed.svg";
            } catch (IOException ignored) {
            }
        }

        // 1. Detect SVG by extension or XML/<svg content
        if (isSvgData(data, hintName)) {
            return renderSvg(data, preferredWidth, preferredHeight);
        }

        // 2. Detect ICO / CUR magic (00 00 01 00 or 00 00 02 00)
        if (data.length >= 6 && data[0] == 0 && data[1] == 0 && (data[2] == 1 || data[2] == 2) && data[3] == 0) {
            BufferedImage ico = decodeIco(data);
            if (ico != null) {
                return ico;
            }
        }

        // 3. Detect Netpbm PPM / PGM / PBM (P3, P6, P2, P5)
        if (data.length >= 3 && data[0] == 'P' && (data[1] >= '1' && data[1] <= '6') && Character.isWhitespace((char) data[2])) {
            BufferedImage ppm = decodePnm(data);
            if (ppm != null) {
                return ppm;
            }
        }

        // 4. Try standard ImageIO (handles PNG, JPEG, GIF, BMP, WBMP, TIFF, and WebP if plugin present)
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(data));
            if (img != null) {
                return toArgbBufferedImage(img);
            }
        } catch (Throwable ignored) {
        }

        // 5. Detect WebP (RIFF....WEBP) and run pure-Java WebP VP8L / VP8 decoder fallback
        if (data.length >= 12
                && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P') {
            BufferedImage webp = SimpleWebpDecoder.decode(data);
            if (webp != null) {
                return webp;
            }
        }

        // 6. Try TGA (Truevision Targa) decoder
        if (hintName != null && hintName.endsWith(".tga") || looksLikeTga(data)) {
            BufferedImage tga = decodeTga(data);
            if (tga != null) {
                return tga;
            }
        }

        throw new IOException("Unsupported or corrupted image format for: " + hintName);
    }

    /**
     * Scales an image to the target dimensions using high-quality bicubic interpolation.
     * If {@code targetHeight <= 0}, preserves the original aspect ratio.
     */
    public static BufferedImage resizeImage(BufferedImage src, int targetWidth, int targetHeight) {
        int srcW = Math.max(1, src.getWidth());
        int srcH = Math.max(1, src.getHeight());
        int w = Math.max(1, targetWidth);
        int h = (targetHeight > 0)
                ? targetHeight
                : Math.max(1, (int) Math.round((double) w * (double) srcH / (double) srcW));

        if (w == srcW && h == srcH && src.getType() == BufferedImage.TYPE_INT_ARGB) {
            return src;
        }

        // Progressive halving for large downscales to prevent aliasing
        BufferedImage current = toArgbBufferedImage(src);
        int curW = current.getWidth();
        int curH = current.getHeight();
        while (curW / 2 >= w && curH / 2 >= h) {
            curW = Math.max(w, curW / 2);
            curH = Math.max(h, curH / 2);
            BufferedImage step = new BufferedImage(curW, curH, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2 = step.createGraphics();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.drawImage(current, 0, 0, curW, curH, null);
            g2.dispose();
            current = step;
        }

        BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = dst.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(current, 0, 0, w, h, null);
        g.dispose();
        return dst;
    }

    /**
     * Converts a {@link BufferedImage} into a 2D grid of {@link BaseBlock}[width][height]
     * where y=0 is the TOP row and y=height-1 is the BOTTOM row.
     */
    public static BaseBlock[][] imageToBlocks(BufferedImage img, boolean dither, boolean allowGlass) {
        int width = img.getWidth();
        int height = img.getHeight();
        BaseBlock[][] grid = new BaseBlock[width][height];

        double[][] rBuf = new double[width][height];
        double[][] gBuf = new double[width][height];
        double[][] bBuf = new double[width][height];
        int[][] aBuf = new int[width][height];

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int argb = img.getRGB(x, y);
                aBuf[x][y] = (argb >>> 24) & 0xFF;
                rBuf[x][y] = (argb >>> 16) & 0xFF;
                gBuf[x][y] = (argb >>> 8) & 0xFF;
                bBuf[x][y] = argb & 0xFF;
            }
        }

        int[] matchedRgb = new int[3];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int a = aBuf[x][y];
                if (a < 32) {
                    grid[x][y] = null;
                    continue;
                }
                int r = clampByte(rBuf[x][y]);
                int g = clampByte(gBuf[x][y]);
                int b = clampByte(bBuf[x][y]);
                int packed = (a << 24) | (r << 16) | (g << 8) | b;

                BaseBlock block = BlockColorMapper.getNearestBlock(packed, allowGlass, matchedRgb);
                grid[x][y] = block;

                if (dither && block != null) {
                    double errR = r - matchedRgb[0];
                    double errG = g - matchedRgb[1];
                    double errB = b - matchedRgb[2];

                    // Floyd-Steinberg error diffusion
                    if (x + 1 < width) {
                        rBuf[x + 1][y] += errR * (7.0 / 16.0);
                        gBuf[x + 1][y] += errG * (7.0 / 16.0);
                        bBuf[x + 1][y] += errB * (7.0 / 16.0);
                    }
                    if (y + 1 < height) {
                        if (x - 1 >= 0) {
                            rBuf[x - 1][y + 1] += errR * (3.0 / 16.0);
                            gBuf[x - 1][y + 1] += errG * (3.0 / 16.0);
                            bBuf[x - 1][y + 1] += errB * (3.0 / 16.0);
                        }
                        rBuf[x][y + 1] += errR * (5.0 / 16.0);
                        gBuf[x][y + 1] += errG * (5.0 / 16.0);
                        bBuf[x][y + 1] += errB * (5.0 / 16.0);
                        if (x + 1 < width) {
                            rBuf[x + 1][y + 1] += errR * (1.0 / 16.0);
                            gBuf[x + 1][y + 1] += errG * (1.0 / 16.0);
                            bBuf[x + 1][y + 1] += errB * (1.0 / 16.0);
                        }
                    }
                }
            }
        }
        return grid;
    }

    /**
     * Places a 2D pixel art block grid into the world relative to the player's position and cardinal direction.
     */
    public static int placePixelArt(
            EditSession editSession,
            Player player,
            BaseBlock[][] grid,
            boolean flat,
            boolean centered
    ) throws MaxChangedBlocksException {
        int width = grid.length;
        int height = width > 0 ? grid[0].length : 0;
        if (width == 0 || height == 0) {
            return 0;
        }

        BlockVector3 basePos = player.getBlockIn().toBlockPoint();
        CardinalDirectionUtil dir = CardinalDirectionUtil.getCardinalDirection(player);
        if (dir == null) {
            dir = CardinalDirectionUtil.NORTH;
        }

        // Right-hand and Forward unit vectors on the X/Z plane from the player's perspective
        int rightX;
        int rightZ;
        int fwdX;
        int fwdZ;
        switch (dir) {
            case SOUTH:
                rightX = -1;
                rightZ = 0;
                fwdX = 0;
                fwdZ = 1;
                break;
            case EAST:
                rightX = 0;
                rightZ = 1;
                fwdX = 1;
                fwdZ = 0;
                break;
            case WEST:
                rightX = 0;
                rightZ = -1;
                fwdX = -1;
                fwdZ = 0;
                break;
            case NORTH:
            default:
                rightX = 1;
                rightZ = 0;
                fwdX = 0;
                fwdZ = -1;
                break;
        }

        int offsetRight = centered ? -(width / 2) : 0;
        int placed = 0;

        for (int ix = 0; ix < width; ix++) {
            for (int iy = 0; iy < height; iy++) {
                BaseBlock block = grid[ix][iy];
                if (block == null) {
                    continue;
                }
                int u = ix + offsetRight;
                // iy = 0 is top of image, iy = height - 1 is bottom of image
                int vFromBottom = (height - 1) - iy;

                BlockVector3 worldPos;
                if (flat) {
                    int offsetFwd = centered ? (vFromBottom - height / 2) : (vFromBottom + 2);
                    int wx = basePos.getBlockX() + u * rightX + offsetFwd * fwdX;
                    int wy = basePos.getBlockY();
                    int wz = basePos.getBlockZ() + u * rightZ + offsetFwd * fwdZ;
                    worldPos = BlockVector3.at(wx, wy, wz);
                } else {
                    int distAhead = 3;
                    int wx = basePos.getBlockX() + u * rightX + distAhead * fwdX;
                    int wy = basePos.getBlockY() + vFromBottom;
                    int wz = basePos.getBlockZ() + u * rightZ + distAhead * fwdZ;
                    worldPos = BlockVector3.at(wx, wy, wz);
                }
                editSession.setBlock(worldPos, (BlockStateHolder) block);
                placed++;
            }
        }
        return placed;
    }

    // =========================================================================
    // Pure-Java SVG Vector Parser & Rasterizer
    // =========================================================================

    private static boolean isSvgData(byte[] data, String hintName) {
        if (hintName != null && (hintName.endsWith(".svg") || hintName.endsWith(".svgz"))) {
            return true;
        }
        int len = Math.min(data.length, 512);
        String head = new String(data, 0, len, StandardCharsets.UTF_8).trim().toLowerCase(Locale.ROOT);
        return head.startsWith("<svg") || (head.startsWith("<?xml") && head.contains("<svg")) || head.contains("<!doctype svg");
    }

    public static BufferedImage renderSvg(byte[] svgBytes, int requestedWidth, int requestedHeight) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setValidating(false);
            try {
                factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
                factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
                factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            } catch (Throwable ignored) {
            }

            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setEntityResolver((publicId, systemId) -> new org.xml.sax.InputSource(new java.io.StringReader("")));
            Document doc = builder.parse(new ByteArrayInputStream(svgBytes));
            Element root = doc.getDocumentElement();
            if (root == null) {
                throw new IOException("Empty SVG document.");
            }

            double vbX = 0;
            double vbY = 0;
            double vbW = 0;
            double vbH = 0;
            String viewBox = root.getAttribute("viewBox");
            if (viewBox == null || viewBox.isEmpty()) {
                viewBox = root.getAttribute("viewbox");
            }
            if (viewBox != null && !viewBox.trim().isEmpty()) {
                double[] nums = parseNumberList(viewBox);
                if (nums.length >= 4) {
                    vbX = nums[0];
                    vbY = nums[1];
                    vbW = nums[2];
                    vbH = nums[3];
                }
            }

            double attrW = parseSvgLength(root.getAttribute("width"), vbW > 0 ? vbW : 256.0);
            double attrH = parseSvgLength(root.getAttribute("height"), vbH > 0 ? vbH : 256.0);
            if (vbW <= 0) {
                vbW = attrW > 0 ? attrW : 256.0;
            }
            if (vbH <= 0) {
                vbH = attrH > 0 ? attrH : 256.0;
            }

            int outW;
            int outH;
            if (requestedWidth > 0 && requestedHeight > 0) {
                outW = requestedWidth;
                outH = requestedHeight;
            } else if (requestedWidth > 0) {
                outW = requestedWidth;
                outH = Math.max(1, (int) Math.round(requestedWidth * (vbH / vbW)));
            } else {
                outW = Math.max(64, (int) Math.round(vbW));
                outH = Math.max(64, (int) Math.round(vbH));
            }

            // Render at 2x supersampled resolution if small, for crisp anti-aliased pixel art
            int renderW = outW < 256 ? outW * 2 : outW;
            int renderH = outH < 256 ? outH * 2 : outH;

            BufferedImage canvas = new BufferedImage(renderW, renderH, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2 = canvas.createGraphics();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            Map<String, Element> defsById = new HashMap<>();
            collectIds(root, defsById);

            AffineTransform baseTransform = new AffineTransform();
            baseTransform.scale((double) renderW / vbW, (double) renderH / vbH);
            baseTransform.translate(-vbX, -vbY);
            g2.transform(baseTransform);

            SvgStyle rootStyle = new SvgStyle();
            rootStyle.applyAttributes(root);
            renderSvgElement(g2, root, rootStyle, defsById);
            g2.dispose();

            if (renderW != outW || renderH != outH) {
                return resizeImage(canvas, outW, outH);
            }
            return canvas;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to parse SVG: " + e.getMessage(), e);
        }
    }

    private static void collectIds(Element el, Map<String, Element> map) {
        String id = el.getAttribute("id");
        if (id != null && !id.isEmpty()) {
            map.put(id, el);
        }
        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n instanceof Element) {
                collectIds((Element) n, map);
            }
        }
    }

    private static final class SvgStyle implements Cloneable {
        String fill = "#000000";
        String stroke = "none";
        double strokeWidth = 1.0;
        double opacity = 1.0;
        double fillOpacity = 1.0;
        double strokeOpacity = 1.0;
        String fillRule = "nonzero";
        String strokeLinecap = "butt";
        String strokeLinejoin = "miter";
        double fontSize = 16.0;
        String fontFamily = "SansSerif";
        String textAnchor = "start";
        boolean displayNone = false;

        @Override
        protected SvgStyle clone() {
            try {
                return (SvgStyle) super.clone();
            } catch (CloneNotSupportedException e) {
                return new SvgStyle();
            }
        }

        void applyAttributes(Element el) {
            applyKeyVal("fill", el.getAttribute("fill"));
            applyKeyVal("stroke", el.getAttribute("stroke"));
            applyKeyVal("stroke-width", el.getAttribute("stroke-width"));
            applyKeyVal("opacity", el.getAttribute("opacity"));
            applyKeyVal("fill-opacity", el.getAttribute("fill-opacity"));
            applyKeyVal("stroke-opacity", el.getAttribute("stroke-opacity"));
            applyKeyVal("fill-rule", el.getAttribute("fill-rule"));
            applyKeyVal("stroke-linecap", el.getAttribute("stroke-linecap"));
            applyKeyVal("stroke-linejoin", el.getAttribute("stroke-linejoin"));
            applyKeyVal("font-size", el.getAttribute("font-size"));
            applyKeyVal("font-family", el.getAttribute("font-family"));
            applyKeyVal("text-anchor", el.getAttribute("text-anchor"));
            applyKeyVal("display", el.getAttribute("display"));
            applyKeyVal("visibility", el.getAttribute("visibility"));

            String styleAttr = el.getAttribute("style");
            if (styleAttr != null && !styleAttr.isEmpty()) {
                for (String part : styleAttr.split(";")) {
                    int colon = part.indexOf(':');
                    if (colon > 0) {
                        String k = part.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                        String v = part.substring(colon + 1).trim();
                        applyKeyVal(k, v);
                    }
                }
            }
        }

        private void applyKeyVal(String key, String val) {
            if (val == null || val.isEmpty()) {
                return;
            }
            switch (key) {
                case "fill":
                    this.fill = val;
                    break;
                case "stroke":
                    this.stroke = val;
                    break;
                case "stroke-width":
                    this.strokeWidth = parseSvgLength(val, this.strokeWidth);
                    break;
                case "opacity":
                    this.opacity = clamp01(parseSvgLength(val, 1.0));
                    break;
                case "fill-opacity":
                    this.fillOpacity = clamp01(parseSvgLength(val, 1.0));
                    break;
                case "stroke-opacity":
                    this.strokeOpacity = clamp01(parseSvgLength(val, 1.0));
                    break;
                case "fill-rule":
                    this.fillRule = val.toLowerCase(Locale.ROOT);
                    break;
                case "stroke-linecap":
                    this.strokeLinecap = val.toLowerCase(Locale.ROOT);
                    break;
                case "stroke-linejoin":
                    this.strokeLinejoin = val.toLowerCase(Locale.ROOT);
                    break;
                case "font-size":
                    this.fontSize = parseSvgLength(val, this.fontSize);
                    break;
                case "font-family":
                    this.fontFamily = val.replace("'", "").replace("\"", "").trim();
                    break;
                case "text-anchor":
                    this.textAnchor = val.toLowerCase(Locale.ROOT);
                    break;
                case "display":
                    if ("none".equalsIgnoreCase(val)) {
                        this.displayNone = true;
                    }
                    break;
                case "visibility":
                    if ("hidden".equalsIgnoreCase(val) || "collapse".equalsIgnoreCase(val)) {
                        this.displayNone = true;
                    }
                    break;
                default:
                    break;
            }
        }
    }

    private static void renderSvgElement(Graphics2D g2, Element el, SvgStyle parentStyle, Map<String, Element> defsById) {
        String tag = el.getTagName();
        int colon = tag.indexOf(':');
        if (colon >= 0) {
            tag = tag.substring(colon + 1);
        }
        tag = tag.toLowerCase(Locale.ROOT);

        if ("defs".equals(tag) || "metadata".equals(tag) || "title".equals(tag) || "desc".equals(tag)
                || "style".equals(tag) || "lineargradient".equals(tag) || "radialgradient".equals(tag)
                || "clippath".equals(tag) || "mask".equals(tag) || "symbol".equals(tag)) {
            return;
        }

        SvgStyle style = parentStyle.clone();
        style.applyAttributes(el);
        if (style.displayNone) {
            return;
        }

        AffineTransform savedTransform = g2.getTransform();
        String transformAttr = el.getAttribute("transform");
        if (transformAttr != null && !transformAttr.isEmpty()) {
            AffineTransform local = parseSvgTransform(transformAttr);
            g2.transform(local);
        }

        Shape shape = null;
        switch (tag) {
            case "svg":
            case "g":
            case "a":
            case "switch": {
                NodeList children = el.getChildNodes();
                for (int i = 0; i < children.getLength(); i++) {
                    Node node = children.item(i);
                    if (node instanceof Element) {
                        renderSvgElement(g2, (Element) node, style, defsById);
                    }
                }
                break;
            }
            case "use": {
                String href = el.getAttribute("href");
                if (href == null || href.isEmpty()) {
                    href = el.getAttribute("xlink:href");
                }
                if (href != null && href.startsWith("#")) {
                    Element ref = defsById.get(href.substring(1));
                    if (ref != null) {
                        double ux = parseSvgLength(el.getAttribute("x"), 0);
                        double uy = parseSvgLength(el.getAttribute("y"), 0);
                        g2.translate(ux, uy);
                        renderSvgElement(g2, ref, style, defsById);
                    }
                }
                break;
            }
            case "rect": {
                double x = parseSvgLength(el.getAttribute("x"), 0);
                double y = parseSvgLength(el.getAttribute("y"), 0);
                double w = parseSvgLength(el.getAttribute("width"), 0);
                double h = parseSvgLength(el.getAttribute("height"), 0);
                double rx = parseSvgLength(el.getAttribute("rx"), 0);
                double ry = parseSvgLength(el.getAttribute("ry"), rx);
                if (rx == 0 && ry > 0) {
                    rx = ry;
                }
                if (w > 0 && h > 0) {
                    if (rx > 0 || ry > 0) {
                        shape = new RoundRectangle2D.Double(x, y, w, h, rx * 2, ry * 2);
                    } else {
                        shape = new Rectangle2D.Double(x, y, w, h);
                    }
                }
                break;
            }
            case "circle": {
                double cx = parseSvgLength(el.getAttribute("cx"), 0);
                double cy = parseSvgLength(el.getAttribute("cy"), 0);
                double r = parseSvgLength(el.getAttribute("r"), 0);
                if (r > 0) {
                    shape = new Ellipse2D.Double(cx - r, cy - r, r * 2, r * 2);
                }
                break;
            }
            case "ellipse": {
                double cx = parseSvgLength(el.getAttribute("cx"), 0);
                double cy = parseSvgLength(el.getAttribute("cy"), 0);
                double rx = parseSvgLength(el.getAttribute("rx"), 0);
                double ry = parseSvgLength(el.getAttribute("ry"), 0);
                if (rx > 0 && ry > 0) {
                    shape = new Ellipse2D.Double(cx - rx, cy - ry, rx * 2, ry * 2);
                }
                break;
            }
            case "line": {
                double x1 = parseSvgLength(el.getAttribute("x1"), 0);
                double y1 = parseSvgLength(el.getAttribute("y1"), 0);
                double x2 = parseSvgLength(el.getAttribute("x2"), 0);
                double y2 = parseSvgLength(el.getAttribute("y2"), 0);
                shape = new Line2D.Double(x1, y1, x2, y2);
                break;
            }
            case "polygon":
            case "polyline": {
                double[] pts = parseNumberList(el.getAttribute("points"));
                if (pts.length >= 4) {
                    Path2D.Double poly = new Path2D.Double(
                            "evenodd".equals(style.fillRule) ? Path2D.WIND_EVEN_ODD : Path2D.WIND_NON_ZERO
                    );
                    poly.moveTo(pts[0], pts[1]);
                    for (int i = 2; i + 1 < pts.length; i += 2) {
                        poly.lineTo(pts[i], pts[i + 1]);
                    }
                    if ("polygon".equals(tag)) {
                        poly.closePath();
                    }
                    shape = poly;
                }
                break;
            }
            case "path": {
                String d = el.getAttribute("d");
                if (d != null && !d.isEmpty()) {
                    shape = parseSvgPath(d, "evenodd".equals(style.fillRule) ? Path2D.WIND_EVEN_ODD : Path2D.WIND_NON_ZERO);
                }
                break;
            }
            case "text": {
                String textContent = el.getTextContent();
                if (textContent != null && !textContent.trim().isEmpty()) {
                    double tx = parseSvgLength(el.getAttribute("x"), 0);
                    double ty = parseSvgLength(el.getAttribute("y"), 0);
                    Font font = new Font(style.fontFamily, Font.PLAIN, Math.max(1, (int) Math.round(style.fontSize)));
                    shape = font.createGlyphVector(g2.getFontRenderContext(), textContent.trim()).getOutline((float) tx, (float) ty);
                    if ("middle".equals(style.textAnchor)) {
                        Rectangle2D bounds = shape.getBounds2D();
                        shape = AffineTransform.getTranslateInstance(-bounds.getWidth() / 2.0, 0).createTransformedShape(shape);
                    } else if ("end".equals(style.textAnchor)) {
                        Rectangle2D bounds = shape.getBounds2D();
                        shape = AffineTransform.getTranslateInstance(-bounds.getWidth(), 0).createTransformedShape(shape);
                    }
                }
                break;
            }
            case "image": {
                String href = el.getAttribute("href");
                if (href == null || href.isEmpty()) {
                    href = el.getAttribute("xlink:href");
                }
                if (href != null && !href.isEmpty()) {
                    try {
                        double ix = parseSvgLength(el.getAttribute("x"), 0);
                        double iy = parseSvgLength(el.getAttribute("y"), 0);
                        double iw = parseSvgLength(el.getAttribute("width"), 0);
                        double ih = parseSvgLength(el.getAttribute("height"), 0);
                        BufferedImage subImg = loadImage(href, (int) Math.max(1, iw), (int) Math.max(1, ih));
                        if (subImg != null) {
                            g2.drawImage(subImg, (int) Math.round(ix), (int) Math.round(iy),
                                    (int) Math.max(1, Math.round(iw)), (int) Math.max(1, Math.round(ih)), null);
                        }
                    } catch (Throwable ignored) {
                    }
                }
                break;
            }
            default:
                break;
        }

        if (shape != null) {
            // Fill shape (unless it's a <line>)
            if (!"line".equals(tag) && style.fill != null && !"none".equalsIgnoreCase(style.fill) && !"transparent".equalsIgnoreCase(style.fill)) {
                Paint fillPaint = resolveSvgPaint(style.fill, style.opacity * style.fillOpacity, shape.getBounds2D(), defsById);
                if (fillPaint != null) {
                    g2.setPaint(fillPaint);
                    g2.fill(shape);
                }
            }
            // Stroke shape
            if (style.stroke != null && !"none".equalsIgnoreCase(style.stroke) && !"transparent".equalsIgnoreCase(style.stroke) && style.strokeWidth > 0) {
                Paint strokePaint = resolveSvgPaint(style.stroke, style.opacity * style.strokeOpacity, shape.getBounds2D(), defsById);
                if (strokePaint != null) {
                    int cap = BasicStroke.CAP_BUTT;
                    if ("round".equals(style.strokeLinecap)) {
                        cap = BasicStroke.CAP_ROUND;
                    } else if ("square".equals(style.strokeLinecap)) {
                        cap = BasicStroke.CAP_SQUARE;
                    }
                    int join = BasicStroke.JOIN_MITER;
                    if ("round".equals(style.strokeLinejoin)) {
                        join = BasicStroke.JOIN_ROUND;
                    } else if ("bevel".equals(style.strokeLinejoin)) {
                        join = BasicStroke.JOIN_BEVEL;
                    }
                    g2.setPaint(strokePaint);
                    g2.setStroke(new BasicStroke((float) style.strokeWidth, cap, join));
                    g2.draw(shape);
                }
            }
        }

        g2.setTransform(savedTransform);
    }

    private static Paint resolveSvgPaint(String spec, double alphaMultiplier, Rectangle2D bounds, Map<String, Element> defsById) {
        if (spec == null) {
            return null;
        }
        spec = spec.trim();
        if (spec.startsWith("url(")) {
            int hash = spec.indexOf('#');
            int close = spec.indexOf(')');
            if (hash >= 0 && close > hash) {
                String id = spec.substring(hash + 1, close).replace("'", "").replace("\"", "").trim();
                Element gradEl = defsById.get(id);
                if (gradEl != null) {
                    Paint grad = buildSvgGradientPaint(gradEl, alphaMultiplier, bounds);
                    if (grad != null) {
                        return grad;
                    }
                }
            }
            return new Color(128, 128, 128, clampByte(alphaMultiplier * 255));
        }
        Color c = parseSvgColor(spec, alphaMultiplier);
        return c;
    }

    private static Paint buildSvgGradientPaint(Element gradEl, double alphaMultiplier, Rectangle2D bounds) {
        List<Float> fractions = new ArrayList<>();
        List<Color> colors = new ArrayList<>();
        NodeList children = gradEl.getChildNodes();
        float lastOffset = -0.001f;
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n instanceof Element) {
                Element stop = (Element) n;
                if (stop.getTagName().toLowerCase(Locale.ROOT).endsWith("stop")) {
                    String offStr = stop.getAttribute("offset").trim();
                    float offset = 0f;
                    if (offStr.endsWith("%")) {
                        offset = (float) (parseSvgLength(offStr.substring(0, offStr.length() - 1), 0) / 100.0);
                    } else {
                        offset = (float) parseSvgLength(offStr, 0);
                    }
                    offset = Math.max(0f, Math.min(1f, offset));
                    if (offset <= lastOffset) {
                        offset = Math.min(1f, lastOffset + 0.0001f);
                    }
                    lastOffset = offset;

                    String stopColor = stop.getAttribute("stop-color");
                    String stopOpacity = stop.getAttribute("stop-opacity");
                    String style = stop.getAttribute("style");
                    if (style != null) {
                        for (String part : style.split(";")) {
                            int c = part.indexOf(':');
                            if (c > 0) {
                                String k = part.substring(0, c).trim().toLowerCase(Locale.ROOT);
                                String v = part.substring(c + 1).trim();
                                if ("stop-color".equals(k)) {
                                    stopColor = v;
                                } else if ("stop-opacity".equals(k)) {
                                    stopOpacity = v;
                                }
                            }
                        }
                    }
                    double op = alphaMultiplier * (stopOpacity != null && !stopOpacity.isEmpty() ? parseSvgLength(stopOpacity, 1.0) : 1.0);
                    Color c = parseSvgColor(stopColor == null || stopColor.isEmpty() ? "#000000" : stopColor, op);
                    if (c != null) {
                        fractions.add(offset);
                        colors.add(c);
                    }
                }
            }
        }
        if (colors.isEmpty()) {
            return null;
        }
        if (colors.size() == 1) {
            return colors.get(0);
        }
        float[] fracArr = new float[fractions.size()];
        for (int i = 0; i < fractions.size(); i++) {
            fracArr[i] = fractions.get(i);
        }
        Color[] colArr = colors.toArray(new Color[0]);
        double bx = bounds.getWidth() > 0 ? bounds.getMinX() : 0;
        double by = bounds.getHeight() > 0 ? bounds.getMinY() : 0;
        double bw = bounds.getWidth() > 0 ? bounds.getWidth() : 100;
        double bh = bounds.getHeight() > 0 ? bounds.getHeight() : 100;

        String tag = gradEl.getTagName().toLowerCase(Locale.ROOT);
        if (tag.contains("radial")) {
            Point2D center = new Point2D.Double(bx + bw * 0.5, by + bh * 0.5);
            float radius = (float) Math.max(1.0, Math.max(bw, bh) * 0.5);
            return new RadialGradientPaint(center, radius, fracArr, colArr);
        } else {
            Point2D start = new Point2D.Double(bx, by);
            Point2D end = new Point2D.Double(bx + bw, by + bh);
            if (start.equals(end)) {
                return colArr[0];
            }
            return new LinearGradientPaint(start, end, fracArr, colArr);
        }
    }

    public static Color parseSvgColor(String str, double alphaMul) {
        if (str == null) {
            return null;
        }
        String s = str.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty() || "none".equals(s) || "transparent".equals(s)) {
            return null;
        }
        int alpha = clampByte(alphaMul * 255.0);
        if (s.startsWith("#")) {
            String hex = s.substring(1);
            try {
                if (hex.length() == 3) {
                    int r = Integer.parseInt(hex.substring(0, 1), 16) * 17;
                    int g = Integer.parseInt(hex.substring(1, 2), 16) * 17;
                    int b = Integer.parseInt(hex.substring(2, 3), 16) * 17;
                    return new Color(r, g, b, alpha);
                } else if (hex.length() == 6) {
                    int rgb = Integer.parseInt(hex, 16);
                    return new Color((rgb >>> 16) & 0xFF, (rgb >>> 8) & 0xFF, rgb & 0xFF, alpha);
                } else if (hex.length() == 8) {
                    long rgba = Long.parseLong(hex, 16);
                    int r = (int) ((rgba >>> 24) & 0xFF);
                    int g = (int) ((rgba >>> 16) & 0xFF);
                    int b = (int) ((rgba >>> 8) & 0xFF);
                    int a = clampByte((rgba & 0xFF) * alphaMul);
                    return new Color(r, g, b, a);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        if (s.startsWith("rgb(") || s.startsWith("rgba(")) {
            int open = s.indexOf('(');
            int close = s.indexOf(')');
            if (open > 0 && close > open) {
                String[] parts = s.substring(open + 1, close).split("[,\\s/]+");
                if (parts.length >= 3) {
                    int r = parseColorChannel(parts[0]);
                    int g = parseColorChannel(parts[1]);
                    int b = parseColorChannel(parts[2]);
                    int a = alpha;
                    if (parts.length >= 4) {
                        double aVal = parseSvgLength(parts[3], 1.0);
                        if (parts[3].endsWith("%")) {
                            aVal /= 100.0;
                        }
                        a = clampByte(aVal * alphaMul * 255.0);
                    }
                    return new Color(r, g, b, a);
                }
            }
        }
        if (s.startsWith("hsl(") || s.startsWith("hsla(")) {
            int open = s.indexOf('(');
            int close = s.indexOf(')');
            if (open > 0 && close > open) {
                double[] nums = parseNumberList(s.substring(open + 1, close));
                if (nums.length >= 3) {
                    float h = (float) ((nums[0] % 360.0) / 360.0);
                    if (h < 0) {
                        h += 1.0f;
                    }
                    float sat = (float) clamp01(nums[1] / 100.0);
                    float l = (float) clamp01(nums[2] / 100.0);
                    int rgb = hslToRgb(h, sat, l);
                    return new Color((rgb >>> 16) & 0xFF, (rgb >>> 8) & 0xFF, rgb & 0xFF, alpha);
                }
            }
        }
        Integer named = CSS_NAMED_COLORS.get(s);
        if (named != null) {
            int rgb = named;
            return new Color((rgb >>> 16) & 0xFF, (rgb >>> 8) & 0xFF, rgb & 0xFF, alpha);
        }
        return new Color(0, 0, 0, alpha);
    }

    private static int parseColorChannel(String part) {
        part = part.trim();
        if (part.endsWith("%")) {
            double pct = parseSvgLength(part.substring(0, part.length() - 1), 0);
            return clampByte(pct * 2.55);
        }
        return clampByte(parseSvgLength(part, 0));
    }

    private static int hslToRgb(float h, float s, float l) {
        float r, g, b;
        if (s == 0f) {
            r = g = b = l;
        } else {
            float q = l < 0.5f ? l * (1f + s) : l + s - l * s;
            float p = 2f * l - q;
            r = hueToRgb(p, q, h + 1f / 3f);
            g = hueToRgb(p, q, h);
            b = hueToRgb(p, q, h - 1f / 3f);
        }
        return (clampByte(r * 255) << 16) | (clampByte(g * 255) << 8) | clampByte(b * 255);
    }

    private static float hueToRgb(float p, float q, float t) {
        if (t < 0f) t += 1f;
        if (t > 1f) t -= 1f;
        if (t < 1f / 6f) return p + (q - p) * 6f * t;
        if (t < 1f / 2f) return q;
        if (t < 2f / 3f) return p + (q - p) * (2f / 3f - t) * 6f;
        return p;
    }

    /**
     * Complete SVG {@code <path d="...">} parser supporting M, L, H, V, C, S, Q, T, A, and Z (both absolute & relative).
     */
    public static Path2D.Double parseSvgPath(String d, int windingRule) {
        Path2D.Double path = new Path2D.Double(windingRule);
        int i = 0;
        int len = d.length();
        char cmd = 0;
        double curX = 0, curY = 0;
        double startX = 0, startY = 0;
        double lastCtrlX = 0, lastCtrlY = 0;
        char prevCmd = 0;

        while (i < len) {
            while (i < len && (Character.isWhitespace(d.charAt(i)) || d.charAt(i) == ',')) {
                i++;
            }
            if (i >= len) {
                break;
            }
            char c = d.charAt(i);
            if (Character.isLetter(c)) {
                cmd = c;
                i++;
            } else if (cmd == 0) {
                break;
            }

            switch (cmd) {
                case 'M':
                case 'm': {
                    double[] xOut = new double[1];
                    double[] yOut = new double[1];
                    i = readNextNumber(d, i, xOut);
                    i = readNextNumber(d, i, yOut);
                    curX = (cmd == 'm') ? curX + xOut[0] : xOut[0];
                    curY = (cmd == 'm') ? curY + yOut[0] : yOut[0];
                    startX = curX;
                    startY = curY;
                    path.moveTo(curX, curY);
                    cmd = (cmd == 'm') ? 'l' : 'L';
                    break;
                }
                case 'L':
                case 'l': {
                    double[] xOut = new double[1];
                    double[] yOut = new double[1];
                    i = readNextNumber(d, i, xOut);
                    i = readNextNumber(d, i, yOut);
                    curX = (cmd == 'l') ? curX + xOut[0] : xOut[0];
                    curY = (cmd == 'l') ? curY + yOut[0] : yOut[0];
                    path.lineTo(curX, curY);
                    break;
                }
                case 'H':
                case 'h': {
                    double[] xOut = new double[1];
                    i = readNextNumber(d, i, xOut);
                    curX = (cmd == 'h') ? curX + xOut[0] : xOut[0];
                    path.lineTo(curX, curY);
                    break;
                }
                case 'V':
                case 'v': {
                    double[] yOut = new double[1];
                    i = readNextNumber(d, i, yOut);
                    curY = (cmd == 'v') ? curY + yOut[0] : yOut[0];
                    path.lineTo(curX, curY);
                    break;
                }
                case 'C':
                case 'c': {
                    double[] n = new double[6];
                    double[] tmp = new double[1];
                    for (int k = 0; k < 6; k++) {
                        i = readNextNumber(d, i, tmp);
                        n[k] = tmp[0];
                    }
                    double x1 = (cmd == 'c') ? curX + n[0] : n[0];
                    double y1 = (cmd == 'c') ? curY + n[1] : n[1];
                    double x2 = (cmd == 'c') ? curX + n[2] : n[2];
                    double y2 = (cmd == 'c') ? curY + n[3] : n[3];
                    double x = (cmd == 'c') ? curX + n[4] : n[4];
                    double y = (cmd == 'c') ? curY + n[5] : n[5];
                    path.curveTo(x1, y1, x2, y2, x, y);
                    lastCtrlX = x2;
                    lastCtrlY = y2;
                    curX = x;
                    curY = y;
                    break;
                }
                case 'S':
                case 's': {
                    double[] n = new double[4];
                    double[] tmp = new double[1];
                    for (int k = 0; k < 4; k++) {
                        i = readNextNumber(d, i, tmp);
                        n[k] = tmp[0];
                    }
                    double x1 = (prevCmd == 'C' || prevCmd == 'c' || prevCmd == 'S' || prevCmd == 's')
                            ? (2 * curX - lastCtrlX) : curX;
                    double y1 = (prevCmd == 'C' || prevCmd == 'c' || prevCmd == 'S' || prevCmd == 's')
                            ? (2 * curY - lastCtrlY) : curY;
                    double x2 = (cmd == 's') ? curX + n[0] : n[0];
                    double y2 = (cmd == 's') ? curY + n[1] : n[1];
                    double x = (cmd == 's') ? curX + n[2] : n[2];
                    double y = (cmd == 's') ? curY + n[3] : n[3];
                    path.curveTo(x1, y1, x2, y2, x, y);
                    lastCtrlX = x2;
                    lastCtrlY = y2;
                    curX = x;
                    curY = y;
                    break;
                }
                case 'Q':
                case 'q': {
                    double[] n = new double[4];
                    double[] tmp = new double[1];
                    for (int k = 0; k < 4; k++) {
                        i = readNextNumber(d, i, tmp);
                        n[k] = tmp[0];
                    }
                    double x1 = (cmd == 'q') ? curX + n[0] : n[0];
                    double y1 = (cmd == 'q') ? curY + n[1] : n[1];
                    double x = (cmd == 'q') ? curX + n[2] : n[2];
                    double y = (cmd == 'q') ? curY + n[3] : n[3];
                    path.quadTo(x1, y1, x, y);
                    lastCtrlX = x1;
                    lastCtrlY = y1;
                    curX = x;
                    curY = y;
                    break;
                }
                case 'T':
                case 't': {
                    double[] n = new double[2];
                    double[] tmp = new double[1];
                    for (int k = 0; k < 2; k++) {
                        i = readNextNumber(d, i, tmp);
                        n[k] = tmp[0];
                    }
                    double x1 = (prevCmd == 'Q' || prevCmd == 'q' || prevCmd == 'T' || prevCmd == 't')
                            ? (2 * curX - lastCtrlX) : curX;
                    double y1 = (prevCmd == 'Q' || prevCmd == 'q' || prevCmd == 'T' || prevCmd == 't')
                            ? (2 * curY - lastCtrlY) : curY;
                    double x = (cmd == 't') ? curX + n[0] : n[0];
                    double y = (cmd == 't') ? curY + n[1] : n[1];
                    path.quadTo(x1, y1, x, y);
                    lastCtrlX = x1;
                    lastCtrlY = y1;
                    curX = x;
                    curY = y;
                    break;
                }
                case 'A':
                case 'a': {
                    double[] tmp = new double[1];
                    i = readNextNumber(d, i, tmp);
                    double rx = Math.abs(tmp[0]);
                    i = readNextNumber(d, i, tmp);
                    double ry = Math.abs(tmp[0]);
                    i = readNextNumber(d, i, tmp);
                    double xAxisRot = tmp[0];
                    i = readNextArcFlag(d, i, tmp);
                    boolean largeArc = tmp[0] != 0;
                    i = readNextArcFlag(d, i, tmp);
                    boolean sweep = tmp[0] != 0;
                    i = readNextNumber(d, i, tmp);
                    double x = (cmd == 'a') ? curX + tmp[0] : tmp[0];
                    i = readNextNumber(d, i, tmp);
                    double y = (cmd == 'a') ? curY + tmp[0] : tmp[0];

                    appendSvgArc(path, curX, curY, rx, ry, xAxisRot, largeArc, sweep, x, y);
                    curX = x;
                    curY = y;
                    break;
                }
                case 'Z':
                case 'z': {
                    path.closePath();
                    curX = startX;
                    curY = startY;
                    cmd = 0;
                    break;
                }
                default:
                    i++;
                    break;
            }
            prevCmd = cmd;
        }
        return path;
    }

    private static void appendSvgArc(
            Path2D.Double path,
            double x0, double y0,
            double rx, double ry,
            double angleDeg,
            boolean largeArcFlag,
            boolean sweepFlag,
            double x, double y
    ) {
        if (rx == 0 || ry == 0) {
            path.lineTo(x, y);
            return;
        }
        if (x0 == x && y0 == y) {
            return;
        }
        double dx2 = (x0 - x) / 2.0;
        double dy2 = (y0 - y) / 2.0;
        double angle = Math.toRadians(angleDeg % 360.0);
        double cosAngle = Math.cos(angle);
        double sinAngle = Math.sin(angle);

        double x1 = cosAngle * dx2 + sinAngle * dy2;
        double y1 = -sinAngle * dx2 + cosAngle * dy2;

        double Prx = rx * rx;
        double Pry = ry * ry;
        double Px1 = x1 * x1;
        double Py1 = y1 * y1;

        double radiiCheck = Px1 / Prx + Py1 / Pry;
        if (radiiCheck > 1.0) {
            rx = Math.sqrt(radiiCheck) * rx;
            ry = Math.sqrt(radiiCheck) * ry;
            Prx = rx * rx;
            Pry = ry * ry;
        }

        double sign = (largeArcFlag == sweepFlag) ? -1.0 : 1.0;
        double sq = ((Prx * Pry) - (Prx * Py1) - (Pry * Px1)) / ((Prx * Py1) + (Pry * Px1));
        sq = (sq < 0) ? 0 : sq;
        double coef = sign * Math.sqrt(sq);
        double cx1 = coef * ((rx * y1) / ry);
        double cy1 = coef * -((ry * x1) / rx);

        double sx2 = (x0 + x) / 2.0;
        double sy2 = (y0 + y) / 2.0;
        double cx = sx2 + (cosAngle * cx1 - sinAngle * cy1);
        double cy = sy2 + (sinAngle * cx1 + cosAngle * cy1);

        double ux = (x1 - cx1) / rx;
        double uy = (y1 - cy1) / ry;
        double vx = (-x1 - cx1) / rx;
        double vy = (-y1 - cy1) / ry;

        double p = ux;
        double n = Math.sqrt((ux * ux) + (uy * uy));
        double signStart = (uy < 0) ? -1.0 : 1.0;
        double angleStart = Math.toDegrees(signStart * Math.acos(Math.max(-1.0, Math.min(1.0, p / n))));

        n = Math.sqrt((ux * ux + uy * uy) * (vx * vx + vy * vy));
        p = ux * vx + uy * vy;
        double signExtent = (ux * vy - uy * vx < 0) ? -1.0 : 1.0;
        double angleExtent = Math.toDegrees(signExtent * Math.acos(Math.max(-1.0, Math.min(1.0, p / n))));
        if (!sweepFlag && angleExtent > 0) {
            angleExtent -= 360.0;
        } else if (sweepFlag && angleExtent < 0) {
            angleExtent += 360.0;
        }
        angleExtent %= 360.0;
        angleStart %= 360.0;

        Arc2D.Double arc = new Arc2D.Double(cx - rx, cy - ry, rx * 2.0, ry * 2.0, -angleStart, -angleExtent, Arc2D.OPEN);
        Shape rotatedArc = AffineTransform.getRotateInstance(angle, cx, cy).createTransformedShape(arc);
        path.append(rotatedArc, true);
    }

    private static int readNextArcFlag(String s, int idx, double[] out) {
        int len = s.length();
        while (idx < len && (Character.isWhitespace(s.charAt(idx)) || s.charAt(idx) == ',')) {
            idx++;
        }
        if (idx < len && (s.charAt(idx) == '0' || s.charAt(idx) == '1')) {
            out[0] = s.charAt(idx) - '0';
            return idx + 1;
        }
        return readNextNumber(s, idx, out);
    }

    private static int readNextNumber(String s, int idx, double[] out) {
        int len = s.length();
        while (idx < len && (Character.isWhitespace(s.charAt(idx)) || s.charAt(idx) == ',')) {
            idx++;
        }
        if (idx >= len) {
            out[0] = 0;
            return idx;
        }
        int start = idx;
        if (s.charAt(idx) == '+' || s.charAt(idx) == '-') {
            idx++;
        }
        boolean hasDot = false;
        while (idx < len) {
            char c = s.charAt(idx);
            if (Character.isDigit(c)) {
                idx++;
            } else if (c == '.' && !hasDot) {
                hasDot = true;
                idx++;
            } else if ((c == 'e' || c == 'E') && idx > start) {
                idx++;
                if (idx < len && (s.charAt(idx) == '+' || s.charAt(idx) == '-')) {
                    idx++;
                }
            } else {
                break;
            }
        }
        if (idx > start) {
            try {
                out[0] = Double.parseDouble(s.substring(start, idx));
            } catch (NumberFormatException e) {
                out[0] = 0;
            }
        } else {
            out[0] = 0;
        }
        return idx;
    }

    private static double[] parseNumberList(String s) {
        if (s == null || s.trim().isEmpty()) {
            return new double[0];
        }
        List<Double> list = new ArrayList<>();
        int idx = 0;
        int len = s.length();
        double[] tmp = new double[1];
        while (idx < len) {
            int next = readNextNumber(s, idx, tmp);
            if (next <= idx) {
                break;
            }
            list.add(tmp[0]);
            idx = next;
            while (idx < len && (Character.isWhitespace(s.charAt(idx)) || s.charAt(idx) == ',')) {
                idx++;
            }
        }
        double[] arr = new double[list.size()];
        for (int i = 0; i < list.size(); i++) {
            arr[i] = list.get(i);
        }
        return arr;
    }

    private static AffineTransform parseSvgTransform(String transformStr) {
        AffineTransform result = new AffineTransform();
        int i = 0;
        int len = transformStr.length();
        while (i < len) {
            int open = transformStr.indexOf('(', i);
            int close = transformStr.indexOf(')', open + 1);
            if (open < 0 || close < 0) {
                break;
            }
            String name = transformStr.substring(i, open).trim().toLowerCase(Locale.ROOT);
            double[] args = parseNumberList(transformStr.substring(open + 1, close));
            switch (name) {
                case "translate":
                    if (args.length >= 2) {
                        result.translate(args[0], args[1]);
                    } else if (args.length == 1) {
                        result.translate(args[0], 0);
                    }
                    break;
                case "scale":
                    if (args.length >= 2) {
                        result.scale(args[0], args[1]);
                    } else if (args.length == 1) {
                        result.scale(args[0], args[0]);
                    }
                    break;
                case "rotate":
                    if (args.length >= 3) {
                        result.rotate(Math.toRadians(args[0]), args[1], args[2]);
                    } else if (args.length >= 1) {
                        result.rotate(Math.toRadians(args[0]));
                    }
                    break;
                case "skewx":
                    if (args.length >= 1) {
                        result.shear(Math.tan(Math.toRadians(args[0])), 0);
                    }
                    break;
                case "skewy":
                    if (args.length >= 1) {
                        result.shear(0, Math.tan(Math.toRadians(args[0])));
                    }
                    break;
                case "matrix":
                    if (args.length >= 6) {
                        result.concatenate(new AffineTransform(args[0], args[1], args[2], args[3], args[4], args[5]));
                    }
                    break;
                default:
                    break;
            }
            i = close + 1;
            while (i < len && (Character.isWhitespace(transformStr.charAt(i)) || transformStr.charAt(i) == ',')) {
                i++;
            }
        }
        return result;
    }

    private static double parseSvgLength(String val, double defaultVal) {
        if (val == null || val.trim().isEmpty()) {
            return defaultVal;
        }
        String s = val.trim().toLowerCase(Locale.ROOT);
        double mul = 1.0;
        if (s.endsWith("px")) {
            s = s.substring(0, s.length() - 2).trim();
        } else if (s.endsWith("pt")) {
            s = s.substring(0, s.length() - 2).trim();
            mul = 1.3333333;
        } else if (s.endsWith("em") || s.endsWith("rem")) {
            s = s.replace("rem", "").replace("em", "").trim();
            mul = 16.0;
        } else if (s.endsWith("%")) {
            s = s.substring(0, s.length() - 1).trim();
            try {
                return (Double.parseDouble(s) / 100.0) * defaultVal;
            } catch (NumberFormatException e) {
                return defaultVal;
            }
        } else if (s.endsWith("in")) {
            s = s.substring(0, s.length() - 2).trim();
            mul = 96.0;
        } else if (s.endsWith("cm")) {
            s = s.substring(0, s.length() - 2).trim();
            mul = 37.795;
        } else if (s.endsWith("mm")) {
            s = s.substring(0, s.length() - 2).trim();
            mul = 3.7795;
        }
        try {
            return Double.parseDouble(s) * mul;
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }

    // =========================================================================
    // Pure-Java TGA, PNM (PPM/PGM), ICO, and WebP Fallback Decoders
    // =========================================================================

    private static boolean looksLikeTga(byte[] data) {
        if (data.length < 18) {
            return false;
        }
        int colorMapType = data[1] & 0xFF;
        int imageType = data[2] & 0xFF;
        int bpp = data[16] & 0xFF;
        return (colorMapType == 0 || colorMapType == 1)
                && (imageType == 2 || imageType == 3 || imageType == 10 || imageType == 11)
                && (bpp == 8 || bpp == 24 || bpp == 32);
    }

    private static BufferedImage decodeTga(byte[] data) {
        if (data.length < 18) {
            return null;
        }
        int idLen = data[0] & 0xFF;
        int imageType = data[2] & 0xFF;
        int width = ((data[12] & 0xFF) | ((data[13] & 0xFF) << 8));
        int height = ((data[14] & 0xFF) | ((data[15] & 0xFF) << 8));
        int bpp = data[16] & 0xFF;
        int desc = data[17] & 0xFF;
        boolean topToBottom = (desc & 0x20) != 0;

        if (width <= 0 || height <= 0 || width > 8192 || height > 8192) {
            return null;
        }
        int bytesPerPixel = bpp / 8;
        if (bytesPerPixel != 1 && bytesPerPixel != 3 && bytesPerPixel != 4) {
            return null;
        }

        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int offset = 18 + idLen;
        int totalPixels = width * height;
        int pixelIdx = 0;

        boolean rle = (imageType == 10 || imageType == 11);
        while (pixelIdx < totalPixels && offset < data.length) {
            if (rle) {
                int header = data[offset++] & 0xFF;
                int count = (header & 0x7F) + 1;
                if ((header & 0x80) != 0) {
                    int argb = readTgaPixel(data, offset, bytesPerPixel);
                    offset += bytesPerPixel;
                    for (int k = 0; k < count && pixelIdx < totalPixels; k++) {
                        int x = pixelIdx % width;
                        int y = topToBottom ? (pixelIdx / width) : (height - 1 - (pixelIdx / width));
                        img.setRGB(x, y, argb);
                        pixelIdx++;
                    }
                } else {
                    for (int k = 0; k < count && pixelIdx < totalPixels && offset + bytesPerPixel <= data.length; k++) {
                        int argb = readTgaPixel(data, offset, bytesPerPixel);
                        offset += bytesPerPixel;
                        int x = pixelIdx % width;
                        int y = topToBottom ? (pixelIdx / width) : (height - 1 - (pixelIdx / width));
                        img.setRGB(x, y, argb);
                        pixelIdx++;
                    }
                }
            } else {
                if (offset + bytesPerPixel > data.length) {
                    break;
                }
                int argb = readTgaPixel(data, offset, bytesPerPixel);
                offset += bytesPerPixel;
                int x = pixelIdx % width;
                int y = topToBottom ? (pixelIdx / width) : (height - 1 - (pixelIdx / width));
                img.setRGB(x, y, argb);
                pixelIdx++;
            }
        }
        return img;
    }

    private static int readTgaPixel(byte[] data, int offset, int bytesPerPixel) {
        if (offset + bytesPerPixel > data.length) {
            return 0xFF000000;
        }
        if (bytesPerPixel == 1) {
            int v = data[offset] & 0xFF;
            return (0xFF << 24) | (v << 16) | (v << 8) | v;
        }
        int b = data[offset] & 0xFF;
        int g = data[offset + 1] & 0xFF;
        int r = data[offset + 2] & 0xFF;
        int a = (bytesPerPixel == 4) ? (data[offset + 3] & 0xFF) : 0xFF;
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static BufferedImage decodePnm(byte[] data) {
        try {
            int[] idx = {2};
            int format = data[1] - '0';
            int width = Integer.parseInt(nextPnmToken(data, idx));
            int height = Integer.parseInt(nextPnmToken(data, idx));
            int maxVal = (format == 1 || format == 4) ? 1 : Integer.parseInt(nextPnmToken(data, idx));
            if (width <= 0 || height <= 0 || maxVal <= 0) {
                return null;
            }
            BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            if (format == 3) {
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        int r = Integer.parseInt(nextPnmToken(data, idx)) * 255 / maxVal;
                        int g = Integer.parseInt(nextPnmToken(data, idx)) * 255 / maxVal;
                        int b = Integer.parseInt(nextPnmToken(data, idx)) * 255 / maxVal;
                        img.setRGB(x, y, (0xFF << 24) | (r << 16) | (g << 8) | b);
                    }
                }
                return img;
            } else if (format == 6) {
                int pos = idx[0];
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width && pos + 2 < data.length; x++) {
                        int r = (data[pos++] & 0xFF) * 255 / maxVal;
                        int g = (data[pos++] & 0xFF) * 255 / maxVal;
                        int b = (data[pos++] & 0xFF) * 255 / maxVal;
                        img.setRGB(x, y, (0xFF << 24) | (r << 16) | (g << 8) | b);
                    }
                }
                return img;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String nextPnmToken(byte[] data, int[] idx) {
        int i = idx[0];
        while (i < data.length) {
            char c = (char) (data[i] & 0xFF);
            if (c == '#') {
                while (i < data.length && data[i] != '\n') {
                    i++;
                }
            } else if (Character.isWhitespace(c)) {
                i++;
            } else {
                break;
            }
        }
        int start = i;
        while (i < data.length && !Character.isWhitespace((char) (data[i] & 0xFF))) {
            i++;
        }
        idx[0] = i + 1;
        return new String(data, start, i - start, StandardCharsets.US_ASCII);
    }

    private static BufferedImage decodeIco(byte[] data) {
        try {
            ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            buf.position(4);
            int count = buf.getShort() & 0xFFFF;
            int bestOffset = -1;
            int bestSize = 0;
            int bestDim = -1;
            for (int i = 0; i < count && buf.remaining() >= 16; i++) {
                int w = buf.get() & 0xFF;
                if (w == 0) w = 256;
                int h = buf.get() & 0xFF;
                if (h == 0) h = 256;
                buf.position(buf.position() + 6);
                int bytesInRes = buf.getInt();
                int imageOffset = buf.getInt();
                if (w * h >= bestDim && imageOffset >= 0 && imageOffset + bytesInRes <= data.length) {
                    bestDim = w * h;
                    bestOffset = imageOffset;
                    bestSize = bytesInRes;
                }
            }
            if (bestOffset > 0) {
                BufferedImage sub = ImageIO.read(new ByteArrayInputStream(data, bestOffset, bestSize));
                if (sub != null) {
                    return toArgbBufferedImage(sub);
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    // =========================================================================
    // Helper Methods
    // =========================================================================

    private static BufferedImage toArgbBufferedImage(BufferedImage src) {
        if (src.getType() == BufferedImage.TYPE_INT_ARGB) {
            return src;
        }
        BufferedImage copy = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = copy.createGraphics();
        g.setComposite(AlphaComposite.Src);
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return copy;
    }

    public static byte[] downloadBytes(String urlStr, int maxBytes) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(urlStr).toURL().openConnection();
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; ArceonMinecraftPlugin/0.6.0)");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);
        conn.setInstanceFollowRedirects(true);
        try (InputStream in = conn.getInputStream()) {
            return readAllBytes(in, maxBytes);
        }
    }

    public static byte[] readAllBytes(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > maxBytes) {
                throw new IOException("File exceeds maximum allowed size (" + (maxBytes / (1024 * 1024)) + " MB).");
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static int clampByte(double v) {
        int i = (int) Math.round(v);
        return i < 0 ? 0 : (i > 255 ? 255 : i);
    }

    private static double clamp01(double v) {
        return v < 0.0 ? 0.0 : (v > 1.0 ? 1.0 : v);
    }

    private static void initNamedColors() {
        CSS_NAMED_COLORS.put("aliceblue", 0xF0F8FF);
        CSS_NAMED_COLORS.put("antiquewhite", 0xFAEBD7);
        CSS_NAMED_COLORS.put("aqua", 0x00FFFF);
        CSS_NAMED_COLORS.put("aquamarine", 0x7FFFD4);
        CSS_NAMED_COLORS.put("azure", 0xF0FFFF);
        CSS_NAMED_COLORS.put("beige", 0xF5F5DC);
        CSS_NAMED_COLORS.put("bisque", 0xFFE4C4);
        CSS_NAMED_COLORS.put("black", 0x000000);
        CSS_NAMED_COLORS.put("blanchedalmond", 0xFFEBCD);
        CSS_NAMED_COLORS.put("blue", 0x0000FF);
        CSS_NAMED_COLORS.put("blueviolet", 0x8A2BE2);
        CSS_NAMED_COLORS.put("brown", 0xA52A2A);
        CSS_NAMED_COLORS.put("burlywood", 0xDEB887);
        CSS_NAMED_COLORS.put("cadetblue", 0x5F9EA0);
        CSS_NAMED_COLORS.put("chartreuse", 0x7FFF00);
        CSS_NAMED_COLORS.put("chocolate", 0xD2691E);
        CSS_NAMED_COLORS.put("coral", 0xFF7F50);
        CSS_NAMED_COLORS.put("cornflowerblue", 0x6495ED);
        CSS_NAMED_COLORS.put("cornsilk", 0xFFF8DC);
        CSS_NAMED_COLORS.put("crimson", 0xDC143C);
        CSS_NAMED_COLORS.put("cyan", 0x00FFFF);
        CSS_NAMED_COLORS.put("darkblue", 0x00008B);
        CSS_NAMED_COLORS.put("darkcyan", 0x008B8B);
        CSS_NAMED_COLORS.put("darkgoldenrod", 0xB8860B);
        CSS_NAMED_COLORS.put("darkgray", 0xA9A9A9);
        CSS_NAMED_COLORS.put("darkgrey", 0xA9A9A9);
        CSS_NAMED_COLORS.put("darkgreen", 0x006400);
        CSS_NAMED_COLORS.put("darkkhaki", 0xBDB76B);
        CSS_NAMED_COLORS.put("darkmagenta", 0x8B008B);
        CSS_NAMED_COLORS.put("darkolivegreen", 0x556B2F);
        CSS_NAMED_COLORS.put("darkorange", 0xFF8C00);
        CSS_NAMED_COLORS.put("darkorchid", 0x9932CC);
        CSS_NAMED_COLORS.put("darkred", 0x8B0000);
        CSS_NAMED_COLORS.put("darksalmon", 0xE9967A);
        CSS_NAMED_COLORS.put("darkseagreen", 0x8FBC8F);
        CSS_NAMED_COLORS.put("darkslateblue", 0x483D8B);
        CSS_NAMED_COLORS.put("darkslategray", 0x2F4F4F);
        CSS_NAMED_COLORS.put("darkslategrey", 0x2F4F4F);
        CSS_NAMED_COLORS.put("darkturquoise", 0x00CED1);
        CSS_NAMED_COLORS.put("darkviolet", 0x9400D3);
        CSS_NAMED_COLORS.put("deeppink", 0xFF1493);
        CSS_NAMED_COLORS.put("deepskyblue", 0x00BFFF);
        CSS_NAMED_COLORS.put("dimgray", 0x696969);
        CSS_NAMED_COLORS.put("dimgrey", 0x696969);
        CSS_NAMED_COLORS.put("dodgerblue", 0x1E90FF);
        CSS_NAMED_COLORS.put("firebrick", 0xB22222);
        CSS_NAMED_COLORS.put("floralwhite", 0xFFFAF0);
        CSS_NAMED_COLORS.put("forestgreen", 0x228B22);
        CSS_NAMED_COLORS.put("fuchsia", 0xFF00FF);
        CSS_NAMED_COLORS.put("gainsboro", 0xDCDCDC);
        CSS_NAMED_COLORS.put("ghostwhite", 0xF8F8FF);
        CSS_NAMED_COLORS.put("gold", 0xFFD700);
        CSS_NAMED_COLORS.put("goldenrod", 0xDAA520);
        CSS_NAMED_COLORS.put("gray", 0x808080);
        CSS_NAMED_COLORS.put("grey", 0x808080);
        CSS_NAMED_COLORS.put("green", 0x008000);
        CSS_NAMED_COLORS.put("greenyellow", 0xADFF2F);
        CSS_NAMED_COLORS.put("honeydew", 0xF0FFF0);
        CSS_NAMED_COLORS.put("hotpink", 0xFF69B4);
        CSS_NAMED_COLORS.put("indianred", 0xCD5C5C);
        CSS_NAMED_COLORS.put("indigo", 0x4B0082);
        CSS_NAMED_COLORS.put("ivory", 0xFFFFF0);
        CSS_NAMED_COLORS.put("khaki", 0xF0E68C);
        CSS_NAMED_COLORS.put("lavender", 0xE6E6FA);
        CSS_NAMED_COLORS.put("lavenderblush", 0xFFF0F5);
        CSS_NAMED_COLORS.put("lawngreen", 0x7CFC00);
        CSS_NAMED_COLORS.put("lemonchiffon", 0xFFFACD);
        CSS_NAMED_COLORS.put("lightblue", 0xADD8E6);
        CSS_NAMED_COLORS.put("lightcoral", 0xF08080);
        CSS_NAMED_COLORS.put("lightcyan", 0xE0FFFF);
        CSS_NAMED_COLORS.put("lightgoldenrodyellow", 0xFAFAD2);
        CSS_NAMED_COLORS.put("lightgray", 0xD3D3D3);
        CSS_NAMED_COLORS.put("lightgrey", 0xD3D3D3);
        CSS_NAMED_COLORS.put("lightgreen", 0x90EE90);
        CSS_NAMED_COLORS.put("lightpink", 0xFFB6C1);
        CSS_NAMED_COLORS.put("lightsalmon", 0xFFA07A);
        CSS_NAMED_COLORS.put("lightseagreen", 0x20B2AA);
        CSS_NAMED_COLORS.put("lightskyblue", 0x87CEFA);
        CSS_NAMED_COLORS.put("lightslategray", 0x778899);
        CSS_NAMED_COLORS.put("lightslategrey", 0x778899);
        CSS_NAMED_COLORS.put("lightsteelblue", 0xB0C4DE);
        CSS_NAMED_COLORS.put("lightyellow", 0xFFFFE0);
        CSS_NAMED_COLORS.put("lime", 0x00FF00);
        CSS_NAMED_COLORS.put("limegreen", 0x32CD32);
        CSS_NAMED_COLORS.put("linen", 0xFAF0E6);
        CSS_NAMED_COLORS.put("magenta", 0xFF00FF);
        CSS_NAMED_COLORS.put("maroon", 0x800000);
        CSS_NAMED_COLORS.put("mediumaquamarine", 0x66CDAA);
        CSS_NAMED_COLORS.put("mediumblue", 0x0000CD);
        CSS_NAMED_COLORS.put("mediumorchid", 0xBA55D3);
        CSS_NAMED_COLORS.put("mediumpurple", 0x9370DB);
        CSS_NAMED_COLORS.put("mediumseagreen", 0x3CB371);
        CSS_NAMED_COLORS.put("mediumslateblue", 0x7B68EE);
        CSS_NAMED_COLORS.put("mediumspringgreen", 0x00FA9A);
        CSS_NAMED_COLORS.put("mediumturquoise", 0x48D1CC);
        CSS_NAMED_COLORS.put("mediumvioletred", 0xC71585);
        CSS_NAMED_COLORS.put("midnightblue", 0x191970);
        CSS_NAMED_COLORS.put("mintcream", 0xF5FFFA);
        CSS_NAMED_COLORS.put("mistyrose", 0xFFE4E1);
        CSS_NAMED_COLORS.put("moccasin", 0xFFE4B5);
        CSS_NAMED_COLORS.put("navajowhite", 0xFFDEAD);
        CSS_NAMED_COLORS.put("navy", 0x000080);
        CSS_NAMED_COLORS.put("oldlace", 0xFDF5E6);
        CSS_NAMED_COLORS.put("olive", 0x808000);
        CSS_NAMED_COLORS.put("olivedrab", 0x6B8E23);
        CSS_NAMED_COLORS.put("orange", 0xFFA500);
        CSS_NAMED_COLORS.put("orangered", 0xFF4500);
        CSS_NAMED_COLORS.put("orchid", 0xDA70D6);
        CSS_NAMED_COLORS.put("palegoldenrod", 0xEEE8AA);
        CSS_NAMED_COLORS.put("palegreen", 0x98FB98);
        CSS_NAMED_COLORS.put("paleturquoise", 0xAFEEEE);
        CSS_NAMED_COLORS.put("palevioletred", 0xDB7093);
        CSS_NAMED_COLORS.put("papayawhip", 0xFFEFD5);
        CSS_NAMED_COLORS.put("peachpuff", 0xFFDAB9);
        CSS_NAMED_COLORS.put("peru", 0xCD853F);
        CSS_NAMED_COLORS.put("pink", 0xFFC0CB);
        CSS_NAMED_COLORS.put("plum", 0xDDA0DD);
        CSS_NAMED_COLORS.put("powderblue", 0xB0E0E6);
        CSS_NAMED_COLORS.put("purple", 0x800080);
        CSS_NAMED_COLORS.put("rebeccapurple", 0x663399);
        CSS_NAMED_COLORS.put("red", 0xFF0000);
        CSS_NAMED_COLORS.put("rosybrown", 0xBC8F8F);
        CSS_NAMED_COLORS.put("royalblue", 0x4169E1);
        CSS_NAMED_COLORS.put("saddlebrown", 0x8B4513);
        CSS_NAMED_COLORS.put("salmon", 0xFA8072);
        CSS_NAMED_COLORS.put("sandybrown", 0xF4A460);
        CSS_NAMED_COLORS.put("seagreen", 0x2E8B57);
        CSS_NAMED_COLORS.put("seashell", 0xFFF5EE);
        CSS_NAMED_COLORS.put("sienna", 0xA0522D);
        CSS_NAMED_COLORS.put("silver", 0xC0C0C0);
        CSS_NAMED_COLORS.put("skyblue", 0x87CEEB);
        CSS_NAMED_COLORS.put("slateblue", 0x6A5ACD);
        CSS_NAMED_COLORS.put("slategray", 0x708090);
        CSS_NAMED_COLORS.put("slategrey", 0x708090);
        CSS_NAMED_COLORS.put("snow", 0xFFFAFA);
        CSS_NAMED_COLORS.put("springgreen", 0x00FF7F);
        CSS_NAMED_COLORS.put("steelblue", 0x4682B4);
        CSS_NAMED_COLORS.put("tan", 0xD2B48C);
        CSS_NAMED_COLORS.put("teal", 0x008080);
        CSS_NAMED_COLORS.put("thistle", 0xD8BFD8);
        CSS_NAMED_COLORS.put("tomato", 0xFF6347);
        CSS_NAMED_COLORS.put("turquoise", 0x40E0D0);
        CSS_NAMED_COLORS.put("violet", 0xEE82EE);
        CSS_NAMED_COLORS.put("wheat", 0xF5DEB3);
        CSS_NAMED_COLORS.put("white", 0xFFFFFF);
        CSS_NAMED_COLORS.put("whitesmoke", 0xF5F5F5);
        CSS_NAMED_COLORS.put("yellow", 0xFFFF00);
        CSS_NAMED_COLORS.put("yellowgreen", 0x9ACD32);
    }
}

