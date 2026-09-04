# RLTX

A RuneLite plugin that replaces the GPU renderer with a Vulkan ray tracer. Pure triangles with
ray traced lighting: sun and moon that follow real time and place, local lights with shadows,
path traced bounce light, water with refraction and reflection, weather, volumetric mist and
light shafts, and a photo mode. The Vulkan output is composited through the client's OpenGL
canvas, so RuneLite's own interface is untouched.

## Requirements

- A GPU and driver with Vulkan 1.2 ray queries (`VK_KHR_ray_query`) and external memory and
  semaphore file descriptors. Developed on an NVIDIA RTX 4070 Ti on Linux.
- Java 11 or later to build; the client runs on RuneLite's Java 21.
- `glslangValidator` on the path to compile the shaders.

## Building

    ./gradlew build

The tests exercise the camera maths, the solar almanac, the weather mapping, the water table
and the runoff simulation. `./gradlew shadowJar` produces a jar bundling the LWJGL Vulkan
bindings; `./gradlew launchScript` writes a launcher that starts the client in developer mode
with the plugin on the classpath, which `tools/install-jagex-wrapper.sh` can hook into the Jagex
Launcher's RuneLite install.

## Third-party components and notices

This project has no licence of its own yet; see the note at the end.

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

No licence file has been chosen for RLTX's own code yet, so by default all rights are reserved
to its author. Because the project incorporates BSD 2-Clause code and data from 117 HD, a
BSD 2-Clause licence would be the compatible and conventional choice when one is added.
