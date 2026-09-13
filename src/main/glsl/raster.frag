#version 460

// The face's colour, modulated by its texture where it has one, then faded into the scene's fog
// colour with view-space distance. RuneScape stores textures at half brightness, so the sample is
// doubled, and treats alpha below a half as a cutout to drop — the same rules trace.comp uses.
// The distance fade into the fog colour is the atmosphere; a full sky and post chain come later.

layout(set = 0, binding = 4) uniform sampler2DArray gameTextures;

layout(push_constant) uniform Push
{
	vec4 camZoom;
	vec4 row0;
	vec4 row1;
	vec4 row2;
	vec4 viewport;
	vec4 fogColor;
	vec4 fogRange;  // x start, y end
} pc;

layout(location = 0) in vec4 vColor;
layout(location = 1) in vec2 vUv;
layout(location = 2) flat in uint vTex;
layout(location = 3) in float vDepth;

layout(location = 0) out vec4 outColor;

void main()
{
	vec3 rgb = vColor.rgb;
	if (vTex > 0u)
	{
		vec4 texel = texture(gameTextures, vec3(vUv, float(vTex - 1u)));
		if (texel.a < 0.5)
		{
			discard;
		}
		rgb *= texel.rgb * 2.0;
	}
	float fog = clamp((vDepth - pc.fogRange.x) / max(pc.fogRange.y - pc.fogRange.x, 1e-3), 0.0, 1.0);
	rgb = mix(rgb, pc.fogColor.rgb, fog);
	outColor = vec4(rgb, 1.0);
}
