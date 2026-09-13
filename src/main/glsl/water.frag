#version 460

// A cheap reflective water surface for Normal mode. The flat surface (world up is -Y) is tilted by
// two crossed animated sine waves over world xz, giving a perturbed normal; a Schlick Fresnel term
// then blends between the water's own tint (its face colour, deepened) and the sky reflected along
// the mirror ray. The reflection reuses sky.frag's gradient so the two agree in the void, and the
// surface is drawn with alpha blending (depth test on, depth write off) so the bed shows through.
// It is not the tracer's spectral waves or traced reflections; those are a later port.

layout(set = 0, binding = 4) uniform sampler2DArray gameTextures;

layout(push_constant) uniform Push
{
	vec4 camZoom;
	vec4 row0;
	vec4 row1;
	vec4 row2;
	vec4 viewport;
	vec4 fogColor;  // rgb the distance fades to, w sunUp (sine of sun elevation)
	vec4 fogRange;  // x fog start, y fog end, z time seconds, w wave slope strength
	vec4 sun;       // xyz direction to the sun, w intensity
} pc;

layout(location = 0) in vec4 vColor;
layout(location = 1) in vec2 vUv;
layout(location = 2) flat in uint vTex;
layout(location = 3) in float vDepth;
layout(location = 4) in vec3 vWorld;

layout(location = 0) out vec4 outColor;

// The same horizon-to-zenith gradient and sun disc sky.frag paints, sampled along a world ray, so
// the water mirrors exactly what the sky pass draws in the void behind it.
vec3 skyGradient(vec3 w)
{
	float up = clamp(-w.y * 0.5 + 0.5, 0.0, 1.0);
	vec3 zenith = vec3(0.28, 0.48, 0.85);
	vec3 horizon = vec3(0.72, 0.82, 0.92);
	vec3 s = mix(horizon, zenith, up);
	float day = clamp(pc.fogColor.w * 0.5 + 0.5, 0.06, 1.0);
	s *= day;
	float sun = max(dot(w, normalize(pc.sun.xyz)), 0.0);
	s += vec3(1.0, 0.95, 0.8) * pow(sun, 350.0) * pc.sun.w * day;
	return s;
}

void main()
{
	float t = pc.fogRange.z;
	float strength = pc.fogRange.w;

	// Two crossed travelling sine waves over world xz. Their frequencies, directions and speeds are
	// deliberately incommensurate so the interference never settles into a fixed pattern. The normal
	// is the surface gradient's slope tilting the world-up axis (which is -Y in RuneScape space).
	vec2 p = vWorld.xz;
	vec2 d1 = vec2(0.80, 0.60);
	vec2 d2 = vec2(-0.50, 0.86);
	float f1 = 0.11;
	float f2 = 0.17;
	float phase1 = dot(d1, p) * f1 + t * 1.7;
	float phase2 = dot(d2, p) * f2 + t * 1.3;
	vec2 grad = (d1 * (f1 * cos(phase1)) + d2 * (f2 * cos(phase2))) * strength;
	vec3 n = normalize(vec3(-grad.x, -1.0, -grad.y));

	vec3 viewDir = normalize(vWorld - pc.camZoom.xyz);
	float cosI = clamp(dot(-viewDir, n), 0.0, 1.0);
	// Schlick: water's ~2% reflectance head-on rising to a near mirror at grazing angles.
	float fresnel = 0.02 + 0.98 * pow(1.0 - cosI, 5.0);

	vec3 reflectDir = reflect(viewDir, n);
	vec3 reflection = skyGradient(reflectDir);

	// A deliberate deep blue-green body, warmed only partway toward the water's own face colour so
	// regions still differ, is what makes the surface read as water. The face colour halved on its
	// own is whatever murky hue the region baked to, which at oblique angles (where Fresnel is small
	// and the body dominates) leaves the river a flat, washed-out sheet. The texture, where the face
	// has one, darkens the body as a hint of the bed.
	vec3 body = mix(vec3(0.05, 0.19, 0.23), vColor.rgb, 0.35);
	if (vTex > 0u)
	{
		body *= texture(gameTextures, vec3(vUv, float(vTex - 1u))).rgb * 2.0;
	}

	vec3 rgb = mix(body, reflection, fresnel);

	// A tight sun glint off the perturbed surface reads as a wet specular highlight; the mirror term
	// alone, reflecting a matte sky, leaves the water looking like frosted glass.
	float glint = pow(max(dot(reflectDir, normalize(pc.sun.xyz)), 0.0), 80.0) * pc.sun.w;
	rgb += vec3(1.0, 0.97, 0.9) * glint;

	// Distance fog, matching raster.frag so water fades into the scene with everything else.
	float fog = clamp((vDepth - pc.fogRange.x) / max(pc.fogRange.y - pc.fogRange.x, 1e-3), 0.0, 1.0);
	rgb = mix(rgb, pc.fogColor.rgb, fog);

	// Partly transparent so the riverbed under the surface shows through, clearer head-on and
	// opaquer where the grazing mirror takes over.
	float alpha = clamp(0.55 + 0.45 * fresnel, 0.0, 1.0);
	outColor = vec4(rgb, alpha);
}
