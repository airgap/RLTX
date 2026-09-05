package rltx.vk;

import java.util.Collections;
import java.util.Set;

/** Everything the renderer needs from the client for one frame. */
public final class FrameParams
{
	public float cameraX, cameraY, cameraZ;
	public float zoom;
	/** Rows of the inverse camera rotation, see {@code rltx.CameraMath}. */
	public final float[] inverseRotation = new float[9];
	/** Rows of the forward camera rotation, kept for reprojection into the next frame. */
	public final float[] forwardRotation = new float[9];
	public float sunX, sunY, sunZ, sunIntensity;
	public float sunR, sunG, sunB;
	/** Half the sun's apparent angular diameter, in radians. Zero gives hard shadows. */
	public float sunAngularRadius;
	public float skyR, skyG, skyB;
	/** Whether an equirectangular skybox is bound; the sky colour then acts as a tint. */
	public boolean skybox;
	public float skyboxRotation;
	/** Flat background colour shown where no geometry is and no skybox is bound. */
	public float backgroundR, backgroundG, backgroundB;
	public float ambient;
	public float exposure;
	public int historyFrames, dynamicHistoryFrames;
	/** À-trous wavelet iterations of the denoiser; 0 disables it. */
	public int denoisePasses;
	/** Luminance edge-stopping width in standard deviations; larger blurs more. */
	public float denoiseLuminance;
	public boolean cullBackfaces, shadows, pattern, textures, antialias, water;
	/** Slope amplitude of the animated water waves. */
	public float waveStrength;
	/** Fraction of the frame interval the shutter stays open; 0 disables motion blur. */
	public float shutter;
	/** Client cycle counter, driving scrolling textures. */
	public float gameCycle;
	/** Tilt of the shading normal by texture brightness gradients, 0 to 1; 0 disables. */
	public float bumpStrength;
	/** Weather: cloud cover, fog amount, falling rain and snow; all 0 to 1 unless scaled up. */
	public float cloud, fogAmount, rain, snow;
	/** Ground wetness and snow cover 0 to 1, and how far the wind has carried the air so far, in world units. */
	public float wetness, snowCover, windOffsetX, windOffsetZ;
	/** Current wind velocity in world units per second, for the slant of falling rain. */
	public float windVelocityX, windVelocityZ;
	/** Angular radius of the procedural sky's light disc, radians. */
	public float sunDiscRadius;
	/** Linear radiance of the sky dome as it lights things in the open, for particles lit in the final pass. */
	public float skyAmbientR, skyAmbientG, skyAmbientB;
	/** Scales of rain fall speed and streak length, 1 is the default look. */
	public float rainSpeed = 1f, rainLength = 1f;
	/** The character's eye position and how dark what they cannot see is drawn; 0 disables. */
	public float eyeX, eyeY, eyeZ, unseenDarkness;
	/** Clock for particle animation, seconds. */
	public float timeSeconds;
	/** Swamp mist density scale, and the side length and tile offset of the mist grid. */
	public float mist, mistGridSize, mistGridOffset;
	/** Display-space colour of mist lit by the sun and sky. */
	public float mistR, mistG, mistB;
	/** Strength of sunlight scattered by the air along view rays; 0 disables the pass. */
	public float lightShafts;
	/** Corner darkening of the final image, 0 to 1. */
	public float vignette;
	/** Strength of the glow blurred out of what exceeds white, 0 to 1. */
	public float bloom;
	/** How far primary rays reach and static zones are kept, in world units. */
	public float renderDistance;
	/** Share of the render distance over which scenery fades out, 0 to 1. */
	public float distanceFade;
	/** Film grain and chromatic aberration strengths, 0 to 1. */
	public float filmGrain, chromaticAberration;
	/** Aerial perspective strength, 0 disables. */
	public float aerialPerspective;
	/** Sine of the real sun's elevation, negative at night, for day and night blending in the sky. */
	public float sunUp;
	public boolean proceduralSky, clouds, cloudShadows, autoExposure;
	/** Whether the procedural sky reads the scattered-light map instead of its painted gradient. */
	public boolean physicalSky;
	/** Number of local lights uploaded this frame and their brightness scale. */
	public int lightCount;
	public float lightStrength;
	/** Specular strength and gloss exponent for surfaces without a material of their own. */
	public float surfaceGloss, surfaceGlossExponent;
	/** Glow scale of unlit textures, 0 disables; and whether reflection rays are traced. */
	public float emissiveStrength;
	public boolean glossyReflections;
	public boolean caustics, rainRipples, puddles, runoff;
	public boolean terrainTextures, terrainSmoothing;
	public boolean mistEverywhere;
	/** Fireflies over misty ground at night, and sunlit dust motes by day. */
	public boolean fireflies, dustMotes;
	/** Birds, bats and butterflies. */
	public boolean wildlife;
	/** How many smoke plumes were uploaded this frame. */
	public int plumeCount;
	/** Ground marker tiles uploaded this frame and their glow scale; 0 draws none. */
	public int markerCount;
	public float markerStrength;
	/** Lens flare strength, 0 disables. */
	public float lensFlare;
	/** Season 0 none to 4 winter and progress through it; falling leaf and blossom petal amounts. */
	public int season;
	public float seasonProgress, leafFall, petals;
	/** Columns of the rotation from equatorial to world directions, for the star map; and its brightness scale. */
	public final float[] starRotation = new float[9];
	public float starBrightness;
	/** Direction to the sun while the moon lights the scene, and the moon's illuminated fraction. */
	public float moonSunX, moonSunY, moonSunZ, moonFraction;
	/** Rim glow scale for highlighted NPCs, 0 disables, and the palette of highlight colours their faces index, linear RGB. */
	public float rimStrength;
	public final float[] highlightColours = new float[16 * 4];
	/** Strength of terrain normal maps, 0 to 2. */
	public float terrainBump;
	/** Colour grading: contrast about mid grey, saturation, and warm or cool shift from -1 to 1. */
	public float contrast = 1f, saturation = 1f, temperature;
	/** Opacity of the blurred whole frame blended back over itself, 0 to 1. */
	public float diffusion;
	/** Blur radius of the soft glow copy in full-resolution pixels. */
	public float diffusionRadius = 40f;
	/** Display-space colour distance fog fades to, and the current lightning flash. */
	public float fogR, fogG, fogB, flash;
	/** Thin-lens aperture radius in scene units (0 disables) and focus distance along the view axis. */
	public float aperture, focusDistance;
	/** Diffuse bounces per path; 0 leaves only direct light and the ambient floor. */
	public int bounces;
	/** A held frame being accumulated in place, and whether its rays leave a real lens aperture. */
	public boolean still, thinLens;
	/** Display-space colour of the route glow, and how many route entries were uploaded; 0 draws none. */
	public float guideR, guideG, guideB;
	public int guideCount;
}
