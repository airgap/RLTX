package rltx.sky;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * The skies of the Fantasy Skybox pack, addressed relative to its Materials folder.
 * The AUTO entries pick a variant of their family from the sun's position.
 */
@Getter
@RequiredArgsConstructor
public enum Skybox
{
	NONE(null, null, "None (flat colour)"),
	AUTO_FS001("FS001", null, "FS001 (follows time of day)"),
	AUTO_FS002("FS002", null, "FS002 (follows time of day)"),
	AUTO_FS003("FS003", null, "FS003 (follows time of day)"),
	AUTO_FS004("FS004", null, "FS004 (follows time of day)"),
	AUTO_FS005("FS005", null, "FS005 (follows time of day)"),
	AUTO_FS006("FS006", null, "FS006 (follows time of day)"),
	AUTO_FS007("FS007", null, "FS007 (follows time of day)"),
	AUTO_FS008("FS008", null, "FS008 (follows time of day)"),
	AUTO_FS009("FS009", null, "FS009 (follows time of day)"),
	AUTO_FS010("FS010", null, "FS010 (follows time of day)"),
	AUTO_FS011("FS011", null, "FS011 (follows time of day)"),
	AUTO_FS012("FS012", null, "FS012 (follows time of day)"),
	AUTO_FS013("FS013", null, "FS013 (follows time of day)"),
	AUTO_FS014("FS014", null, "FS014 (follows time of day)"),
	AUTO_FS015("FS015", null, "FS015 (follows time of day)"),
	AUTO_FS016("FS016", null, "FS016 (follows time of day)"),
	AUTO_FS017("FS017", null, "FS017 (follows time of day)"),
	AUTO_FS018("FS018", null, "FS018 (follows time of day)"),
	AUTO_FS019("FS019", null, "FS019 (follows time of day)"),
	AUTO_FS020("FS020", null, "FS020 (follows time of day)"),
	AUTO_CLASSIC("Classic", null, "Classic (follows time of day)"),
	FS001_DAY("FS001", "FS001_Day.png", "FS001 Day"),
	FS001_DAY_SUNLESS("FS001", "FS001_Day_Sunless.png", "FS001 Day Sunless"),
	FS001_NIGHT("FS001", "FS001_Night.png", "FS001 Night"),
	FS001_NIGHT_MOONLESS("FS001", "FS001_Night_Moonless.png", "FS001 Night Moonless"),
	FS001_RAINY("FS001", "FS001_Rainy.png", "FS001 Rainy"),
	FS001_SNOWY("FS001", "FS001_Snowy.png", "FS001 Snowy"),
	FS001_SUNRISE("FS001", "FS001_Sunrise.png", "FS001 Sunrise"),
	FS001_SUNSET("FS001", "FS001_Sunset.png", "FS001 Sunset"),
	FS002_DAY("FS002", "FS002_Day.png", "FS002 Day"),
	FS002_DAY_SUNLESS("FS002", "FS002_Day_Sunless.png", "FS002 Day Sunless"),
	FS002_NIGHT("FS002", "FS002_Night.png", "FS002 Night"),
	FS002_NIGHT_MOONLESS("FS002", "FS002_Night_Moonless.png", "FS002 Night Moonless"),
	FS002_RAINY("FS002", "FS002_Rainy.png", "FS002 Rainy"),
	FS002_SNOWY("FS002", "FS002_Snowy.png", "FS002 Snowy"),
	FS002_SUNRISE("FS002", "FS002_Sunrise.png", "FS002 Sunrise"),
	FS002_SUNSET("FS002", "FS002_Sunset.png", "FS002 Sunset"),
	FS003_DAY("FS003", "FS003_Day.png", "FS003 Day"),
	FS003_DAY_SUNLESS("FS003", "FS003_Day_Sunless.png", "FS003 Day Sunless"),
	FS003_NIGHT("FS003", "FS003_Night.png", "FS003 Night"),
	FS003_NIGHT_MOONLESS("FS003", "FS003_Night_Moonless.png", "FS003 Night Moonless"),
	FS003_RAINY("FS003", "FS003_Rainy.png", "FS003 Rainy"),
	FS003_SNOWY("FS003", "FS003_Snowy.png", "FS003 Snowy"),
	FS003_SUNRISE("FS003", "FS003_Sunrise.png", "FS003 Sunrise"),
	FS003_SUNSET("FS003", "FS003_Sunset.png", "FS003 Sunset"),
	FS004_DAY("FS004", "FS004_Day.png", "FS004 Day"),
	FS004_DAY_SUNLESS("FS004", "FS004_Day_Sunless.png", "FS004 Day Sunless"),
	FS004_NIGHT("FS004", "FS004_Night.png", "FS004 Night"),
	FS004_NIGHT_MOONLESS("FS004", "FS004_Night_Moonless.png", "FS004 Night Moonless"),
	FS004_RAINY("FS004", "FS004_Rainy.png", "FS004 Rainy"),
	FS004_SNOWY("FS004", "FS004_Snowy.png", "FS004 Snowy"),
	FS004_SUNRISE("FS004", "FS004_Sunrise.png", "FS004 Sunrise"),
	FS004_SUNSET("FS004", "FS004_Sunset.png", "FS004 Sunset"),
	FS005_DAY("FS005", "FS005_Day.png", "FS005 Day"),
	FS005_DAY_SUNLESS("FS005", "FS005_Day_Sunless.png", "FS005 Day Sunless"),
	FS005_NIGHT("FS005", "FS005_Night.png", "FS005 Night"),
	FS005_NIGHT_MOONLESS("FS005", "FS005_Night_Moonless.png", "FS005 Night Moonless"),
	FS005_RAINY("FS005", "FS005_Rainy.png", "FS005 Rainy"),
	FS005_SNOWY("FS005", "FS005_Snowy.png", "FS005 Snowy"),
	FS005_SUNRISE("FS005", "FS005_Sunrise.png", "FS005 Sunrise"),
	FS005_SUNSET("FS005", "FS005_Sunset.png", "FS005 Sunset"),
	FS006_DAY("FS006", "FS006_Day.png", "FS006 Day"),
	FS006_DAY_SUNLESS("FS006", "FS006_Day_Sunless.png", "FS006 Day Sunless"),
	FS006_NIGHT("FS006", "FS006_Night.png", "FS006 Night"),
	FS006_NIGHT_MOONLESS("FS006", "FS006_Night_Moonless.png", "FS006 Night Moonless"),
	FS006_RAINY("FS006", "FS006_Rainy.png", "FS006 Rainy"),
	FS006_SNOWY("FS006", "FS006_Snowy.png", "FS006 Snowy"),
	FS006_SUNRISE("FS006", "FS006_Sunrise.png", "FS006 Sunrise"),
	FS006_SUNSET("FS006", "FS006_Sunset.png", "FS006 Sunset"),
	FS007_DAY("FS007", "FS007_Day.png", "FS007 Day"),
	FS007_DAY_SUNLESS("FS007", "FS007_Day_Sunless.png", "FS007 Day Sunless"),
	FS007_NIGHT("FS007", "FS007_Night.png", "FS007 Night"),
	FS007_NIGHT_MOONLESS("FS007", "FS007_Night_Moonless.png", "FS007 Night Moonless"),
	FS007_RAINY("FS007", "FS007_Rainy.png", "FS007 Rainy"),
	FS007_SNOWY("FS007", "FS007_Snowy.png", "FS007 Snowy"),
	FS007_SUNRISE("FS007", "FS007_Sunrise.png", "FS007 Sunrise"),
	FS007_SUNSET("FS007", "FS007_Sunset.png", "FS007 Sunset"),
	FS008_DAY("FS008", "FS008_Day.png", "FS008 Day"),
	FS008_DAY_SUNLESS("FS008", "FS008_Day_Sunless.png", "FS008 Day Sunless"),
	FS008_NIGHT("FS008", "FS008_Night.png", "FS008 Night"),
	FS008_NIGHT_MOONLESS("FS008", "FS008_Night_Moonless.png", "FS008 Night Moonless"),
	FS008_RAINY("FS008", "FS008_Rainy.png", "FS008 Rainy"),
	FS008_SNOWY("FS008", "FS008_Snowy.png", "FS008 Snowy"),
	FS008_SUNRISE("FS008", "FS008_Sunrise.png", "FS008 Sunrise"),
	FS008_SUNSET("FS008", "FS008_Sunset.png", "FS008 Sunset"),
	FS009_DAY("FS009", "FS009_Day.png", "FS009 Day"),
	FS009_DAY_SUNLESS("FS009", "FS009_Day_Sunless.png", "FS009 Day Sunless"),
	FS009_NIGHT("FS009", "FS009_Night.png", "FS009 Night"),
	FS009_NIGHT_MOONLESS("FS009", "FS009_Night_Moonless.png", "FS009 Night Moonless"),
	FS009_RAINY("FS009", "FS009_Rainy.png", "FS009 Rainy"),
	FS009_SNOWY("FS009", "FS009_Snowy.png", "FS009 Snowy"),
	FS009_SUNRISE("FS009", "FS009_Sunrise.png", "FS009 Sunrise"),
	FS009_SUNSET("FS009", "FS009_Sunset.png", "FS009 Sunset"),
	FS010_DAY("FS010", "FS010_Day.png", "FS010 Day"),
	FS010_DAY_SUNLESS("FS010", "FS010_Day_Sunless.png", "FS010 Day Sunless"),
	FS010_NIGHT("FS010", "FS010_Night.png", "FS010 Night"),
	FS010_NIGHT_MOONLESS("FS010", "FS010_Night_Moonless.png", "FS010 Night Moonless"),
	FS010_RAINY("FS010", "FS010_Rainy.png", "FS010 Rainy"),
	FS010_SNOWY("FS010", "FS010_Snowy.png", "FS010 Snowy"),
	FS010_SUNRISE("FS010", "FS010_Sunrise.png", "FS010 Sunrise"),
	FS010_SUNSET("FS010", "FS010_Sunset.png", "FS010 Sunset"),
	FS011_DAY("FS011", "FS011_Day.png", "FS011 Day"),
	FS011_DAY_SUNLESS("FS011", "FS011_Day_Sunless.png", "FS011 Day Sunless"),
	FS011_NIGHT("FS011", "FS011_Night.png", "FS011 Night"),
	FS011_NIGHT_MOONLESS("FS011", "FS011_Night_Moonless.png", "FS011 Night Moonless"),
	FS011_RAINY("FS011", "FS011_Rainy.png", "FS011 Rainy"),
	FS011_SNOWY("FS011", "FS011_Snowy.png", "FS011 Snowy"),
	FS011_SUNRISE("FS011", "FS011_Sunrise.png", "FS011 Sunrise"),
	FS011_SUNSET("FS011", "FS011_Sunset.png", "FS011 Sunset"),
	FS012_DAY("FS012", "FS012_Day.png", "FS012 Day"),
	FS012_DAY_SUNLESS("FS012", "FS012_Day_Sunless.png", "FS012 Day Sunless"),
	FS012_NIGHT("FS012", "FS012_Night.png", "FS012 Night"),
	FS012_NIGHT_MOONLESS("FS012", "FS012_Night_Moonless.png", "FS012 Night Moonless"),
	FS012_RAINY("FS012", "FS012_Rainy.png", "FS012 Rainy"),
	FS012_SNOWY("FS012", "FS012_Snowy.png", "FS012 Snowy"),
	FS012_SUNRISE("FS012", "FS012_Sunrise.png", "FS012 Sunrise"),
	FS012_SUNSET("FS012", "FS012_Sunset.png", "FS012 Sunset"),
	FS013_DAY("FS013", "FS013_Day.png", "FS013 Day"),
	FS013_DAY_SUNLESS("FS013", "FS013_Day_Sunless.png", "FS013 Day Sunless"),
	FS013_NIGHT("FS013", "FS013_Night.png", "FS013 Night"),
	FS013_NIGHT_MOONLESS("FS013", "FS013_Night_Moonless.png", "FS013 Night Moonless"),
	FS013_RAINY("FS013", "FS013_Rainy.png", "FS013 Rainy"),
	FS013_SNOWY("FS013", "FS013_Snowy.png", "FS013 Snowy"),
	FS013_SUNRISE("FS013", "FS013_Sunrise.png", "FS013 Sunrise"),
	FS013_SUNSET("FS013", "FS013_Sunset.png", "FS013 Sunset"),
	FS014_DAY("FS014", "FS014_Day.png", "FS014 Day"),
	FS014_DAY_SUNLESS("FS014", "FS014_Day_Sunless.png", "FS014 Day Sunless"),
	FS014_NIGHT("FS014", "FS014_Night.png", "FS014 Night"),
	FS014_NIGHT_MOONLESS("FS014", "FS014_Night_Moonless.png", "FS014 Night Moonless"),
	FS014_RAINY("FS014", "FS014_Rainy.png", "FS014 Rainy"),
	FS014_SNOWY("FS014", "FS014_Snowy.png", "FS014 Snowy"),
	FS014_SUNRISE("FS014", "FS014_Sunrise.png", "FS014 Sunrise"),
	FS014_SUNSET("FS014", "FS014_Sunset.png", "FS014 Sunset"),
	FS015_DAY("FS015", "FS015_Day.png", "FS015 Day"),
	FS015_DAY_SUNLESS("FS015", "FS015_Day_Sunless.png", "FS015 Day Sunless"),
	FS015_NIGHT("FS015", "FS015_Night.png", "FS015 Night"),
	FS015_NIGHT_MOONLESS("FS015", "FS015_Night_Moonless.png", "FS015 Night Moonless"),
	FS015_RAINY("FS015", "FS015_Rainy.png", "FS015 Rainy"),
	FS015_SNOWY("FS015", "FS015_Snowy.png", "FS015 Snowy"),
	FS015_SUNRISE("FS015", "FS015_Sunrise.png", "FS015 Sunrise"),
	FS015_SUNSET("FS015", "FS015_Sunset.png", "FS015 Sunset"),
	FS016_DAY("FS016", "FS016_Day.png", "FS016 Day"),
	FS016_NIGHT("FS016", "FS016_Night.png", "FS016 Night"),
	FS016_NIGHT_MOONLESS("FS016", "FS016_Night_Moonless.png", "FS016 Night Moonless"),
	FS016_RAINY("FS016", "FS016_Rainy.png", "FS016 Rainy"),
	FS016_SANDSTORM("FS016", "FS016_Sandstorm.png", "FS016 Sandstorm"),
	FS016_SUNNY("FS016", "FS016_Sunny.png", "FS016 Sunny"),
	FS016_SUNRISE("FS016", "FS016_Sunrise.png", "FS016 Sunrise"),
	FS016_SUNSET("FS016", "FS016_Sunset.png", "FS016 Sunset"),
	FS017_DAY("FS017", "FS017_Day.png", "FS017 Day"),
	FS017_DAY_SUNLESS("FS017", "FS017_Day_Sunless.png", "FS017 Day Sunless"),
	FS017_NIGHT("FS017", "FS017_Night.png", "FS017 Night"),
	FS017_NIGHT_MOONLESS("FS017", "FS017_Night_Moonless.png", "FS017 Night Moonless"),
	FS017_RAINY("FS017", "FS017_Rainy.png", "FS017 Rainy"),
	FS017_SNOWY("FS017", "FS017_Snowy.png", "FS017 Snowy"),
	FS017_SUNRISE("FS017", "FS017_Sunrise.png", "FS017 Sunrise"),
	FS017_SUNSET("FS017", "FS017_Sunset.png", "FS017 Sunset"),
	FS018_DAY("FS018", "FS018_Day.png", "FS018 Day"),
	FS018_DAY_SUNLESS("FS018", "FS018_Day_Sunless.png", "FS018 Day Sunless"),
	FS018_NIGHT("FS018", "FS018_Night.png", "FS018 Night"),
	FS018_NIGHT_MOONLESS("FS018", "FS018_Night_Moonless.png", "FS018 Night Moonless"),
	FS018_RAINY("FS018", "FS018_Rainy.png", "FS018 Rainy"),
	FS018_SNOWY("FS018", "FS018_Snowy.png", "FS018 Snowy"),
	FS018_SUNRISE("FS018", "FS018_Sunrise.png", "FS018 Sunrise"),
	FS018_SUNSET("FS018", "FS018_Sunset.png", "FS018 Sunset"),
	FS019_DAY("FS019", "FS019_Day.png", "FS019 Day"),
	FS019_DAY_SUNLESS("FS019", "FS019_Day_Sunless.png", "FS019 Day Sunless"),
	FS019_NIGHT("FS019", "FS019_Night.png", "FS019 Night"),
	FS019_NIGHT_MOONLESS("FS019", "FS019_Night_Moonless.png", "FS019 Night Moonless"),
	FS019_RAINY("FS019", "FS019_Rainy.png", "FS019 Rainy"),
	FS019_SNOWY("FS019", "FS019_Snowy.png", "FS019 Snowy"),
	FS019_SUNRISE("FS019", "FS019_Sunrise.png", "FS019 Sunrise"),
	FS019_SUNSET("FS019", "FS019_Sunset.png", "FS019 Sunset"),
	FS020_DAY("FS020", "FS020_Day.png", "FS020 Day"),
	FS020_DAY_SUNLESS("FS020", "FS020_Day_Sunless.png", "FS020 Day Sunless"),
	FS020_NIGHT("FS020", "FS020_Night.png", "FS020 Night"),
	FS020_NIGHT_MOONLESS("FS020", "FS020_Night_Moonless.png", "FS020 Night Moonless"),
	FS020_RAINY("FS020", "FS020_Rainy.png", "FS020 Rainy"),
	FS020_SNOWY("FS020", "FS020_Snowy.png", "FS020 Snowy"),
	FS020_SUNRISE("FS020", "FS020_Sunrise.png", "FS020 Sunrise"),
	FS020_SUNSET("FS020", "FS020_Sunset.png", "FS020 Sunset"),
	CLASSIC_DAY_01("Classic", "FS000_Day_01.png", "Classic Day 01"),
	CLASSIC_DAY_01_SUNLESS("Classic", "FS000_Day_01_Sunless.png", "Classic Day 01 Sunless"),
	CLASSIC_DAY_02("Classic", "FS000_Day_02.png", "Classic Day 02"),
	CLASSIC_DAY_02_SUNLESS("Classic", "FS000_Day_02_Sunless.png", "Classic Day 02 Sunless"),
	CLASSIC_DAY_03("Classic", "FS000_Day_03.png", "Classic Day 03"),
	CLASSIC_DAY_03_SUNLESS("Classic", "FS000_Day_03_Sunless.png", "Classic Day 03 Sunless"),
	CLASSIC_DAY_04("Classic", "FS000_Day_04.png", "Classic Day 04"),
	CLASSIC_DAY_04_SUNLESS("Classic", "FS000_Day_04_Sunless.png", "Classic Day 04 Sunless"),
	CLASSIC_DAY_05("Classic", "FS000_Day_05.png", "Classic Day 05"),
	CLASSIC_DAY_05_SUNLESS("Classic", "FS000_Day_05_Sunless.png", "Classic Day 05 Sunless"),
	CLASSIC_DAY_06("Classic", "FS000_Day_06.png", "Classic Day 06"),
	CLASSIC_DAY_06_SUNLESS("Classic", "FS000_Day_06_Sunless.png", "Classic Day 06 Sunless"),
	CLASSIC_NIGHT_01("Classic", "FS000_Night_01.png", "Classic Night 01"),
	CLASSIC_NIGHT_01_MOONLESS("Classic", "FS000_Night_01_Moonless.png", "Classic Night 01 Moonless"),
	CLASSIC_NIGHT_02("Classic", "FS000_Night_02.png", "Classic Night 02"),
	CLASSIC_NIGHT_02_MOONLESS("Classic", "FS000_Night_02_Moonless.png", "Classic Night 02 Moonless"),
	CLASSIC_NIGHT_03("Classic", "FS000_Night_03.png", "Classic Night 03"),
	CLASSIC_NIGHT_03_MOONLESS("Classic", "FS000_Night_03_Moonless.png", "Classic Night 03 Moonless");

	/** Time-of-day phases an AUTO entry can resolve to. */
	public enum Phase
	{
		DAY, SUNRISE, SUNSET, NIGHT
	}

	private final String folder;
	private final String file;
	private final String label;

	public boolean isAuto()
	{
		return folder != null && file == null;
	}

	/** Whether this sky is the sunless or moonless variant of another. */
	public boolean isBodyless()
	{
		return name().endsWith("_SUNLESS") || name().endsWith("_MOONLESS");
	}

	/**
	 * The sunless or moonless twin of this sky, whose difference from it locates the sun or
	 * moon, or null when the pack has none.
	 */
	public Skybox twin()
	{
		if (file == null || isBodyless())
		{
			return null;
		}
		for (String suffix : new String[]{"_SUNLESS", "_MOONLESS"})
		{
			try
			{
				return valueOf(name() + suffix);
			}
			catch (IllegalArgumentException ignored)
			{
				// no twin with that suffix
			}
		}
		return null;
	}

	/** The concrete sky to show for a phase; fixed entries return themselves. */
	public Skybox resolve(Phase phase)
	{
		if (!isAuto())
		{
			return this;
		}
		if ("Classic".equals(folder))
		{
			switch (phase)
			{
				case NIGHT:
					return CLASSIC_NIGHT_01;
				case SUNRISE:
				case SUNSET:
					return CLASSIC_DAY_05;
				default:
					return CLASSIC_DAY_01;
			}
		}
		String suffix;
		switch (phase)
		{
			case NIGHT:
				suffix = "_NIGHT";
				break;
			case SUNRISE:
				suffix = "_SUNRISE";
				break;
			case SUNSET:
				suffix = "_SUNSET";
				break;
			default:
				suffix = "_DAY";
		}
		return valueOf(folder.toUpperCase() + suffix);
	}

	@Override
	public String toString()
	{
		return label;
	}
}
