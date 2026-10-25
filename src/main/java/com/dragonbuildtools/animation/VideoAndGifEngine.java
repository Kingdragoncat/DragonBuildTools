package com.dragonbuildtools.animation;

import com.dragonbuildtools.DragonBuildToolsPlugin;
import com.dragonbuildtools.converter.ImagePixelArtConverter;
import com.dragonbuildtools.converter.Model3DVoxelizer.VoxelGrid;
import com.dragonbuildtools.util.CardinalDirectionUtil;
import com.sk89q.worldedit.world.block.BaseBlock;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Decodes Animated GIFs, Video files (.mp4, .webm, .mov, .mkv, .avi), and Sprite Sheets
 * into 3D {@link VoxelGrid} frame sequences for flat walls, floors, or curved cinema screens.
 */
public final class VideoAndGifEngine {

    private static final String[] VIDEO_EXTENSIONS = {
            ".gif", ".mp4", ".webm", ".mov", ".mkv", ".avi", ".png", ".webp", ".jpg", ".jpeg"
    };

    private VideoAndGifEngine() {
    }

    public static File getVideosDirectory() {
        File base = (DragonBuildToolsPlugin.getInstance() != null)
                ? DragonBuildToolsPlugin.getInstance().getDataFolder()
                : new File(".");
        File dir = new File(base, "videos");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    public static File resolveLocalVideoOrGif(String source) {
        File[] dirs = new File[]{
                getVideosDirectory(),
                ImagePixelArtConverter.getImagesDirectory(),
                AnimatedModelEngine.getAnimationsDirectory()
        };
        for (File dir : dirs) {
            File direct = new File(dir, source);
            if (direct.exists()) {
                return direct;
            }
            for (String ext : VIDEO_EXTENSIONS) {
                File candidate = new File(dir, source + ext);
                if (candidate.exists()) {
                    return candidate;
                }
            }
        }
        File raw = new File(source);
        if (raw.exists()) {
            return raw;
        }
        return null;
    }

    /**
     * Loads frames from an animated GIF, video file, image directory, or URL and converts them into
     * 3D {@link VoxelGrid} frames ready for {@link AnimationManager}.
     */
    public static List<VoxelGrid> loadVideoFramesAsVoxels(
            String source,
            int targetWidth,
            int targetHeight,
            int maxFrames,
            boolean flatFloor,
            boolean curvedScreen,
            boolean dither,
            boolean allowGlass,
            CardinalDirectionUtil facing
    ) throws IOException {
        int clampedW = Math.max(4, Math.min(256, targetWidth));
        int clampedMaxFrames = Math.max(2, Math.min(240, maxFrames));

        List<BufferedImage> rawFrames = extractImageFrames(source, clampedW, targetHeight, clampedMaxFrames);
        if (rawFrames.isEmpty()) {
            throw new IOException("No animation frames could be decoded from: " + source);
        }

        // Subsample if rawFrames exceeds clampedMaxFrames
        if (rawFrames.size() > clampedMaxFrames) {
            List<BufferedImage> subsampled = new ArrayList<>(clampedMaxFrames);
            for (int i = 0; i < clampedMaxFrames; i++) {
                int idx = (int) Math.min(rawFrames.size() - 1, Math.round((double) i * (rawFrames.size() - 1) / (clampedMaxFrames - 1)));
                subsampled.add(rawFrames.get(idx));
            }
            rawFrames = subsampled;
        }

        List<VoxelGrid> voxelFrames = new ArrayList<>(rawFrames.size());
        for (BufferedImage raw : rawFrames) {
            BufferedImage scaled = ImagePixelArtConverter.resizeImage(raw, clampedW, targetHeight);
            BaseBlock[][] grid2D = ImagePixelArtConverter.imageToBlocks(scaled, dither, allowGlass);
            voxelFrames.add(project2DGridToVoxelScreen(grid2D, flatFloor, curvedScreen, facing));
        }

        return AnimatedModelEngine.alignVoxelGridCenters(voxelFrames);
    }

    public static List<BufferedImage> extractImageFrames(
            String source,
            int targetWidth,
            int targetHeight,
            int maxFrames
    ) throws IOException {
        if (source.startsWith("http://") || source.startsWith("https://")) {
            byte[] bytes = ImagePixelArtConverter.downloadBytes(source, 64 * 1024 * 1024);
            List<BufferedImage> gifFrames = decodeAnimatedGifBytes(bytes);
            if (gifFrames.size() >= 2) {
                return gifFrames;
            }
            BufferedImage single = ImagePixelArtConverter.decodeImageBytes(bytes, source);
            return generatePanFramesFromSingleImage(single, maxFrames);
        }

        File file = resolveLocalVideoOrGif(source);
        if (file == null || !file.exists()) {
            throw new IOException("Video/GIF file not found: " + source
                    + " (Place files in plugins/DragonBuildTools/videos/ or plugins/DragonBuildTools/images/ or use a URL)");
        }

        if (file.isDirectory()) {
            File[] children = file.listFiles(File::isFile);
            if (children == null || children.length == 0) {
                throw new IOException("No frame images found in folder: " + file.getName());
            }
            Arrays.sort(children, Comparator.comparing(File::getName));
            List<BufferedImage> list = new ArrayList<>();
            for (int i = 0; i < Math.min(maxFrames, children.length); i++) {
                try (InputStream in = new FileInputStream(children[i])) {
                    byte[] data = ImagePixelArtConverter.readAllBytes(in, 32 * 1024 * 1024);
                    list.add(ImagePixelArtConverter.decodeImageBytes(data, children[i].getName()));
                }
            }
            return list;
        }

        String lower = file.getName().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".mp4") || lower.endsWith(".webm") || lower.endsWith(".mov")
                || lower.endsWith(".mkv") || lower.endsWith(".avi")) {
            return extractVideoFramesViaFfmpeg(file, targetWidth, targetHeight, maxFrames);
        }

        byte[] data;
        try (InputStream in = new FileInputStream(file)) {
            data = ImagePixelArtConverter.readAllBytes(in, 64 * 1024 * 1024);
        }

        if (lower.endsWith(".gif") || (data.length >= 4 && data[0] == 'G' && data[1] == 'I' && data[2] == 'F')) {
            List<BufferedImage> gifFrames = decodeAnimatedGifBytes(data);
            if (!gifFrames.isEmpty()) {
                return gifFrames;
            }
        }

        BufferedImage single = ImagePixelArtConverter.decodeImageBytes(data, file.getName());
        return generatePanFramesFromSingleImage(single, maxFrames);
    }

    /**
     * Decodes all frames of an animated GIF, properly handling frame offsets and disposal methods.
     */
    public static List<BufferedImage> decodeAnimatedGifBytes(byte[] gifBytes) {
        List<BufferedImage> frames = new ArrayList<>();
        try (ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(gifBytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("gif");
            if (!readers.hasNext()) {
                return frames;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream, false, false);
                int numFrames = reader.getNumImages(true);
                if (numFrames <= 0) {
                    return frames;
                }

                int canvasW = reader.getWidth(0);
                int canvasH = reader.getHeight(0);
                for (int i = 0; i < numFrames; i++) {
                    canvasW = Math.max(canvasW, reader.getWidth(i));
                    canvasH = Math.max(canvasH, reader.getHeight(i));
                }

                BufferedImage master = new BufferedImage(canvasW, canvasH, BufferedImage.TYPE_INT_ARGB);
                Graphics2D masterG = master.createGraphics();

                for (int i = 0; i < numFrames; i++) {
                    BufferedImage rawFrame = reader.read(i);
                    int fx = 0, fy = 0;
                    String disposal = "none";

                    try {
                        IIOMetadata meta = reader.getImageMetadata(i);
                        if (meta != null) {
                            String format = meta.getNativeMetadataFormatName();
                            if (format != null) {
                                Node root = meta.getAsTree(format);
                                NodeList children = root.getChildNodes();
                                for (int c = 0; c < children.getLength(); c++) {
                                    Node node = children.item(c);
                                    if ("ImageDescriptor".equalsIgnoreCase(node.getNodeName())) {
                                        NamedNodeMap attrs = node.getAttributes();
                                        fx = parseAttrInt(attrs, "imageLeftPosition", 0);
                                        fy = parseAttrInt(attrs, "imageTopPosition", 0);
                                    } else if ("GraphicControlExtension".equalsIgnoreCase(node.getNodeName())) {
                                        NamedNodeMap attrs = node.getAttributes();
                                        Node dispNode = attrs != null ? attrs.getNamedItem("disposalMethod") : null;
                                        if (dispNode != null) {
                                            disposal = dispNode.getNodeValue();
                                        }
                                    }
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                    }

                    BufferedImage backup = null;
                    if ("restoreToPrevious".equalsIgnoreCase(disposal)) {
                        backup = copyImage(master);
                    }

                    masterG.drawImage(rawFrame, fx, fy, null);
                    frames.add(copyImage(master));

                    if ("restoreToBackgroundColor".equalsIgnoreCase(disposal)) {
                        masterG.setComposite(AlphaComposite.Clear);
                        masterG.fillRect(fx, fy, rawFrame.getWidth(), rawFrame.getHeight());
                        masterG.setComposite(AlphaComposite.SrcOver);
                    } else if ("restoreToPrevious".equalsIgnoreCase(disposal) && backup != null) {
                        masterG.setComposite(AlphaComposite.Src);
                        masterG.drawImage(backup, 0, 0, null);
                        masterG.setComposite(AlphaComposite.SrcOver);
                    }
                }
                masterG.dispose();
            } finally {
                reader.dispose();
            }
        } catch (Throwable ignored) {
        }
        return frames;
    }

    private static int parseAttrInt(NamedNodeMap attrs, String name, int def) {
        if (attrs == null) return def;
        Node n = attrs.getNamedItem(name);
        if (n == null) return def;
        try {
            return Integer.parseInt(n.getNodeValue());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static BufferedImage copyImage(BufferedImage src) {
        BufferedImage copy = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = copy.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return copy;
    }

    private static List<BufferedImage> extractVideoFramesViaFfmpeg(
            File videoFile,
            int targetWidth,
            int targetHeight,
            int maxFrames
    ) throws IOException {
        File tempDir = Files.createTempDirectory("dbt_video_frames_").toFile();
        try {
            int h = targetHeight > 0 ? targetHeight : -1;
            String scaleFilter = "scale=" + targetWidth + ":" + h;
            ProcessBuilder pb = new ProcessBuilder(
                    "ffmpeg",
                    "-y",
                    "-i", videoFile.getAbsolutePath(),
                    "-vf", scaleFilter,
                    "-frames:v", String.valueOf(maxFrames),
                    new File(tempDir, "frame_%04d.png").getAbsolutePath()
            );
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            proc.getInputStream().readAllBytes();
            proc.waitFor(45, TimeUnit.SECONDS);

            File[] extracted = tempDir.listFiles((d, name) -> name.endsWith(".png"));
            if (extracted == null || extracted.length == 0) {
                throw new IOException("FFmpeg produced no frames for " + videoFile.getName()
                        + ". Convert to an animated .gif or ensure 'ffmpeg' is installed on PATH.");
            }
            Arrays.sort(extracted, Comparator.comparing(File::getName));
            List<BufferedImage> result = new ArrayList<>(extracted.length);
            for (File f : extracted) {
                BufferedImage img = ImageIO.read(f);
                if (img != null) {
                    result.add(img);
                }
            }
            return result;
        } catch (IOException e) {
            throw new IOException("To play .mp4/.webm/.mov directly, install 'ffmpeg' on the server PATH, or use an animated .gif (which works natively in pure Java). Details: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Video frame extraction interrupted.", e);
        } finally {
            File[] files = tempDir.listFiles();
            if (files != null) {
                for (File f : files) f.delete();
            }
            tempDir.delete();
        }
    }

    private static List<BufferedImage> generatePanFramesFromSingleImage(BufferedImage src, int frameCount) {
        int frames = Math.max(2, Math.min(36, frameCount));
        List<BufferedImage> list = new ArrayList<>(frames);
        int w = src.getWidth();
        int h = src.getHeight();
        for (int f = 0; f < frames; f++) {
            BufferedImage frame = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            int shiftX = (int) Math.round(((double) f / frames) * w);
            Graphics2D g = frame.createGraphics();
            g.setColor(new Color(0, 0, 0, 0));
            g.drawImage(src, -shiftX, 0, null);
            g.drawImage(src, w - shiftX, 0, null);
            g.dispose();
            list.add(frame);
        }
        return list;
    }

    /**
     * Projects a 2D {@code BaseBlock[width][height]} frame into a 3D {@link VoxelGrid} (flat vertical wall,
     * flat horizontal floor, or curved 110-degree cylindrical screen).
     */
    public static VoxelGrid project2DGridToVoxelScreen(
            BaseBlock[][] grid2D,
            boolean flatFloor,
            boolean curvedScreen,
            CardinalDirectionUtil dir
    ) {
        int width = grid2D.length;
        int height = width > 0 ? grid2D[0].length : 0;
        if (width == 0 || height == 0) {
            return new VoxelGrid(1, 1, 1, new BaseBlock[1][1][1], 0);
        }
        if (dir == null) {
            dir = CardinalDirectionUtil.NORTH;
        }

        if (flatFloor) {
            // Flat floor canvas on (X, 0, Z)
            BaseBlock[][][] blocks = new BaseBlock[width][1][height];
            int count = 0;
            for (int ix = 0; ix < width; ix++) {
                for (int iy = 0; iy < height; iy++) {
                    BaseBlock b = grid2D[ix][height - 1 - iy];
                    if (b != null) {
                        blocks[ix][0][iy] = b;
                        count++;
                    }
                }
            }
            return new VoxelGrid(width, 1, height, blocks, count);
        }

        if (curvedScreen && width >= 6) {
            int depth = Math.max(3, width / 4);
            boolean ewFacing = (dir == CardinalDirectionUtil.EAST || dir == CardinalDirectionUtil.WEST);
            int sx = ewFacing ? depth : width;
            int sz = ewFacing ? width : depth;
            BaseBlock[][][] blocks = new BaseBlock[sx][height][sz];
            int count = 0;
            for (int ix = 0; ix < width; ix++) {
                double norm = (2.0 * ix / Math.max(1.0, width - 1.0)) - 1.0; // [-1..1]
                int curveOffset = (int) Math.round((1.0 - norm * norm) * (depth - 1));
                for (int iy = 0; iy < height; iy++) {
                    BaseBlock b = grid2D[ix][iy];
                    if (b == null) continue;
                    int vy = height - 1 - iy;
                    if (ewFacing) {
                        int vx = (dir == CardinalDirectionUtil.EAST) ? curveOffset : (depth - 1 - curveOffset);
                        int vz = (dir == CardinalDirectionUtil.EAST) ? ix : (width - 1 - ix);
                        blocks[vx][vy][vz] = b;
                    } else {
                        int vx = (dir == CardinalDirectionUtil.NORTH) ? ix : (width - 1 - ix);
                        int vz = (dir == CardinalDirectionUtil.SOUTH) ? curveOffset : (depth - 1 - curveOffset);
                        blocks[vx][vy][vz] = b;
                    }
                    count++;
                }
            }
            return new VoxelGrid(sx, height, sz, blocks, count);
        }

        // Standard flat vertical screen
        boolean ewFacing = (dir == CardinalDirectionUtil.EAST || dir == CardinalDirectionUtil.WEST);
        int sx = ewFacing ? 1 : width;
        int sz = ewFacing ? width : 1;
        BaseBlock[][][] blocks = new BaseBlock[sx][height][sz];
        int count = 0;
        for (int ix = 0; ix < width; ix++) {
            for (int iy = 0; iy < height; iy++) {
                BaseBlock b = grid2D[ix][iy];
                if (b == null) continue;
                int vy = height - 1 - iy;
                int u = (dir == CardinalDirectionUtil.SOUTH || dir == CardinalDirectionUtil.WEST)
                        ? (width - 1 - ix)
                        : ix;
                if (ewFacing) {
                    blocks[0][vy][u] = b;
                } else {
                    blocks[u][vy][0] = b;
                }
                count++;
            }
        }
        return new VoxelGrid(sx, height, sz, blocks, count);
    }
}
