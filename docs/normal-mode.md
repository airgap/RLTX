# Normal mode: a lighter backend behind the same plugin

Uber mode is the renderer as it stands: a Vulkan ray tracer. Normal mode is a second
backend that rasterises primary visibility and fakes the light transport, so it runs on a
GPU with no ray-query support and at a fraction of the cost. Both live in one plugin behind
one switch. Normal is the default and the front door; Uber is the tier you unlock by owning
the hardware.

Normal is **not** Uber with the sliders turned down. Uber with everything off is flat and
aliased (see the photo path before it got its jitter back). Normal is a purpose-built raster
look that has to be tuned on its own. The point of this document is to fix the seam between
the two backends so that everything worth sharing is shared and only the middle forks.

## Where the seam falls

Most of the plugin is not the ray tracer. Three layers, and only the middle one forks:

- **Front end (shared).** Geometry pulled from the client scene — static zones, dynamic
  actors, foliage, water — plus textures, relief maps, materials, local lights, and the
  environment/weather/season/time systems. All of it decides *what* to draw and hands the
  backend a `FrameParams` and a set of geometry buffers. None of it knows how a pixel is
  found.
- **Middle (forks).** Primary visibility, shadows, and the lighting integration. Uber traces
  it; Normal rasterises a G-buffer and shades it with shadow maps, screen-space and planar
  reflections, froxel volumetrics, and probe or baked irradiance.
- **Back end (shared).** Sky, light shafts, bloom, exposure, and post (tonemap, depth of
  field, grain, aberration, colour grade), then the upscale and the blit through RuneLite's
  GL canvas. These are image-space compute passes that read a G-buffer, not ray hits.

## The shared input: `FrameParams`

`rltx.vk.FrameParams` is already the whole contract for what a backend is told each frame —
camera, sun and sky, weather, seasons, the light list, and every look and post knob. It is
backend-agnostic today and stays verbatim. Normal reads the same struct Uber does; a field
Normal cannot honour physically (a diffuse `bounces` count, say) it approximates or ignores,
but the struct does not grow a Normal-only twin.

The uploaded geometry, material, texture, relief, and environment buffers (the `setX`/`push`
calls the front end already makes) are likewise shared inputs. A backend consumes them; it
does not own them.

## The shared interstage contract: what Normal must produce

For the back half to run unchanged, Normal must fill the same three per-pixel images the
trace currently writes, at the internal (pre-upscale) resolution:

| Image        | Format                     | Meaning                                              |
|--------------|----------------------------|------------------------------------------------------|
| scene colour | `R16G16B16A16_SFLOAT`      | linear HDR radiance before any post                  |
| linear depth | `R32_SFLOAT`               | view-space depth, for shafts, fog, DoF, and upscale  |
| motion       | `R16G16_SFLOAT`            | screen-space motion vectors, for TAA/DLSS and blur   |

The final `presented` image (`R8G8B8A8_UNORM`, backed by external memory and blitted to GL)
is written by the shared back end, not the backend — both modes hand it the same three
inputs and get the same output path, `outputHandle()` / `importSceneImage`.

Light shafts are the one back-half pass that is not purely image-space: it needs the sun's
visibility along each view ray. Uber marches a shadow ray; Normal samples its shadow map or
a low-res scattering volume. So `shafts.comp` stays shared but takes a **sun-visibility
source** as an input the backend supplies — a shadow lookup, not a TLAS.

## What Uber produces that Normal skips

Uber additionally writes the noisy radiance and the RT-denoiser/DLSS guide buffers — albedo,
normal, specular albedo, bias — and builds the BLAS/TLAS. Normal shades clean directly, so it
**skips the whole denoiser** (`resolve` + `atrous`) and never builds an acceleration
structure. Ray Reconstruction, which stands in for that accumulation, is inherently Uber-only.
DLSS drops to what it is on any raster engine: an optional upscaler/AA fed by colour, depth,
motion, and jitter — no longer mode-defining.

## Pass ownership

The eleven compute pipelines, sorted by who owns them:

| Pipeline              | Owner            | Note                                                    |
|-----------------------|------------------|---------------------------------------------------------|
| `trace`               | Uber only        | the ray tracer; Normal replaces it with a raster G-buffer + shading |
| `resolve`, `atrous`   | Uber only        | RT temporal accumulation + wavelet denoiser; Normal shades clean |
| `ripple`, `displace`  | mostly shared    | water field sim and displacement; feed either backend's water |
| `motion`              | shared output    | Normal derives motion from the raster reprojection, same buffer |
| `shafts`              | shared + shim    | needs a sun-visibility source per backend               |
| `bloom`               | shared           | image-space                                             |
| `post`                | shared           | tonemap, DoF, grain, aberration, grade — image-space    |
| `exposure`            | shared           | meters the resolved frame                               |
| `upscale`             | shared           | plain upscale when DLSS is off                          |

Nine of eleven passes are shared or nearly so. The new work is `trace`'s raster replacement
and the shadow/GI/reflection approximations that feed the scene-colour buffer.

## Mode-specific settings and the panel

Settings split three ways: shared (all of `FrameParams`' look and atmosphere knobs), Uber-only
(diffuse bounces, Ray Reconstruction, glossy reflection rays, AO rays), and Normal-only
(shadow-map cascade count and resolution, SSR quality, probe density). The `ControlPanel`
should hide or grey the controls that do not apply to the active mode, and the slider
cost-colouring keys off the active backend — Uber carries the red-hot sliders, Normal's run
cool. A setting that exists in both but means different things (shadow softness: penumbra rays
vs. PCF kernel) keeps one label and each backend interprets it.

## Switching modes

Mode is a **startup setting** first. Swapping backends tears down and rebuilds most GPU state,
so changing it asks for a client restart — trivial for a once-in-a-while choice. Hot-swap is a
later luxury, not a launch requirement.

## What Normal approximates, and what it loses

Honest gaps, to be stated in the settings and docs rather than hidden behind a slider that
pretends to do the same work:

- **Global illumination** becomes probe or baked irradiance — low-frequency, no sharp indirect
  or crisp colour bleed. (Uber already runs at `bounces=0` for many, so the floor is close.)
- **Reflections** are screen-space plus planar water; off-screen geometry does not reflect, and
  SSR ghosts at screen edges. OSRS's matte world hides most of this.
- **Shadows** are cascaded maps plus a screen-space contact pass; expect cascade seams and
  penumbra that approximates rather than resolves. Fog, which RLTX leans on, hides much of it.
- **Ambient occlusion** is SSAO/GTAO, an approximation of the contact darkening RT gets exactly.

The through-line: raster is a bag of tricks that occasionally disagree at boundaries, where
Uber is one coherent light transport. Normal's tuning budget goes into hiding those seams, and
atmosphere is the main tool for it.

## First milestone

Prove the vibe before building the expensive half. The cheapest front end that could look like
RLTX: a raster G-buffer, cascaded shadow maps, height/distance fog, and the **existing** sky
and post passes run over it. Put that frame next to an Uber screenshot of the same spot and
judge the gap before committing to probe GI and reflective water. If the fog, sky, shadows, and
colour grade land, the rest is polish; if they do not, no amount of reflection accuracy saves it.
