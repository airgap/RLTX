# RLTX

A RuneLite plugin that replaces the GPU renderer with a Vulkan ray tracer. The game's own
triangles are lit by traced light: a sun and sky that follow real time and place, local lights
with shadows, bounced light, reflective and refractive water, weather, and a photo mode that
renders noise-free stills and video. The traced image is composited back through the client's
OpenGL canvas, so RuneLite's interface is untouched.

![home](docs/screenshots/home.png)

![swamp](docs/screenshots/swamp-sunset.png)

![standoff](docs/screenshots/standoff.png)

![forge](docs/screenshots/forge.png)

![silhouette](docs/screenshots/moonrise.png)

![library](docs/screenshots/library.png)

## What it does

Everything below has its own setting.

**Light.** Ray traced sun and moon shadows, local lights from 117 HD's light data including
spells and projectiles, path traced bounce light, glossy and wet reflections, and a
line-of-sight mode that darkens what your character could not see.

**Sky.** A procedural sky computed from sunlight scattering in the atmosphere, or your own
skybox pack. The sun follows the clock and your location; at night the real stars and Milky Way
turn overhead and the moon lights the scene from where it truly is, showing its phase.
Volumetric clouds, aurora at high latitudes, rainbows after rain, a lens flare.

**Weather and seasons.** Real weather for your location or a preset: cloud, fog, rain, snow,
storms and wind. Wet ground, puddles and a small runoff simulation; mist over swamps and
graveyards; smoke from chimneys and fires with heat shimmer above them. Seasons from the date
turn and drop the leaves, bare the trees in winter and blossom in spring.

**Water.** A wind-driven wave spectrum with refraction, reflection, caustics and rain ripples.
The free camera can go beneath the surface.

**Life.** Fireflies, dust in sunbeams, birds, bats and butterflies. Footprints in snow and wet
ground, ripples from steps in puddles, plants pushed aside by whoever walks through them.

**Photography.** A photo mode that hides the interface and accumulates each photo over hundreds
of frames through a real thin lens, giving clean images with true bokeh. Click to focus, focus
peaking, a linear HDR file beside each shot, a quad-resolution photo key, a free camera tethered
to your character, and a cinema mode that renders keyframed camera paths, clock and all, as
frame sequences for video. Bloom, vignette, grain, aberration, soft glow and colour grading.

**Other plugins.** Shortest Path's route becomes a trail worn into the ground with wisps of light
along it, or a glowing ribbon. Ground Markers' tiles become pools of light. NPCs highlighted by
NPC Indicators, Slayer and others wear a rim of their colour. Your character can carry a lit
torch that casts real light.

Nothing here reads or changes gameplay. The plugin draws only what the client already has, and
sends nothing to the game.

## Requirements

- Linux, or Windows. Linux is where RLTX is developed and played; the Windows path compiles but
  has not yet been run. No macOS: Apple's drivers have no Vulkan ray queries.
- A GPU and driver with Vulkan 1.2 ray queries and external memory sharing. Developed on an
  NVIDIA RTX 4070 Ti.
- A JDK, 17 or newer, and `glslangValidator` on the path (`glslang-tools` on Debian and Ubuntu,
  `glslang` on Arch and Fedora, the Vulkan SDK on Windows).
- RuneLite installed through the Jagex Launcher, to play with a Jagex account.
- For DLSS, optional: an RTX GPU, `gcc` and `git`. The launch script fetches NVIDIA's DLSS SDK
  from GitHub and compiles a small bridge to it; without them the DLSS setting says so in the log
  and does nothing.

## Building

    ./gradlew build

Compiles the plugin and shaders and runs the tests.

## Installing

RuneLite disables plugin sideloading whenever a launcher starts it, and RLTX is not on the Plugin
Hub, so the Jagex Launcher is made to start a client of our own with the plugin built in.

First, on either system:

    ./gradlew launchScript

This writes a launch script into `build` with this machine's classpath: `rltx-client.sh` on
Linux, `rltx-client.cmd` on Windows. Rerun it after any code change.

**Linux.** The launcher runs `~/.local/share/Jagex Launcher/games/runelite/RuneLite.AppImage`.
Install RuneLite from the launcher and start it once, then run `tools/install-jagex-wrapper.sh`.
It keeps the AppImage as `RuneLite.AppImage.stock` and puts a wrapper in its place that starts
our client. Press Play. To use the stock client without uninstalling, create the file
`~/.runelite/rltx-use-stock`; to uninstall, rename the stock AppImage back.

**Windows.** The launcher runs `RuneLite.exe`, a stub that reads `config.json` beside it for the
class path and main class to start. Install RuneLite from the launcher and start it once, then in
PowerShell run `.\gradlew.bat launchScript` and `.\tools\install-jagex-launcher.ps1`. It keeps
the original as `config.json.stock`. Press Play. To uninstall, copy the stock file back.
Reinstalling RuneLite also rewrites `config.json`, after which the script needs running again.

In the client, turn off the GPU plugin and 117 HD, then turn on RLTX. Console output goes to
`~/.runelite/logs/rltx-console.log`, because the launcher never reads the pipe it gives the
client. If Vulkan setup fails, RLTX turns itself off and the reason is in that log.

`./gradlew run` starts the developer-mode client without the launcher, for a legacy account or a
quick check; `-PruneliteHome=/some/dir` keeps it away from your real profile. `./gradlew
shadowJar` builds a sideloadable jar for clients started in developer mode.

## Settings

Settings live in RuneLite's sidebar under RLTX, and in a floating panel on F8 for when the
sidebar is out of the way; `docs/settings.md` lists every one with its default. The panel also
holds three tabs the sidebar cannot: Presets save every setting to a file or the clipboard and
load them back; Areas bind settings to places, bounded by polygons walked corner by corner or by
misty ground, applied when you enter and undone when you leave, with starter areas for
Lumbridge bundled; Cinema records, previews, saves and renders camera paths. Keys, all
rebindable: F11 photo mode, F9 quad-resolution photo, F8 settings panel, F10 free camera, and
with the free camera on, Ctrl+K, Ctrl+Shift+K, Ctrl+Alt+K and Ctrl+Alt+P to record, clear, render
and preview a cinema path.

Skyboxes are your own files: the Skybox setting lists the Fantasy Skybox pack by Render Knight
and needs its `Materials` folder in the pack folder setting. Without it, use the procedural sky.

## Licence and notices

RLTX is released under the BSD 2-Clause License; see `LICENSE`.

- **117 HD** (https://github.com/117HD/RLHD), BSD 2-Clause, copyright (c) 2021, 117; licence
  bundled as `src/main/resources/rltx/hd/LICENSE-117HD.txt`. Used: `lights.json` and
  `materials.json` unchanged, a table of the ids they name, its water type table, and its water
  shading, shading reversal, light placement, flicker and falloff, ported into the shaders and
  Java here. Six ground textures from its pack are in `src/main/resources/rltx/hd/ground/` with
  the pack's provenance notes beside them: gravel (3dtextures.me, CC0), snow (AmbientCG, CC0),
  sand (from a photograph by Romain Dancre, Unsplash licence), rock (117 HD, BSD 2-Clause),
  grass and dirt (117 HD, BSD 2-Clause). None of the textures 117 HD marks as derived from
  Jagex's property are included.
- **LWJGL** (https://www.lwjgl.org), BSD 3-Clause, bundled in the shadow jar; licence in
  `src/main/resources/rltx/LICENSE-LWJGL.txt`.
- **RuneLite** (https://runelite.net), BSD 2-Clause; built against, not redistributed.
- **Yale Bright Star Catalogue**, 5th revised edition (Hoffleit and Warren 1991), from the CDS
  VizieR archive as V/50; its 9,096 stars are repacked into `src/main/resources/rltx/stars.bin`.
- **Open-Meteo** (https://open-meteo.com), weather data under CC BY 4.0, fetched in the
  real-weather mode. **ipapi.co** (https://ipapi.co) supplies an approximate location in the
  real time and place mode.
- **Old School RuneScape** models, textures and terrain are read from the running client and are
  not distributed. Jagex Ltd. owns RuneScape and its content; this is a fan project under the
  Jagex Fan Content Policy.
