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

// Liquid glass: a pane is a thick rounded slab over the scene. What shows through is blurred and
// darkened, bends toward the rim as if through the slab's edge, and the rim itself catches a
// gold light from the upper left.
vec3 glass(vec2 px) {
  vec2 texel = 1.0 / uiSize;
  const int STEPS = 12;
  const float STEP = 2.0;
  float nearest = float(STEPS) * STEP;
  vec2 toEdge = vec2(0.0);
  for (int i = 0; i < 8; ++i) {
    float a = float(i) * 0.7853982;
    vec2 d = vec2(cos(a), sin(a));
    for (int s = 1; s <= STEPS; ++s) {
      float dist = float(s) * STEP;
      if (!isKey(texture(tex, (px + d * dist) * texel).rgb)) {
        nearest = min(nearest, dist);
        toEdge += d / dist;
        break;
      }
    }
  }
  float edge = 1.0 - clamp(nearest / (float(STEPS) * STEP), 0.0, 1.0);
  vec2 normal = length(toEdge) > 1e-4 ? normalize(toEdge) : vec2(0.0);
  // Through the slab's rounded edge the scene beyond the pane is pulled in.
  vec2 bent = px + normal * (glassLook.z * edge * edge);
  vec2 sceneUv = (bent - sceneRect.xy) / sceneRect.zw;
  vec3 behind;
  if (any(lessThan(sceneUv, vec2(0.0))) || any(greaterThan(sceneUv, vec2(1.0)))) {
    behind = vec3(0.03, 0.03, 0.04);
  } else {
    vec2 sTexel = glassLook.y / sceneRect.zw;
    vec3 sum = texture(scene, sceneUv).rgb * 2.0;
    const vec2 TAPS[12] = vec2[12](
      vec2(-0.326, -0.406), vec2(-0.840, -0.074), vec2(-0.696, 0.457), vec2(-0.203, 0.621),
      vec2(0.962, -0.195), vec2(0.473, -0.480), vec2(0.519, 0.767), vec2(0.185, -0.893),
      vec2(0.507, 0.064), vec2(0.896, 0.412), vec2(-0.322, -0.933), vec2(-0.792, -0.598));
    for (int i = 0; i < 12; ++i) {
      sum += texture(scene, sceneUv + TAPS[i] * sTexel).rgb;
    }
    behind = sum / 14.0;
  }
  vec3 colour = behind * mix(vec3(1.0), vec3(0.30, 0.32, 0.38), glassLook.x) + vec3(0.012, 0.012, 0.018);
  // A soft inner light where the slab thickens, brightest on the rims that face the light.
  float lit = clamp(dot(normal, normalize(vec2(-0.6, -0.8))), 0.0, 1.0);
  float rim = pow(edge, 2.5) * (0.25 + 0.75 * lit) * glassLook.w;
  colour += vec3(0.84, 0.70, 0.40) * rim;
  colour += vec3(0.10, 0.10, 0.12) * pow(edge, 6.0);
  return colour;
}

void main() {
  vec4 c = texture(tex, TexCoord);
  if (glassKey.a > 0.5 && isKey(c.rgb)) {
    c = vec4(glass(TexCoord * uiSize), 1.0);
  }
  FragColor = vec4(c.rgb + alphaOverlay.rgb * (1.0 - c.a), c.a + alphaOverlay.a * (1.0 - c.a));
}
