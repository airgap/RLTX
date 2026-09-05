# RLTX

A RuneLite plugin that replaces the GPU renderer with a Vulkan ray tracer. Pure triangles with
ray traced lighting: sun and moon that follow real time and place, local lights with shadows,
path traced bounce light, water with refraction and reflection, weather, volumetric mist and
light shafts, and a photo mode. The Vulkan output is composited through the client's OpenGL
canvas, so RuneLite's own interface is untouched.

## Screenshots

All four were taken in game with the plugin's photo mode.

![Night rain outside a stone house, lit by two wall torches and a window](docs/screenshots/rain-at-the-door.png)

Night rain in front of a stone house. The only light is the two wall torches and the window;
the rain and the wet ground pick it up, and the shallow focus comes from the depth of field
setting.

![Sunset over the swamp, mist between the dead trees, the castle on the skyline](docs/screenshots/swamp-sunset.png)

Sunset over the swamp, with the castle on the skyline. The mist between the dead trees is
volumetric, lit by the low sun and the sky, and thins as the ground rises.

![A cottage under a full moon, its windows and doorway glowing](docs/screenshots/moonlit-cottage.png)

A cottage under the full moon, with the photo tilt lowering the camera. Moonlight and the
warm light spilling from the windows and doorway are both ray traced, shadows included.

![The player silhouetted against the rising moon](docs/screenshots/moonrise.png)

The moon rising behind the player, seen from the free camera. Sun and moon follow the real
time and place, so this is what the sky looked like when the photo was taken.

## Requirements

- Linux. The install route below goes through the Jagex Launcher's Linux AppImage; there is no
  Windows or macOS path yet.
- A GPU and driver with Vulkan 1.2 ray queries (`VK_KHR_ray_query`) and external memory and
  semaphore file descriptors. Developed on an NVIDIA RTX 4070 Ti.
- A JDK, 17 or newer. The client runs on whichever Java runs the build.
- `glslangValidator` on the path to compile the shaders: package `glslang-tools` on Debian and
  Ubuntu, `glslang` on Arch and Fedora.
- RuneLite installed through the Jagex Launcher, to play with a Jagex account.

## Building

    ./gradlew build

Compiles the plugin and the shaders and runs the tests. Gradle fetches RuneLite and LWJGL from
RuneLite's repository and Maven Central on the first run.

## Installing

RuneLite switches developer mode off whenever a launcher starts it, and with it goes plugin
sideloading. RLTX is not on the Plugin Hub, so the way to play with a Jagex account is to have
the Jagex Launcher start a client of our own with the plugin built in. The launcher runs
`~/.local/share/Jagex Launcher/games/runelite/RuneLite.AppImage` with the login session in its
environment, and the installer keeps that file and puts a small wrapper in its place that runs
our client instead.

1. Install RuneLite from the Jagex Launcher if you have not already, and start it once.
2. Write the launch script:

       ./gradlew launchScript

   This compiles everything and writes `build/rltx-client.sh` with this machine's classpath.
   The script points at the build output, so after any code change rerunning this one command
   and restarting the client is all that is needed.
3. Install the wrapper:

       tools/install-jagex-wrapper.sh

   The original AppImage is kept beside it as `RuneLite.AppImage.stock`.
4. Press Play in the Jagex Launcher. The client that opens is RuneLite in developer mode with
   RLTX in its plugin list.
5. In the client, turn off the GPU plugin and 117 HD, or any other renderer plugin; only one
   can own the canvas. Then turn on RLTX.

Console output of the launcher-started client goes to `~/.runelite/logs/rltx-console.log`. The
launcher never reads the pipe it hands the client, and a full pipe would freeze the game, so
the script writes to a file instead. If Vulkan setup fails, RLTX disables itself and the reason
is in that log.

To play the stock client without uninstalling, create the file `~/.runelite/rltx-use-stock`
and delete it to come back; the wrapper also falls back to the stock client whenever the launch
script is missing. To uninstall, delete `RuneLite.AppImage` in the folder above and rename
`RuneLite.AppImage.stock` back to `RuneLite.AppImage`.

### Skyboxes

The Skybox setting lists the skies of the Fantasy Skybox pack by Render Knight and needs its
`Materials` folder in the Skybox pack folder setting. Without the pack, leave Skybox on None and
the procedural sky is used. No skybox images are distributed here.

## Running from Gradle

    ./gradlew run

starts the developer-mode client directly, without the launcher, which works for a legacy
account login or for checking that the renderer comes up. Add `-PruneliteHome=/some/dir` to
use a separate RuneLite home so your real profile is untouched. A fresh home has the GPU plugin
on by default; turn it off in that client before enabling RLTX.

## Sideloadable jar

    ./gradlew shadowJar

builds `build/libs/rltx-0.1.0-SNAPSHOT-all.jar`, the plugin with the LWJGL Vulkan bindings
included, for `~/.runelite/sideloaded-plugins/` in a client started in developer mode. LWJGL's
core is left out because RuneLite ships its own copy. The launcher-started client never loads
sideloaded plugins, which is why the wrapper above exists.

## Third-party components and notices

RLTX itself is released under the BSD 2-Clause License; see `LICENSE`.

**117 HD** (https://github.com/117HD/RLHD), BSD 2-Clause, copyright (c) 2021, 117. Its licence is
bundled as `src/main/resources/rltx/hd/LICENSE-117HD.txt`. The plugin uses, in original or
adapted form:

- `lights.json` and `materials.json`, bundled unchanged, and `light_ids.json`, a table of the
  object and NPC ids those files name, extracted from 117 HD's game value list.
- Its water type table, from which `WaterType.java` is generated; its water shading, terrain
  and model shading reversal, light placement, flicker and pulse, and point light falloff,
  which are ported into the shaders and Java here.
- Six ground textures from its texture pack, in `src/main/resources/rltx/hd/ground/`, with the
  pack's own provenance notes bundled beside them as `LICENSES.txt`:
  - `gravel.jpg`, `gravel_n.png`: 3dtextures.me, CC0.
  - `snow_1.jpg`, `snow_1_n.png`: AmbientCG, CC0.
  - `sand_1.jpg`, `sand_1_n.png`: derived from a photograph by Romain Dancre on Unsplash, under
    the Unsplash licence.
  - `rock_1.jpg`, `rock_1_n.png`: 117 HD's own, BSD 2-Clause.
  - `grass_1.jpg`, `dirt_1.jpg`, `dirt_1_n.png`: not itemised in the pack's notes; distributed
    by 117 HD under its BSD 2-Clause licence as textures of the project.

  None of the textures 117 HD marks as derivative of Jagex's intellectual property are included.

**LWJGL** (https://www.lwjgl.org), BSD 3-Clause, bundled in the shadow jar; its licence is in
`src/main/resources/rltx/LICENSE-LWJGL.txt`.

**RuneLite** (https://runelite.net), BSD 2-Clause. The plugin builds against the RuneLite client
and its `rlawt` OpenGL context and does not redistribute them.

**Open-Meteo** (https://open-meteo.com), weather data under CC BY 4.0. In the real-weather mode
the plugin fetches current conditions from Open-Meteo. Weather data by Open-Meteo.com.

**ipapi.co** (https://ipapi.co) supplies the machine's approximate location in the real time and
place sun mode, under its free tier terms; no data is sent beyond the request itself.

**Old School RuneScape** assets, the models, textures, terrain and object definitions the plugin
renders, are read from the running game client at runtime and are not distributed with this
project. The bundled 117 HD data describes game objects by id and name. Jagex Ltd. owns
RuneScape and its content; this is a fan project under the Jagex Fan Content Policy.

The skyboxes the plugin can load are the user's own files, chosen by directory; none are
distributed here.

### Licence of this project

RLTX is licensed under the BSD 2-Clause License, the same terms as the 117 HD code and data it
incorporates; the full text is in `LICENSE`.
