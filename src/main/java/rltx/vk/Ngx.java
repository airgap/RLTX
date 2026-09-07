package rltx.vk;

import java.io.File;
import lombok.extern.slf4j.Slf4j;

/**
 * NVIDIA's DLSS through the NGX SDK, reached over the small JNI shim the launch script compiles
 * from src/main/c. Everything here is optional: without the shim, the SDK's library, an RTX GPU
 * or the Vulkan extensions NGX asks for, DLSS is simply unavailable and the reason is logged.
 * NGX is process-wide and not thread safe, and every call here happens on the client thread.
 */
@Slf4j
public final class Ngx
{
	public static final int QUALITY_PERFORMANCE = 0;
	public static final int QUALITY_BALANCED = 1;
	public static final int QUALITY_QUALITY = 2;
	public static final int QUALITY_ULTRA_PERFORMANCE = 3;
	public static final int QUALITY_DLAA = 5;

	public static final int FLAG_HDR = 1;
	public static final int FLAG_MV_LOW_RES = 1 << 1;
	public static final int FLAG_MV_JITTERED = 1 << 2;
	public static final int FLAG_DEPTH_INVERTED = 1 << 3;
	public static final int FLAG_AUTO_EXPOSURE = 1 << 6;

	private static boolean loaded;
	private static boolean extensions;
	private static String unavailable = "not loaded";
	private static String appDataPath;
	private static String libraryDir;

	private Ngx()
	{
	}

	/**
	 * Loads the shim from the native directory and remembers where NGX may write its logs and
	 * where the DLSS library lives. Safe to call without a native directory: DLSS stays unavailable.
	 */
	public static void load(String nativeDir, String appData)
	{
		if (loaded)
		{
			return;
		}
		if (nativeDir == null)
		{
			unavailable = "the client was started without -Drltx.nativeDir (run ./gradlew launchScript)";
			return;
		}
		File shim = new File(nativeDir, "librltxngx.so");
		if (!shim.isFile())
		{
			unavailable = shim + " is missing (the launch script builds it when gcc is installed)";
			return;
		}
		new File(appData).mkdirs();
		try
		{
			System.load(shim.getAbsolutePath());
		}
		catch (UnsatisfiedLinkError e)
		{
			unavailable = "the shim did not load: " + e.getMessage();
			return;
		}
		appDataPath = appData;
		libraryDir = nativeDir;
		loaded = true;
		unavailable = null;
	}

	public static boolean loaded()
	{
		return loaded;
	}

	/** Why DLSS is unavailable, or null while it may still be. */
	public static String unavailableReason()
	{
		return unavailable;
	}

	static String appDataPath()
	{
		return appDataPath;
	}

	static String libraryDir()
	{
		return libraryDir;
	}

	static void disable(String reason)
	{
		loaded = false;
		unavailable = reason;
		log.info("DLSS unavailable: {}", reason);
	}

	/** The Vulkan instance and device carry every extension NGX asked for. */
	static void markExtensions()
	{
		extensions = true;
	}

	/** Initialises NGX on the created device and checks that DLSS is offered on it. */
	static boolean initialise(long instance, long physicalDevice, long device)
	{
		if (!loaded || !extensions)
		{
			return false;
		}
		int result = init(instance, physicalDevice, device, appDataPath, libraryDir);
		switch (result)
		{
			case 1:
				log.info("DLSS ready");
				return true;
			case -2:
				disable("the NVIDIA driver is too old for this DLSS library");
				return false;
			case -3:
				disable("this GPU does not offer DLSS");
				return false;
			case -4:
				disable("NGX denied DLSS to this application (result 0x" + Integer.toHexString(lastResult()) + ")");
				return false;
			default:
				disable("NGX initialisation failed with result 0x" + Integer.toHexString(result));
				return false;
		}
	}

	/** The nth point of the Halton sequence in the given base, in [0, 1). */
	static double halton(int index, int base)
	{
		double result = 0.0;
		double fraction = 1.0 / base;
		int i = index;
		while (i > 0)
		{
			result += fraction * (i % base);
			i /= base;
			fraction /= base;
		}
		return result;
	}

	static native String[] instanceExtensions(String appDataPath, String libraryDir);

	static native String[] deviceExtensions(long instance, long physicalDevice, String appDataPath, String libraryDir);

	private static native int init(long instance, long physicalDevice, long device, String appDataPath, String libraryDir);

	/** The traced width and height DLSS wants for a view of the given size at the quality, or null when that mode is not offered. */
	static native int[] optimalSettings(int outWidth, int outHeight, int quality);

	static native long createFeature(long device, long cmd, int renderWidth, int renderHeight, int outWidth, int outHeight, int quality, int flags);

	static native int evaluate(long cmd, long feature,
		long colorImage, long colorView, int colorFormat, int inWidth, int inHeight,
		long outImage, long outView, int outFormat, int outWidth, int outHeight,
		long depthImage, long depthView, int depthFormat,
		long motionImage, long motionView, int motionFormat,
		float jitterX, float jitterY, boolean reset);

	/** A Ray Reconstruction feature denoising at one size; flags as for DLSS. */
	static native long createDenoiser(long device, long cmd, int width, int height, int flags);

	static native int evaluateDenoiser(long cmd, long feature,
		long colorImage, long colorView, int colorFormat, int width, int height,
		long albedoImage, long albedoView, int albedoFormat,
		long specularImage, long specularView, int specularFormat,
		long normalImage, long normalView, int normalFormat,
		long depthImage, long depthView, int depthFormat,
		long motionImage, long motionView, int motionFormat,
		long outImage, long outView, int outFormat,
		float jitterX, float jitterY, boolean reset);

	static native void releaseFeature(long feature);

	static native void shutdown(long device);

	static native int lastResult();
}
