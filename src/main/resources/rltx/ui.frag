#version 330

uniform sampler2D tex;
uniform sampler2D scene;
uniform vec4 alphaOverlay;
// The traced scene's place on the interface, in interface pixels: x, y, width, height.
uniform vec4 sceneRect;
uniform vec2 uiSize;
// The key colour the chrome's panes are drawn in, and whether they are glass this frame.
uniform vec4 glassKey;
uniform vec4 glassLook;

in vec2 TexCoord;
out vec4 FragColor;

bool isKey(vec3 c) {
  return all(lessThan(abs(c - glassKey.rgb), vec3(1.5 / 255.0)));
}

// The chrome's panes are traced as glass in the scene itself, so over the viewport they are cut
// out of the interface and the traced glass shows; beyond the viewport there is no scene to
// trace, and a pane there is drawn solid and dark.
void main() {
  vec4 c = texture(tex, TexCoord);
  if (glassKey.a > 0.5 && isKey(c.rgb)) {
    vec2 px = TexCoord * uiSize;
    vec2 sceneUv = (px - sceneRect.xy) / sceneRect.zw;
    if (all(greaterThanEqual(sceneUv, vec2(0.0))) && all(lessThanEqual(sceneUv, vec2(1.0)))) {
      FragColor = vec4(0.0);
      return;
    }
    c = vec4(0.06, 0.065, 0.08, 1.0);
  }
  FragColor = vec4(c.rgb + alphaOverlay.rgb * (1.0 - c.a), c.a + alphaOverlay.a * (1.0 - c.a));
}
