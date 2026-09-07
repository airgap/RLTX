#version 330

uniform sampler2D tex;
uniform sampler2D scene;
uniform vec4 alphaOverlay;
// The traced scene's place on the interface, in interface pixels: x, y, width, height.
uniform vec4 sceneRect;
uniform vec2 uiSize;
// The key colour the chrome's panes are drawn in, and whether they are glass this frame.
uniform vec4 glassKey;
// Darkening of what shows through, blur radius in pixels, bend at the edges in pixels, rim strength.
uniform vec4 glassLook;

in vec2 TexCoord;
out vec4 FragColor;

bool isKey(vec3 c) {
  return all(lessThan(abs(c - glassKey.rgb), vec3(1.5 / 255.0)));
}

// Liquid glass, built from the pane's silhouette alone. The distance to the pane's true edge,
// found by marching out in twelve directions and stepping over the text and icons that sit on
// the pane, gives a rounded slab: flat in the middle, a quarter-circle at the rim. The scene is
// refracted through the slab's surface with Snell's law, lightly frosted and tinted, thicker
// glass absorbing more; the surface reflects a neutral sky by Fresnel, takes a highlight from a
// light at the upper left, and is bound by a fine gold rim.
const int DIRECTIONS = 12;
const int STEPS = 12;
const float STEP = 2.0;
const float RIM_RADIUS = 12.0;
const float SLAB = 9.0;

vec3 glass(vec2 px) {
  vec2 texel = 1.0 / uiSize;
  float nearest = float(STEPS) * STEP;
  vec2 toEdge = vec2(0.0);
  for (int i = 0; i < DIRECTIONS; ++i) {
    float a = float(i) * (6.2831853 / float(DIRECTIONS));
    vec2 d = vec2(cos(a), sin(a));
    int s = 1;
    while (s <= STEPS) {
      float dist = float(s) * STEP;
      if (isKey(texture(tex, (px + d * dist) * texel).rgb)) {
        ++s;
        continue;
      }
      // Something other than glass: the pane's edge, unless glass resumes just beyond it, in
      // which case it is a letter or an icon lying on the pane and the march goes on past it.
      if (isKey(texture(tex, (px + d * (dist + 3.0)) * texel).rgb) || isKey(texture(tex, (px + d * (dist + 6.0)) * texel).rgb)) {
        s += 4;
        continue;
      }
      nearest = min(nearest, dist);
      toEdge += d / dist;
      break;
    }
  }
  vec2 outward = length(toEdge) > 1e-4 ? normalize(toEdge) : vec2(0.0);
  // The slab's profile: height 0 to 1 from the rim inward along a quarter circle, then flat.
  float t = clamp(nearest / RIM_RADIUS, 0.0, 1.0);
  float height = sqrt(max(1.0 - (1.0 - t) * (1.0 - t), 0.0));
  float slope = t < 1.0 ? (1.0 - t) / max(height, 0.05) : 0.0;
  vec3 normal = normalize(vec3(outward * slope, 1.0));
  // Looking straight into the screen, the view ray bends toward the normal and crosses the slab.
  vec3 refracted = refract(vec3(0.0, 0.0, -1.0), normal, 1.0 / 1.5);
  vec2 bend = refracted.xy / max(-refracted.z, 0.2) * (SLAB * (0.4 + 0.6 * height));
  vec2 sceneUv = (px + bend - sceneRect.xy) / sceneRect.zw;
  vec3 behind;
  if (any(lessThan(sceneUv, vec2(0.0))) || any(greaterThan(sceneUv, vec2(1.0)))) {
    behind = vec3(0.03, 0.03, 0.04);
  } else {
    vec2 sTexel = glassLook.y / sceneRect.zw;
    vec3 sum = texture(scene, sceneUv).rgb * 2.0;
    const vec2 TAPS[8] = vec2[8](
      vec2(-0.7, -0.5), vec2(0.7, -0.5), vec2(-0.7, 0.5), vec2(0.7, 0.5),
      vec2(0.0, -0.9), vec2(0.0, 0.9), vec2(-0.9, 0.0), vec2(0.9, 0.0));
    for (int i = 0; i < 8; ++i) {
      sum += texture(scene, sceneUv + TAPS[i] * sTexel).rgb;
    }
    behind = sum / 10.0;
  }
  // Tinted glass, thicker at the rim where more of the light is absorbed.
  float absorb = mix(1.0, 0.7, 1.0 - height);
  vec3 colour = behind * mix(vec3(1.0), vec3(0.26, 0.28, 0.34), glassLook.x) * absorb + vec3(0.01, 0.01, 0.015);
  // The surface: a sky reflected more at the rim, and a highlight from the upper left.
  float fresnel = 0.04 + 0.96 * pow(1.0 - normal.z, 5.0);
  colour += vec3(0.62, 0.66, 0.74) * (fresnel * 0.55);
  vec3 light = normalize(vec3(-0.5, -0.7, 0.55));
  vec3 half_ = normalize(light + vec3(0.0, 0.0, 1.0));
  colour += vec3(1.0, 0.94, 0.80) * (pow(max(dot(normal, half_), 0.0), 60.0) * 0.9 * glassLook.w);
  // The gold rim, a fine line at the very edge with a lit inner bevel behind it.
  float line = 1.0 - smoothstep(1.0, 2.5, nearest);
  colour = mix(colour, vec3(0.84, 0.70, 0.40), line);
  return colour;
}

void main() {
  vec4 c = texture(tex, TexCoord);
  if (glassKey.a > 0.5 && isKey(c.rgb)) {
    c = vec4(glass(TexCoord * uiSize), 1.0);
  }
  FragColor = vec4(c.rgb + alphaOverlay.rgb * (1.0 - c.a), c.a + alphaOverlay.a * (1.0 - c.a));
}
