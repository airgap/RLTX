package rltx;

import net.runelite.api.Constants;
import net.runelite.api.Perspective;
import net.runelite.api.WorldView;
import rltx.scene.GeometryBuffer;
import rltx.scene.StaticScene;
import rltx.scene.WaterType;
import rltx.vk.FrameParams;
import rltx.vk.RtRenderer;

/**
 * Water near the camera as real geometry: the eight longest waves of the shader's spectrum lift
 * and lower the surface each frame, so silhouettes, shorelines and pillars meet moving water.
 * The chop stays in the shading normals, which the shader still works out for all the waves.
 */
final class Waves
{
	private static final float WATER_RANGE = 14 * Perspective.LOCAL_TILE_SIZE;
	private static final int WATER_FACE_BUDGET = 60_000;
	private static final int GEOMETRY_WAVES = 8;

	private final RltxConfig config;
	private final FrameParams frame;
	private final double[] waveK = new double[GEOMETRY_WAVES];
	private final double[] waveDx = new double[GEOMETRY_WAVES];
	private final double[] waveDz = new double[GEOMETRY_WAVES];
	private final double[] waveOmega = new double[GEOMETRY_WAVES];
	private final double[] waveAmplitude = new double[GEOMETRY_WAVES];
	private final double[] wavePhase = new double[GEOMETRY_WAVES];
	private float[] waterScratch = new float[0];

	Waves(RltxConfig config, FrameParams frame)
	{
		this.config = config;
		this.frame = frame;
	}

	// The same wave table the shader builds, for its eight longest waves: indices 16 to 23.
	private void prepareWaves(double windAngle)
	{
		for (int w = 0; w < GEOMETRY_WAVES; ++w)
		{
			double fi = 24 - GEOMETRY_WAVES + w;
			double h1 = fract(Math.sin(fi * 12.9898) * 43758.5453);
			double h2 = fract(Math.sin(fi * 78.233 + 1.0) * 43758.5453);
			double h3 = fract(Math.sin(fi * 37.719 + 2.0) * 43758.5453);
			double wavelength = 15.0 * Math.pow(400.0 / 15.0, (fi + h1) / 24.0);
			double k = 2.0 * Math.PI / wavelength;
			double spread = 0.35 + (1.2 - 0.35) * (1.0 - fi / 24.0);
			double a = windAngle + (h2 - 0.5) * 2.0 * spread;
			waveK[w] = k;
			waveDx[w] = Math.cos(a);
			waveDz[w] = Math.sin(a);
			waveOmega[w] = Math.sqrt(9.8 * 128.0 * k);
			// The shader's slope amplitude over k gives the height amplitude of the same wave.
			waveAmplitude[w] = 2.0 * 0.22 * Math.pow(wavelength / 400.0, 0.25) / k;
			wavePhase[w] = h3 * 2.0 * Math.PI;
		}
	}

	private static double fract(double v)
	{
		return v - Math.floor(v);
	}

	// Height of the surface above rest at a point, in world units, for a wave time t.
	private float waveHeight(float x, float z, double t)
	{
		double h = 0.0;
		for (int w = 0; w < GEOMETRY_WAVES; ++w)
		{
			double phase = (waveDx[w] * x + waveDz[w] * z) * waveK[w] - waveOmega[w] * t + wavePhase[w];
			double s = 0.5 + 0.5 * Math.sin(phase);
			h += waveAmplitude[w] * (s * Math.sqrt(s) - 0.42);
		}
		return (float) h;
	}

	void push(LoadedScene top, GeometryBuffer dynamicWater, RtRenderer renderer)
	{
		if (!config.waveGeometry() || !frame.water || top == null)
		{
			renderer.setDisplacedZones(WorldView.TOPLEVEL, null);
			return;
		}
		StaticScene built = top.built;
		if (top.displaced == null || top.displaced.length != built.zones.length)
		{
			top.displaced = new boolean[built.zones.length];
		}
		double windAngle = frame.windVelocityX * frame.windVelocityX + frame.windVelocityZ * frame.windVelocityZ > 1f
			? Math.atan2(frame.windVelocityZ, frame.windVelocityX) : Math.atan2(0.78, 0.62);
		prepareWaves(windAngle);
		int offsetTiles = (built.zonesX * 8 - Constants.SCENE_SIZE) / 2;
		int budget = WATER_FACE_BUDGET;
		WaterType[] types = WaterType.values();
		for (int i = 0; i < built.zones.length; ++i)
		{
			StaticScene.Zone zone = built.zones[i];
			top.displaced[i] = false;
			if (zone == null)
			{
				continue;
			}
			int waterFaces = 0;
			for (int g = 0; g < zone.groupWater.length; ++g)
			{
				waterFaces += zone.groupWater[g] ? zone.groupFaceCount[g] : 0;
			}
			if (waterFaces == 0 || budget < waterFaces)
			{
				continue;
			}
			float centreX = ((i / built.zonesZ) * 8 - offsetTiles + 4) * Perspective.LOCAL_TILE_SIZE;
			float centreZ = ((i % built.zonesZ) * 8 - offsetTiles + 4) * Perspective.LOCAL_TILE_SIZE;
			float dx = centreX - frame.cameraX;
			float dz = centreZ - frame.cameraZ;
			if (dx * dx + dz * dz > WATER_RANGE * WATER_RANGE)
			{
				continue;
			}
			top.displaced[i] = true;
			budget -= waterFaces;
			float[] pos = zone.geometry.positions();
			int[] colors = zone.geometry.colors();
			int[] textures = zone.geometry.textures();
			float[] uvs = zone.geometry.uvs();
			for (int g = 0; g < zone.groupWater.length; ++g)
			{
				if (!zone.groupWater[g])
				{
					continue;
				}
				int start = dynamicWater.faces();
				int first = zone.groupFaceBase[g];
				int count = zone.groupFaceCount[g];
				if (waterScratch.length < count * 9)
				{
					waterScratch = new float[count * 9];
				}
				for (int f = 0; f < count; ++f)
				{
					int face = first + f;
					int o = face * 9;
					int tex = textures[face];
					int typeIndex = (tex >> 16) & 0xFF;
					WaterType type = typeIndex > 0 && typeIndex <= types.length ? types[typeIndex - 1] : null;
					float strength = type == null || type.flat ? 0f : type.normalStrength * frame.waveStrength;
					double t = frame.timeSeconds * 0.9 / Math.max(type == null ? 1f : type.duration, 0.05f);
					int so = f * 9;
					for (int v = 0; v < 3; ++v)
					{
						float x = pos[o + v * 3];
						float z = pos[o + v * 3 + 2];
						waterScratch[so + v * 3] = x;
						waterScratch[so + v * 3 + 1] = pos[o + v * 3 + 1] - (strength > 0f ? waveHeight(x, z, t) * strength : 0f);
						waterScratch[so + v * 3 + 2] = z;
					}
					int uo = face * 6;
					dynamicWater.face(waterScratch[so], waterScratch[so + 1], waterScratch[so + 2], waterScratch[so + 3], waterScratch[so + 4], waterScratch[so + 5],
						waterScratch[so + 6], waterScratch[so + 7], waterScratch[so + 8], colors[face], tex,
						uvs[uo], uvs[uo + 1], uvs[uo + 2], uvs[uo + 3], uvs[uo + 4], uvs[uo + 5]);
				}
				dynamicWater.setPreviousPositions(start, waterScratch, count);
			}
		}
		renderer.setDisplacedZones(WorldView.TOPLEVEL, top.displaced);
	}
}
