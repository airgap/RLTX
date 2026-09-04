package rltx.scene;

/**
 * Water appearance per vanilla water texture, taken from 117 HD's water_types.json. Colours are
 * the sRGB values over 255, used as multipliers the way 117 HD's shader uses them; gloss is a
 * specular exponent, and flat types are opaque with no visible bed.
 * The ordinal plus one is the index the shader uses; 0 means not water.
 */
public enum WaterType
{
	WATER(-1, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.4118f, 0.5020f, 0.6118f, 0.6902f, 0.6431f, 0.5725f, 0.0000f, 0.4588f, 0.5569f),
	SWAMP_WATER_FLAT(25, true, 0.1f, 100.0f, 0.05f, 0.8f, 0.3f, true, 1.2f,
		0.0902f, 0.1294f, 0.0784f, 0.4510f, 0.4706f, 0.3961f, 0.1608f, 0.3216f, 0.1020f),
	VANILLA_130(130, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.3333f, 0.4353f, 0.5608f, 0.6902f, 0.6431f, 0.5725f, 0.0000f, 0.4588f, 0.5569f),
	VANILLA_131(131, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.3255f, 0.4196f, 0.5333f, 0.6902f, 0.6431f, 0.5725f, 0.0000f, 0.4588f, 0.5569f),
	VANILLA_132(132, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.3020f, 0.3647f, 0.5059f, 0.6902f, 0.6431f, 0.5725f, 0.0000f, 0.4588f, 0.5569f),
	VANILLA_133(133, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.2353f, 0.2941f, 0.4431f, 0.6902f, 0.6431f, 0.5725f, 0.0000f, 0.4588f, 0.5569f),
	VANILLA_134(134, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.2157f, 0.2667f, 0.4039f, 0.6902f, 0.6431f, 0.5725f, 0.0000f, 0.4588f, 0.5569f),
	VANILLA_135(135, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.4078f, 0.5451f, 0.6118f, 0.6902f, 0.6431f, 0.5725f, 0.0000f, 0.4588f, 0.5569f),
	VANILLA_136(136, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.3882f, 0.5059f, 0.5373f, 0.6902f, 0.6431f, 0.5725f, 0.0000f, 0.4588f, 0.5569f),
	VANILLA_137(137, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.3255f, 0.4667f, 0.5137f, 0.6902f, 0.6431f, 0.5725f, 0.0000f, 0.4588f, 0.5569f),
	VANILLA_138(138, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.3333f, 0.4392f, 0.4980f, 0.6902f, 0.6431f, 0.5725f, 0.0000f, 0.4588f, 0.5569f),
	VANILLA_139(139, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.2667f, 0.3608f, 0.4471f, 0.6902f, 0.6431f, 0.5725f, 0.0000f, 0.4588f, 0.5569f),
	VANILLA_140(140, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.3020f, 0.4196f, 0.4980f, 0.6902f, 0.6431f, 0.5725f, 0.0000f, 0.4588f, 0.5569f),
	VANILLA_141(141, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.2902f, 0.3961f, 0.4706f, 0.6902f, 0.6431f, 0.5725f, 0.0000f, 0.4588f, 0.5569f),
	VANILLA_142(142, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.2667f, 0.3804f, 0.4588f, 0.6902f, 0.6431f, 0.5725f, 0.0000f, 0.4588f, 0.5569f),
	VANILLA_143(143, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.2588f, 0.3569f, 0.4431f, 0.6902f, 0.6431f, 0.5725f, 0.0000f, 0.4588f, 0.5569f),
	VANILLA_144(144, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.2471f, 0.3451f, 0.4235f, 0.6902f, 0.6431f, 0.5725f, 0.0000f, 0.4588f, 0.5569f),
	VANILLA_145(145, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.4235f, 0.4706f, 0.6353f, 0.6902f, 0.6431f, 0.5725f, 0.0000f, 0.4588f, 0.5569f),
	VANILLA_146(146, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.3765f, 0.4196f, 0.6000f, 0.6902f, 0.6431f, 0.5725f, 0.0000f, 0.4588f, 0.5569f),
	VANILLA_147(147, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.3451f, 0.3686f, 0.5608f, 0.6902f, 0.6431f, 0.5725f, 0.0000f, 0.4588f, 0.5569f),
	VANILLA_148(148, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.2784f, 0.2392f, 0.4471f, 0.6902f, 0.6431f, 0.5725f, 0.0000f, 0.4588f, 0.5569f),
	VANILLA_149(149, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.2471f, 0.2196f, 0.3882f, 0.6902f, 0.6431f, 0.5725f, 0.0000f, 0.4588f, 0.5569f),
	VANILLA_150(150, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.4078f, 0.4745f, 0.6157f, 0.6902f, 0.6431f, 0.5725f, 0.0000f, 0.4588f, 0.5569f),
	VANILLA_151(151, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.3765f, 0.4314f, 0.5647f, 0.6902f, 0.6431f, 0.5725f, 0.0000f, 0.4588f, 0.5569f),
	VANILLA_152(152, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.3255f, 0.3922f, 0.5373f, 0.6902f, 0.6431f, 0.5725f, 0.0000f, 0.4588f, 0.5569f),
	VANILLA_153(153, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.3098f, 0.3412f, 0.5059f, 0.6902f, 0.6431f, 0.5725f, 0.0000f, 0.4588f, 0.5569f),
	VANILLA_154(154, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.2471f, 0.2745f, 0.4471f, 0.6902f, 0.6431f, 0.5725f, 0.0000f, 0.4588f, 0.5569f),
	VANILLA_155(155, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.4314f, 0.4588f, 0.5843f, 0.6902f, 0.6431f, 0.5725f, 0.0000f, 0.4588f, 0.5569f),
	VANILLA_156(156, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.3961f, 0.4196f, 0.5333f, 0.6902f, 0.6431f, 0.5725f, 0.0000f, 0.4588f, 0.5569f),
	VANILLA_157(157, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.3490f, 0.3804f, 0.5059f, 0.6902f, 0.6431f, 0.5725f, 0.0000f, 0.4588f, 0.5569f),
	VANILLA_158(158, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.3451f, 0.3451f, 0.4549f, 0.6902f, 0.6431f, 0.5725f, 0.0000f, 0.4588f, 0.5569f),
	VANILLA_159(159, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.2824f, 0.2745f, 0.3922f, 0.6902f, 0.6431f, 0.5725f, 0.0000f, 0.4588f, 0.5569f),
	VANILLA_160(160, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.4784f, 0.5529f, 0.6392f, 0.6902f, 0.6431f, 0.5725f, 0.0000f, 0.4588f, 0.5569f),
	VANILLA_161(161, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.4471f, 0.5137f, 0.5922f, 0.6902f, 0.6431f, 0.5725f, 0.0000f, 0.4588f, 0.5569f),
	VANILLA_162(162, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.4000f, 0.4784f, 0.5686f, 0.6902f, 0.6431f, 0.5725f, 0.0000f, 0.4588f, 0.5569f),
	VANILLA_163(163, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.3804f, 0.4314f, 0.5451f, 0.6902f, 0.6431f, 0.5725f, 0.0000f, 0.4588f, 0.5569f),
	VANILLA_164(164, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.3137f, 0.3608f, 0.4784f, 0.6902f, 0.6431f, 0.5725f, 0.0000f, 0.4588f, 0.5569f),
	VANILLA_165(165, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.5333f, 0.5961f, 0.6784f, 0.6902f, 0.6431f, 0.5725f, 0.0000f, 0.4588f, 0.5569f),
	VANILLA_166(166, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.5098f, 0.5647f, 0.6392f, 0.6902f, 0.6431f, 0.5725f, 0.0000f, 0.4588f, 0.5569f),
	VANILLA_167(167, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.4745f, 0.5373f, 0.6196f, 0.6902f, 0.6431f, 0.5725f, 0.0000f, 0.4588f, 0.5569f),
	VANILLA_168(168, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.4549f, 0.4980f, 0.6000f, 0.6902f, 0.6431f, 0.5725f, 0.0000f, 0.4588f, 0.5569f),
	VANILLA_169(169, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.4078f, 0.4471f, 0.5529f, 0.6902f, 0.6431f, 0.5725f, 0.0000f, 0.4588f, 0.5569f),
	VANILLA_170(170, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.4471f, 0.5961f, 0.7059f, 0.6902f, 0.6431f, 0.5725f, 0.0000f, 0.4588f, 0.5569f),
	VANILLA_171(171, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.3725f, 0.5255f, 0.6392f, 0.6902f, 0.6431f, 0.5725f, 0.0000f, 0.4588f, 0.5569f),
	VANILLA_172(172, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.3294f, 0.4784f, 0.6000f, 0.6902f, 0.6431f, 0.5725f, 0.0000f, 0.4588f, 0.5569f),
	VANILLA_173(173, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.2863f, 0.4314f, 0.5686f, 0.6902f, 0.6431f, 0.5725f, 0.0000f, 0.4588f, 0.5569f),
	VANILLA_174(174, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.2627f, 0.4078f, 0.5451f, 0.6902f, 0.6431f, 0.5725f, 0.0000f, 0.4588f, 0.5569f),
	VANILLA_175(175, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.3647f, 0.4980f, 0.4471f, 0.6902f, 0.6431f, 0.5725f, 0.0000f, 0.4588f, 0.5569f),
	VANILLA_176(176, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.3176f, 0.4863f, 0.4235f, 0.6902f, 0.6431f, 0.5725f, 0.0000f, 0.4588f, 0.5569f),
	VANILLA_177(177, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.2902f, 0.4078f, 0.4275f, 0.6902f, 0.6431f, 0.5725f, 0.0000f, 0.4588f, 0.5569f),
	VANILLA_178(178, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.2902f, 0.4078f, 0.4275f, 0.6902f, 0.6431f, 0.5725f, 0.0000f, 0.4588f, 0.5569f),
	VANILLA_179(179, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.2392f, 0.3490f, 0.3608f, 0.6902f, 0.6431f, 0.5725f, 0.0000f, 0.4588f, 0.5569f),
	VANILLA_180(180, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.3529f, 0.4824f, 0.5647f, 0.6902f, 0.6431f, 0.5725f, 0.0000f, 0.4588f, 0.5569f),
	VANILLA_181(181, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.3255f, 0.4392f, 0.5176f, 0.6902f, 0.6431f, 0.5725f, 0.0000f, 0.4588f, 0.5569f),
	VANILLA_182(182, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.2863f, 0.4039f, 0.4824f, 0.6902f, 0.6431f, 0.5725f, 0.0000f, 0.4588f, 0.5569f),
	VANILLA_183(183, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.3137f, 0.3961f, 0.4549f, 0.6902f, 0.6431f, 0.5725f, 0.0000f, 0.4588f, 0.5569f),
	VANILLA_184(184, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.1961f, 0.3059f, 0.4235f, 0.6902f, 0.6431f, 0.5725f, 0.0000f, 0.4588f, 0.5569f),
	VANILLA_185(185, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.3020f, 0.3686f, 0.4549f, 0.6902f, 0.6431f, 0.5725f, 0.0000f, 0.4588f, 0.5569f),
	VANILLA_186(186, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.2667f, 0.3216f, 0.4039f, 0.6902f, 0.6431f, 0.5725f, 0.0000f, 0.4588f, 0.5569f),
	VANILLA_187(187, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.2235f, 0.2824f, 0.3686f, 0.6902f, 0.6431f, 0.5725f, 0.0000f, 0.4588f, 0.5569f),
	VANILLA_188(188, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.2235f, 0.3020f, 0.3882f, 0.6902f, 0.6431f, 0.5725f, 0.0000f, 0.4588f, 0.5569f),
	VANILLA_189(189, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.1922f, 0.2549f, 0.3412f, 0.6902f, 0.6431f, 0.5725f, 0.0000f, 0.4588f, 0.5569f),
	VANILLA_208(208, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.1490f, 0.1765f, 0.2706f, 0.6902f, 0.6431f, 0.5725f, 0.0000f, 0.4588f, 0.5569f);

	/** Vanilla texture id this type is the default for, or -1 when several map to it. */
	public final int texture;
	public final boolean flat;
	public final float specularStrength;
	public final float specularGloss;
	public final float normalStrength;
	public final float baseOpacity;
	public final float fresnelAmount;
	public final boolean hasFoam;
	public final float duration;
	public final float[] surfaceColor;
	public final float[] foamColor;
	public final float[] depthColor;

	WaterType(int texture, boolean flat, float specularStrength, float specularGloss, float normalStrength, float baseOpacity, float fresnelAmount, boolean hasFoam, float duration,
		float sr, float sg, float sb, float fr, float fg, float fb, float dr, float dg, float db)
	{
		this.texture = texture;
		this.flat = flat;
		this.specularStrength = specularStrength;
		this.specularGloss = specularGloss;
		this.normalStrength = normalStrength;
		this.baseOpacity = baseOpacity;
		this.fresnelAmount = fresnelAmount;
		this.hasFoam = hasFoam;
		this.duration = duration;
		this.surfaceColor = new float[]{sr, sg, sb};
		this.foamColor = new float[]{fr, fg, fb};
		this.depthColor = new float[]{dr, dg, db};
	}

	/** Same fallback rule as 117 HD: plain water for 1 and 24, swamp for 25, per-texture types for the sailing set. */
	public static WaterType forTexture(int textureId)
	{
		if (textureId == 1 || textureId == 24)
		{
			return WATER;
		}
		for (WaterType t : values())
		{
			if (t.texture == textureId)
			{
				return t;
			}
		}
		return null;
	}

	/** Floats per type in the GPU table: four vec4s. */
	public static final int FLOATS = 16;

	/** Packs every type for the shader, index 0 left empty for "not water". */
	public static float[] table()
	{
		WaterType[] all = values();
		float[] out = new float[(all.length + 1) * FLOATS];
		for (WaterType t : all)
		{
			int o = (t.ordinal() + 1) * FLOATS;
			out[o] = t.surfaceColor[0];
			out[o + 1] = t.surfaceColor[1];
			out[o + 2] = t.surfaceColor[2];
			out[o + 3] = (t.flat ? 1 : 0) | (t.hasFoam ? 2 : 0);
			out[o + 4] = t.foamColor[0];
			out[o + 5] = t.foamColor[1];
			out[o + 6] = t.foamColor[2];
			out[o + 7] = t.duration;
			out[o + 8] = t.depthColor[0];
			out[o + 9] = t.depthColor[1];
			out[o + 10] = t.depthColor[2];
			out[o + 11] = t.baseOpacity;
			out[o + 12] = t.specularStrength;
			out[o + 13] = t.specularGloss;
			out[o + 14] = t.normalStrength;
			out[o + 15] = t.fresnelAmount;
		}
		return out;
	}
}
