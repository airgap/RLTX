package rltx;

import net.runelite.api.Scene;
import rltx.scene.StaticScene;
import rltx.scene.StaticSceneBuilder;
import rltx.scene.WaterSim;
import rltx.scene.lights.SceneLights;

/** A scene the renderer holds, keyed by world view: its built geometry and what was derived from it. */
final class LoadedScene
{
	final Scene scene;
	final StaticScene built;
	final StaticSceneBuilder.WaterBed waterBed;
	/** Lights placed in the scene; null for nested world views. */
	final SceneLights lights;
	/** Zones whose foliage was drawn swayed last frame. */
	boolean[] swayed;
	/** Zones whose water was drawn displaced last frame. */
	boolean[] displaced;
	/** Rain runoff over the ground; null for nested world views. */
	WaterSim water;
	/** The mist grid as uploaded, for asking whether ground is misty. */
	float[] mist;
	int[][][] terrainLight;

	LoadedScene(Scene scene, StaticScene built, int[][][] terrainLight, StaticSceneBuilder.WaterBed waterBed, SceneLights lights)
	{
		this.scene = scene;
		this.built = built;
		this.terrainLight = terrainLight;
		this.waterBed = waterBed;
		this.lights = lights;
	}
}
