#version 460

// Draws the game's triangle soup the same way the tracer sees it: a non-indexed vertex stream of
// faces*3 vertices, positions three floats each, one packed RGBA colour per face, six UV floats per
// face (two per corner) and one texture id per face. The camera matches trace.comp's convention
// exactly — a pinhole where a view direction is ((px - w/2)/zoom, (py - h/2)/zoom, 1) rotated into
// the world, so here we invert it: rotate a world point into view with the forward rotation and
// project by the same zoom. Left-handed, +Z into the screen, +Y down, which is already Vulkan's clip
// Y direction, so no flip. The view-space depth is passed on for distance fog.

layout(std430, set = 0, binding = 0) readonly buffer Positions { float pos[]; };
layout(std430, set = 0, binding = 1) readonly buffer Colors { uint col[]; };
layout(std430, set = 0, binding = 2) readonly buffer Uvs { float uvs[]; };
layout(std430, set = 0, binding = 3) readonly buffer Texs { uint texs[]; };
// One packed normal per vertex (three signed bytes, as GeometryBuffer.packNormal writes them);
// zero where the client left a face flat, in which case the geometric normal is used instead.
layout(std430, set = 0, binding = 5) readonly buffer Normals { uint nrm[]; };

layout(push_constant) uniform Push
{
	vec4 camZoom;   // xyz camera position, w zoom (focal length in pixels)
	vec4 row0;      // forward rotation rows (world -> view), xyz used
	vec4 row1;
	vec4 row2;
	vec4 viewport;  // x width, y height, z near, w far
	vec4 fogColor;  // rgb the distance fades to
	vec4 fogRange;  // x fog start, y fog end (view-space world units), z translucent flag (fragment only)
	vec4 sunDir;    // xyz world-space direction to the sun
	vec4 sunColour; // rgb sun colour times intensity
	vec4 ambient;   // rgb sky ambient
} pc;

layout(location = 0) out vec4 vColor;
layout(location = 1) out vec2 vUv;
layout(location = 2) flat out uint vTex;
layout(location = 3) out float vDepth;
layout(location = 4) out vec3 vNormal;  // world-space, shaded in the fragment stage

void main()
{
	uint vid = uint(gl_VertexIndex);
	uint face = vid / 3u;
	uint corner = vid % 3u;
	uint o = vid * 3u;
	vec3 world = vec3(pos[o], pos[o + 1u], pos[o + 2u]);

	// Packed as trace.comp reads it: red in the low byte, then green, then blue, then alpha.
	uint c = col[face];
	vColor = vec4(float(c & 0xffu), float((c >> 8) & 0xffu), float((c >> 16) & 0xffu), float((c >> 24) & 0xffu)) / 255.0;
	vUv = vec2(uvs[face * 6u + corner * 2u], uvs[face * 6u + corner * 2u + 1u]);
	vTex = texs[face];

	vec3 rel = world - pc.camZoom.xyz;
	vec3 v = vec3(dot(pc.row0.xyz, rel), dot(pc.row1.xyz, rel), dot(pc.row2.xyz, rel));
	vDepth = v.z;

	// World-space normal for lighting: the client's packed vertex normal where it has one, else the
	// face's geometric normal from its three world positions. The geometric one is turned to face the
	// camera (rel points camera -> vertex), the same orientation trace.comp gives a back face, so a
	// flat face lit correctly whatever its winding. Packed normals are the client's Gouraud normals
	// and are left as authored. Static geometry is baked to world space and dynamic is world space, so
	// no rotation is applied here (see NormalRenderer.bake).
	uint pn = nrm[vid];
	if (pn == 0u)
	{
		uint b = face * 9u;
		vec3 p0 = vec3(pos[b], pos[b + 1u], pos[b + 2u]);
		vec3 p1 = vec3(pos[b + 3u], pos[b + 4u], pos[b + 5u]);
		vec3 p2 = vec3(pos[b + 6u], pos[b + 7u], pos[b + 8u]);
		vec3 gn = normalize(cross(p1 - p0, p2 - p0));
		vNormal = dot(gn, rel) > 0.0 ? -gn : gn;
	}
	else
	{
		vNormal = normalize(vec3(
			float(bitfieldExtract(int(pn), 0, 8)),
			float(bitfieldExtract(int(pn), 8, 8)),
			float(bitfieldExtract(int(pn), 16, 8))) / 127.0);
	}

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
