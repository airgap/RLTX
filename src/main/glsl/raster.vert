#version 460

// Draws the game's triangle soup the same way the tracer sees it: a non-indexed vertex stream of
// faces*3 vertices, positions three floats each, one packed RGBA colour per face. The camera matches
// trace.comp's convention exactly — a pinhole where a view direction is ((px - w/2)/zoom, (py - h/2)/
// zoom, 1) rotated into the world, so here we invert it: rotate a world point into view with the
// forward rotation and project by the same zoom. Left-handed, +Z into the screen, +Y down, which is
// already Vulkan's clip Y direction, so no flip.

layout(std430, set = 0, binding = 0) readonly buffer Positions { float pos[]; };
layout(std430, set = 0, binding = 1) readonly buffer Colors { uint col[]; };

layout(push_constant) uniform Push
{
	vec4 camZoom;   // xyz camera position, w zoom (focal length in pixels)
	vec4 row0;      // forward rotation rows (world -> view), xyz used
	vec4 row1;
	vec4 row2;
	vec4 viewport;  // x width, y height, z near, w far
} pc;

layout(location = 0) out vec4 vColor;

void main()
{
	uint vid = uint(gl_VertexIndex);
	uint o = vid * 3u;
	vec3 world = vec3(pos[o], pos[o + 1u], pos[o + 2u]);

	uint c = col[vid / 3u];
	vColor = vec4(float((c >> 16) & 0xffu), float((c >> 8) & 0xffu), float(c & 0xffu), float((c >> 24) & 0xffu)) / 255.0;

	vec3 rel = world - pc.camZoom.xyz;
	vec3 v = vec3(dot(pc.row0.xyz, rel), dot(pc.row1.xyz, rel), dot(pc.row2.xyz, rel));

	float zoom = pc.camZoom.w;
	float w = pc.viewport.x;
	float h = pc.viewport.y;
	float near = pc.viewport.z;
	float far = pc.viewport.w;

	// Screen = (v.x/v.z*zoom + w/2, v.y/v.z*zoom + h/2). In clip space, before the divide by v.z:
	// x,y carry the 2*zoom/dim scale, w is v.z, and z maps [near,far] to [0,1].
	gl_Position = vec4(
		v.x * (2.0 * zoom / w),
		v.y * (2.0 * zoom / h),
		far / (far - near) * (v.z - near),
		v.z);
}
