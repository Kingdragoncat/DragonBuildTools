<p align="center">
  <img src="assets/logo.png" width="180" alt="Dragon Build Tools">
</p>

<h1 align="center">Dragon Build Tools</h1>

<p align="center">
  Extra building, 3D importing, animation, and procedural generation tools for <b>FastAsyncWorldEdit</b>.
</p>

---

Dragon Build Tools (DBT) is a Paper/Spigot addon for FastAsyncWorldEdit that adds commands for importing 3D models, images, heightmaps, videos/GIFs, 3D text, and running 3D Wave Function Collapse builds directly in-game.

When converting 3D meshes, terrain, or text, DBT doesn't just place full cubes. It checks surface angles and sub-voxel occupancy to place directional stairs, top/bottom slabs, walls, fences, carpets, and snow layers (1-7) where they fit, while avoiding ugly stacked stairs or off-hue blocks on neutral stone/white builds. All static generation commands run through FAWE edit sessions so `//undo` and `//redo` work normally.

## Requirements

- **Server:** Paper, Purpur, or Spigot (`1.20.4` - `1.21.x`, `26.1` - `26.3`)
- **Java:** 21+
- **Dependency:** [FastAsyncWorldEdit (FAWE)](https://intellectualsites.github.io/download/fawe.html)
- *(Optional)* `ffmpeg` installed on the host machine if you want to load `.mp4` / `.webm` / `.mov` files with `//video` (animated `.gif` files work out of the box with no extra dependencies).

## Installation

1. Put `DragonBuildTools-1.0.0.jar` into your server's `plugins/` folder alongside FastAsyncWorldEdit.
2. Start or restart the server.
3. Drop any local files you want to use into `plugins/DragonBuildTools/` (`images/`, `models/`, `videos/`, `animations/`, or `heightmaps/`), or pass direct `http://` / `https://` URLs in commands.

Run `/dbt help` (or `/dbt help <command>`) in-game for usage details.

## Commands

### 3D Models (`//model`)
```text
//model <file|url> [size=48] [block] [rx=0] [ry=0] [rz=0] [-s] [-d] [-g] [-l] [-b] [-c]
```
Aliases: `//3dmodel`, `//voxel`, `//dbtmodel`

Imports `.obj` (including `.mtl` materials and textures), `.glb`, `.gltf`, `.stl`, `.ply`, `.vox`, and `.off` files from `plugins/DragonBuildTools/models/` or a URL.
- `-s` fill the inside of the mesh solid
- `-l` apply directional shading
- `-b` full blocks only (turn off stairs/slabs/sub-blocks)
- `-d` disable color dithering
- `-g` disable stained glass for translucent surfaces
- `-c` center on your position

### Animated 3D Models (`//animmodel`)
```text
//animmodel <file|folder|url> [size=40] [mode=wingflap] [frames=16] [fps=8] [block] [-s] [-b] [-n] [-c]
```
Aliases: `//3danim`, `//animate3d`

Animates a 3D model in the world by pre-voxelizing frames and only swapping the blocks that change between frames. Supports multi-frame `.vox` files, numbered mesh folders in `plugins/DragonBuildTools/animations/`, or procedural motion modes (`wingflap`, `spin`, `hover`, `breathe`, `swing`) on a static model.
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
- Other controls:
  - `//video start <name|all>`
  - `//video pause <name|all>`
  - `//video stop <name|all> [-r]` (`-r` hides the screen and restores the original blocks behind it)
  - `//video restart <name|all>`
  - `//video seek <name> <frame>`
  - `//video fps <name|all> <fps>`
  - `//video loop <name> [on|off]`
  - `//video move <name> [-c]`
  - `//video clone <src_name> <new_name>`
  - `//video remove <name|all> [-k]` (`-k` leaves the current frame placed as blocks)
  - `//video list` / `//video info <name>`

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
//pixelart <file|url> [width=64] [height=0] [-f] [-d] [-g] [-c]
```
Aliases: `//dbtimage`, `//dbtimg`

Converts `PNG`, `JPG`, `GIF`, `WEBP`, `SVG`/`SVGZ`, `BMP`, `TGA`, `ICO`, `PPM`, and `TIFF` images from `plugins/DragonBuildTools/images/` or a URL into upright wall art or flat floor builds (`-f`).

### Heightmaps to Terrain (`//heightmap`)
```text
//heightmap <file|url> [size=80] [maxHeight=40] [colormap|none] [block] [-w] [-h] [-b] [-c]
```
Aliases: `//terrain`, `//dem`

Generates 3D terrain from a grayscale heightmap in `plugins/DragonBuildTools/heightmaps/` (or URL), using either automatic elevation layers or an optional colormap image.
- `-w` fill low basins with water
- `-h` hollow shell

### 3D Text (`//text3d`)
```text
//text3d <height> <depth> <block> <text> [-v] [-b] [-c]
```
Aliases: `//3dtext`

Extrudes 3D text using `<block>` (can be any WorldEdit pattern). Use `_` for spaces and `-v` for beveled edges.

## Permissions

- `dragonbuildtools.use` - Access to DBT building commands (default: `op`)
- `dragonbuildtools.admin` - Manage active server animations (default: `op`)

## Building

```bash
./gradlew build
```

The compiled JAR is written to `build/libs/DragonBuildTools-1.0.0.jar`.

---
*Logo by Dustbeam.*
