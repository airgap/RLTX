package rltx.gl;

import static org.lwjgl.opengl.GL43C.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import lombok.extern.slf4j.Slf4j;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.EXTMemoryObject;
import org.lwjgl.opengl.EXTMemoryObjectFD;
import org.lwjgl.opengl.EXTSemaphore;
import org.lwjgl.opengl.EXTSemaphoreFD;
import org.lwjgl.opengl.GLCapabilities;

/**
 * The OpenGL half of the frame: imports the Vulkan output image and the two
 * shared semaphores, draws the traced scene into the viewport and the
 * client's software-rendered interface over the whole canvas.
 */
@Slf4j
public final class GlCompositor
{
	private final int quadVao;
	private final int quadVbo;
	private final int uiProgram;
	private final int uiOverlayLocation;
	private final int sceneProgram;
	private final int uiTexture;
	private final int uiPbo;
	private int uiTextureWidth = -1;
	private int uiTextureHeight = -1;

	private int sceneTexture;
	private int sceneMemory;
	private int semaphoreVkDone;
	private int semaphoreGlDone;

	private final IntBuffer noBuffers = BufferUtils.createIntBuffer(0);
	private final IntBuffer oneTexture = BufferUtils.createIntBuffer(1);
	private final IntBuffer oneLayout = BufferUtils.createIntBuffer(1);

	public GlCompositor(GLCapabilities caps)
	{
		if (!caps.OpenGL43)
		{
			throw new IllegalStateException("OpenGL 4.3 is required");
		}
		if (!caps.GL_EXT_memory_object || !caps.GL_EXT_memory_object_fd || !caps.GL_EXT_semaphore || !caps.GL_EXT_semaphore_fd)
		{
			throw new IllegalStateException("OpenGL driver lacks EXT_memory_object_fd / EXT_semaphore_fd, needed to share the Vulkan image");
		}

		uiProgram = linkProgram("/rltx/quad.vert", "/rltx/ui.frag");
		uiOverlayLocation = glGetUniformLocation(uiProgram, "alphaOverlay");
		glUseProgram(uiProgram);
		glUniform1i(glGetUniformLocation(uiProgram, "tex"), 0);
		sceneProgram = linkProgram("/rltx/quad.vert", "/rltx/scene.frag");
		glUseProgram(sceneProgram);
		glUniform1i(glGetUniformLocation(sceneProgram, "tex"), 0);
		glUseProgram(0);

		quadVao = glGenVertexArrays();
		quadVbo = glGenBuffers();
		glBindVertexArray(quadVao);
		glBindBuffer(GL_ARRAY_BUFFER, quadVbo);
		// Texture row 0 lands at the top of the screen, matching both the client's
		// interface image and the compute shader's output.
		glBufferData(GL_ARRAY_BUFFER, new float[]{
			1f, 1f, 0f, 1f, 0f,
			1f, -1f, 0f, 1f, 1f,
			-1f, -1f, 0f, 0f, 1f,
			-1f, 1f, 0f, 0f, 0f,
		}, GL_STATIC_DRAW);
		glVertexAttribPointer(0, 3, GL_FLOAT, false, 5 * Float.BYTES, 0);
		glEnableVertexAttribArray(0);
		glVertexAttribPointer(1, 2, GL_FLOAT, false, 5 * Float.BYTES, 3 * Float.BYTES);
		glEnableVertexAttribArray(1);
		glBindVertexArray(0);
		glBindBuffer(GL_ARRAY_BUFFER, 0);

		uiPbo = glGenBuffers();
		uiTexture = glGenTextures();
		glBindTexture(GL_TEXTURE_2D, uiTexture);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
		glBindTexture(GL_TEXTURE_2D, 0);

		oneLayout.put(0, EXTSemaphore.GL_LAYOUT_GENERAL_EXT);
	}

	public byte[] deviceUuid()
	{
		ByteBuffer uuid = BufferUtils.createByteBuffer(EXTMemoryObject.GL_UUID_SIZE_EXT);
		EXTMemoryObject.glGetUnsignedBytei_vEXT(EXTMemoryObject.GL_DEVICE_UUID_EXT, 0, uuid);
		byte[] out = new byte[EXTMemoryObject.GL_UUID_SIZE_EXT];
		uuid.get(out);
		return out;
	}

	public void importSemaphores(int vkDoneFd, int glDoneFd)
	{
		IntBuffer ids = BufferUtils.createIntBuffer(2);
		EXTSemaphore.glGenSemaphoresEXT(ids);
		semaphoreVkDone = ids.get(0);
		semaphoreGlDone = ids.get(1);
		EXTSemaphoreFD.glImportSemaphoreFdEXT(semaphoreVkDone, EXTMemoryObjectFD.GL_HANDLE_TYPE_OPAQUE_FD_EXT, vkDoneFd);
		EXTSemaphoreFD.glImportSemaphoreFdEXT(semaphoreGlDone, EXTMemoryObjectFD.GL_HANDLE_TYPE_OPAQUE_FD_EXT, glDoneFd);
		checkErrors("import semaphores");
	}

	public void importSceneImage(int fd, long allocationSize, int width, int height)
	{
		releaseSceneImage();

		IntBuffer mem = BufferUtils.createIntBuffer(1);
		EXTMemoryObject.glCreateMemoryObjectsEXT(mem);
		sceneMemory = mem.get(0);
		EXTMemoryObject.glMemoryObjectParameteriEXT(sceneMemory, EXTMemoryObject.GL_DEDICATED_MEMORY_OBJECT_EXT, GL_TRUE);
		EXTMemoryObjectFD.glImportMemoryFdEXT(sceneMemory, allocationSize, EXTMemoryObjectFD.GL_HANDLE_TYPE_OPAQUE_FD_EXT, fd);

		sceneTexture = glGenTextures();
		glBindTexture(GL_TEXTURE_2D, sceneTexture);
		glTexParameteri(GL_TEXTURE_2D, EXTMemoryObject.GL_TEXTURE_TILING_EXT, EXTMemoryObject.GL_OPTIMAL_TILING_EXT);
		EXTMemoryObject.glTexStorageMem2DEXT(GL_TEXTURE_2D, 1, GL_RGBA8, width, height, sceneMemory, 0);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
		glBindTexture(GL_TEXTURE_2D, 0);
		oneTexture.put(0, sceneTexture);
		checkErrors("import scene image");
	}

	private void releaseSceneImage()
	{
		if (sceneTexture != 0)
		{
			glDeleteTextures(sceneTexture);
			sceneTexture = 0;
		}
		if (sceneMemory != 0)
		{
			EXTMemoryObject.glDeleteMemoryObjectsEXT(sceneMemory);
			sceneMemory = 0;
		}
	}

	public void updateUiTexture(int[] pixels, int width, int height, int canvasWidth, int canvasHeight)
	{
		if (canvasWidth != uiTextureWidth || canvasHeight != uiTextureHeight)
		{
			uiTextureWidth = canvasWidth;
			uiTextureHeight = canvasHeight;
			glBindTexture(GL_TEXTURE_2D, uiTexture);
			glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, canvasWidth, canvasHeight, 0, GL_BGRA, GL_UNSIGNED_BYTE, 0);
			glBindTexture(GL_TEXTURE_2D, 0);
		}

		glBindBuffer(GL_PIXEL_UNPACK_BUFFER, uiPbo);
		glBufferData(GL_PIXEL_UNPACK_BUFFER, (long) width * height * Integer.BYTES, GL_STREAM_DRAW);
		ByteBuffer mapped = glMapBuffer(GL_PIXEL_UNPACK_BUFFER, GL_WRITE_ONLY);
		if (mapped == null)
		{
			glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
			return;
		}
		mapped.asIntBuffer().put(pixels, 0, width * height);
		glUnmapBuffer(GL_PIXEL_UNPACK_BUFFER);
		glBindTexture(GL_TEXTURE_2D, uiTexture);
		glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, 0);
		glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
		glBindTexture(GL_TEXTURE_2D, 0);
	}

	/**
	 * Waits for Vulkan to finish the frame, draws it into the given viewport
	 * rectangle and hands the image back to Vulkan.
	 */
	public void drawScene(int x, int y, int width, int height)
	{
		EXTSemaphore.glWaitSemaphoreEXT(semaphoreVkDone, noBuffers, oneTexture, oneLayout);
		glViewport(x, y, width, height);
		glUseProgram(sceneProgram);
		glBindTexture(GL_TEXTURE_2D, sceneTexture);
		glBindVertexArray(quadVao);
		glDrawArrays(GL_TRIANGLE_FAN, 0, 4);
		glBindVertexArray(0);
		glBindTexture(GL_TEXTURE_2D, 0);
		glUseProgram(0);
		EXTSemaphore.glSignalSemaphoreEXT(semaphoreGlDone, noBuffers, oneTexture, oneLayout);
	}

	/** Reads the shared scene texture back and returns the RGBA8 texel at (x, y). Debug use only. */
	public int readSceneTexel(int x, int y, int width, int height)
	{
		ByteBuffer pixels = BufferUtils.createByteBuffer(width * height * 4);
		glBindTexture(GL_TEXTURE_2D, sceneTexture);
		glGetTexImage(GL_TEXTURE_2D, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
		glBindTexture(GL_TEXTURE_2D, 0);
		return pixels.getInt((y * width + x) * 4);
	}

	public void drawUi(int overlayColor, int x, int y, int width, int height)
	{
		glEnable(GL_BLEND);
		glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
		glViewport(x, y, width, height);
		glUseProgram(uiProgram);
		glUniform4f(uiOverlayLocation,
			(overlayColor >> 16 & 0xFF) / 255f,
			(overlayColor >> 8 & 0xFF) / 255f,
			(overlayColor & 0xFF) / 255f,
			(overlayColor >>> 24) / 255f);
		glBindTexture(GL_TEXTURE_2D, uiTexture);
		glBindVertexArray(quadVao);
		glDrawArrays(GL_TRIANGLE_FAN, 0, 4);
		glBindVertexArray(0);
		glBindTexture(GL_TEXTURE_2D, 0);
		glUseProgram(0);
		glDisable(GL_BLEND);
	}

	public void destroy()
	{
		releaseSceneImage();
		if (semaphoreVkDone != 0)
		{
			IntBuffer ids = BufferUtils.createIntBuffer(2);
			ids.put(0, semaphoreVkDone).put(1, semaphoreGlDone);
			EXTSemaphore.glDeleteSemaphoresEXT(ids);
		}
		glDeleteTextures(uiTexture);
		glDeleteBuffers(uiPbo);
		glDeleteBuffers(quadVbo);
		glDeleteVertexArrays(quadVao);
		glDeleteProgram(uiProgram);
		glDeleteProgram(sceneProgram);
	}

	public static void checkErrors(String where)
	{
		int err = glGetError();
		if (err != GL_NO_ERROR)
		{
			throw new IllegalStateException("OpenGL error 0x" + Integer.toHexString(err) + " after " + where);
		}
	}

	private static int linkProgram(String vertexResource, String fragmentResource)
	{
		int vs = compile(GL_VERTEX_SHADER, vertexResource);
		int fs = compile(GL_FRAGMENT_SHADER, fragmentResource);
		int program = glCreateProgram();
		glAttachShader(program, vs);
		glAttachShader(program, fs);
		glLinkProgram(program);
		glDeleteShader(vs);
		glDeleteShader(fs);
		if (glGetProgrami(program, GL_LINK_STATUS) != GL_TRUE)
		{
			throw new IllegalStateException("Link failed for " + fragmentResource + ": " + glGetProgramInfoLog(program));
		}
		return program;
	}

	private static int compile(int type, String resource)
	{
		String source;
		try (InputStream in = GlCompositor.class.getResourceAsStream(resource))
		{
			if (in == null)
			{
				throw new IllegalStateException("Missing shader resource " + resource);
			}
			source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException e)
		{
			throw new IllegalStateException("Failed to read " + resource, e);
		}
		int shader = glCreateShader(type);
		glShaderSource(shader, source);
		glCompileShader(shader);
		if (glGetShaderi(shader, GL_COMPILE_STATUS) != GL_TRUE)
		{
			throw new IllegalStateException("Compile failed for " + resource + ": " + glGetShaderInfoLog(shader));
		}
		return shader;
	}
}
