package rltx.scene;

import java.util.Arrays;

/**
 * A shallow-water pass over the terrain height grid: rain adds water, water runs to lower
 * neighbours by the difference in surface level, the ground drains it away, and a cap stands
 * in for runoff beyond the scene. Depth and flow per grid vertex feed the shader's wet ground.
 */
public final class WaterSim
{
	public static final int FLOATS = 4;
	private static final float RAIN_RATE = 14f;
	private static final float DRAIN_RAINING = 1.2f;
	private static final float DRAIN_DRY = 3.5f;
	private static final float MAX_DEPTH = 24f;
	private static final float RELAX_PER_SECOND = 6f;

	private final int side;
	/** Ground elevation with up positive, so water runs toward smaller values. */
	private final float[] elevation;
	private final float[] depth;
	private final float[] delta;
	private final float[] flowX;
	private final float[] flowZ;
	private final float[] packed;
	private boolean active;

	public WaterSim(int[][] heights)
	{
		side = heights.length;
		elevation = new float[side * side];
		for (int x = 0; x < side; ++x)
		{
			for (int y = 0; y < side; ++y)
			{
				elevation[x * side + y] = -heights[x][y];
			}
		}
		depth = new float[side * side];
		delta = new float[side * side];
		flowX = new float[side * side];
		flowZ = new float[side * side];
		packed = new float[side * side * FLOATS];
	}

	/** Advances the water by dt seconds under the given rain, 0 to 1. Returns whether any water remains. */
	public boolean step(float dt, float rain)
	{
		if (dt <= 0f)
		{
			return active;
		}
		float relax = Math.min(RELAX_PER_SECOND * dt, 0.5f);
		float drain = (rain > 0f ? DRAIN_RAINING : DRAIN_DRY) * dt;
		float fall = RAIN_RATE * rain * dt;
		Arrays.fill(delta, 0f);
		boolean any = false;
		for (int x = 0; x < side; ++x)
		{
			for (int y = 0; y < side; ++y)
			{
				int i = x * side + y;
				float d = depth[i] + fall;
				if (d <= 0f)
				{
					depth[i] = 0f;
					flowX[i] *= 0.9f;
					flowZ[i] *= 0.9f;
					continue;
				}
				float surface = elevation[i] + d;
				// Outflow to each lower neighbour scales with the drop in surface level; the sum
				// never exceeds what would level the cell with its neighbours this step.
				float total = 0f;
				float toXm = x > 0 ? Math.max(0f, surface - elevation[i - side] - depth[i - side]) : 0f;
				float toXp = x < side - 1 ? Math.max(0f, surface - elevation[i + side] - depth[i + side]) : 0f;
				float toYm = y > 0 ? Math.max(0f, surface - elevation[i - 1] - depth[i - 1]) : 0f;
				float toYp = y < side - 1 ? Math.max(0f, surface - elevation[i + 1] - depth[i + 1]) : 0f;
				total = toXm + toXp + toYm + toYp;
				if (total > 0f)
				{
					float moving = Math.min(d, total * 0.5f) * relax;
					float scale = moving / total;
					delta[i] -= moving;
					if (toXm > 0f) delta[i - side] += toXm * scale;
					if (toXp > 0f) delta[i + side] += toXp * scale;
					if (toYm > 0f) delta[i - 1] += toYm * scale;
					if (toYp > 0f) delta[i + 1] += toYp * scale;
					float vx = (toXp - toXm) * scale / dt;
					float vz = (toYp - toYm) * scale / dt;
					flowX[i] += (vx - flowX[i]) * 0.2f;
					flowZ[i] += (vz - flowZ[i]) * 0.2f;
				}
				else
				{
					flowX[i] *= 0.9f;
					flowZ[i] *= 0.9f;
				}
				depth[i] = d;
			}
		}
		for (int i = 0; i < depth.length; ++i)
		{
			float d = depth[i] + delta[i] - drain;
			d = Math.max(0f, Math.min(MAX_DEPTH, d));
			depth[i] = d;
			any |= d > 0f;
			int o = i * FLOATS;
			packed[o] = d;
			packed[o + 1] = flowX[i];
			packed[o + 2] = flowZ[i];
		}
		active = any;
		return active;
	}

	/** Depth, flow x, flow z per vertex, in the height grid's order. */
	public float[] packed()
	{
		return packed;
	}
}
