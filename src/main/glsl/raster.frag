#version 460

// The face's colour, modulated by its texture where it has one. RuneScape stores textures at half
// brightness, so the sample is doubled, and treats alpha below a half as a cutout to drop — the same
// rule trace.comp uses. Untextured faces keep their flat baked colour. No lighting or atmosphere yet.

layout(set = 0, binding = 4) uniform sampler2DArray gameTextures;

layout(location = 0) in vec4 vColor;
layout(location = 1) in vec2 vUv;
layout(location = 2) flat in uint vTex;

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
	outColor = vec4(rgb, 1.0);
}
