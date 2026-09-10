package rltx.vk;

import java.nio.ByteBuffer;
import java.util.Set;
import rltx.scene.GeometryBuffer;
import rltx.scene.StaticScene;

/**
 * A rendering backend behind the plugin. The front end (geometry extraction, environment,
 * UI) hands a backend the scene and a {@link FrameParams} each frame and reads the finished
 * image back through a shared handle; it does not know whether the backend traces the frame
 * (the Vulkan ray tracer, {@link RtRenderer}) or rasterises it. See {@code docs/normal-mode.md}.
 *
 * <p>The surface below is the whole of what the front end asks of a backend today. It is
 * grouped by role. Where a method carries a concept only one backend has — the {@code dlss}
 * and {@code rayReconstruction} arguments of {@link #ensureOutput}, say — a backend that
 * cannot honour it treats it as its nearest equivalent or ignores it, rather than the
 * contract growing a second shape.
 */
public interface Renderer
{
	// ---- scene geometry and materials the front end uploads ----

	void setStaticSet(int id, StaticScene scene, float[] transform);
	boolean updateZone(int id, int zx, int zz, StaticScene.Zone zone);
	void removeStaticSet(int id);
	boolean hasStaticSet(int id);
	void setStaticView(int id, float[] transform, int minLevel, int level, int maxLevel, Set<Integer> hiddenRoofIds);
	void setSwayedZones(int id, boolean[] swayed);
	void setDisplacedZones(int id, boolean[] displaced);
	void setGroundRange(int first, int count);
	void setMaterials(float[] table);
	void setTextureArray(int layers, int size, int levels, ByteBuffer rgba);
	void setReliefArray(int layers, int size, int levels, ByteBuffer heights);
	void setTextureAnimation(float[] uvPerCycle);
	void setTerrainHeights(float[] heights);
	void setCells(int[] bits);
	void setWaterMask(int[] bits);

	// ---- sky, environment and the per-frame effect buffers ----

	void setSkybox(int width, int height, ByteBuffer rgba);
	void setStarMap(int width, int height, ByteBuffer rgba);
	void setAtmosphere(int width, int height, ByteBuffer rgba);
	void setMistGrid(float[] grid);
	void setLights(float[] packed, int count);
	void setGuide(float[] packed, int floats);
	void setPlumes(float[] packed, int floats);
	void setTrees(float[] packed, int floats);
	void setPrints(float[] packed, int floats);
	void setMarkers(float[] packed, int floats);
	void setRunoff(float[] packed);
	void setRipples(float[] packed, int floats);
	void setUiMask(int[] pixels, int width, int height, int keyRgb);

	// ---- output sizing and the handles the GL compositor shares ----

	boolean ensureOutput(int width, int height, float scale, int dlss, boolean rayReconstruction);
	long outputHandle();
	long outputAllocationSize();
	long semaphoreVkDoneHandle();
	long semaphoreGlDoneHandle();
	int outputWidth();
	int outputHeight();
	int internalWidth();
	int internalHeight();

	// ---- drawing one frame ----

	void submit(FrameParams params, GeometryBuffer dynamic, GeometryBuffer translucent, GeometryBuffer water, boolean waitForGl, boolean signalGl);
	void resetHistory();

	// ---- reading the frame back, and instrumentation ----

	int[] readbackOutput();
	float[] readbackColor();
	float readbackDepth(int x, int y);
	long waitNanos();
	double averageLogLuminance();
	double lastGpuMillis();
	String passReport();

	// ---- lifecycle ----

	void destroy();
}
