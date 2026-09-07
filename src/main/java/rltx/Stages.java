package rltx;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.RuneLite;
import rltx.scene.GeometryBuffer;
import rltx.scene.StaticScene;

/**
 * Places across Gielinor a portrait can be posed in. The client only ever holds the scene around
 * the character, so a place's surroundings are kept from a visit: when the character passes
 * within a few tiles of the spot, the static geometry around it is written to disk relative to
 * the spot, and from then on a portrait can stand there wherever the character actually is.
 */
@Slf4j
final class Stages
{
	static final File FOLDER = new File(RuneLite.RUNELITE_DIR, "rltx/stages");
	/** Tiles around the spot kept; and how close the character must come for the place to be captured. */
	private static final int RADIUS_TILES = 12;
	private static final int CAPTURE_TILES = 6;
	private static final int MAGIC = 0x52535431;

	/** A place: where the character stands, which way the camera looks from, in world terms. */
	static final class Place
	{
		final RltxConfig.PortraitScene scene;
		final String label;
		final WorldPoint spot;
		/** The camera's yaw, 0 for a camera south of the spot looking north, growing clockwise seen from above. */
		final float cameraYaw;

		Place(RltxConfig.PortraitScene scene, String label, int x, int y, int plane, float cameraYawDegrees)
		{
			this.scene = scene;
			this.label = label;
			this.spot = new WorldPoint(x, y, plane);
			this.cameraYaw = (float) Math.toRadians(cameraYawDegrees);
		}

		File file()
		{
			return new File(FOLDER, scene.name().toLowerCase() + ".stage");
		}

		boolean captured()
		{
			return file().exists();
		}
	}

	/** The captured surroundings of a place, relative to its spot with the ground there at height zero. */
	static final class Stage
	{
		final GeometryBuffer opaque;
		final GeometryBuffer translucent;
		final GeometryBuffer water;

		Stage(GeometryBuffer opaque, GeometryBuffer translucent, GeometryBuffer water)
		{
			this.opaque = opaque;
			this.translucent = translucent;
			this.water = water;
		}
	}

	static final Place[] PLACES = {
		new Place(RltxConfig.PortraitScene.LUMBRIDGE, "Lumbridge Castle", 3222, 3212, 0, 0f),
		new Place(RltxConfig.PortraitScene.VARROCK, "Varrock fountain", 3212, 3421, 0, 0f),
		new Place(RltxConfig.PortraitScene.FALADOR, "Falador Park", 2997, 3368, 0, 0f),
		new Place(RltxConfig.PortraitScene.DRAYNOR, "Draynor Manor", 3109, 3345, 0, 0f),
		new Place(RltxConfig.PortraitScene.GRAND_EXCHANGE, "Grand Exchange", 3164, 3480, 0, 0f),
		new Place(RltxConfig.PortraitScene.EDGEVILLE, "Edgeville", 3094, 3486, 0, 0f),
		new Place(RltxConfig.PortraitScene.SEERS, "Seers' Village", 2725, 3486, 0, 0f),
	};

	static Place place(RltxConfig.PortraitScene scene)
	{
		for (Place p : PLACES)
		{
			if (p.scene == scene)
			{
				return p;
			}
		}
		return null;
	}

	private final Client client;
	private final Consumer<String> say;
	private Place loadedPlace;
	private Stage loaded;

	Stages(Client client, Consumer<String> say)
	{
		this.client = client;
		this.say = say;
	}

	/** Once a tick: a place the character has come to that is not yet kept is captured from the loaded scene. */
	void tick(LoadedScene top, WorldPoint here)
	{
		if (top == null || here == null)
		{
			return;
		}
		for (Place place : PLACES)
		{
			if (place.spot.getPlane() != here.getPlane() || place.spot.distanceTo(here) > CAPTURE_TILES || place.captured())
			{
				continue;
			}
			try
			{
				capture(top, place);
				say.accept("RLTX: kept the " + place.label + " scene for portraits");
			}
			catch (IOException e)
			{
				log.warn("Scene {} not kept", place.label, e);
				say.accept("RLTX: could not keep the " + place.label + " scene, " + e.getMessage());
			}
		}
	}

	// Every static face within the radius of the spot, moved so the spot is the origin and the
	// ground there is at height zero, written as three face lists: solid, translucent, water.
	private void capture(LoadedScene top, Place place) throws IOException
	{
		WorldView wv = client.getTopLevelWorldView();
		LocalPoint lp = wv == null ? null : LocalPoint.fromWorld(wv, place.spot);
		if (lp == null)
		{
			throw new IOException("the spot is outside the loaded scene");
		}
		float ox = lp.getX();
		float oz = lp.getY();
		float oy = Perspective.getTileHeight(client, lp, place.spot.getPlane());
		float radius = RADIUS_TILES * Perspective.LOCAL_TILE_SIZE;
		GeometryBuffer opaque = new GeometryBuffer(1 << 16);
		GeometryBuffer translucent = new GeometryBuffer(1 << 12);
		GeometryBuffer water = new GeometryBuffer(1 << 12);
		StaticScene built = top.built;
		for (StaticScene.Zone zone : built.zones)
		{
			if (zone == null)
			{
				continue;
			}
			float[] pos = zone.geometry.positions();
			int[] colors = zone.geometry.colors();
			int[] textures = zone.geometry.textures();
			float[] uvs = zone.geometry.uvs();
			int[] normals = zone.geometry.normals();
			for (int g = 0; g < zone.groupCount(); ++g)
			{
				GeometryBuffer out = zone.groupWater[g] ? water : zone.groupTranslucent[g] ? translucent : opaque;
				for (int f = zone.groupFaceBase[g]; f < zone.groupFaceBase[g] + zone.groupFaceCount[g]; ++f)
				{
					int o = f * 9;
					float cx = (pos[o] + pos[o + 3] + pos[o + 6]) / 3f - ox;
					float cz = (pos[o + 2] + pos[o + 5] + pos[o + 8]) / 3f - oz;
					if (cx * cx + cz * cz > radius * radius)
					{
						continue;
					}
					int uo = f * 6;
					out.face(pos[o] - ox, pos[o + 1] - oy, pos[o + 2] - oz, pos[o + 3] - ox, pos[o + 4] - oy, pos[o + 5] - oz,
						pos[o + 6] - ox, pos[o + 7] - oy, pos[o + 8] - oz, colors[f], textures[f],
						uvs[uo], uvs[uo + 1], uvs[uo + 2], uvs[uo + 3], uvs[uo + 4], uvs[uo + 5]);
					out.lastNormals(normals[f * 3], normals[f * 3 + 1], normals[f * 3 + 2]);
				}
			}
		}
		if (opaque.faces() == 0)
		{
			throw new IOException("nothing stands within reach of the spot");
		}
		if (!FOLDER.exists() && !FOLDER.mkdirs())
		{
			throw new IOException("could not create " + FOLDER);
		}
		int bytes = 8 + size(opaque) + size(translucent) + size(water);
		ByteBuffer buffer = ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN);
		buffer.putInt(MAGIC);
		buffer.putInt(1);
		write(buffer, opaque);
		write(buffer, translucent);
		write(buffer, water);
		buffer.flip();
		try (FileChannel channel = FileChannel.open(place.file().toPath(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE))
		{
			while (buffer.hasRemaining())
			{
				channel.write(buffer);
			}
		}
		log.info("Kept the {} scene: {} solid, {} translucent, {} water faces", place.label, opaque.faces(), translucent.faces(), water.faces());
	}

	private static int size(GeometryBuffer b)
	{
		return 4 + b.faces() * (GeometryBuffer.FLOATS_PER_FACE * 4 + 4 + 4 + GeometryBuffer.UV_FLOATS_PER_FACE * 4 + GeometryBuffer.NORMALS_PER_FACE * 4);
	}

	private static void write(ByteBuffer buffer, GeometryBuffer b)
	{
		int n = b.faces();
		buffer.putInt(n);
		buffer.asFloatBuffer().put(b.positions(), 0, n * GeometryBuffer.FLOATS_PER_FACE);
		buffer.position(buffer.position() + n * GeometryBuffer.FLOATS_PER_FACE * 4);
		buffer.asIntBuffer().put(b.colors(), 0, n);
		buffer.position(buffer.position() + n * 4);
		buffer.asIntBuffer().put(b.textures(), 0, n);
		buffer.position(buffer.position() + n * 4);
		buffer.asFloatBuffer().put(b.uvs(), 0, n * GeometryBuffer.UV_FLOATS_PER_FACE);
		buffer.position(buffer.position() + n * GeometryBuffer.UV_FLOATS_PER_FACE * 4);
		buffer.asIntBuffer().put(b.normals(), 0, n * GeometryBuffer.NORMALS_PER_FACE);
		buffer.position(buffer.position() + n * GeometryBuffer.NORMALS_PER_FACE * 4);
	}

	/** The kept surroundings of a place, read once and held; null when the place has not been captured. */
	Stage load(Place place) throws IOException
	{
		if (loaded != null && loadedPlace == place)
		{
			return loaded;
		}
		if (!place.captured())
		{
			return null;
		}
		ByteBuffer buffer = ByteBuffer.wrap(Files.readAllBytes(place.file().toPath())).order(ByteOrder.LITTLE_ENDIAN);
		if (buffer.getInt() != MAGIC || buffer.getInt() != 1)
		{
			throw new IOException(place.file() + " is not a scene file this build reads");
		}
		loaded = new Stage(read(buffer), read(buffer), read(buffer));
		loadedPlace = place;
		return loaded;
	}

	private static GeometryBuffer read(ByteBuffer buffer)
	{
		int n = buffer.getInt();
		GeometryBuffer b = new GeometryBuffer(Math.max(n, 1));
		float[] pos = new float[n * GeometryBuffer.FLOATS_PER_FACE];
		buffer.asFloatBuffer().get(pos);
		buffer.position(buffer.position() + pos.length * 4);
		int[] colors = new int[n];
		buffer.asIntBuffer().get(colors);
		buffer.position(buffer.position() + n * 4);
		int[] textures = new int[n];
		buffer.asIntBuffer().get(textures);
		buffer.position(buffer.position() + n * 4);
		float[] uvs = new float[n * GeometryBuffer.UV_FLOATS_PER_FACE];
		buffer.asFloatBuffer().get(uvs);
		buffer.position(buffer.position() + uvs.length * 4);
		int[] normals = new int[n * GeometryBuffer.NORMALS_PER_FACE];
		buffer.asIntBuffer().get(normals);
		buffer.position(buffer.position() + normals.length * 4);
		for (int f = 0; f < n; ++f)
		{
			int o = f * 9;
			int uo = f * 6;
			b.face(pos[o], pos[o + 1], pos[o + 2], pos[o + 3], pos[o + 4], pos[o + 5], pos[o + 6], pos[o + 7], pos[o + 8], colors[f], textures[f],
				uvs[uo], uvs[uo + 1], uvs[uo + 2], uvs[uo + 3], uvs[uo + 4], uvs[uo + 5]);
			b.lastNormals(normals[f * 3], normals[f * 3 + 1], normals[f * 3 + 2]);
		}
		return b;
	}
}
