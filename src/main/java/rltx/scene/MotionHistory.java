package rltx.scene;

import java.util.HashMap;
import java.util.Map;
import net.runelite.api.Renderable;

/**
 * Remembers where each dynamic renderable's faces were on the previous frame so the frame's
 * dynamic geometry can be built at a random moment inside the shutter interval.
 */
public final class MotionHistory
{
	private static final class Slices
	{
		float[] opaque;
		float[] translucent;
	}

	private Map<Renderable, Slices> last = new HashMap<>();
	private Map<Renderable, Slices> next = new HashMap<>();

	/**
	 * Records the faces just pushed for {@code renderable}, occupying [opaqueStart, opaque.faces())
	 * and [translucentStart, translucent.faces()), and fills their previous positions from the
	 * last frame when the face counts still match.
	 */
	public void record(Renderable renderable, GeometryBuffer opaque, int opaqueStart, GeometryBuffer translucent, int translucentStart)
	{
		Slices now = new Slices();
		now.opaque = slice(opaque, opaqueStart);
		now.translucent = slice(translucent, translucentStart);
		next.put(renderable, now);

		Slices before = last.get(renderable);
		if (before == null)
		{
			return;
		}
		if (before.opaque.length == now.opaque.length)
		{
			opaque.setPreviousPositions(opaqueStart, before.opaque, opaque.faces() - opaqueStart);
		}
		if (before.translucent.length == now.translucent.length)
		{
			translucent.setPreviousPositions(translucentStart, before.translucent, translucent.faces() - translucentStart);
		}
	}

	private static float[] slice(GeometryBuffer buffer, int firstFace)
	{
		int floats = (buffer.faces() - firstFace) * GeometryBuffer.FLOATS_PER_FACE;
		float[] out = new float[floats];
		System.arraycopy(buffer.positions(), firstFace * GeometryBuffer.FLOATS_PER_FACE, out, 0, floats);
		return out;
	}

	/** Ends the frame: this frame's positions become the next frame's history. */
	public void endFrame()
	{
		Map<Renderable, Slices> recycled = last;
		last = next;
		recycled.clear();
		next = recycled;
	}
}
