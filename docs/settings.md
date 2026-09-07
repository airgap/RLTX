# RLTX settings

Generated from the plugin's configuration by `./gradlew settingsDoc`; do not edit by hand.

## Sun

| Setting | Default | Description |
| --- | --- | --- |
| Sun and moon | true | Untick to remove the sun and moon entirely, for caves and mines: no direct light, shadows, glints or beams, leaving the sky, local lights and the ambient floor. |
| Sun position | Real time and place | Real time and place follows the real sun for where this machine actually is, found from its network address, and the real clock, overriding every other sun setting including a skybox's painted sun. Real time, chosen place uses the latitude, longitude and time offset below instead. Manual uses the azimuth and elevation. |
| Latitude | 45° (-90 to 90) | Degrees north of the equator, negative for south. Used by Real time, chosen place, and as the fallback before the real place is known. |
| Longitude | -60° (-180 to 180) | Degrees east of Greenwich, negative for west. Used by Real time, chosen place; the default is estimated from the system time zone. |
| Time offset | 0 h (-12 to 12) | Hours added to the clock in Real time, chosen place, to preview another time of day. Real time and place ignores it. |
| Moonlight | 25% (0 to 50) | Direct light at night as a fraction of the sun's intensity, cast from opposite the sun |
| Azimuth | 225° (0 to 359) | Manual mode: compass direction the sunlight comes from. 0 is north, 90 is east. |
| Elevation | 50° (-90 to 90) | Height of the sun above the horizon in manual mode. In manual mode a skybox with a visible sun or moon overrides this so shadows match it. |
| Intensity | 100% (0 to 300) | Brightness of direct sunlight |
| Sun size | 3° (0 to 20) | Apparent diameter of the sun in degrees. Larger values give softer shadow edges; 0 gives hard shadows. |
| Ray traced shadows | true | Trace a shadow ray toward the sun from every visible point |
| Light shafts | 100% (0 to 300) | Sunlight scattered by the air, marched along every view ray with shadow rays toward the sun, so beams form through trees and openings. 0 disables. |
| Sun disc size | 5 ⁄10° (2 to 100) | Apparent diameter of the sun or moon disc in the procedural sky, in tenths of a degree; the real sun is about 5. Visual only: shadow softness comes from Sun size. |
| Hidden roofs shade interiors | true | Roofs the client hides so you can see inside still keep out the sun and sky, so rooms are lit through their windows, doors and lamps rather than lying open to the sky. |

## Sky and indirect

| Setting | Default | Description |
| --- | --- | --- |
| Local lights | true | Torches, fires, lamps and glowing things from 117 HD's light data, each casting ray traced shadows. |
| Local light strength | 100% (0 to 300) | Scales the brightness of all local lights. |
| Sampled local lights | true | With more than four lights in range, each pixel traces shadow rays to two lights chosen by how much they would contribute and averages the rest over frames. Off traces a shadow ray to every light in range. |
| Local light range | 100% (50 to 400) | Scales how far every local light reaches. Braziers and torches in mines carry further with more. |
| Skybox | None (flat colour) | Sky image from the Fantasy Skybox pack, used as the background and as the sky light. A fixed sky does not move the sun; the 'follows time of day' entries switch with the sun position. Requires the pack folder below. |
| Procedural sky | false | Replace the skybox with an analytic sky: a gradient that follows the sun through the day, a sun or moon disc, stars at night, and clouds from the weather. The light then always matches the sky. |
| Cloud shadows | true | Drifting cloud shadows over the ground whenever the weather has cloud cover. |
| Skybox pack folder | /home/nicole/Downloads/Fantasy Skybox/Materials | Path to the Materials folder of the Fantasy Skybox pack |
| Aerial perspective | 100% (0 to 300) | Air between you and distant scenery scatters blue into it by day and warmth at dusk, lifting far shadows before the fog hides them. 0 disables. |
| Skybox rotation | 0° (0 to 359) | Extra turn applied to the sky image. Images with a visible sun or moon are already aligned to the light direction. |
| Sky colour | #87aeeb | Colour of the sky when no skybox is selected. Lights surfaces that can see the sky. |
| Sky intensity | 45% (0 to 200) | Brightness of the sky light |
| Light bounces | 2 (0 to 4) | Diffuse bounces traced per pixel. One lights surfaces from their neighbours and the sky; two or more carry light around corners into enclosed spaces. 0 disables indirect light. |
| Ambient floor | 5% (0 to 100) | Light that reaches every surface regardless of occlusion |
| Exposure | 180% (25 to 400) | Scales the lit result before the filmic tone curve. 100 keeps midtones roughly as lit. |
| Real stars | true | With the procedural sky, the night shows the real stars and Milky Way for your place and the hour, and the moon lights the scene from where it truly is, showing its phase. Star positions come from the Yale Bright Star Catalogue. |
| Star brightness | 60% (0 to 300) | Brightness of the real stars and Milky Way |
| Physical atmosphere | true | Computes the procedural sky from sunlight scattering in the air, for the true colours of dawn, dusk and twilight; off keeps the painted gradient. |
| Aurora | Where it belongs | Curtains of aurora on clear nights: where they belong, above about fifty degrees of latitude by your real place, or everywhere |

## Weather

| Setting | Default | Description |
| --- | --- | --- |
| Weather | REAL_TIME | Real weather for the sun's location, fetched every 10 minutes (weather data by Open-Meteo.com, CC BY 4.0); a chosen preset; or none. |
| Preset | RAIN | Conditions used when the weather is set manually. |
| Precipitation density | 100% (0 to 200) | Scales how much rain and snow falls for the given conditions. |
| Fog | 100% (0 to 200) | Scales the distance fog of foggy, rainy and snowy conditions. |
| Swamp mist | 100% (0 to 200) | Low mist drifting over swamp water and the ground around it. 0 disables. |
| Mist everywhere | false | Lay the ground mist over the whole scene instead of only swamps and graveyards. |
| Rain speed | 100% (30 to 300) | How fast drops fall; 100 is about seven tiles a second for the heaviest drops. |
| Rain streak length | 100% (30 to 300) | Length of each drop's streak; 100 is just under a tile. |
| Mist indoors | false | Lets mist drift through roofed rooms as well. Off keeps it outside; an area can turn it on for a haunted or cursed place, as the bundled Draynor Manor area does. |
| Lightning | true | Flashes of light during thunderstorms. |
| Rain ripples | true | Rings spreading on water surfaces where raindrops land. |
| Rain runoff | true | Simulate water on the terrain while it rains: it collects in hollows, runs down slopes into streams, and drains away afterwards, replacing the fixed puddle spots. |
| Puddles | true | Dips in the ground fill with mirror-like water while it rains and dry out afterwards. |
| Foliage wind | true | Trees, bushes and plants near the camera sway in the wind, their bases fixed and canopies moving. |
| Foliage wind strength | 100% (0 to 300) | How far foliage bends; the weather's wind adds to it. |
| Foliage wind range | 16 (4 to 32) | How many tiles from the camera foliage is animated. Each swaying tree is rebuilt every frame, so this is the main cost of the effect. |
| Fireflies | true | Fireflies drifting over swamps and graveyards on dry nights |
| Dust motes | true | Specks of dust hanging in the air, seen where sunlight catches them |
| Smoke | true | Smoke rising from chimneys and fires, carried by the wind |
| Season | Real date and place | Leaves turn and fall through autumn, trees stand bare in winter and blossom in spring, with the ground and a chosen weather preset following suit, by the real date for your hemisphere or a season of your choosing. |
| Wildlife | true | Flocks of birds wheeling by day and at dusk, bats over swamps and graveyards at night, and butterflies low over the ground in fair weather |
| Rainbows | true | A rainbow, and its fainter second bow, when the sun is low behind you and rain is falling or the ground is still wet |
| Heat shimmer | true | Hot air above fires and braziers bending what stands behind it |

## Temporal

| Setting | Default | Description |
| --- | --- | --- |
| DLSS | Off | NVIDIA's DLSS super resolution reconstructs the view from a frame traced at the size its quality mode chooses, taking over antialiasing; it overrides the render scale. Needs an RTX GPU and the client started by the launch script, which builds the bridge to it. When unavailable the log says why and this does nothing. |
| Ray Reconstruction | false | NVIDIA's DLSS Ray Reconstruction denoises the traced frame in place of the temporal accumulation and the wavelet filter, using the albedo, normals, roughness, depth and motion vectors. Needs what DLSS needs; combines with DLSS or the render scale for the upscale. |
| Temporal accumulation | true | Reuse lighting from previous frames where the same surface was visible. Off gives a noisy but ghost-free image. |
| Render scale | 100% (50 to 100) | The fraction of the view's size the frame is traced and denoised at before being scaled up to fit. 75 to 85 saves a third to a half of the GPU time; photos and cinema renders are always traced at full size. |
| History frames | 32 (1 to 128) | How many frames of lighting are averaged on static surfaces. More frames mean less noise and slower response to changes. |
| History frames on models | 6 (1 to 64) | Frames averaged on players, NPCs and animated objects |
| Denoiser passes | 4 (0 to 5) | Edge-aware wavelet filter passes over the accumulated lighting. Each pass doubles the filter radius; 0 disables the denoiser. |
| Denoiser strength | 4 (1 to 16) | How far the filter reaches across brightness differences, in standard deviations of the estimated noise. Higher smooths more and softens lighting detail. |

## Camera

| Setting | Default | Description |
| --- | --- | --- |
| Antialiasing | true | Jitter each pixel's ray within the pixel every frame; the accumulation averages the samples into smooth edges |
| Aperture | 0 (0 to 64) | Lens radius in scene units, where a tile is 128. 0 keeps everything in focus; larger blurs more away from the focus distance. |
| Focus | Follow the player | Keep your character in focus, or focus at a fixed distance from the camera |
| Focus distance | 12 (1 to 60) | Fixed-distance focus, in tiles from the camera |
| Motion blur | 50% (0 to 100) | Shutter time as a fraction of the frame interval. Blurs camera movement and moving models; 0 disables it. |
| Auto exposure | true | Meter the scene's brightness and adapt the exposure to it over a second or two, like the eye. The Exposure slider then biases the result. |
| Vignette | 25% (0 to 100) | Darkens the corners of the view. 0 disables. |
| Bloom | 35% (0 to 100) | Glow bled out of anything brighter than white after exposure. 0 disables. |
| Render distance | 90 tiles (16 to 92) | How far the scene is drawn, in tiles. Zones beyond it leave the ray tracer, the last stretch fades into the horizon, and the client stops processing actors and clicks there. |
| Distance fade | 45% (5 to 95) | Share of the render distance over which scenery fades into the horizon. Larger values start the fade nearer and make the edge softer. |
| Film grain | 15% (0 to 100) | Fine random grain over the image, changing every frame and strongest in the shadows. 0 disables. |
| Chromatic aberration | 20% (0 to 100) | Colour fringing that grows toward the edges of the view, as a lens would give. 0 disables. |
| Contrast | 100% (50 to 150) | Colour grading: contrast around mid grey after tone mapping. |
| Saturation | 100% (0 to 200) | Colour grading: 0 is monochrome, 100 leaves colours as rendered. |
| Soft glow | true | Blend a blurred copy of the whole frame back over it; the sliders below set how much and how wide. |
| Colour temperature | 0 (-100 to 100) | Colour grading: negative cools the image toward blue, positive warms it toward orange. |
| Soft glow opacity | 25% (0 to 100) | A blurred copy of the whole frame blended back over it, softening contrast into a dreamy haze. 0 disables. |
| Soft glow radius | 40 px (8 to 240) | How widely the soft glow copy is blurred, in pixels at full resolution. |
| Photo tilt | false | Render from a pitch below the client's limit, by the angle set below; rendering only, so clicks do not line up while it is on. |
| Photo tilt angle | 0° (-60 to 60) | Lowers the rendered camera by this many degrees below the client's pitch, pivoting about what you are looking at, for shots from nearer the ground. Rendering only: the client still picks clicks from its own camera, so use it for photos. |
| Photo mode key | F11 | Hides every interface, overlay and text so only the scene shows. While hidden, click the top-left corner of the view to bring the interface back, or the bottom-right corner to save a photo to the screenshots folder under RLTX. |
| Line of sight | false | Darken what your character could not see from where they stand, so walls hide what lies beyond them. One extra ray per pixel from the character's eyes. |
| Unseen darkness | 85% (10 to 100) | How dark the unseen areas are drawn; 100 is black. |
| Free camera key | F10 | Detaches the rendered camera where it is and lets you fly it: W A S D move, Q and E go down and up, Shift speeds up, middle-drag looks around. The game keeps running underneath; clicks do not line up while detached. Press again to reattach. |
| Torch in hand | false | Shows your character carrying a lit torch in place of their weapon, with its own flickering light. Only you see it, and local lights must be on for the light. |
| Free camera speed | 100% (10 to 500) | Flight speed of the detached camera; 100 is six tiles a second. |
| Free camera range | 8 (2 to 40) | How far the free camera may stray from your character, in tiles. Kept short, it frames shots around you without becoming a way to look anywhere in the loaded area. |
| Photo burst | 150 (0 to 2000) | Frames of the same view accumulated before a photo is saved, so it has no noise or denoiser blur. The game pauses while they render; 0 saves the frame as shown. |
| Lens flare | 60% (0 to 200) | Glow, starburst, streak and ghost reflections from the sun or moon when it is in frame |
| Cinema keyframe key | Ctrl+K | In the free camera, records where the camera is and where it looks as the next keyframe of a cinema path. The cinema keys only act while the free camera is on. |
| Cinema clear key | Ctrl+Shift+K | Forgets the recorded cinema keyframes. |
| Cinema render key | Ctrl+Alt+K | Renders a smooth camera path through the keyframes as numbered PNG frames in a new folder under the screenshots folder, each accumulated like a photo, with the interface hidden. The game stalls while it renders; press again to stop early. |
| Cinema preview key | Ctrl+Alt+P | Plays the path live at normal quality without saving anything, to check the keyframes. Press again to stop. |
| Seconds per keyframe | 4 (1 to 30) | How long the camera takes to travel from one cinema keyframe to the next |
| Cinema frame rate | 30 (12 to 60) | Frames per second of the rendered sequence |
| Cinema burst | 24 (0 to 500) | Frames accumulated for each cinema frame. More is cleaner and slower; the world's own motion advances between frames by however long each takes. |
| Cinema motion | Ease at each keyframe | Whether the camera runs at a constant pace or slows into and out of each keyframe |
| Encode cinema with ffmpeg | true | When ffmpeg is on the path, the frames are piped straight into it and a cinema.mp4 lands in the folder instead of numbered PNGs. |
| Cinema follows the clock | true | Each keyframe also records the time of day, or the manual sun, and the rendered path runs the clock between them, so a path can carry the sun down and the stars out. |
| Click to focus | true | In photo mode, Ctrl-clicking the view focuses the lens at that distance and switches Focus to a fixed distance. Plain clicks reach the game as usual. |
| Focus peaking | false | Paints the edges of whatever the lens holds sharp red, to see the plane of focus while framing. Photos never show it. |
| Save linear HDR too | false | Saves each photo's accumulated light as a Radiance .hdr file beside the PNG, linear and unclipped, for editing elsewhere. |
| Control panel key | F8 | Opens or hides a floating window holding every RLTX setting, for adjusting them while the sidebar is out of the way. |
| Showcase | false | Every quality setting at its top, for showing the renderer off: four bounces, the longest history and the most denoiser passes, exact local lights, textures, relief, DLAA, the widest foliage sway and the full draw distance. Your own values come back when it goes off, and after a restart if the client closed with it on. Looks such as bloom, glow and colours are left as you set them. |
| Quad-resolution photo key | F9 | Takes a photo at twice the width and height of the view, accumulated like any photo, whether or not the interface is hidden. The game pauses while it renders, about four times as long as a normal photo. |
| Showcase key | F7 | Toggles the showcase. |

## Surfaces

| Setting | Default | Description |
| --- | --- | --- |
| Remove baked shading | true | Reverse the vanilla renderer's fixed-direction shading out of model and terrain colours so only the ray traced lighting shapes them. Approximate for models. |
| Textures | false | Sample the game's textures on textured faces instead of their average colour |
| Texture relief | 35% (0 to 100) | Treats the brightness of a texture as height and tilts the lighting normal across it, giving brick and stone textures relief. Only applies with textures on. 0 disables. |
| Reflective water | true | Render water tiles as a reflective surface with animated waves and a sun glint |
| Glossy reflections | true | Trace a reflection ray from every visible surface, blurred by its roughness, so polished and wet things mirror their surroundings. |
| Wave strength | 40% (0 to 100) | How much the waves tilt the water surface; 0 is a flat mirror |
| Surface sheen | 15% (0 to 100) | Specular highlight and reflection strength of surfaces whose texture defines none, like a light clear coat. 0 leaves them matte. |
| Surface roughness | 55% (0 to 100) | How blurred that sheen is: low is glassy, high is a broad soft highlight. |
| Emissive surfaces | 100% (0 to 300) | Lava, fire capes and other textures 117 HD marks unlit glow with their own colour and feed the bloom. 0 disables. |
| Water caustics | true | Sunlight focused by the waves plays across the bed under clear water. |
| Tree scale | 100% (100 to 250) | Draws trees larger about their base, purely visually: click boxes and collision stay where the game puts them, and a large canopy can reach into nearby roofs. |
| Terrain textures | true | Untextured ground keeps its vanilla colour but gains grass, dirt, sand, rock, gravel or snow grain chosen by that colour, with 117 HD's normal maps for relief. |
| Terrain relief | 60% (0 to 200) | Strength of the terrain textures' normal maps. 0 keeps the grain but flattens the relief. |
| Terrain smoothing | false | Interpolate terrain colours and normals across tiles instead of flat facets, as the vanilla renderer does. |
| Footprints | true | Footprints pressed into snow and wet ground behind everyone who walks, fading over minutes, rings spreading from each step through puddles, and low plants leaning away from anyone standing in them. |
| Underwater camera | true | When the free camera dips below the water: the view greens and dims with distance, sunlight dances on the bed, and the surface mirrors or lets the sky through as the angle allows |
| Texture displacement | true | Textured surfaces such as bark, brick and thatch are traced into the relief their texture describes, so ridges and hollows shift with the viewpoint rather than only their shading. Uses the Texture relief strength. |
| Wave geometry near the camera | true | Water within fourteen tiles rises and falls as real geometry, cut finely enough to carry the simulated ripples, so shorelines, pillars and low views meet moving water. The lifting runs on the GPU. |
| Water ripples | true | Ripples simulated on the water near the camera: rain, and anyone standing or wading in it, raise waves that spread, meet and lap against the shore. |

## Other plugins

| Setting | Default | Description |
| --- | --- | --- |
| Shortest Path glow | true | With the Shortest Path plugin installed, draws its route as a ribbon of light on the ground with pulses running toward the destination, in place of its tile outlines. |
| Route style | Worn trail and wisps | How the Shortest Path route shows: a ribbon of light with pulses running along it; a trail worn into the ground, turf giving way to bare earth; wisps of light drifting along it toward the destination; or the trail with wisps. |
| Route glow colour | #ffc460 | Colour of the Shortest Path ribbon and wisps |
| Route glow strength | 100% (10 to 400) | Brightness of the Shortest Path ribbon and wisps |
| Ground marker glow | true | Draws Ground Markers' tiles as pools of their colour lying on the ground, in place of the plugin's outlines. |
| Marker glow strength | 100% (10 to 400) | Brightness of the ground marker pools |
| NPC highlight rim | 100% (0 to 400) | Gives NPCs highlighted by NPC Indicators, Slayer and other plugins a rim of light in their highlight colour. Those plugins' own hull, tile and outline drawing stays under their settings; 0 turns the rim off. |

## Areas

| Setting | Default | Description |
| --- | --- | --- |
| Apply area settings | true | Whether the settings saved for map regions in the F8 panel's Areas tab take over when you enter them. Presets of every setting can be saved to files or the clipboard in its Presets tab. |

## Debug

| Setting | Default | Description |
| --- | --- | --- |
| Cull back faces | true | Skip triangles facing away from the camera, matching the vanilla renderer |
| Test pattern on login screen | false | Run the Vulkan pass with a synthetic pattern while logged out, to verify the Vulkan to OpenGL handoff |
