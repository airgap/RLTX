package rltx.scene;

/**
 * All static geometry of a loaded scene, grouped so roofs and levels can be
 * toggled per frame the way the vanilla zbuf renderer does.
 */
public final class StaticScene
{
	public final GeometryBuffer geometry;
	public final int[] groupLevel;
	public final int[] groupRoofId;
	public final int[] groupFaceBase;
	public final int[] groupFaceCount;
	/** Whether a group holds translucent faces, which need the non-opaque ray query path. */
	public final boolean[] groupTranslucent;

	StaticScene(GeometryBuffer geometry, int[] groupLevel, int[] groupRoofId, int[] groupFaceBase, int[] groupFaceCount, boolean[] groupTranslucent)
	{
		this.geometry = geometry;
		this.groupLevel = groupLevel;
		this.groupRoofId = groupRoofId;
		this.groupFaceBase = groupFaceBase;
		this.groupFaceCount = groupFaceCount;
		this.groupTranslucent = groupTranslucent;
	}

	public int groupCount()
	{
		return groupLevel.length;
	}
}
