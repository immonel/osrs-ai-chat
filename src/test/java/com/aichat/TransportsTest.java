package com.aichat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** What get_transport_options says, from plain values (no game needed): above all, unknown is never said as yes or no. */
public class TransportsTest
{
	private static final long MINUTE = 60_000;
	private static final int STANDARD = 0;
	private static final int ANCIENT = 1;
	private static final int LUNAR = 2;
	private static final int ARCEUUS = 3;

	/** A player as the game would read them: every quest the tables read in one state, and the rest set below. */
	private static final class Player
	{
		final Map<Quest, QuestState> quests = new EnumMap<>(Quest.class);
		final Map<Integer, Integer> varbits = new HashMap<>();
		final Map<Integer, Integer> varps = new HashMap<>();
		int magic;
		int boostedMagic;
		Transports.Held held;

		Player(QuestState every, boolean diaries, int spellbook, int magic)
		{
			for (Quest q : Transports.QUESTS)
			{
				quests.put(q, every);
			}
			for (int id : Transports.VARBITS)
			{
				varbits.put(id, diaries ? 1 : 0);
			}
			varbits.put(VarbitID.SPELLBOOK, spellbook);
			boolean done = every == QuestState.FINISHED;
			varbits.put(VarbitID.FAIRY2_QUEENCURE_QUEST, done ? 100 : 0);
			// Plague City done, and its Ardougne teleport scroll read.
			varps.put(VarPlayerID.ELENAQUEST, done ? 30 : 0);
			this.magic = magic;
			this.boostedMagic = magic;
		}

		Player quest(Quest q, QuestState state)
		{
			quests.put(q, state);
			return this;
		}

		Transports.State state()
		{
			return new Transports.State(quests, varbits, varps, magic, boostedMagic, held);
		}

		String text()
		{
			return Transports.text(state(), null, 10 * MINUTE);
		}

		String text(String category)
		{
			return Transports.text(state(), category, 10 * MINUTE);
		}
	}

	/** Well into the game: every quest and diary done, on the standard spellbook, 99 Magic, with some jewellery. */
	private static Player wellProgressed()
	{
		Player p = new Player(QuestState.FINISHED, true, STANDARD, 99);
		p.held = new Transports.Held(
			Collections.singletonList(new GameData.ItemLine("Neck", "Amulet of glory(4)", false, 1, 0)),
			Arrays.asList(item("Ring of dueling(8)", 1), item("Shark", 10)),
			new GameData.Bank(Arrays.asList(item("Games necklace(8)", 3), item("Ring of wealth", 1),
				item("Dramen staff", 1)), 0));
		return p;
	}

	/** A new account: nothing done, the standard spellbook, 1 Magic, nothing teleport-worthy held, bank never opened. */
	private static Player fresh()
	{
		Player p = new Player(QuestState.NOT_STARTED, false, STANDARD, 1);
		p.held = new Transports.Held(Collections.emptyList(),
			Collections.singletonList(item("Bronze axe", 1)), null);
		return p;
	}

	private static GameData.ItemLine item(String name, long quantity)
	{
		return new GameData.ItemLine(null, name, false, quantity, 0);
	}

	private static String line(String text, String start)
	{
		for (String l : text.split("\n"))
		{
			if (l.startsWith(start))
			{
				return l;
			}
		}
		throw new AssertionError("no line starting \"" + start + "\" in:\n" + text);
	}

	@Test
	public void aWellProgressedAccount()
	{
		String text = wellProgressed().text();
		assertTrue(text, text.startsWith("What the player can use to travel, read from their game just now. \"Unknown\" "
			+ "means AI Chat can't tell from the game, not that they can't"));
		assertTrue(text, text.contains("\n\nFairy rings: available: they have the Fairy Godfather's permission. No staff "
			+ "needed: they've done the elite Lumbridge & Draynor diary."));
		assertTrue(text, text.contains("read the Wiki page \"Fairy ring\": its sections \"A\" combinations to \"D\" "
			+ "combinations list the codes by first letter"));

		assertEquals("- Gnome Stronghold, to travel from it: available", line(text, "- Gnome Stronghold, to travel from"));
		assertEquals("- Prifddinas: available", line(text, "- Prifddinas"));
		assertEquals("- Incomitatus, east of the Poison Waste: available", line(text, "- Incomitatus"));
		assertTrue(text, text.contains("\nFor where each tree stands, read the Wiki page \"Spirit tree\", section \"Spirit "
			+ "tree locations\"."));
		assertEquals("- Ookookolly Undri, north-west Ape Atoll: available", line(text, "- Ookookolly Undri"));
		assertTrue(text, text.contains("read the Wiki page \"Gnome glider\", section \"Transportation\"."));

		assertTrue(text, text.contains("\n\nTeleport spells: they're on the standard spellbook, with Magic 99 now."));
		assertEquals("- Ardougne Teleport (51 Magic), to the East Ardougne market: available",
			line(text, "- Ardougne Teleport"));
		assertEquals("- Ape Atoll Teleport (64 Magic), to the Temple of Marimbo on Ape Atoll: available",
			line(text, "- Ape Atoll Teleport"));
		assertTrue(text, text.contains("\nThe Wiki page \"Standard spellbook\" lists every spell with its runes."));
		// Ancient spells aren't on this book: not listed at all.
		assertFalse(text, text.contains("Paddewwa"));

		// A line per kind, in the same order every time.
		assertTrue(text, text.contains("\n- Amulet of glory: (4) worn\n- Games necklace: (8) x3 in their bank\n"
			+ "- Ring of dueling: (8) in their inventory\n"));
		assertTrue(text, text.contains("found in what they wear and carry, and in their bank as last seen 10 minutes ago"));
		// No charges: listed, said so, and no page to read for it.
		assertEquals("- Ring of wealth: uncharged in their bank", line(text, "- Ring of wealth"));
		assertTrue(text, text.contains("\nUncharged ones have to be recharged before they teleport."));
		assertFalse(text, text.contains("Noted ones"));
		assertTrue(text, text.contains("\nFor where each goes, read its Wiki page: \"Amulet of glory\", \"Games necklace\", "
			+ "\"Ring of dueling\"."));

		assertEquals("- Camelot Teleport can be switched to Seers' Village bank: available",
			line(text, "- Camelot Teleport can"));
		assertTrue(text, text.endsWith("and the other networks."));
		assertTrue(text, text.contains("\n\nCovered here: fairy rings, spirit trees, gnome gliders"));
		assertTrue(text, text.length() <= Transports.MAX_CHARS);
		assertFalse(text, text.contains("not available"));
	}

	@Test
	public void whatTheGameCantShowStaysUnknownEvenWhenEverythingElseIsDone()
	{
		String text = wellProgressed().text();
		// The tables need these, and nothing the game shows a plugin says them: never "available".
		assertEquals("- Laguna Aurorae, an island in the Sunset Ocean: unknown: also needs having moored at the island "
			+ "once (58 Sailing), which AI Chat can't see", line(text, "- Laguna Aurorae"));
		assertEquals("- Teleport to House (40 Magic), to their player-owned house: unknown: needs a player-owned house, "
			+ "which AI Chat doesn't check", line(text, "- Teleport to House"));
		assertEquals("- Watchtower Teleport (58 Magic), to the top of the Watchtower, north of Yanille: unknown: also "
			+ "needs the spell scroll Watchtower gives read, which AI Chat doesn't check", line(text, "- Watchtower Teleport"));
		// A grown tree is a farming patch, which can't be read from afar.
		assertTrue(text, line(text, "- Spirit trees they've grown themselves").contains(": unknown: AI Chat can only see a "
			+ "farming patch while the player is near it"));
	}

	@Test
	public void aFreshAccount()
	{
		String text = fresh().text();
		assertTrue(text, text.contains("\n\nFairy rings: not available: needs Fairytale II - Cure a Queen started, far "
			+ "enough to get the Fairy Godfather's permission to use the rings (not started).\n\n"));
		assertTrue(text, text.contains("\n\nSpirit trees: not available: needs Tree Gnome Village (not started).\n\n"));
		assertTrue(text, text.contains("\n\nGnome gliders: not available: needs The Grand Tree (not started).\n\n"));
		// Nothing to read about transport they can't use.
		assertFalse(text, text.contains("\"Fairy ring\""));
		assertFalse(text, text.contains("\"Spirit tree\""));
		assertFalse(text, text.contains("\"Gnome glider\""));
		assertFalse(text, text.contains("\"Standard spellbook\""));

		// A level short is said with the level compared, never as plain "not available".
		assertEquals("- Varrock Teleport (25 Magic), to Varrock's town square: needs 25 Magic (theirs is 1 now, and boosts "
			+ "count)", line(text, "- Varrock Teleport"));
		assertEquals("- Ardougne Teleport (51 Magic), to the East Ardougne market: not available: needs Plague City (not "
			+ "started); needs 51 Magic (theirs is 1 now, and boosts count)", line(text, "- Ardougne Teleport"));
		// A quest not started rules it out, whatever else can't be seen.
		assertEquals("- Watchtower Teleport (58 Magic), to the top of the Watchtower, north of Yanille: not available: "
			+ "needs Watchtower (not started); needs 58 Magic (theirs is 1 now, and boosts count)",
			line(text, "- Watchtower Teleport"));

		assertTrue(text, text.contains("\n\nTeleport jewellery: none found in what they wear and carry, with their bank not "
			+ "checked: it hasn't been seen since AI Chat could look (they can open it once).\n"));
		assertEquals("- Fairy rings without a dramen or lunar staff: not available: needs the elite Lumbridge & Draynor "
			+ "diary (not done)", line(text, "- Fairy rings without"));
	}

	@Test
	public void partOfAQuestInProgressIsUnknownNotNo()
	{
		Player p = wellProgressed()
			.quest(Quest.ONE_SMALL_FAVOUR, QuestState.IN_PROGRESS)
			.quest(Quest.THE_PATH_OF_GLOUPHRIE, QuestState.IN_PROGRESS)
			.quest(Quest.THE_GRAND_TREE, QuestState.IN_PROGRESS);
		String gliders = p.text(Transports.GLIDERS);
		assertTrue(gliders, gliders.contains("Gnome gliders: not available: needs The Grand Tree (started, not finished)."));
		p.quest(Quest.THE_GRAND_TREE, QuestState.FINISHED);
		gliders = p.text(Transports.GLIDERS);
		assertEquals("- Lemantolly Undri, western Feldip Hills: unknown: needs part of One Small Favour done; they've "
			+ "started it, and AI Chat can't tell whether they're far enough", line(gliders, "- Lemantolly Undri"));
		String trees = p.text(Transports.SPIRIT_TREES);
		assertTrue(trees, line(trees, "- Incomitatus").endsWith(": unknown: needs part of The Path of Glouphrie done; "
			+ "they've started it, and AI Chat can't tell whether they're far enough"));
		// Not started is certain, though.
		p.quest(Quest.ONE_SMALL_FAVOUR, QuestState.NOT_STARTED);
		assertTrue(line(p.text(Transports.GLIDERS), "- Lemantolly Undri")
			.endsWith(": not available: needs part of One Small Favour done (not started)"));
	}

	@Test
	public void fairyRingsGoByTheStageOfFairyTaleTwo()
	{
		Player p = fresh().quest(Quest.FAIRYTALE_II__CURE_A_QUEEN, QuestState.IN_PROGRESS);
		p.varbits.put(VarbitID.FAIRY2_QUEENCURE_QUEST, 30);
		assertTrue(p.text(Transports.FAIRY_RINGS), p.text(Transports.FAIRY_RINGS).contains("Fairy rings: not available: "
			+ "they've started Fairytale II - Cure a Queen but haven't got the Fairy Godfather's permission to use the "
			+ "rings yet."));
		// The Fairy Godfather's permission: the rings are unlocked with the quest still in progress.
		p.varbits.put(VarbitID.FAIRY2_QUEENCURE_QUEST, 40);
		String text = p.text(Transports.FAIRY_RINGS);
		assertTrue(text, text.contains("\n\nFairy rings: unlocked; needs a dramen or lunar staff wielded (none found in "
			+ "what they wear or carry; their bank hasn't been seen since AI Chat could look, so they can open it once). "
			+ "Some rings need more"));
		assertTrue(text, text.contains("\"Fairy ring\""));

		// The quest's state unread: unknown, not "not started".
		p.quests.remove(Quest.FAIRYTALE_II__CURE_A_QUEEN);
		p.varbits.remove(VarbitID.FAIRY2_QUEENCURE_QUEST);
		text = p.text(Transports.FAIRY_RINGS);
		assertTrue(text, text.contains("Fairy rings: unknown: needs Fairytale II - Cure a Queen, whose state couldn't be "
			+ "read."));
		assertFalse(text, text.contains("\"Fairy ring\""));
	}

	/** The fairy rings' status, the one line their section is. */
	private static String rings(Player p)
	{
		return line(p.text(Transports.FAIRY_RINGS), "Fairy rings: ");
	}

	@Test
	public void withoutTheEliteDiaryTheRingsAreUnlockedAndNeedAStaffNeverPlainAvailable()
	{
		Player p = wellProgressed();
		p.varbits.put(VarbitID.LUMBRIDGE_DIARY_ELITE_COMPLETE, 0);
		// A staff in the bank: needed, and where it is, the bank dated as the jewellery is.
		assertTrue(rings(p), rings(p).startsWith("Fairy rings: unlocked; needs a dramen or lunar staff wielded (they have: "
			+ "Dramen staff in their bank as last seen 10 minutes ago). Some rings need more"));
		// One worn and one banked: each place said.
		p.held = new Transports.Held(Collections.singletonList(new GameData.ItemLine("Weapon", "Lunar staff - pt2", false, 1,
			0)), Collections.emptyList(), new GameData.Bank(Collections.singletonList(item("Dramen staff", 1)), 0));
		assertTrue(rings(p), rings(p).startsWith("Fairy rings: unlocked; needs a dramen or lunar staff wielded (they have: "
			+ "Lunar staff - pt2 worn; Dramen staff in their bank as last seen 10 minutes ago)."));

		// No staff anywhere AI Chat looked: none found, and where it looked.
		p.held = new Transports.Held(Collections.emptyList(), Collections.singletonList(item("Shark", 5)),
			new GameData.Bank(Collections.singletonList(item("Coins", 1000)), 0));
		assertTrue(rings(p), rings(p).startsWith("Fairy rings: unlocked; needs a dramen or lunar staff wielded (none found "
			+ "in what they wear, carry or keep in their bank, as last seen 10 minutes ago). Some rings need more"));
		// Without "Share items and gear", the items weren't read at all: said as why none was found.
		p.held = null;
		assertTrue(rings(p), rings(p).startsWith("Fairy rings: unlocked; needs a dramen or lunar staff wielded (none found: "
			+ "\"Share items and gear\" is off, so their items weren't checked). Some rings need more"));
		assertTrue(p.text(Transports.ITEMS).contains("Teleport jewellery: left out: \"Share items and gear\" is off."));
		// Noted staves can't be wielded.
		p.held = new Transports.Held(Collections.emptyList(), Collections.singletonList(new GameData.ItemLine(null,
			"Dramen staff", true, 3, 0)), null);
		assertTrue(rings(p), rings(p).contains("(none found in what they wear or carry;"));

		// One status line, never a paragraph of its own, and never plain "available" while a staff is needed.
		assertFalse(rings(p), rings(p).contains("Fairy rings: available"));
		assertEquals(1, p.text(Transports.FAIRY_RINGS).split("\n\nFairy rings: ")[1].split("\n").length);
	}

	@Test
	public void withTheEliteDiaryTheRingsAreAvailableWithoutAStaff()
	{
		Player p = wellProgressed();
		p.held = new Transports.Held(Collections.emptyList(), Collections.emptyList(), null);
		assertTrue(rings(p), rings(p).startsWith("Fairy rings: available: they have the Fairy Godfather's permission. No "
			+ "staff needed: they've done the elite Lumbridge & Draynor diary. Some rings need more"));
		assertFalse(rings(p), rings(p).contains("staff wielded"));
		assertFalse(rings(p), rings(p).contains("unlocked"));
	}

	@Test
	public void spellsGoByTheLevelNowBoostsIncluded()
	{
		Player p = new Player(QuestState.FINISHED, true, ANCIENT, 70);
		p.boostedMagic = 72;
		String text = p.text(Transports.SPELLS);
		assertTrue(text, text.startsWith("What the player can use to travel"));
		assertTrue(text, text.contains("Teleport spells: they're on Ancient Magicks, with Magic 72 now, 70 without boosts. "
			+ "Each spell's level is compared with the level they have now, since boosts count for casting."));
		// Boosted to 72: Lassar can be cast now, though their own level is 70, and the line says the boost is why.
		assertEquals("- Lassar Teleport (72 Magic), to the top of Ice Mountain: available while boosted (70 without)",
			line(text, "- Lassar Teleport"));
		// Below their own level: plainly available, boost or not.
		assertEquals("- Kharyrll Teleport (66 Magic), to Canifis: available", line(text, "- Kharyrll Teleport"));
		assertEquals("- Dareeyak Teleport (78 Magic), to ruins in level 23 Wilderness: needs 78 Magic (theirs is 72 now, 70 "
			+ "without boosts, and boosts count)", line(text, "- Dareeyak Teleport"));
		assertTrue(text, text.contains("\"Ancient Magicks\" lists every spell"));
		// One kind only: no other kind, and no summary of what the whole answer covers.
		assertFalse(text, text.contains("Fairy rings"));
		assertFalse(text, text.contains("Covered here"));

		// Drained below their own level: the level now is what counts.
		p.magic = 74;
		p.boostedMagic = 70;
		assertTrue(line(p.text(Transports.SPELLS), "- Lassar Teleport").endsWith(": needs 72 Magic (theirs is 70 now, 74 "
			+ "without boosts, and boosts count)"));

		// Only a boost reaches any spell: still marked, and the Wiki page for the book still given.
		Player low = new Player(QuestState.FINISHED, true, ANCIENT, 53);
		low.boostedMagic = 54;
		String lowText = low.text(Transports.SPELLS);
		assertEquals("- Paddewwa Teleport (54 Magic), to the Edgeville Dungeon: available while boosted (53 without)",
			line(lowText, "- Paddewwa Teleport"));
		assertTrue(lowText, lowText.contains("\"Ancient Magicks\" lists every spell"));
		// Boosted, but something unseen as well: unknown wins, as it would without the boost.
		Player arceuus = new Player(QuestState.FINISHED, true, ARCEUUS, 80);
		arceuus.boostedMagic = 83;
		assertTrue(line(arceuus.text(Transports.SPELLS), "- Barrows Teleport").endsWith(": unknown: also needs Drezel's "
			+ "warning, given after Priest in Peril, which AI Chat can't see"));

		// A level short and something unseen: both are said.
		Player lunar = new Player(QuestState.FINISHED, false, LUNAR, 70);
		assertEquals("- Ourania Teleport (71 Magic), to the Ourania Cave, just outside it: needs 71 Magic (theirs is 70 now, and "
			+ "boosts count); also needs having spoken to Baba Yaga on the Lunar spellbook, and a Seal of Passage until the "
			+ "elite Fremennik diary is done, which AI Chat doesn't check", line(lunar.text(Transports.SPELLS), "- Ourania"));
	}

	@Test
	public void whatCouldntBeReadIsUnknownNeverYesOrNo()
	{
		// A diary tier unread: neither done nor not done.
		Player p = wellProgressed();
		p.varbits.remove(VarbitID.KANDARIN_DIARY_HARD_COMPLETE);
		assertEquals("- Camelot Teleport can be switched to Seers' Village bank: unknown: needs the hard Kandarin diary, "
			+ "which couldn't be read", line(p.text(Transports.DIARIES), "- Camelot Teleport can"));
		// The elite Lumbridge & Draynor diary unread: the staff is asked for, with why.
		p.varbits.remove(VarbitID.LUMBRIDGE_DIARY_ELITE_COMPLETE);
		assertTrue(rings(p), rings(p).startsWith("Fairy rings: unlocked; needs a dramen or lunar staff wielded (they have: "
			+ "Dramen staff in their bank as last seen 10 minutes ago), unless they've done the elite Lumbridge & Draynor "
			+ "diary, which couldn't be read."));
		assertFalse(rings(p), rings(p).contains("No staff needed"));

		// The quest that opens a whole network unread: unknown, and no tree or glider is listed as if it were open.
		p.quests.remove(Quest.TREE_GNOME_VILLAGE);
		p.quests.remove(Quest.THE_GRAND_TREE);
		String trees = p.text(Transports.SPIRIT_TREES);
		assertTrue(trees, trees.contains("Spirit trees: unknown: needs Tree Gnome Village, whose state couldn't be read."));
		assertFalse(trees, trees.contains(": available"));
		String gliders = p.text(Transports.GLIDERS);
		assertTrue(gliders, gliders.contains("Gnome gliders: unknown: needs The Grand Tree, whose state couldn't be read."));
		assertFalse(gliders, gliders.contains(": available"));

		// Fairytale II started, but its stage unread: unknown, not "haven't got permission yet".
		Player started = fresh().quest(Quest.FAIRYTALE_II__CURE_A_QUEEN, QuestState.IN_PROGRESS);
		started.varbits.remove(VarbitID.FAIRY2_QUEENCURE_QUEST);
		String startedRings = started.text(Transports.FAIRY_RINGS);
		assertTrue(startedRings, startedRings.contains("Fairy rings: unknown: needs Fairytale II - Cure a Queen far "
			+ "enough to get the Fairy Godfather's permission to use the rings; they've started it, and its stage couldn't "
			+ "be read."));
	}

	@Test
	public void morytaniaArceuusTeleportsNeedPriestInPerilAndStayUnknownAfterIt()
	{
		// Priest in Peril not done: certain, whatever Drezel would say.
		Player p = new Player(QuestState.FINISHED, true, ARCEUUS, 99).quest(Quest.PRIEST_IN_PERIL, QuestState.NOT_STARTED);
		String text = p.text(Transports.SPELLS);
		assertTrue(text, text.contains("Teleport spells: they're on the Arceuus spellbook"));
		assertEquals("- Barrows Teleport (83 Magic), to the Barrows: not available: needs Priest in Peril (not started)",
			line(text, "- Barrows Teleport"));
		// Done: Drezel's warning can't be read, so never "available".
		p.quest(Quest.PRIEST_IN_PERIL, QuestState.FINISHED);
		text = p.text(Transports.SPELLS);
		for (String spell : Arrays.asList("- Salve Graveyard Teleport", "- Fenkenstrain's Castle Teleport",
			"- Barrows Teleport"))
		{
			assertTrue(line(text, spell), line(text, spell).endsWith(": unknown: also needs Drezel's warning, given after "
				+ "Priest in Peril, which AI Chat can't see"));
		}
		assertEquals("- Ape Atoll Teleport (90 Magic), to the entrance of the Ape Atoll Dungeon: available",
			line(text, "- Ape Atoll Teleport"));
	}

	@Test
	public void theArdougneTeleportNeedsItsScrollRead()
	{
		Player p = wellProgressed();
		p.varps.put(VarPlayerID.ELENAQUEST, 29);
		assertTrue(line(p.text(Transports.SPELLS), "- Ardougne Teleport").endsWith(": not available: they've done Plague "
			+ "City but haven't read the Ardougne teleport scroll it gives yet"));
		p.varps.remove(VarPlayerID.ELENAQUEST);
		assertTrue(line(p.text(Transports.SPELLS), "- Ardougne Teleport").endsWith(": unknown: needs the Ardougne teleport "
			+ "scroll from Plague City read, which AI Chat couldn't check"));
	}

	@Test
	public void anUnreadSpellbookListsNoSpells()
	{
		Player p = wellProgressed();
		p.varbits.put(VarbitID.SPELLBOOK, 9);
		String text = p.text(Transports.SPELLS);
		assertTrue(text, text.contains("Teleport spells: unknown: their spellbook couldn't be read, so its teleports aren't "
			+ "listed."));
		assertFalse(text, text.contains("Varrock Teleport"));
	}

	@Test
	public void jewelleryChargesComeFromItsName()
	{
		assertEquals(3, Transports.jewel("Games necklace(3)").charges);
		assertEquals("Games necklace", Transports.jewel("Games necklace(3)").page);
		assertEquals(5, Transports.jewel("Digsite pendant (5)").charges);
		assertEquals(8, Transports.jewel("Slayer ring (8)").charges);
		// The trimmed glory and imbued ring of wealth are the same jewellery.
		assertEquals("Amulet of glory", Transports.jewel("Amulet of glory (t4)").page);
		assertEquals(4, Transports.jewel("Amulet of glory (t4)").charges);
		assertEquals(5, Transports.jewel("Ring of wealth (i5)").charges);
		// Out of charges.
		assertEquals(0, Transports.jewel("Amulet of glory").charges);
		assertEquals(0, Transports.jewel("Ring of wealth (i)").charges);
		// Never runs out.
		assertEquals(-1, Transports.jewel("Amulet of eternal glory").charges);
		assertEquals("Slayer ring (eternal)", Transports.jewel("Slayer ring (eternal)").page);
		// Not jewellery that teleports.
		assertNull(Transports.jewel("Ring of wealth scroll"));
		assertNull(Transports.jewel("Ring of recoil"));
		assertNull(Transports.jewel("Shark"));
		assertNull(Transports.jewel(null));
	}

	@Test
	public void notedJewelleryIsCountedAndSaidToBeNoted()
	{
		Player p = wellProgressed();
		p.held = new Transports.Held(Collections.emptyList(), Arrays.asList(item("Games necklace(8)", 2),
			new GameData.ItemLine(null, "Games necklace(8)", true, 20, 0)), null);
		String text = p.text(Transports.ITEMS);
		// Not "none found": a bank unnotes them.
		assertFalse(text, text.contains("none found"));
		assertEquals("- Games necklace: (8) x2 in their inventory, (8) x20 noted in their inventory",
			line(text, "- Games necklace"));
		assertTrue(text, text.contains("\nNoted ones have to be unnoted at a bank before they teleport."));
		// The bank wasn't seen: said so, not taken for none there.
		assertTrue(text, text.contains("with their bank not checked"));
	}

	@Test
	public void everyKindOfJewelleryKeepsItsLine()
	{
		List<String> kinds = Arrays.asList("Amulet of glory", "Games necklace", "Ring of dueling", "Skills necklace",
			"Combat bracelet", "Ring of wealth", "Digsite pendant", "Necklace of passage", "Burning amulet", "Slayer ring",
			"Ring of returning");
		// A bank with every charge of every kind: far more pieces than a list could take whole.
		List<GameData.ItemLine> bank = new ArrayList<>();
		for (String kind : kinds)
		{
			for (int i = 8; i >= 1; i--)
			{
				bank.add(item(kind + "(" + i + ")", 2));
			}
			bank.add(item(kind, 5));
		}
		bank.add(item("Amulet of glory (t4)", 1));
		bank.add(item("Amulet of glory (t)", 1));
		bank.add(item("Ring of wealth (i5)", 1));
		bank.add(item("Amulet of eternal glory", 1));
		bank.add(item("Slayer ring (eternal)", 1));
		Player p = wellProgressed();
		p.held = new Transports.Held(Collections.singletonList(new GameData.ItemLine("Neck", "Amulet of glory(2)", false,
			1, 0)), Collections.emptyList(), new GameData.Bank(bank, 0));
		String text = p.text(Transports.ITEMS);

		List<String> listed = new ArrayList<>();
		for (String l : text.split("\n"))
		{
			if (l.startsWith("- "))
			{
				listed.add(l.substring(2, l.indexOf(':')));
			}
		}
		List<String> every = new ArrayList<>(kinds);
		every.addAll(Arrays.asList("Amulet of eternal glory", "Slayer ring (eternal)"));
		assertEquals(every, listed);
		// Each kind is cut on its own line, nearest first, then the most charges: the glory worn leads its line.
		assertEquals("- Amulet of glory: (2) worn, (8) x2, (7) x2, (6) x2, (5) x2, (4) x2, (t4), (3) x2 in their bank, "
			+ "and 4 more not listed", line(text, "- Amulet of glory"));
		assertEquals("- Ring of returning: (8) x2, (7) x2, (6) x2, (5) x2, (4) x2, (3) x2, (2) x2, (1) x2 in their bank, "
			+ "and 1 more not listed", line(text, "- Ring of returning"));
		assertEquals("- Slayer ring (eternal): in their bank; it never runs out", line(text, "- Slayer ring (eternal)"));
		// However much they hold, the jewellery stays a small part of a whole answer.
		assertTrue(text, text.length() < Transports.MAX_CHARS / 3);
	}

	@Test
	public void unchargedTrimmedAndImbuedJewelleryIsTheSameKind()
	{
		Player p = wellProgressed();
		p.held = new Transports.Held(Collections.emptyList(), Arrays.asList(item("Amulet of glory (t)", 1),
			item("Ring of wealth (i5)", 1), item("Ring of wealth", 3)), new GameData.Bank(Collections.emptyList(), 0));
		String text = p.text(Transports.ITEMS);
		assertEquals("- Amulet of glory: (t) uncharged in their inventory", line(text, "- Amulet of glory"));
		// Two in the same place: the place is said once, after both.
		assertEquals("- Ring of wealth: (i5), uncharged x3 in their inventory", line(text, "- Ring of wealth"));
		// Only what can teleport has a page to read.
		assertTrue(text, text.contains("\nFor where each goes, read its Wiki page: \"Ring of wealth\"."));
	}

	@Test
	public void aLongAnswerLeavesOutWholeKindsAndSaysWhich()
	{
		String whole = wellProgressed().text();
		String cut = Transports.text(wellProgressed().state(), null, 10 * MINUTE, 2500);
		assertTrue(cut.length() < whole.length());
		assertTrue(cut, cut.contains("\n\nFairy rings: available"));
		assertTrue(cut, cut.contains("\n\nLeft out to keep this short: "));
		assertTrue(cut, cut.contains("diaries. Ask again with one of those as the category."));
		assertFalse(cut, cut.contains("Travel unlocks from achievement diaries"));
		// What the answer covers still comes last.
		assertTrue(cut, cut.endsWith("and the other networks."));
		// Even a tiny limit keeps the first kind: asking for one kind always answers it.
		String one = Transports.text(wellProgressed().state(), Transports.SPIRIT_TREES, 10 * MINUTE, 10);
		assertTrue(one, one.contains("Spirit trees: available"));
		assertFalse(one, one.contains("Left out"));
	}

	@Test
	public void theGameIsAskedForWhatTheTablesUse()
	{
		assertTrue(Transports.QUESTS.containsAll(Arrays.asList(Quest.FAIRYTALE_II__CURE_A_QUEEN, Quest.TREE_GNOME_VILLAGE,
			Quest.THE_GRAND_TREE, Quest.ONE_SMALL_FAVOUR, Quest.PLAGUE_CITY, Quest.DESERT_TREASURE_I, Quest.PRIEST_IN_PERIL)));
		// Each quest state runs a game script: the tables' own quests only, not all of them.
		assertTrue(Transports.QUESTS.size() < 30);
		assertTrue(Transports.VARBITS.containsAll(Arrays.asList(VarbitID.SPELLBOOK, VarbitID.FAIRY2_QUEENCURE_QUEST,
			VarbitID.LUMBRIDGE_DIARY_ELITE_COMPLETE, VarbitID.VARROCK_DIARY_MEDIUM_COMPLETE)));
		assertEquals(Collections.singleton(VarPlayerID.ELENAQUEST), Transports.VARPS);
		assertTrue(Transports.needsItems(null));
		assertTrue(Transports.needsItems(Transports.FAIRY_RINGS));
		assertTrue(Transports.needsItems(Transports.ITEMS));
		assertFalse(Transports.needsItems(Transports.SPELLS));
	}
}
