#version 460

// A stand-in sky for the void where no geometry is: a horizon-to-zenith gradient along the world-up
// axis, dimmed toward night by the sun's elevation, with a soft sun disc. It reconstructs the same
// per-pixel world ray the tracer's camera uses (view dir rotated by the inverse rotation). It is not
// the tracer's procedural atmospheric scattering — that is a later port; this just stops the void
// from being flat black.

layout(push_constant) uniform Push
{
	vec4 inv0;  // inverse rotation rows (view dir -> world)
	vec4 inv1;
	vec4 inv2;
	vec4 cam;   // x zoom, y width, z height, w sunUp (sine of sun elevation, negative at night)
	vec4 sun;   // xyz direction to the sun, w intensity
} pc;

layout(location = 0) out vec4 outColor;

void main()
{
	vec3 viewDir = vec3((gl_FragCoord.x - pc.cam.y * 0.5) / pc.cam.x, (gl_FragCoord.y - pc.cam.z * 0.5) / pc.cam.x, 1.0);
	vec3 w = normalize(vec3(dot(pc.inv0.xyz, viewDir), dot(pc.inv1.xyz, viewDir), dot(pc.inv2.xyz, viewDir)));

	// RuneScape's world Y points down, so looking up (-w.y) reaches the zenith.
	float up = clamp(-w.y * 0.5 + 0.5, 0.0, 1.0);
	vec3 zenith = vec3(0.28, 0.48, 0.85);
	vec3 horizon = vec3(0.72, 0.82, 0.92);
	vec3 sky = mix(horizon, zenith, up);

	float day = clamp(pc.cam.w * 0.5 + 0.5, 0.06, 1.0);
	sky *= day;

	float s = max(dot(w, normalize(pc.sun.xyz)), 0.0);
	sky += vec3(1.0, 0.95, 0.8) * pow(s, 350.0) * pc.sun.w * day;

	outColor = vec4(sky, 1.0);
}
