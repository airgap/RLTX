// The per-frame uniform block, shared by every pass. Its layout must match RtRenderer's
// writeFrameUniforms exactly; members are only ever appended.
layout(std140, set = 0, binding = 6) uniform Frame {
  vec4 cam;     // camera position, zoom
  vec4 r0;      // inverse rotation rows: view direction to world direction
  vec4 r1;
  vec4 r2;
  vec4 prevCam; // previous camera position, previous zoom
  vec4 p0;      // previous forward rotation rows: world to view
  vec4 p1;
  vec4 p2;
  vec4 sun;     // unit vector toward the sun, intensity
  vec4 sky;     // sky radiance, ambient floor
  vec4 params;  // sun angular radius (radians), exposure, history frames, dynamic history frames
  uvec4 misc;   // frame, flags, width, height
  vec4 extra;   // skybox rotation (radians), flat background colour rgb
  vec4 denoise; // luminance, normal and position sigmas, unused
  vec4 sunColor; // rgb tint of the direct light, unused
  vec4 path;     // diffuse bounce count, lens aperture radius, focus distance, wave strength
  vec4 motion;   // shutter fraction, game cycle for texture scrolling, texture relief, unused
  vec4 weather;  // cloud cover, fog amount, rain, snow
  vec4 weather2; // ground wetness, snow cover, accumulated wind displacement x and z in world units
  vec4 fog;      // fog colour in display space, lightning flash
  vec4 weather3; // seconds, swamp mist density, mist grid side length, mist grid tile offset
  vec4 mistColor;
  vec4 post;     // vignette, bloom, render distance in world units, fade share
  vec4 post2;    // film grain, chromatic aberration, aerial perspective, sine of the real sun's elevation
  vec4 lights;   // local light count, local light strength scale, default sheen strength, default sheen exponent
  vec4 material; // emissive glow scale, glossy reflections flag, terrain relief, soft glow sigma
  vec4 post3;
  vec4 wind;     // wind velocity x and z, angular radius of the sky's light disc
  vec4 skyAmbient;
  vec4 eye;      // the character's eye position, and how dark what they cannot see is drawn
  vec4 guide;
  vec4 marks;    // ground marker count, marker glow scale, NPC rim glow scale
  vec4 highlight[16]; // linear colours the faces of highlighted NPCs index
  vec4 season;   // season 0 none to 4 winter, progress, falling leaves, blossom petals
  vec4 star0;    // columns of the equatorial to world rotation for the star map; star brightness in w
  vec4 star1;
  vec4 star2;
  vec4 night;    // direction to the sun while the moon lights the scene, moon illuminated fraction
  vec4 ground;   // footprint count, footprint strength, water surface height over the camera, bare earth texture layer
  vec4 horizon[8]; // fog colour by eighth of the compass, from north through east
  uvec4 flags2;    // x: flags once misc.y is full, bit 0 sampled local lights, bit 1 mist indoors, bit 3 ripples; y: texture size in texels; z: mip bias as float bits
  vec4 lens;       // this frame's jitter x and y in traced pixels, whether DLSS upscales, whether Ray Reconstruction denoises
} u;
