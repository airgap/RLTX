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
	/** Display-space colour distance fog fades to, and the current lightning flash. */
	public float fogR, fogG, fogB, flash;
	/** Thin-lens aperture radius in scene units (0 disables) and focus distance along the view axis. */
	public float aperture, focusDistance;
	/** Diffuse bounces per path; 0 leaves only direct light and the ambient floor. */
	public int bounces;
}
