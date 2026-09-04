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
	/** Thin-lens aperture radius in scene units (0 disables) and focus distance along the view axis. */
	public float aperture, focusDistance;
	/** Diffuse bounces per path; 0 leaves only direct light and the ambient floor. */
	public int bounces;
	public int minLevel, level, maxLevel;
	public Set<Integer> hiddenRoofIds = Collections.emptySet();
}
