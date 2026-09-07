package rltx.scene;

/**
 * All static geometry of a loaded scene, split into zones so a change to one zone rebuilds
 * only that zone, and within a zone into groups by level, roof and translucency so the
 * per-frame visibility rules of the vanilla zbuf renderer can be applied.
 */
public final class StaticScene
{
	/** One zone's geometry; groups index into {@link #geometry} by face. */
	public static final class Zone
	{
		public final int zx;
		public final int zz;
		public final GeometryBuffer geometry;
		public final int[] groupLevel;
		public final int[] groupRoofId;
		public final int[] groupFaceBase;
		public final int[] groupFaceCount;
		public final boolean[] groupTranslucent;
		/** Water surfaces, kept apart so shadow rays can pass through them. */
		public final boolean[] groupWater;
		/** The water faces cut fine and flat for the dynamic path, built on first use and kept with the zone. */
		public GeometryBuffer waterMesh;
		/** Foliage that sways in the wind; near the camera these groups are replaced by swayed copies each frame. */
		public final boolean[] groupSway;
		/** The foliage faces of this zone in their resting pose, and per vertex how freely each moves. */
		public final GeometryBuffer sway;
		public final float[] swayWeights;

		Zone(int zx, int zz, GeometryBuffer geometry, int[] groupLevel, int[] groupRoofId, int[] groupFaceBase, int[] groupFaceCount, boolean[] groupTranslucent, boolean[] groupWater,
			boolean[] groupSway, GeometryBuffer sway, float[] swayWeights)
		{
			this.zx = zx;
			this.zz = zz;
			this.geometry = geometry;
			this.groupLevel = groupLevel;
			this.groupRoofId = groupRoofId;
			this.groupFaceBase = groupFaceBase;
			this.groupFaceCount = groupFaceCount;
			this.groupTranslucent = groupTranslucent;
			this.groupWater = groupWater;
			this.groupSway = groupSway;
			this.sway = sway;
			this.swayWeights = swayWeights;
		}

		public int groupCount()
		{
			return groupLevel.length;
		}
	}

	public final int zonesX;
	public final int zonesZ;
	/** Indexed by {@code zx * zonesZ + zz}; entries without faces are null. */
	public final Zone[] zones;
	/** Where smoke rises from: x, y, z of each chimney mouth or standing fire's top, then its strength. */
	public final float[] plumes;
	/** The trees: x, the height of the crown, z and the crown's radius each, for what falls from them. */
	public final float[] trees;

	StaticScene(int zonesX, int zonesZ, Zone[] zones, float[] plumes, float[] trees)
	{
		this.zonesX = zonesX;
		this.zonesZ = zonesZ;
		this.zones = zones;
		this.plumes = plumes;
		this.trees = trees;
	}

	public int totalFaces()
	{
		int total = 0;
		for (Zone zone : zones)
		{
			if (zone != null)
			{
				total += zone.geometry.faces();
			}
		}
		return total;
	}
}
