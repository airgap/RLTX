package rltx;

/**
 * Camera conventions shared with the vanilla zbuf projection:
 * view = rotX(pitch) * rotY(yaw) * (world - camera), and a pixel at
 * (px, py) from the top-left sees view direction ((px - w/2)/zoom, (py - h/2)/zoom, 1).
 */
public final class CameraMath
{
	private CameraMath()
	{
	}

	/** Writes the rows of the camera rotation, which maps world-space offsets to view space. */
	public static void forwardRotation(float pitch, float yaw, float[] out)
	{
		float sx = (float) Math.sin(pitch);
		float cx = (float) Math.cos(pitch);
		float sy = (float) Math.sin(yaw);
		float cy = (float) Math.cos(yaw);

		out[0] = cy;
		out[1] = 0;
		out[2] = sy;

		out[3] = sx * sy;
		out[4] = cx;
		out[5] = -sx * cy;

		out[6] = -cx * sy;
		out[7] = sx;
		out[8] = cx * cy;
	}

	/**
	 * Writes the rows of the inverse camera rotation, which maps view-space
	 * directions back to world space.
	 */
	public static void inverseRotation(float pitch, float yaw, float[] out)
	{
		float sx = (float) Math.sin(pitch);
		float cx = (float) Math.cos(pitch);
		float sy = (float) Math.sin(yaw);
		float cy = (float) Math.cos(yaw);

		out[0] = cy;
		out[1] = sx * sy;
		out[2] = -cx * sy;

		out[3] = 0;
		out[4] = cx;
		out[5] = sx;

		out[6] = sy;
		out[7] = -sx * cy;
		out[8] = cx * cy;
	}
}
