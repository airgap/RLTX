#version 460

// Flat pass for increment 2a: the face's own colour, no lighting or textures yet. Written into the
// R8G8B8A8_UNORM output the compositor blits, so the colour is treated as display-space as it stands.

layout(location = 0) in vec4 vColor;
layout(location = 0) out vec4 outColor;

void main()
{
	outColor = vec4(vColor.rgb, 1.0);
}
