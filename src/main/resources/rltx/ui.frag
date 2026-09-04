#version 330

uniform sampler2D tex;
uniform vec4 alphaOverlay;

in vec2 TexCoord;
out vec4 FragColor;

void main() {
  vec4 c = texture(tex, TexCoord);
  FragColor = vec4(c.rgb + alphaOverlay.rgb * (1.0 - c.a), c.a + alphaOverlay.a * (1.0 - c.a));
}
