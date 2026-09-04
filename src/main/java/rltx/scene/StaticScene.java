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

		Zone(int zx, int zz, GeometryBuffer geometry, int[] groupLevel, int[] groupRoofId, int[] groupFaceBase, int[] groupFaceCount, boolean[] groupTranslucent, boolean[] groupWater)
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

	StaticScene(int zonesX, int zonesZ, Zone[] zones)
	{
		this.zonesX = zonesX;
		this.zonesZ = zonesZ;
		this.zones = zones;
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
