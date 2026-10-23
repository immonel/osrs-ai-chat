package com.aichat;

import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import net.runelite.api.Client;
import net.runelite.api.IndexedObjectSet;
import net.runelite.api.Player;
import net.runelite.api.WorldEntity;
import net.runelite.api.WorldType;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * What get_location reads from the game and what it tells the model: the place's name from RuneLite's regions, which
 * floor, the nearest named place for somewhere unnamed, and above all what the game says about the player being
 * attackable there, which is never left unsaid.
 */
public class LocationTest
{
	/** An ordinary spot: not an instance, not on a boat, an ordinary world, and nowhere the game marks as PvP. */
	private static GameData.Location at(int x, int y, int plane)
	{
		return new GameData.Location(x, y, plane, false, false, false, 0, false, null, false);
	}

	/** Somewhere the game counts as the Wilderness, showing {@code shownLevel} (0 for none it could read). */
	private static GameData.Location wilderness(int x, int y, int shownLevel)
	{
		return new GameData.Location(x, y, 0, false, false, true, shownLevel, false, null, false);
	}

	private static GameData.Location instance(int x, int y, int plane)
	{
		return new GameData.Location(x, y, plane, true, false, false, 0, false, null, false);
	}

	private static String text(GameData.Location at)
	{
		return GameData.locationText(at);
	}

	// ------------------------------------------------------------------
	// Naming the place
	// ------------------------------------------------------------------

	@Test
	public void namesTheRegionAsRuneLiteDoes()
	{
		assertStarts("Place: Varrock, near the west bank.", at(3185, 3436, 0));
		assertStarts("Place: Grand Exchange.", at(3165, 3487, 0));
		assertStarts("Place: Lumbridge, near the castle.", at(3222, 3218, 0));
		// Places a table drawn from memory got wrong or left out: Prifddinas and Mor Ul Rek sit in parts of the map set
		// apart from the rest, Keldagrim's east end and West Ardougne were past its boxes' edges.
		assertStarts("Place: Prifddinas.", at(3260, 6100, 0));
		assertStarts("Place: Mor Ul Rek.", at(2480, 5150, 0));
		assertStarts("Place: Keldagrim (underground).", at(2941, 10179, 0));
		assertStarts("Place: Ardougne.", at(2500, 3300, 0));
		assertStarts("Place: Yanille.", at(2530, 3090, 0));
		assertStarts("Place: Hosidius.", at(1740, 3610, 0));
	}

	@Test
	public void everyLandmarkStandsInTheRegionItsNamedFor()
	{
		for (GameData.Spot s : GameData.SPOTS)
		{
			assertEquals(s.elsewhere, s.area, AreaNames.at(s.x, s.y));
			assertStarts("Place: " + s.area + ", near " + s.here + ".", at(s.x, s.y, 0));
		}
	}

	@Test
	public void aLandmarkIsNeverTakenForAnotherTownsOwn()
	{
		// In the town's own region, by its short name.
		assertStarts("Place: Edgeville, near the bank.", at(3094, 3478, 0));
		assertStarts("Place: Falador, near the west bank.", at(2935, 3368, 0));
		// From a neighbouring region, with the town's name: "Asgarnia, near the party room" would be a party room of
		// Asgarnia's.
		assertStarts("Place: Asgarnia, near the Falador Party Room.", at(3046, 3392, 0));
		assertStarts("Place: Seers' Village, near Camelot Castle.", at(2745, 3477, 0));
		// Nothing near enough: the region alone.
		assertStarts("Place: Varrock. ", at(3253, 3485, 0));
	}

	@Test
	public void saysWhichFloorAndWhenItsBelowGround()
	{
		assertStarts("Place: Lumbridge, near the castle (one floor up).", at(3222, 3218, 1));
		assertStarts("Place: Keldagrim (underground, two floors up).", at(2845, 10200, 2));
		assertStarts("Place: Barrows (underground).", at(3550, 9700, 0));
		// The one named region that far north that isn't below ground.
		assertStarts("Place: Underwater.", at(3740, 10270, 0));
		assertStarts("Place: Karamja (aboard a boat).",
			new GameData.Location(3000, 3000, 0, false, true, false, 0, false, null, false));
	}

	@Test
	public void instancesAreNamedAfterTheirTemplateAndNothingElse()
	{
		// A player-owned house is copied from a template 6400 tiles north of nowhere in particular: it's no more
		// underground than the house is, and there's nothing "nearest" to it.
		String house = text(instance(1900, 7100, 1));
		assertTrue(house, house.startsWith("Place: Player Owned House. This is a private instance"));
		assertFalse(house, house.contains("underground"));
		assertFalse(house, house.contains("nearest"));
		assertStarts("Place: Theatre of Blood. This is a private instance", instance(3150, 4300, 0));

		String unnamed = text(instance(100, 100, 0));
		assertTrue(unnamed, unnamed.startsWith("Place: a private instance AI Chat has no name for"));
		assertFalse(unnamed, unnamed.contains("nearest"));
		assertFalse(unnamed, unnamed.contains("Nothing within"));
	}

	@Test
	public void noFloorIsSaidInAnInstance()
	{
		// The floor read in an instance is its template's, which needn't be the one the player is on (a player-owned
		// house's ground floor, say): until that's been checked in the game, none is said.
		for (int plane = 0; plane <= 3; plane++)
		{
			String text = text(instance(1900, 7100, plane));
			assertTrue(text, text.startsWith("Place: Player Owned House. "));
			assertFalse(text, text.contains("floor"));
		}
		assertStarts("Place: Theatre of Blood. ", instance(3150, 4300, 2));
		// Outside instances, as before.
		assertStarts("Place: Lumbridge, near the castle (two floors up).", at(3222, 3218, 2));
	}

	@Test
	public void theNearestNamedPlaceIsOnlyLookedForNearbyAndOnTheSameLayer()
	{
		assertNull(AreaNames.at(3800, 2800));
		assertStarts("Place: somewhere AI Chat has no name for. The nearest named place is Harmony Island, 16 tiles north.",
			at(3800, 2800, 0));
		// Below ground, only what's below ground: Keldagrim, not the land above it.
		assertStarts("Place: somewhere AI Chat has no name for (underground). The nearest named place is Keldagrim, "
			+ "7 tiles west.", at(2950, 10180, 0));
		// Too far from anything to say: no "400 tiles south-east" of a place in another part of the world.
		assertNull(AreaNames.at(100, 100));
		assertStarts("Place: somewhere AI Chat has no name for. Nothing within about 120 tiles has a name either.",
			at(100, 100, 0));
		// In the parts of the map set apart from the rest, nearness means nothing at all, even with Mor Ul Rek a region
		// away.
		assertNull(AreaNames.at(2560, 5150));
		String apart = text(at(2560, 5150, 0));
		assertTrue(apart, apart.contains("set apart from the rest"));
		assertFalse(apart, apart.contains("nearest"));
	}

	@Test
	public void ofPlacesEquallyNearTheFirstInRuneLitesOrderIsNamed()
	{
		// Tai Bwo Wannai and Karamja are each 32 tiles away: RuneLite lists its towns before the land around them, so
		// it's the town, though Karamja comes first by name and by region number.
		assertStarts("Place: somewhere AI Chat has no name for. The nearest named place is Tai Bwo Wannai, about 30 "
			+ "tiles north-east.", at(2720, 2980, 0));
	}

	@Test
	public void directionsAndRoundedDistances()
	{
		assertNull(GameData.direction(0, 0));
		assertEquals("west", GameData.direction(-5, 0));
		assertEquals("north", GameData.direction(0, 5));
		// A way is only named where it's more than half the other.
		assertEquals("east", GameData.direction(100, 50));
		assertEquals("north-east", GameData.direction(100, 51));
		assertEquals("south-west", GameData.direction(-51, -100));
		assertEquals("south", GameData.direction(-50, -100));

		assertEquals("1 tile", GameData.tiles(1));
		assertEquals("19 tiles", GameData.tiles(19));
		assertEquals("about 20 tiles", GameData.tiles(20));
		assertEquals("about 20 tiles", GameData.tiles(24));
		assertEquals("about 30 tiles", GameData.tiles(25));
		assertEquals("about 120 tiles", GameData.tiles(120));
	}

	@Test
	public void everyRegionHasOneName()
	{
		// AreaNames refuses a region named twice the first time it's used; this uses it, and pins the copy's size.
		assertEquals(982, AreaNames.all().size());
		assertEquals(12597, AreaNames.region(3185, 3436));
		assertEquals("Varrock", AreaNames.at(3185, 3436));
	}

	@Test
	public void theCopiedTablesNoticeShipsWithThePlugin() throws Exception
	{
		// RuneLite's licence asks for its notice with every copy, the plugin's jar included: the same text at the
		// repository's root and among the resources that go into the jar, and the notice in it word for word as
		// AreaNames.java carries it.
		String root = new String(Files.readAllBytes(Paths.get("THIRD-PARTY-NOTICES")), StandardCharsets.UTF_8);
		String bundled;
		try (InputStream in = LocationTest.class.getResourceAsStream("/META-INF/THIRD-PARTY-NOTICES"))
		{
			assertNotNull("in the jar's resources", in);
			bundled = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		assertEquals(root, bundled);
		String source = new String(Files.readAllBytes(Paths.get("src/main/java/com/aichat/AreaNames.java")),
			StandardCharsets.UTF_8);
		String header = source.substring(source.indexOf("Copyright (c)"), source.indexOf("*/"));
		for (String line : header.split("\n"))
		{
			String text = line.replaceFirst("^ \\* ?", "").trim();
			assertTrue(text, root.contains(text));
		}
		assertTrue(root.contains("DiscordGameEventType"));
	}

	@Test
	public void theCoordinatesNeverGoToTheModel()
	{
		// Some content hides the minimap on purpose (the Barrows tunnels); the exact spot would give it away.
		for (GameData.Location at : Arrays.asList(at(3550, 9700, 0), at(3185, 3436, 1), at(3800, 2800, 0),
			wilderness(3100, 3700, 0), instance(1900, 7100, 0)))
		{
			String text = text(at);
			assertFalse(text, text.contains(String.valueOf(at.x)));
			assertFalse(text, text.contains(String.valueOf(at.y)));
			assertFalse(text, text.toLowerCase().contains("coordinate"));
		}
	}

	// ------------------------------------------------------------------
	// Whether other players can attack them
	// ------------------------------------------------------------------

	@Test
	public void aMissingWarningNeverStandsForSafe()
	{
		// Every write-up says one way or the other, so silence can never be read as "safe".
		for (GameData.Location at : Arrays.asList(at(3185, 3436, 0), at(100, 100, 0), at(2560, 5150, 0),
			instance(1900, 7100, 0), wilderness(3100, 3700, 23), wilderness(3100, 5000, 0)))
		{
			String text = text(at);
			assertTrue(text, text.contains(" In the Wilderness: "));
		}
	}

	@Test
	public void whetherItsTheWildernessIsTheGamesToSay()
	{
		// The game's flag is what says "yes", wherever the map puts the Wilderness's edges...
		assertContains("In the Wilderness: yes, about level 11", wilderness(2943, 3600, 0));
		// ...and the map can only add a warning to its "no": never a "no" of its own.
		assertContains("In the Wilderness: the game doesn't count this spot as Wilderness", at(3100, 3700, 0));
		assertContains("In the Wilderness: no, and the game doesn't mark this spot as a PvP area.", at(3392, 3600, 0));
		// Under the Wilderness east of where a hand-drawn box ended: the Wilderness Slayer Cave.
		GameData.Location cave = wilderness(3400, 10100, 0);
		assertStarts("Place: the Wilderness (underground).", cave);
		assertContains("In the Wilderness: yes, about level 23", cave);
		assertContains("Other players can attack them here.", cave);
	}

	@Test
	public void feroxEnclaveIsSaidToBeAmidTheWilderness()
	{
		// The game doesn't count Ferox Enclave as Wilderness, but every way out of it is: no "Edgeville, 110 tiles
		// south" to walk to, and a plain warning instead.
		assertEquals("Place: somewhere AI Chat has no name for, inside the Wilderness's borders on the map. In the "
			+ "Wilderness: the game doesn't count this spot as Wilderness (a safe zone such as Ferox Enclave, or the game "
			+ "hasn't updated yet), but it's inside the Wilderness's borders on the map: the Wilderness is all around it, "
			+ "so leaving this spot can mean entering it, where other players can attack them. The game doesn't mark "
			+ "this spot as a PvP area either. Place names are RuneLite's names for the map's 64-tile regions, so they "
			+ "give the area rather than the exact spot.", text(at(3140, 3630, 0)));
	}

	@Test
	public void aFlagTheGameHasntUpdatedIsntTakenForSafety()
	{
		// Level 2 Wilderness, just after logging in, say, before the game has set its flag: no nearest place to head
		// for, and the warning all the same.
		String justIn = text(at(3094, 3530, 0));
		assertTrue(justIn, justIn.startsWith("Place: somewhere AI Chat has no name for, inside the Wilderness's borders "
			+ "on the map."));
		assertTrue(justIn, justIn.contains("or the game hasn't updated yet"));
		assertTrue(justIn, justIn.contains("other players can attack them"));
		assertFalse(justIn, justIn.contains("nearest"));
		assertFalse(justIn, justIn.contains("In the Wilderness: no"));
		// Below ground: the Revenant Caves, the Edgeville Dungeon past its gate, the Slayer Cave's east end, the
		// Wilderness God Wars and Deep Wilderness dungeons.
		for (GameData.Location below : Arrays.asList(at(3200, 10100, 0), at(3100, 9950, 0), at(3430, 10100, 0),
			at(3040, 10140, 0), at(3050, 10345, 0)))
		{
			String text = text(below);
			assertTrue(text, text.contains("In the Wilderness: the game doesn't count this spot as Wilderness"));
			assertFalse(text, text.contains("nearest"));
		}
		// The Edgeville Dungeon past its gate keeps its name; the warning comes with it.
		assertStarts("Place: Edgeville Dungeon (underground). In the Wilderness: the game doesn't count", at(3100, 9950, 0));
		// A PvP world's own hedge stays, and a PvP area is still one.
		GameData.Location pvpWorld = new GameData.Location(3140, 3630, 0, false, false, false, 0, false, "a PvP world",
			false);
		assertContains("all around it", pvpWorld);
		assertContains("AI Chat can't confirm that, so don't assume they're safe from other players.", pvpWorld);
		assertContains("The game marks this spot as a PvP area, though: other players can attack them here.",
			new GameData.Location(3140, 3630, 0, false, false, false, 0, true, null, false));
	}

	@Test
	public void theWildernessBordersAreOnlyWhereTheWildernessIs()
	{
		// Just south of the ditch, Edgeville: no warning.
		assertContains("In the Wilderness: no, and the game doesn't mark this spot as a PvP area.", at(3094, 3519, 0));
		// The Slayer Tower's basement in Morytania, below the Wilderness's level band but east of its borders.
		assertStarts("Place: Slayer Tower (underground). In the Wilderness: no, and", at(3420, 9940, 0));
		// Just east of Keldagrim, below the Wilderness's west edge but west of its dungeons.
		assertContains("The nearest named place is Keldagrim, 7 tiles west. In the Wilderness: no, and", at(2950, 10180, 0));
		// The Edgeville Dungeon before its Wilderness gate.
		assertContains("In the Wilderness: no, and the game doesn't mark this spot as a PvP area.", at(3100, 9900, 0));
		// An instance's coordinates are its template's, not where the player is.
		assertContains("In the Wilderness: no, and the game doesn't mark this spot as a PvP area.",
			instance(3140, 3630, 0));
		// Where the game says "yes", that's what's said.
		assertContains("In the Wilderness: yes, level 13 as the game shows it.", wilderness(3140, 3630, 13));
		assertFalse(text(wilderness(3140, 3630, 13)), text(wilderness(3140, 3630, 13)).contains("doesn't count"));
	}

	@Test
	public void theLevelIsTheOneTheGameShows()
	{
		// The game's own figure, even where working it out from the tile would say otherwise (29 here).
		GameData.Location shown = wilderness(3050, 10150, 21);
		assertContains("In the Wilderness: yes, level 21 as the game shows it. Other players can attack them here.", shown);
		assertFalse(text(shown), text(shown).contains("about level"));
	}

	@Test
	public void theLevelIsOnlyWorkedOutWhenTheGameDoesntShowIt()
	{
		assertContains("yes, about level 1, worked out from where they are", wilderness(3100, 3522, 0));
		assertContains("yes, about level 1,", wilderness(3100, 3523, 0));
		assertContains("yes, about level 1,", wilderness(3100, 3527, 0));
		assertContains("yes, about level 2,", wilderness(3100, 3528, 0));
		assertContains("yes, about level 56,", wilderness(3100, 3967, 0));
		// In the dungeons under it, counted from y 9920.
		assertContains("yes, about level 1,", wilderness(3100, 9920, 0));
		assertContains("yes, about level 2,", wilderness(3100, 9928, 0));
		assertContains("yes, about level 55,", wilderness(3100, 10359, 0));
		// Nowhere the sum works: no number at all, rather than a made-up one.
		assertContains("In the Wilderness: yes, at a level AI Chat couldn't read. Other players can attack them here.",
			wilderness(3100, 5000, 0));
	}

	@Test
	public void pvpAreasAndWorldsAreWarnedAboutOutsideTheWilderness()
	{
		GameData.Location highRisk = new GameData.Location(3185, 3436, 0, false, false, false, 0, false,
			"a High Risk PvP world", true);
		assertContains("World: a High Risk PvP world, where players can attack each other anywhere outside the safe "
			+ "zones.", highRisk);
		// The game's flags don't say outright that a spot is a safe zone: AI Chat doesn't either.
		assertContains("AI Chat can't confirm that, so don't assume they're safe from other players.", highRisk);

		GameData.Location pvpArea = new GameData.Location(3200, 3420, 0, false, false, false, 0, true, "a PvP world",
			false);
		assertContains("In the Wilderness: no, but the game marks this spot as a PvP area. Other players can attack them "
			+ "here.", pvpArea);
		assertContains("In the Wilderness: no, but the game marks this spot as a PvP area.",
			new GameData.Location(3200, 3420, 0, false, false, false, 0, true, null, false));
	}

	@Test
	public void theWildernessOnAPvpWorldIsWarnedAboutTwice()
	{
		GameData.Location at = new GameData.Location(3100, 3700, 0, false, false, true, 23, true, "a PvP world", false);
		assertContains("World: a PvP world, where players can attack each other anywhere outside the safe zones. ", at);
		assertContains("In the Wilderness: yes, level 23 as the game shows it. Other players can attack them here.", at);
		assertFalse(text(at), text(at).contains("can't confirm"));
	}

	@Test
	public void aPvpWorldCountsAsLevel15WildernessOutsideTheSafeZones()
	{
		String rule = "On a PvP world, anywhere outside a safe zone counts as level 15 Wilderness for who can attack "
			+ "whom, and in the Wilderness 15 is added to its own level (level 20 acts as level 35), though what's lost on "
			+ "death still goes by the Wilderness's own level.";
		GameData.Location town = new GameData.Location(3185, 3436, 0, false, false, false, 0, false, "a PvP world",
			false);
		assertContains("World: a PvP world, where players can attack each other anywhere outside the safe zones. " + rule
			+ " In the Wilderness: no.", town);
		// The safe zone still can't be confirmed.
		assertContains("AI Chat can't confirm that, so don't assume they're safe from other players.", town);
		assertContains(rule, new GameData.Location(3200, 3420, 0, false, false, false, 0, true, "a High Risk PvP world",
			true));
		assertContains(rule, new GameData.Location(3100, 3700, 0, false, false, true, 23, true, "a PvP world", false));
		// Deadman worlds have rules of their own.
		GameData.Location deadman = new GameData.Location(3185, 3436, 0, false, false, false, 0, false,
			GameData.pvpWorld(EnumSet.of(WorldType.DEADMAN)), false);
		assertContains("World: a Deadman world, where players can attack each other anywhere outside the safe zones.",
			deadman);
		assertFalse(text(deadman), text(deadman).contains("level 15"));
		// Nor on an ordinary world.
		assertFalse(text(at(3185, 3436, 0)), text(at(3185, 3436, 0)).contains("level 15"));
	}

	/**
	 * Asked only where they were, a player on Lunar Isle was told they weren't in the Wilderness and were "safe": the "no"
	 * is there for when they ask, not to be volunteered, and isn't "safe" from monsters. A warning still goes unprompted.
	 */
	@Test
	public void aNoIsntVolunteeredOrCalledSafe()
	{
		assertContains("Other players can't attack them here, which isn't worth saying unless they ask about danger or "
			+ "other players; and don't call it safe, since monsters still can.", at(3185, 3436, 0));
		String wild = text(wilderness(2943, 3600, 0));
		assertFalse(wild, wild.contains("isn't worth saying"));
		assertFalse(wild, wild.contains("don't call it safe"));
	}

	@Test
	public void aHighRiskWorldThatIsntPvpIsntCalledOne()
	{
		// Worlds like 365: High Risk, but not PvP, so outside the Wilderness no one can attack them. What they'd lose is
		// still said, since that changes what's worth taking into the Wilderness.
		GameData.Location town = new GameData.Location(3185, 3436, 0, false, false, false, 0, false, null, true);
		assertContains("World: a High Risk world, which isn't a PvP world. On High Risk worlds the Protect Item prayer "
			+ "doesn't work, and a player killed by another player loses all their items. In the Wilderness: no, and the "
			+ "game doesn't mark this spot as a PvP area.", town);
		assertFalse(text(town), text(town).contains("anywhere outside the safe zones"));

		GameData.Location wild = new GameData.Location(3100, 3700, 0, false, false, true, 23, true, null, true);
		assertContains("Protect Item prayer doesn't work", wild);
		assertContains("In the Wilderness: yes, level 23 as the game shows it. Other players can attack them here.", wild);

		// On a High Risk PvP world, both.
		GameData.Location pvp = new GameData.Location(3185, 3436, 0, false, false, false, 0, false,
			"a High Risk PvP world", true);
		assertContains("World: a High Risk PvP world, where players can attack each other anywhere outside the safe "
			+ "zones. ", pvp);
		assertContains("On High Risk worlds the Protect Item prayer doesn't work", pvp);
	}

	@Test
	public void whichWorldsLetPlayersAttackAlmostAnywhere()
	{
		assertNull(GameData.pvpWorld(null));
		assertNull(GameData.pvpWorld(EnumSet.noneOf(WorldType.class)));
		assertNull(GameData.pvpWorld(EnumSet.of(WorldType.MEMBERS, WorldType.SKILL_TOTAL)));
		assertEquals("a PvP world", GameData.pvpWorld(EnumSet.of(WorldType.MEMBERS, WorldType.PVP)));
		assertEquals("a High Risk PvP world", GameData.pvpWorld(EnumSet.of(WorldType.PVP, WorldType.HIGH_RISK)));
		// High Risk alone isn't PvP (worlds 365, 474 and 533), as RuneLite's own WorldType.isPvpWorld has it.
		assertNull(GameData.pvpWorld(EnumSet.of(WorldType.MEMBERS, WorldType.HIGH_RISK)));
		assertFalse(WorldType.isPvpWorld(EnumSet.of(WorldType.HIGH_RISK)));
		assertEquals("a Deadman world", GameData.pvpWorld(EnumSet.of(WorldType.DEADMAN, WorldType.SEASONAL)));
	}

	@Test
	public void readsTheLevelAsTheGameWritesIt()
	{
		assertEquals(23, GameData.shownLevel("Level: 23"));
		// RuneLite's Combat Level plugin adds the combat levels that can attack on a line after it.
		assertEquals(23, GameData.shownLevel("Level: 23<br>90-116"));
		// On a PvP world the same spot can show only that range: no level to read.
		assertEquals(0, GameData.shownLevel("90-116"));
		assertEquals(0, GameData.shownLevel(""));
		assertEquals(0, GameData.shownLevel(null));
		assertEquals(0, GameData.shownLevel("Level: 99999"));
	}

	// ------------------------------------------------------------------
	// Reading the game (GameData.location), against a stand-in client
	// ------------------------------------------------------------------

	/** What the stand-in client answers with; each test sets what it needs. */
	private LocalPoint here;
	private boolean instanced;
	private int baseX = 3136;
	private int baseY = 3392;
	private int plane;
	private int[][][] templates;
	private WorldEntity vessel;
	private final Map<Integer, Integer> varbits = new HashMap<>();
	private Set<WorldType> worldTypes = EnumSet.of(WorldType.MEMBERS);
	private String levelText;
	private boolean levelHidden;
	/** The interface that shows the Wilderness level isn't there at all. */
	private boolean noLevelWidget;
	private boolean noPlayer;

	/** One of the boat's own world views: anything but the top level. */
	private static final int BOAT_VIEW = WorldView.TOPLEVEL + 3;

	/** The middle of a tile of the top level's scene. */
	private static LocalPoint tile(int sceneX, int sceneY, int worldView)
	{
		return new LocalPoint(sceneX * 128 + 64, sceneY * 128 + 64, worldView);
	}

	private GameData.Location read()
	{
		WorldView top = fake(WorldView.class, Map.of(
			"isInstance", a -> instanced,
			"getId", a -> WorldView.TOPLEVEL,
			"getBaseX", a -> baseX,
			"getBaseY", a -> baseY,
			"getPlane", a -> plane,
			"getInstanceTemplateChunks", a -> templates,
			"worldEntities", a -> fake(IndexedObjectSet.class, Map.of(
				"byIndex", b -> (int) b[0] == BOAT_VIEW ? vessel : null))));
		Player me = fake(Player.class, Map.of("getLocalLocation", a -> here));
		Widget level = fake(Widget.class, Map.of("getText", a -> levelText, "isHidden", a -> levelHidden));
		Client client = fake(Client.class, Map.of(
			"getLocalPlayer", a -> noPlayer ? null : me,
			"getTopLevelWorldView", a -> top,
			"getWorldView", a -> (int) a[0] == WorldView.TOPLEVEL ? top : null,
			"getVarbitValue", a -> a.length == 1 ? varbits.getOrDefault((Integer) a[0], 0) : 0,
			"getWorldType", a -> worldTypes.isEmpty() ? EnumSet.noneOf(WorldType.class) : EnumSet.copyOf(worldTypes),
			"getWidget", a -> a.length == 1 && a[0] instanceof Integer
				&& (int) a[0] == InterfaceID.PvpIcons.WILDERNESSLEVEL && !noLevelWidget ? level : null));
		return new GameData(client, null).location();
	}

	@Test
	public void readsWhereAnOrdinaryPlayerStands()
	{
		here = tile(49, 44, WorldView.TOPLEVEL);
		GameData.Location at = read();
		assertNotNull(at);
		assertEquals(3185, at.x);
		assertEquals(3436, at.y);
		assertEquals(0, at.plane);
		assertFalse(at.instanced);
		assertFalse(at.aboard);
		assertFalse(at.inWilderness);
		assertFalse(at.pvpArea);
		assertNull(at.pvpWorld);
		assertStarts("Place: Varrock, near the west bank.", at);
	}

	@Test
	public void readsTheMapUnderABoat()
	{
		// The player stands in the boat's own world, which the boat carries over the sea.
		here = tile(2, 3, BOAT_VIEW);
		baseX = 2944;
		baseY = 2944;
		vessel = fake(WorldEntity.class, Map.of("transformToMainWorld", a -> tile(56, 56, WorldView.TOPLEVEL)));
		GameData.Location at = read();
		assertTrue(at.aboard);
		assertEquals(3000, at.x);
		assertEquals(3000, at.y);

		// A boat the game no longer has: nowhere to say.
		vessel = null;
		assertNull(read());
	}

	@Test
	public void readsAnInstanceByItsTemplate()
	{
		// Every chunk of the scene copied from the player-owned house's template, a floor up.
		instanced = true;
		plane = 1;
		templates = new int[4][13][13];
		for (int p = 0; p < 4; p++)
		{
			for (int[] row : templates[p])
			{
				Arrays.fill(row, p << 24 | (1896 / 8) << 14 | (7096 / 8) << 3);
			}
		}
		here = tile(4, 4, WorldView.TOPLEVEL);
		GameData.Location at = read();
		assertTrue(at.instanced);
		assertEquals(1900, at.x);
		assertEquals(7100, at.y);
		assertEquals(1, at.plane);
		// The floor is read, but not said: it's the template's.
		assertStarts("Place: Player Owned House. This is a private instance", at);
	}

	@Test
	public void readsTheGamesOwnWordOnDanger()
	{
		here = tile(49, 44, WorldView.TOPLEVEL);
		varbits.put(VarbitID.INSIDE_WILDERNESS, 1);
		varbits.put(VarbitID.PVP_AREA_CLIENT, 1);
		worldTypes = EnumSet.of(WorldType.PVP, WorldType.HIGH_RISK);
		levelText = "Level: 21<br>90-120";
		GameData.Location at = read();
		assertTrue(at.inWilderness);
		assertEquals(21, at.shownLevel);
		assertTrue(at.pvpArea);
		assertEquals("a High Risk PvP world", at.pvpWorld);
		assertTrue(at.highRisk);

		// A level that isn't on screen isn't read.
		levelHidden = true;
		assertEquals(0, read().shownLevel);
		// Nor one left over from earlier, once the game says they've left the Wilderness.
		levelHidden = false;
		varbits.put(VarbitID.INSIDE_WILDERNESS, 0);
		assertEquals(0, read().shownLevel);
		assertFalse(read().inWilderness);
	}

	@Test
	public void withoutTheLevelOnScreenTheWildernessIsStillSaid()
	{
		// Varrock's west bank by its coordinates, but the game's flag says Wilderness: the flag wins, and with no level
		// interface to read, there's no level rather than a made-up one.
		here = tile(49, 44, WorldView.TOPLEVEL);
		varbits.put(VarbitID.INSIDE_WILDERNESS, 1);
		noLevelWidget = true;
		GameData.Location at = read();
		assertTrue(at.inWilderness);
		assertEquals(0, at.shownLevel);
		assertContains("In the Wilderness: yes, at a level AI Chat couldn't read. Other players can attack them here.",
			at);
	}

	@Test
	public void readsAHighRiskWorldThatIsntPvp()
	{
		here = tile(49, 44, WorldView.TOPLEVEL);
		worldTypes = EnumSet.of(WorldType.MEMBERS, WorldType.HIGH_RISK);
		GameData.Location at = read();
		assertNull(at.pvpWorld);
		assertTrue(at.highRisk);
		assertFalse(read().pvpArea);
	}

	@Test
	public void noPlayerNoPlace()
	{
		noPlayer = true;
		assertNull(read());
		noPlayer = false;
		here = null;
		assertNull(read());
	}

	// ------------------------------------------------------------------

	private static void assertStarts(String start, GameData.Location at)
	{
		String text = text(at);
		assertTrue(text, text.startsWith(start));
	}

	private static void assertContains(String part, GameData.Location at)
	{
		String text = text(at);
		assertTrue(text, text.contains(part));
	}

	/**
	 * A stand-in for one of the game's interfaces: each method answered by name, everything else empty (null, false or
	 * 0), so a test only has to say what the code under it reads.
	 */
	private static <T> T fake(Class<T> type, Map<String, Function<Object[], Object>> answers)
	{
		return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) ->
		{
			Object[] given = args == null ? new Object[0] : args;
			switch (method.getName())
			{
				case "equals":
					return proxy == given[0];
				case "hashCode":
					return System.identityHashCode(proxy);
				case "toString":
					return "fake " + type.getSimpleName();
				default:
					break;
			}
			Function<Object[], Object> answer = answers.get(method.getName());
			return answer != null ? answer.apply(given) : EMPTY.get(method.getReturnType());
		}));
	}

	/** What a stand-in answers when a test didn't say: nothing, in each primitive's own kind. */
	private static final Map<Class<?>, Object> EMPTY = Map.of(boolean.class, false, byte.class, (byte) 0,
		short.class, (short) 0, char.class, (char) 0, int.class, 0, long.class, 0L, float.class, 0f, double.class, 0d);
}
