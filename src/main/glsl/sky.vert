#version 460

// A single full-screen triangle; the fragment shader paints the sky into it. No inputs — the three
// clip positions are derived from the vertex index. Depth 1 (far) so geometry draws over it.

void main()
{
	vec2 p = vec2(float((gl_VertexIndex << 1) & 2), float(gl_VertexIndex & 2));
	gl_Position = vec4(p * 2.0 - 1.0, 1.0, 1.0);
}
