<p align="center">
  <img src="assets/logo.png" width="170" alt="Dragon Build Tools">
</p>

<h1 align="center">Dragon Build Tools v2.1</h1>

<p align="center">
  3D model importing, live animations, video screens, 3D WFC, terrain heightmaps, and resource-pack <code>.obj</code> exporting for <b>FastAsyncWorldEdit</b>.
</p>

---

Dragon Build Tools (DBT) is a Paper/Spigot addon for FastAsyncWorldEdit that adds in-game tools for converting 3D meshes, images, heightmaps, animated GIFs/videos, and 3D text into Minecraft builds, generating modular builds with 3D Wave Function Collapse, and exporting builds back out to textured `.obj` + `.mtl` meshes for Blender.

Instead of only placing full cubes, DBT checks surface slopes and sub-voxel occupancy to place directional stairs, top/bottom slabs, walls, fences, carpets, and snow layers (1-7) where they fit, while avoiding ugly stacked stairs or mismatched wood/terracotta hues on neutral stone and white builds. Static generators run through normal FAWE edit sessions (`//undo` and `//redo`) or copy straight to your `//paste` clipboard with `-o`.

## What's New in v2.1

- **Resource Pack Models & UV Texture Atlas in `//exportobj`:** Drop any `.zip` resource pack or unzipped pack folder into `plugins/DragonBuildTools/resourcepacks/` and `//exportobj` exports custom 3D JSON block models, blockstates (stairs, slabs, fences, walls, cross plants, trapdoors, lanterns, directional logs), biome foliage/grass tints, and a crisp `<name>_atlas.png` UV texture sheet ready for Blender renders.
- **Zero Bundled Vanilla Assets & Full 1,200-Block Color Coverage:** No Mojang textures or models are packaged inside the plugin JAR. When no custom pack overrides a block, DBT layers over FAWE's runtime-downloaded client asset JAR (`plugins/FastAsyncWorldEdit/textures/`) and includes a complete 1,200-block per-face color & shape fallback table (`1.20.4` - `26.3`) so blocks never export with missing gray colors.
- **In-Game Studio GUI & Starter Demos (`/dbt`):** Browse local folders, click to spawn or copy builds to your clipboard, test starter demos (`/dbt demo`), and pause/speed-adjust/stop active animations from a chest menu.
- **Direct Clipboard Mode (`-o`):** Generate `//model`, `//pixelart`, `//heightmap`, or `//text3d` directly onto your WorldEdit clipboard instead of placing immediately in the world.

## Requirements

- **Server:** Paper, Purpur, or Spigot (`1.20.4` - `1.21.x`, `26.1` - `26.3`)
- **Java:** 21+
- **Dependency:** [FastAsyncWorldEdit (FAWE)](https://intellectualsites.github.io/download/fawe.html)
- *(Optional)* `ffmpeg` on the host machine if you want to load `.mp4` / `.webm` / `.mov` files with `//video`. Animated `.gif` files work out of the box with no extra dependencies.

## Installation

1. Put `DragonBuildTools-2.1.0.jar` into your server's `plugins/` folder alongside FastAsyncWorldEdit.
2. Start or restart the server.
3. Run `/dbt` in-game to open the Studio GUI and click any starter demo, or drop your own files into `plugins/DragonBuildTools/` (`models/`, `resourcepacks/`, `images/`, `videos/`, `animations/`, `heightmaps/`) or pass `http://` / `https://` URLs directly in commands.

## Commands

### Studio GUI & Starter Demos (`/dbt`)
```text
/dbt
/dbt demo <dragon|cinema|knot|terrain|text|setup>
/dbt help [topic]
/dbt files
```
Running `/dbt` as a player opens the chest GUI where you can launch starter demos, click files from your folders (Left-Click to place, Right-Click to copy to `//paste`), or control running animations.

### 3D Models (`//model`)
```text
//model <file|url> [size=48] [block] [rx=0] [ry=0] [rz=0] [-s] [-d] [-g] [-l] [-b] [-c] [-o]
```
Aliases: `//3dmodel`, `//voxel`, `//dbtmodel`

Imports `.obj` (including `.mtl` materials and textures), `.glb`, `.gltf`, `.stl`, `.ply`, `.vox`, and `.off` files from `plugins/DragonBuildTools/models/` or a URL.
- `-s` fill the inside of the mesh solid
- `-l` apply directional shading
- `-b` full blocks only (turn off stairs/slabs/sub-blocks)
- `-d` disable color dithering
- `-g` disable stained glass for translucent surfaces
- `-c` center on your position
- `-o` copy to your `//paste` clipboard instead of placing

### Export Selection to `.OBJ` (`//exportobj`)
```text
//exportobj <name> [pack|auto|colors] [-p <pack>] [-c] [-m]
```
Aliases: `//schemtoobj`, `//toobj`

Exports your WorldEdit selection to `plugins/DragonBuildTools/exports/<name>.obj` (+ `<name>.mtl` and `<name>_atlas.png`) with hidden-face culling, resource-pack 3D block models, UV coordinates, biome foliage/grass tints, and sub-block geometry for stairs, slabs, fences, walls, cross plants, trapdoors, lanterns, and snow layers.
- Drop any `.zip` resource pack or unzipped pack folder into `plugins/DragonBuildTools/resourcepacks/` to export using that pack's textures and 3D JSON block models (layered over FAWE's runtime-downloaded client asset JAR, with no vanilla textures embedded in the plugin).
- `[pack]` or `-p <pack>` pick a specific pack in `resourcepacks/` (or pass `colors` for flat solid-color materials without a texture atlas)
- `-c` export from your `//copy` clipboard instead of your selection
- `-m` also copy the exported `.obj` + `.mtl` (+ `_atlas.png`) into `models/` so you can re-import or animate it with `//animmodel`

### Animated 3D Models (`//animmodel`)
```text
//animmodel <file|folder|url> [size=40] [mode=wingflap] [frames=16] [fps=8] [block] [-s] [-b] [-n] [-c]
```
Aliases: `//3danim`, `//animate3d`

Animates a 3D model in the world by pre-voxelizing frames and only swapping blocks that change between frames. Supports multi-frame `.vox` files, numbered mesh folders in `plugins/DragonBuildTools/animations/`, or procedural motion modes (`wingflap`, `spin`, `hover`, `breathe`, `swing`) on a static model.
- `-n` play once without looping

### Video & GIF Screens (`//video`)
```text
//video create <name> <file|url> [width=64] [height=0] [fps=10] [maxFrames=60] [-f] [-u] [-d] [-g] [-n] [-c] [-a]
```
Aliases: `//gif`, `//screen`, `//dbtvideo`

Creates named in-world screens from `.gif` files, image folders, or video files in `plugins/DragonBuildTools/videos/`. Multiple screens can run at the same time using the same source file.
- `-f` flat on the floor
- `-u` curved screen
- `-a` start playing right away
- Subcommands: `start`, `pause`, `stop [-r]`, `restart`, `seek`, `fps`, `loop`, `move`, `clone`, `remove [-k]`, `info`, `list`

### Animation Control (`//anim`)
```text
//anim <list|start|pause|resume|speed|frame|stop> [name|id|all] [value] [-k]
```
General controller for both `//animmodel` and `//video` instances. Stopping an animation restores the original terrain unless `-k` is set.

### 3D Wave Function Collapse (`//newwfc`)
```text
//newwfc <grid_size> [wfc_size=1] [variation_count=2] [adherence=1] [timeout=5] [-n <mask>] [-c] [-b] [-j] [-l <int>] [-g]
```
Generates 3D builds from variation cells in a grid (or from your clipboard). Bottom-layer variation blocks stay restricted to the bottom layer of the output.
- `-n <mask>` ignore blocks matching `<mask>` in variations and treat adjacent blocks like variation edges (defaults to `structure_void`)
- `-c` read variations from your clipboard instead of the selection
- `-b` keep the existing bottom layer in the selection and generate upward from it
- `-j` solve the whole selection as one continuous WFC area
- `-l <int>` lazy neighbor matching (allows placing a piece even if up to `<int>` neighbors don't list it)
- `-g` restrict outer walls and the ceiling layer to blocks from those boundary layers, just like the floor

### 2D Pixel Art (`//pixelart`)
```text
//pixelart <file|url> [width=64] [height=0] [-f] [-d] [-g] [-c] [-o]
```
Aliases: `//dbtimage`, `//dbtimg`

Converts `PNG`, `JPG`, `GIF`, `WEBP`, `SVG`/`SVGZ`, `BMP`, `TGA`, `ICO`, `PPM`, and `TIFF` images from `plugins/DragonBuildTools/images/` or a URL into upright wall art, flat floor builds (`-f`), or clipboard (`-o`).

### Heightmaps to Terrain (`//heightmap`)
```text
//heightmap <file|url> [size=80] [maxHeight=40] [colormap|none] [block] [-w] [-h] [-b] [-c] [-o]
```
Aliases: `//terrain`, `//dem`

Generates 3D terrain from a grayscale heightmap in `plugins/DragonBuildTools/heightmaps/` (or URL), using automatic elevation layers or an optional colormap image.

### 3D Text (`//text3d`)
```text
//text3d <height> <depth> <block> <text> [-v] [-b] [-c] [-o]
```
Aliases: `//3dtext`

Extrudes 3D text using `<block>` (can be any WorldEdit pattern). Use `_` for spaces, `-v` for beveled edges, and `-o` to copy to clipboard.

## Permissions

- `dragonbuildtools.use` - Access to DBT commands and GUI (default: `op`)
- `dragonbuildtools.admin` - Manage active server animations (default: `op`)

## Building

```bash
./gradlew build
```

The compiled JAR is written to `build/libs/DragonBuildTools-2.1.0.jar`.

---
*Logo by Dustbeam.*
