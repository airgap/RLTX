package rltx.scene;

/**
 * Water appearance per vanilla water texture, taken from 117 HD's water_types.json: colours
 * are linear RGB, gloss is a specular exponent, and flat types are opaque with no visible bed.
 * The ordinal plus one is the index the shader uses; 0 means not water.
 */
public enum WaterType
{
	WATER(-1, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.1413f, 0.2159f, 0.3325f, 0.4342f, 0.3712f, 0.2874f, 0.0000f, 0.1779f, 0.2705f),
	SWAMP_WATER_FLAT(25, true, 0.1f, 100.0f, 0.05f, 0.8f, 0.3f, true, 1.2f,
		0.0086f, 0.0152f, 0.0070f, 0.1714f, 0.1878f, 0.1301f, 0.0222f, 0.0844f, 0.0103f),
	VANILLA_130(130, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.0908f, 0.1590f, 0.2747f, 0.4342f, 0.3712f, 0.2874f, 0.0000f, 0.1779f, 0.2705f),
	VANILLA_131(131, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.0865f, 0.1470f, 0.2462f, 0.4342f, 0.3712f, 0.2874f, 0.0000f, 0.1779f, 0.2705f),
	VANILLA_132(132, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.0742f, 0.1095f, 0.2195f, 0.4342f, 0.3712f, 0.2874f, 0.0000f, 0.1779f, 0.2705f),
	VANILLA_133(133, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.0452f, 0.0704f, 0.1651f, 0.4342f, 0.3712f, 0.2874f, 0.0000f, 0.1779f, 0.2705f),
	VANILLA_134(134, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.0382f, 0.0578f, 0.1356f, 0.4342f, 0.3712f, 0.2874f, 0.0000f, 0.1779f, 0.2705f),
	VANILLA_135(135, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.1384f, 0.2582f, 0.3325f, 0.4342f, 0.3712f, 0.2874f, 0.0000f, 0.1779f, 0.2705f),
	VANILLA_136(136, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.1248f, 0.2195f, 0.2502f, 0.4342f, 0.3712f, 0.2874f, 0.0000f, 0.1779f, 0.2705f),
	VANILLA_137(137, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.0865f, 0.1845f, 0.2270f, 0.4342f, 0.3712f, 0.2874f, 0.0000f, 0.1779f, 0.2705f),
	VANILLA_138(138, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.0908f, 0.1620f, 0.2122f, 0.4342f, 0.3712f, 0.2874f, 0.0000f, 0.1779f, 0.2705f),
	VANILLA_139(139, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.0578f, 0.1070f, 0.1683f, 0.4342f, 0.3712f, 0.2874f, 0.0000f, 0.1779f, 0.2705f),
	VANILLA_140(140, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.0742f, 0.1470f, 0.2122f, 0.4342f, 0.3712f, 0.2874f, 0.0000f, 0.1779f, 0.2705f),
	VANILLA_141(141, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.0685f, 0.1301f, 0.1878f, 0.4342f, 0.3712f, 0.2874f, 0.0000f, 0.1779f, 0.2705f),
	VANILLA_142(142, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.0578f, 0.1195f, 0.1779f, 0.4342f, 0.3712f, 0.2874f, 0.0000f, 0.1779f, 0.2705f),
	VANILLA_143(143, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.0545f, 0.1046f, 0.1651f, 0.4342f, 0.3712f, 0.2874f, 0.0000f, 0.1779f, 0.2705f),
	VANILLA_144(144, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.0497f, 0.0976f, 0.1500f, 0.4342f, 0.3712f, 0.2874f, 0.0000f, 0.1779f, 0.2705f),
	VANILLA_145(145, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.1500f, 0.1878f, 0.3613f, 0.4342f, 0.3712f, 0.2874f, 0.0000f, 0.1779f, 0.2705f),
	VANILLA_146(146, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.1170f, 0.1470f, 0.3185f, 0.4342f, 0.3712f, 0.2874f, 0.0000f, 0.1779f, 0.2705f),
	VANILLA_147(147, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.0976f, 0.1119f, 0.2747f, 0.4342f, 0.3712f, 0.2874f, 0.0000f, 0.1779f, 0.2705f),
	VANILLA_148(148, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.0630f, 0.0467f, 0.1683f, 0.4342f, 0.3712f, 0.2874f, 0.0000f, 0.1779f, 0.2705f),
	VANILLA_149(149, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.0497f, 0.0395f, 0.1248f, 0.4342f, 0.3712f, 0.2874f, 0.0000f, 0.1779f, 0.2705f),
	VANILLA_150(150, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.1384f, 0.1912f, 0.3372f, 0.4342f, 0.3712f, 0.2874f, 0.0000f, 0.1779f, 0.2705f),
	VANILLA_151(151, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.1170f, 0.1559f, 0.2789f, 0.4342f, 0.3712f, 0.2874f, 0.0000f, 0.1779f, 0.2705f),
	VANILLA_152(152, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.0865f, 0.1274f, 0.2502f, 0.4342f, 0.3712f, 0.2874f, 0.0000f, 0.1779f, 0.2705f),
	VANILLA_153(153, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.0782f, 0.0953f, 0.2195f, 0.4342f, 0.3712f, 0.2874f, 0.0000f, 0.1779f, 0.2705f),
	VANILLA_154(154, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.0497f, 0.0612f, 0.1683f, 0.4342f, 0.3712f, 0.2874f, 0.0000f, 0.1779f, 0.2705f),
	VANILLA_155(155, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.1559f, 0.1779f, 0.3005f, 0.4342f, 0.3712f, 0.2874f, 0.0000f, 0.1779f, 0.2705f),
	VANILLA_156(156, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.1301f, 0.1470f, 0.2462f, 0.4342f, 0.3712f, 0.2874f, 0.0000f, 0.1779f, 0.2705f),
	VANILLA_157(157, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.0999f, 0.1195f, 0.2195f, 0.4342f, 0.3712f, 0.2874f, 0.0000f, 0.1779f, 0.2705f),
	VANILLA_158(158, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.0976f, 0.0976f, 0.1746f, 0.4342f, 0.3712f, 0.2874f, 0.0000f, 0.1779f, 0.2705f),
	VANILLA_159(159, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.0648f, 0.0612f, 0.1274f, 0.4342f, 0.3712f, 0.2874f, 0.0000f, 0.1779f, 0.2705f),
	VANILLA_160(160, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.1946f, 0.2664f, 0.3663f, 0.4342f, 0.3712f, 0.2874f, 0.0000f, 0.1779f, 0.2705f),
	VANILLA_161(161, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.1683f, 0.2270f, 0.3095f, 0.4342f, 0.3712f, 0.2874f, 0.0000f, 0.1779f, 0.2705f),
	VANILLA_162(162, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.1329f, 0.1946f, 0.2831f, 0.4342f, 0.3712f, 0.2874f, 0.0000f, 0.1779f, 0.2705f),
	VANILLA_163(163, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.1195f, 0.1559f, 0.2582f, 0.4342f, 0.3712f, 0.2874f, 0.0000f, 0.1779f, 0.2705f),
	VANILLA_164(164, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.0802f, 0.1070f, 0.1946f, 0.4342f, 0.3712f, 0.2874f, 0.0000f, 0.1779f, 0.2705f),
	VANILLA_165(165, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.2462f, 0.3140f, 0.4179f, 0.4342f, 0.3712f, 0.2874f, 0.0000f, 0.1779f, 0.2705f),
	VANILLA_166(166, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.2232f, 0.2789f, 0.3663f, 0.4342f, 0.3712f, 0.2874f, 0.0000f, 0.1779f, 0.2705f),
	VANILLA_167(167, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.1912f, 0.2502f, 0.3419f, 0.4342f, 0.3712f, 0.2874f, 0.0000f, 0.1779f, 0.2705f),
	VANILLA_168(168, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.1746f, 0.2122f, 0.3185f, 0.4342f, 0.3712f, 0.2874f, 0.0000f, 0.1779f, 0.2705f),
	VANILLA_169(169, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.1384f, 0.1683f, 0.2664f, 0.4342f, 0.3712f, 0.2874f, 0.0000f, 0.1779f, 0.2705f),
	VANILLA_170(170, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.1683f, 0.3140f, 0.4564f, 0.4342f, 0.3712f, 0.2874f, 0.0000f, 0.1779f, 0.2705f),
	VANILLA_171(171, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.1144f, 0.2384f, 0.3663f, 0.4342f, 0.3712f, 0.2874f, 0.0000f, 0.1779f, 0.2705f),
	VANILLA_172(172, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.0887f, 0.1946f, 0.3185f, 0.4342f, 0.3712f, 0.2874f, 0.0000f, 0.1779f, 0.2705f),
	VANILLA_173(173, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.0666f, 0.1559f, 0.2831f, 0.4342f, 0.3712f, 0.2874f, 0.0000f, 0.1779f, 0.2705f),
	VANILLA_174(174, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.0561f, 0.1384f, 0.2582f, 0.4342f, 0.3712f, 0.2874f, 0.0000f, 0.1779f, 0.2705f),
	VANILLA_175(175, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.1095f, 0.2122f, 0.1683f, 0.4342f, 0.3712f, 0.2874f, 0.0000f, 0.1779f, 0.2705f),
	VANILLA_176(176, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.0823f, 0.2016f, 0.1500f, 0.4342f, 0.3712f, 0.2874f, 0.0000f, 0.1779f, 0.2705f),
	VANILLA_177(177, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.0685f, 0.1384f, 0.1529f, 0.4342f, 0.3712f, 0.2874f, 0.0000f, 0.1779f, 0.2705f),
	VANILLA_178(178, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.0685f, 0.1384f, 0.1529f, 0.4342f, 0.3712f, 0.2874f, 0.0000f, 0.1779f, 0.2705f),
	VANILLA_179(179, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.0467f, 0.0999f, 0.1070f, 0.4342f, 0.3712f, 0.2874f, 0.0000f, 0.1779f, 0.2705f),
	VANILLA_180(180, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.1022f, 0.1981f, 0.2789f, 0.4342f, 0.3712f, 0.2874f, 0.0000f, 0.1779f, 0.2705f),
	VANILLA_181(181, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.0865f, 0.1620f, 0.2307f, 0.4342f, 0.3712f, 0.2874f, 0.0000f, 0.1779f, 0.2705f),
	VANILLA_182(182, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.0666f, 0.1356f, 0.1981f, 0.4342f, 0.3712f, 0.2874f, 0.0000f, 0.1779f, 0.2705f),
	VANILLA_183(183, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.0802f, 0.1301f, 0.1746f, 0.4342f, 0.3712f, 0.2874f, 0.0000f, 0.1779f, 0.2705f),
	VANILLA_184(184, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.0319f, 0.0762f, 0.1500f, 0.4342f, 0.3712f, 0.2874f, 0.0000f, 0.1779f, 0.2705f),
	VANILLA_185(185, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.0742f, 0.1119f, 0.1746f, 0.4342f, 0.3712f, 0.2874f, 0.0000f, 0.1779f, 0.2705f),
	VANILLA_186(186, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.0578f, 0.0844f, 0.1356f, 0.4342f, 0.3712f, 0.2874f, 0.0000f, 0.1779f, 0.2705f),
	VANILLA_187(187, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.0409f, 0.0648f, 0.1119f, 0.4342f, 0.3712f, 0.2874f, 0.0000f, 0.1779f, 0.2705f),
	VANILLA_188(188, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.0409f, 0.0742f, 0.1248f, 0.4342f, 0.3712f, 0.2874f, 0.0000f, 0.1779f, 0.2705f),
	VANILLA_189(189, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.0307f, 0.0529f, 0.0953f, 0.4342f, 0.3712f, 0.2874f, 0.0000f, 0.1779f, 0.2705f),
	VANILLA_208(208, false, 0.5f, 500.0f, 0.09f, 0.5f, 0.85f, true, 1.0f,
		0.0194f, 0.0262f, 0.0595f, 0.4342f, 0.3712f, 0.2874f, 0.0000f, 0.1779f, 0.2705f);

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
