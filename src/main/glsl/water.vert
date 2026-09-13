#version 460

// Water surfaces, projected exactly as raster.vert projects the opaque world so both share the one
// depth buffer, but carrying the world position through to the fragment shader as well: the ripple
// waves are animated over world xz there, and the Fresnel reflection needs the world-space view ray.
// Same non-indexed face stream and same colour/uv/tex packing as the opaque pass.

layout(std430, set = 0, binding = 0) readonly buffer Positions { float pos[]; };
layout(std430, set = 0, binding = 1) readonly buffer Colors { uint col[]; };
layout(std430, set = 0, binding = 2) readonly buffer Uvs { float uvs[]; };
layout(std430, set = 0, binding = 3) readonly buffer Texs { uint texs[]; };

layout(push_constant) uniform Push
{
	vec4 camZoom;   // xyz camera position, w zoom (focal length in pixels)
	vec4 row0;      // forward rotation rows (world -> view), xyz used
	vec4 row1;
	vec4 row2;
	vec4 viewport;  // x width, y height, z near, w far
	vec4 fogColor;  // rgb the distance fades to, w sunUp (sine of sun elevation)
	vec4 fogRange;  // x fog start, y fog end, z time seconds, w wave slope strength
	vec4 sun;       // xyz direction to the sun, w intensity
} pc;

layout(location = 0) out vec4 vColor;
layout(location = 1) out vec2 vUv;
layout(location = 2) flat out uint vTex;
layout(location = 3) out float vDepth;
layout(location = 4) out vec3 vWorld;

void main()
{
	uint vid = uint(gl_VertexIndex);
	uint face = vid / 3u;
	uint corner = vid % 3u;
	uint o = vid * 3u;
	vec3 world = vec3(pos[o], pos[o + 1u], pos[o + 2u]);
	vWorld = world;

	// Packed as trace.comp reads it: red in the low byte, then green, then blue, then alpha.
	uint c = col[face];
	vColor = vec4(float(c & 0xffu), float((c >> 8) & 0xffu), float((c >> 16) & 0xffu), float((c >> 24) & 0xffu)) / 255.0;
	vUv = vec2(uvs[face * 6u + corner * 2u], uvs[face * 6u + corner * 2u + 1u]);
	vTex = texs[face];

	vec3 rel = world - pc.camZoom.xyz;
	vec3 v = vec3(dot(pc.row0.xyz, rel), dot(pc.row1.xyz, rel), dot(pc.row2.xyz, rel));
	vDepth = v.z;

	float zoom = pc.camZoom.w;
	float w = pc.viewport.x;
	float h = pc.viewport.y;
	float near = pc.viewport.z;
	float far = pc.viewport.w;

	gl_Position = vec4(
		v.x * (2.0 * zoom / w),
		v.y * (2.0 * zoom / h),
		far / (far - near) * (v.z - near),
		v.z);
}
