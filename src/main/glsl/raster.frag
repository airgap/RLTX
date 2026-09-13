#version 460

// The face's colour, modulated by its texture, lit by the sun and sky ambient, then faded into the
// fog colour with distance. RuneScape stores textures at half brightness (doubled here) and treats
// alpha below a half as a cutout to drop — for opaque faces. Translucent faces (flagged through
// fogRange.z) skip the cutout and emit the face's opacity so the blend pass can composite them.

layout(set = 0, binding = 4) uniform sampler2DArray gameTextures;

layout(push_constant) uniform Push
{
	vec4 camZoom;
	vec4 row0;
	vec4 row1;
	vec4 row2;
	vec4 viewport;
	vec4 fogColor;  // rgb the distance fades to
	vec4 fogRange;  // x start, y end, z translucent flag (0 opaque, 1 translucent)
	vec4 sunDir;    // xyz world-space direction to the sun
	vec4 sunColour; // rgb sun colour times intensity
	vec4 ambient;   // rgb sky ambient
} pc;

layout(location = 0) in vec4 vColor;
layout(location = 1) in vec2 vUv;
layout(location = 2) flat in uint vTex;
layout(location = 3) in float vDepth;
layout(location = 4) in vec3 vNormal;

// A small floor under the sky ambient so shadowed faces keep some colour rather than crushing to
// black at dusk or indoors, without the wash of a large floor.
const float AMBIENT_FLOOR = 0.22;

layout(location = 0) out vec4 outColor;

void main()
{
	bool translucent = pc.fogRange.z > 0.5;
	vec3 rgb = vColor.rgb;
	float texA = 1.0;
	if (vTex > 0u)
	{
		vec4 texel = texture(gameTextures, vec3(vUv, float(vTex - 1u)));
		texA = texel.a;
		rgb *= texel.rgb * 2.0;
	}

	// Opaque faces drop the sub-half texel as a cutout; translucent faces blend it instead.
	if (!translucent && texA < 0.5)
	{
		discard;
	}

	// Re-light the albedo. Terrain and model colours have the vanilla fixed-direction shading divided
	// out by default (see unlitColours), so they arrive as flat, full-luminance albedos: the sky
	// ambient fills shadow and the sun adds a warm directional term where it strikes, with the total
	// capped at one so an albedo is only ever darkened, never pushed past its own colour. A larger
	// additive term left the shading-removed albedos washed out and bright-hued slopes glowing.
	float ndl = max(dot(normalize(vNormal), pc.sunDir.xyz), 0.0);
	vec3 light = max(pc.ambient.rgb, vec3(AMBIENT_FLOOR)) + pc.sunColour.rgb * ndl;
	rgb *= min(light, vec3(1.0));

	float fog = clamp((vDepth - pc.fogRange.x) / max(pc.fogRange.y - pc.fogRange.x, 1e-3), 0.0, 1.0);
	rgb = mix(rgb, pc.fogColor.rgb, fog);

	outColor = vec4(rgb, translucent ? clamp(vColor.a * texA, 0.0, 1.0) : 1.0);
}
