package com.aichat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;

/**
 * What the player has unlocked to travel with, for get_transport_options: fairy rings, spirit trees, gnome gliders,
 * the teleport spells on their spellbook, the teleport jewellery they hold, and the travel unlocks their achievement
 * diaries give. It reports and never routes: the assistant can work out a route, but not which of the ways it knows
 * this player can use, so it would send someone to a fairy ring they can't open. Every line is available, not
 * available (with why), or unknown. A requirement AI Chat can't read is unknown, never a guess either way: a wasted
 * trip across the map costs the player more than an honest "can't tell".
 *
 * <p>The tables are game facts, each checked against its OSRS Wiki page. Which quests and varbits gate the fairy rings,
 * spirit trees, gliders and spells was first taken from the Shortest Path plugin (github.com/Skretzo/shortest-path,
 * BSD 2-Clause), and the Fairy Tale II stage and Ardougne teleport scroll value were confirmed in Quest Helper
 * (github.com/Zoinkwiz/quest-helper, BSD 2-Clause). Only facts were taken from them, no code. Where they and the Wiki
 * disagreed on what a transport needs, the Wiki was followed: Shortest Path's spell table has the Barrows Teleport
 * without Priest in Peril, which the Wiki and the game's own refusal message both name. A requirement the game can't
 * show a plugin, or whose value only one of them gives (the Watchtower spell scroll), is reported as unknown.
 *
 * <p>Reading happens in {@link GameData#transports}, on the client thread, and gives a {@link State} of plain values;
 * everything here writes that up (static, any thread), so it can be tested without a game.
 */
final class Transports
{
	static final String FAIRY_RINGS = "fairy_rings";
	static final String SPIRIT_TREES = "spirit_trees";
	static final String GLIDERS = "gnome_gliders";
	static final String SPELLS = "spells";
	static final String ITEMS = "items";
	static final String DIARIES = "diaries";
	/** What the model can ask for one at a time, in the order a whole answer gives them. */
	static final List<String> CATEGORIES = Collections.unmodifiableList(
		Arrays.asList(FAIRY_RINGS, SPIRIT_TREES, GLIDERS, SPELLS, ITEMS, DIARIES));

	/**
	 * An answer's length at most, about 2,000 tokens: a well-progressed account with a bank full of jewellery would
	 * otherwise run long, and the model can ask for the one kind it needs.
	 */
	static final int MAX_CHARS = 8_000;
	/**
	 * Pieces of one kind of teleport jewellery listed at most: a kind can come in every charge, trimmed or imbued,
	 * uncharged and noted, in each place, and past this its line says how many more. Every kind keeps its own line, so
	 * none drops off the list however much of another kind they hold.
	 */
	static final int PIECES_PER_KIND = 8;

	/**
	 * The Fairytale II stage at which the Fairy Godfather lets the player use the rings, the point the Wiki gives for
	 * them: Shortest Path opens the rings above 39, and Quest Helper notes 40 as the talk with him.
	 */
	private static final int FAIRY_RING_PERMISSION = 40;
	/**
	 * Plague City's varp once the Ardougne teleport scroll the quest gives has been read, which is what teaches the
	 * spell (Wiki: "Ardougne Teleport"). Shortest Path's spell table and Quest Helper's Plague City both give 30.
	 */
	private static final int ARDOUGNE_SCROLL_READ = 30;

	/** The staves that open a fairy ring: the dramen and lunar staves, and the lunar staff part-made in Lunar Diplomacy. */
	private static final Set<String> STAVES = new LinkedHashSet<>(Arrays.asList("Dramen staff", "Lunar staff",
		"Lunar staff - pt1", "Lunar staff - pt2", "Lunar staff - pt3"));

	// ------------------------------------------------------------------
	// Plain values: what GameData reads, and what the writing up takes
	// ------------------------------------------------------------------

	/** What the game said, read on the client thread. Anything missing from a map couldn't be read, and is unknown. */
	static final class State
	{
		/** Only the quests the tables below name ({@link Transports#QUESTS}). */
		final Map<Quest, QuestState> quests;
		/** Only the varbits ({@link Transports#VARBITS}) and varps ({@link Transports#VARPS}) the tables name, by id. */
		final Map<Integer, Integer> varbits;
		final Map<Integer, Integer> varps;
		/** Magic without boosts, and as it is right now: casting goes by the boosted level. */
		final int magic;
		final int boostedMagic;
		/** What they wear, carry and keep in the bank; null when that wasn't read ("Share items and gear" off). */
		final Held held;

		State(Map<Quest, QuestState> quests, Map<Integer, Integer> varbits, Map<Integer, Integer> varps, int magic,
			int boostedMagic, Held held)
		{
			this.quests = quests;
			this.varbits = varbits;
			this.varps = varps;
			this.magic = magic;
			this.boostedMagic = boostedMagic;
			this.held = held;
		}
	}

	/** The player's items, from the same reads as get_equipment, get_inventory and get_bank. */
	static final class Held
	{
		final List<GameData.ItemLine> worn;
		final List<GameData.ItemLine> carried;
		/** As last seen, or null when it hasn't been since it could be. */
		final GameData.Bank bank;

		Held(List<GameData.ItemLine> worn, List<GameData.ItemLine> carried, GameData.Bank bank)
		{
			this.worn = worn;
			this.carried = carried;
			this.bank = bank;
		}
	}

	// ------------------------------------------------------------------
	// Requirements, and what the game says of them
	// ------------------------------------------------------------------

	/**
	 * Worst last: a transport is as good as its worst requirement. {@link #BOOSTED} is available now, but only while a
	 * Magic boost lasts.
	 */
	private enum Verdict
	{
		AVAILABLE, BOOSTED, UNKNOWN, LEVEL, UNAVAILABLE
	}

	/** One requirement's answer for this player: why, for anything but {@link Verdict#AVAILABLE}. */
	private static final class Check
	{
		final Verdict verdict;
		final String why;

		Check(Verdict verdict, String why)
		{
			this.verdict = verdict;
			this.why = why;
		}
	}

	private static final Check MET = new Check(Verdict.AVAILABLE, null);

	/** One thing a transport needs. */
	private abstract static class Need
	{
		/** Adds what {@link #check} reads, so GameData reads exactly that: each quest state runs a game script. */
		void reads(Set<Quest> quests, Set<Integer> varbits, Set<Integer> varps)
		{
		}

		abstract Check check(State s);
	}

	/** A quest completed. */
	private static final class Done extends Need
	{
		final Quest quest;

		Done(Quest quest)
		{
			this.quest = quest;
		}

		@Override
		void reads(Set<Quest> quests, Set<Integer> varbits, Set<Integer> varps)
		{
			quests.add(quest);
		}

		@Override
		Check check(State s)
		{
			QuestState state = s.quests.get(quest);
			if (state == null)
			{
				return unread(quest);
			}
			if (state == QuestState.FINISHED)
			{
				return MET;
			}
			return new Check(Verdict.UNAVAILABLE, "needs " + quest.getName()
				+ (state == QuestState.IN_PROGRESS ? " (started, not finished)" : " (not started)"));
		}
	}

	/**
	 * Part of a quest done, to a point AI Chat can't see: done or not started says for certain, in progress doesn't. The
	 * Wiki gives these as "partial completion" without a stage that could be read.
	 */
	private static final class Partly extends Need
	{
		final Quest quest;

		Partly(Quest quest)
		{
			this.quest = quest;
		}

		@Override
		void reads(Set<Quest> quests, Set<Integer> varbits, Set<Integer> varps)
		{
			quests.add(quest);
		}

		@Override
		Check check(State s)
		{
			QuestState state = s.quests.get(quest);
			if (state == null)
			{
				return unread(quest);
			}
			if (state == QuestState.FINISHED)
			{
				return MET;
			}
			if (state == QuestState.NOT_STARTED)
			{
				return new Check(Verdict.UNAVAILABLE, "needs part of " + quest.getName() + " done (not started)");
			}
			return new Check(Verdict.UNKNOWN, "needs part of " + quest.getName() + " done; they've started it, and AI Chat "
				+ "can't tell whether they're far enough");
		}
	}

	/** A quest done up to a stage its varbit shows: finished, or past {@code min} while in progress. */
	private static final class Stage extends Need
	{
		final Quest quest;
		final int varbit;
		final int min;
		/** What the stage gives: "the Fairy Godfather's permission". */
		final String what;

		Stage(Quest quest, int varbit, int min, String what)
		{
			this.quest = quest;
			this.varbit = varbit;
			this.min = min;
			this.what = what;
		}

		@Override
		void reads(Set<Quest> quests, Set<Integer> varbits, Set<Integer> varps)
		{
			quests.add(quest);
			varbits.add(varbit);
		}

		@Override
		Check check(State s)
		{
			QuestState state = s.quests.get(quest);
			Integer stage = s.varbits.get(varbit);
			if (state == QuestState.FINISHED || stage != null && stage >= min)
			{
				return MET;
			}
			if (state == QuestState.NOT_STARTED)
			{
				return new Check(Verdict.UNAVAILABLE, "needs " + quest.getName() + " started, far enough to get " + what
					+ " (not started)");
			}
			if (state == QuestState.IN_PROGRESS && stage != null)
			{
				return new Check(Verdict.UNAVAILABLE, "they've started " + quest.getName() + " but haven't got " + what
					+ " yet");
			}
			if (state == QuestState.IN_PROGRESS)
			{
				// The quest's state was read; only how far they are wasn't.
				return new Check(Verdict.UNKNOWN, "needs " + quest.getName() + " far enough to get " + what
					+ "; they've started it, and its stage couldn't be read");
			}
			return unread(quest);
		}
	}

	/** A quest done, and then a scroll it gives read, which a varp shows. */
	private static final class Scroll extends Need
	{
		final Quest quest;
		final int varp;
		final int min;
		final String scroll;

		Scroll(Quest quest, int varp, int min, String scroll)
		{
			this.quest = quest;
			this.varp = varp;
			this.min = min;
			this.scroll = scroll;
		}

		@Override
		void reads(Set<Quest> quests, Set<Integer> varbits, Set<Integer> varps)
		{
			quests.add(quest);
			varps.add(varp);
		}

		@Override
		Check check(State s)
		{
			Check done = new Done(quest).check(s);
			if (done.verdict != Verdict.AVAILABLE)
			{
				return done;
			}
			Integer value = s.varps.get(varp);
			if (value == null)
			{
				return new Check(Verdict.UNKNOWN, "needs the " + scroll + " from " + quest.getName()
					+ " read, which AI Chat couldn't check");
			}
			return value >= min ? MET : new Check(Verdict.UNAVAILABLE, "they've done " + quest.getName()
				+ " but haven't read the " + scroll + " it gives yet");
		}
	}

	/** An achievement diary tier done. Every tier used here is 1 when done, as GameData's own diary table says. */
	private static final class DiaryTier extends Need
	{
		final int varbit;
		/** "the elite Lumbridge & Draynor diary". */
		final String tier;

		DiaryTier(int varbit, String tier)
		{
			this.varbit = varbit;
			this.tier = tier;
		}

		@Override
		void reads(Set<Quest> quests, Set<Integer> varbits, Set<Integer> varps)
		{
			varbits.add(varbit);
		}

		@Override
		Check check(State s)
		{
			Integer done = s.varbits.get(varbit);
			if (done == null)
			{
				return new Check(Verdict.UNKNOWN, "needs " + tier + ", which couldn't be read");
			}
			return done == 1 ? MET : new Check(Verdict.UNAVAILABLE, "needs " + tier + " (not done)");
		}
	}

	/**
	 * A Magic level. Casting goes by the level the player has right now, boosts included, so that's the one compared;
	 * the answer says so, and gives the level without boosts too, so the model can say what a boost would do. A spell
	 * only the boost reaches says so on its own line ("available while boosted (70 without)"): the boost wears off, and
	 * a route planned now may be travelled later.
	 */
	private static final class Magic extends Need
	{
		final int level;

		Magic(int level)
		{
			this.level = level;
		}

		@Override
		Check check(State s)
		{
			if (s.boostedMagic >= level)
			{
				return s.magic >= level ? MET : new Check(Verdict.BOOSTED, s.magic + " without");
			}
			return new Check(Verdict.LEVEL, "needs " + level + " Magic (theirs is " + magicNow(s) + ", and boosts count)");
		}
	}

	/** Something the game doesn't let a plugin see. Never a guess: the transport is unknown while everything else is met. */
	private static final class Unseen extends Need
	{
		final String why;

		Unseen(String why)
		{
			this.why = why;
		}

		@Override
		Check check(State s)
		{
			return new Check(Verdict.UNKNOWN, why);
		}
	}

	private static Check unread(Quest quest)
	{
		return new Check(Verdict.UNKNOWN, "needs " + quest.getName() + ", whose state couldn't be read");
	}

	private static Need done(Quest quest)
	{
		return new Done(quest);
	}

	/** "85 now", or "83 now, 85 without boosts" when they differ either way. */
	private static String magicNow(State s)
	{
		return s.boostedMagic + " now" + (s.boostedMagic == s.magic ? "" : ", " + s.magic + " without boosts");
	}

	// ------------------------------------------------------------------
	// The tables: game facts, checked against the Wiki
	// ------------------------------------------------------------------

	/** One way to travel: a fairy ring network, a spirit tree, a glider, a spell. */
	private static final class Way
	{
		final String name;
		/** Where it is or goes, said after the name; null when the name says it. */
		final String where;
		/** Magic level for a spell, or 0. */
		final int magic;
		final Need[] needs;

		Way(String name, String where, int magic, Need... needs)
		{
			this.name = name;
			this.where = where;
			this.magic = magic;
			this.needs = needs;
		}
	}

	private static Way way(String name, String where, Need... needs)
	{
		return new Way(name, where, 0, needs);
	}

	private static Way spell(String name, int magic, String to, Need... needs)
	{
		Need[] all = Arrays.copyOf(needs, needs.length + 1);
		all[needs.length] = new Magic(magic);
		return new Way(name, to, magic, all);
	}

	/** Fairy rings: Fairy Tale II up to the Fairy Godfather's permission (Wiki: "Fairy ring"). Fairy Tale I comes first. */
	private static final Need FAIRY_RING_ACCESS = new Stage(Quest.FAIRYTALE_II__CURE_A_QUEEN,
		VarbitID.FAIRY2_QUEENCURE_QUEST, FAIRY_RING_PERMISSION, "the Fairy Godfather's permission to use the rings");
	/** The tier that does away with the staff (Wiki: "Fairy ring"). */
	private static final Need NO_STAFF_NEEDED = new DiaryTier(VarbitID.LUMBRIDGE_DIARY_ELITE_COMPLETE,
		"the elite Lumbridge & Draynor diary");

	/** Every spirit tree needs Tree Gnome Village (Wiki: "Spirit tree"). */
	private static final Need SPIRIT_TREE_ACCESS = done(Quest.TREE_GNOME_VILLAGE);
	/**
	 * The trees that don't have to be grown, by the Wiki's "Spirit tree locations". The Stronghold's tree takes a player
	 * there without The Grand Tree, but not away.
	 */
	private static final Way[] SPIRIT_TREES_TABLE = {
		way("Tree Gnome Village", "in the middle of the Gnome Maze"),
		way("Gnome Stronghold, to travel to it", null),
		way("Gnome Stronghold, to travel from it", null, done(Quest.THE_GRAND_TREE)),
		way("Battlefield of Khazard", "just south of West Ardougne"),
		way("Grand Exchange", "by its north-east corner, in Varrock"),
		way("Feldip Hills", "next to the Myths' Guild"),
		way("Prifddinas", null, done(Quest.SONG_OF_THE_ELVES)),
		way("Incomitatus", "east of the Poison Waste", new Partly(Quest.THE_PATH_OF_GLOUPHRIE)),
		way("Laguna Aurorae", "an island in the Sunset Ocean", done(Quest.PANDEMONIUM),
			new Unseen("also needs having moored at the island once (58 Sailing), which AI Chat can't see")),
	};

	/** Gliders need The Grand Tree (Wiki: "Gnome glider"). */
	private static final Need GLIDER_ACCESS = done(Quest.THE_GRAND_TREE);
	private static final Way[] GLIDERS_TABLE = {
		way("Ta Quir Priw", "the Grand Tree, in the Tree Gnome Stronghold"),
		way("Gandius", "south of the shipyard on Karamja"),
		way("Kar-Hewo", "Al Kharid"),
		way("Sindarpos", "the peak of White Wolf Mountain"),
		way("Lemanto Andra", "west of the Digsite; one way only, there's no flying back"),
		way("Lemantolly Undri", "western Feldip Hills", new Partly(Quest.ONE_SMALL_FAVOUR)),
		way("Ookookolly Undri", "north-west Ape Atoll", done(Quest.MONKEY_MADNESS_II)),
	};

	/** The Arceuus teleports that the game turns down without Drezel's warning, said to follow Priest in Peril. */
	private static final Need DREZEL = new Unseen("also needs Drezel's warning, given after Priest in Peril, which AI Chat "
		+ "can't see");

	/**
	 * Each spellbook's teleports to a place, by their Wiki pages: level, where they land, and what they need besides the
	 * level and the spellbook itself. Home teleports, Minigame Teleport, Teleport to Boat, Teleport to Target and the
	 * teleports that move other players are left out.
	 */
	private static final Book[] BOOKS = {
		new Book(0, "the standard spellbook", "Standard spellbook", null,
			spell("Varrock Teleport", 25, "Varrock's town square"),
			spell("Lumbridge Teleport", 31, "Lumbridge"),
			spell("Falador Teleport", 37, "Falador"),
			spell("Teleport to House", 40, "their player-owned house",
				new Unseen("needs a player-owned house, which AI Chat doesn't check")),
			spell("Camelot Teleport", 45, "the front gate of Camelot"),
			spell("Kourend Castle Teleport", 48, "the Kourend Castle courtyard", done(Quest.CLIENT_OF_KOUREND)),
			spell("Ardougne Teleport", 51, "the East Ardougne market",
				new Scroll(Quest.PLAGUE_CITY, VarPlayerID.ELENAQUEST, ARDOUGNE_SCROLL_READ, "Ardougne teleport scroll")),
			spell("Civitas illa Fortis Teleport", 54, "Civitas illa Fortis", done(Quest.TWILIGHTS_PROMISE)),
			// The scroll Watchtower gives is read to learn the spell, as with Ardougne's, but only Shortest Path says which
			// varp value shows it, so it stays unknown.
			spell("Watchtower Teleport", 58, "the top of the Watchtower, north of Yanille", done(Quest.WATCHTOWER),
				new Unseen("also needs the spell scroll Watchtower gives read, which AI Chat doesn't check")),
			spell("Trollheim Teleport", 61, "the top of Trollheim", done(Quest.EADGARS_RUSE)),
			spell("Ape Atoll Teleport", 64, "the Temple of Marimbo on Ape Atoll",
				done(Quest.RECIPE_FOR_DISASTER__KING_AWOWOGEI))),
		new Book(1, "Ancient Magicks", "Ancient Magicks", Quest.DESERT_TREASURE_I,
			spell("Paddewwa Teleport", 54, "the Edgeville Dungeon"),
			spell("Senntisten Teleport", 60, "the Digsite exam centre"),
			spell("Kharyrll Teleport", 66, "Canifis"),
			spell("Lassar Teleport", 72, "the top of Ice Mountain"),
			spell("Dareeyak Teleport", 78, "ruins in level 23 Wilderness"),
			spell("Carrallanger Teleport", 84, "the Graveyard of Shadows, level 19 Wilderness"),
			spell("Annakarl Teleport", 90, "the Demonic Ruins, level 47 Wilderness"),
			spell("Ghorrock Teleport", 96, "the Frozen Waste Plateau, deep Wilderness near the level 44 obelisk")),
		new Book(2, "the Lunar spellbook", "Lunar spellbook", Quest.LUNAR_DIPLOMACY,
			spell("Moonclan Teleport", 69, "the village gate on Lunar Isle"),
			spell("Ourania Teleport", 71, "the Ourania Cave, just outside it",
				new Unseen("also needs having spoken to Baba Yaga on the Lunar spellbook, and a Seal of Passage until the "
					+ "elite Fremennik diary is done, which AI Chat doesn't check")),
			spell("Waterbirth Teleport", 72, "Waterbirth Island"),
			spell("Barbarian Teleport", 75, "the Barbarian Outpost"),
			spell("Khazard Teleport", 78, "Port Khazard"),
			spell("Fishing Guild Teleport", 85, "the Fishing Guild, outside its door"),
			spell("Catherby Teleport", 87, "Catherby, near the bank"),
			spell("Ice Plateau Teleport", 89, "the Ice Plateau, level 53 Wilderness")),
		new Book(3, "the Arceuus spellbook", "Arceuus spellbook", null,
			spell("Arceuus Library Teleport", 6, "Great Kourend, north of the Arceuus Library"),
			spell("Draynor Manor Teleport", 17, "the front doors of Draynor Manor"),
			spell("Battlefront Teleport", 23, "the Battlefront, in the Kebos Lowlands"),
			spell("Mind Altar Teleport", 28, "the entrance of the Mind Altar"),
			spell("Respawn Teleport", 34, "their respawn point"),
			spell("Salve Graveyard Teleport", 40, "the graveyard at the entrance to Mort Myre Swamp",
				done(Quest.PRIEST_IN_PERIL), DREZEL),
			spell("Fenkenstrain's Castle Teleport", 48, "Fenkenstrain's Castle", done(Quest.PRIEST_IN_PERIL), DREZEL),
			spell("West Ardougne Teleport", 61, "West Ardougne", done(Quest.BIOHAZARD)),
			spell("Harmony Island Teleport", 65, "Harmony Island", done(Quest.THE_GREAT_BRAIN_ROBBERY)),
			spell("Cemetery Teleport", 71, "the Forgotten Cemetery, level 31 Wilderness"),
			spell("Barrows Teleport", 83, "the Barrows", done(Quest.PRIEST_IN_PERIL), DREZEL),
			spell("Ape Atoll Teleport", 90, "the entrance of the Ape Atoll Dungeon", done(Quest.MONKEY_MADNESS_I))),
	};

	/** One spellbook, by the value of its varbit (Shortest Path and Quest Helper agree: 0 to 3). */
	private static final class Book
	{
		final int id;
		/** As a sentence names it: "they're on the Lunar spellbook". */
		final String name;
		/** Its Wiki page, which lists every spell with its runes. */
		final String page;
		final Way[] spells;

		Book(int id, String name, String page, Quest quest, Way... spells)
		{
			this.id = id;
			this.name = name;
			this.page = page;
			// Every spell of the book needs its quest (Desert Treasure I, Lunar Diplomacy): being on the book all but says
			// so, but a line shouldn't claim more than the game says.
			this.spells = quest == null ? spells : withQuest(spells, quest);
		}

		private static Way[] withQuest(Way[] spells, Quest quest)
		{
			Way[] out = new Way[spells.length];
			for (int i = 0; i < spells.length; i++)
			{
				Need[] needs = Arrays.copyOf(spells[i].needs, spells[i].needs.length + 1);
				needs[spells[i].needs.length] = done(quest);
				out[i] = new Way(spells[i].name, spells[i].where, spells[i].magic, needs);
			}
			return out;
		}
	}

	/**
	 * The diary tiers that change how the player travels without a reward item, by the Wiki's pages for the fairy rings
	 * and each spell. Reward items (the Ardougne cloak, Karamja gloves and so on) and the diaries' other perks aren't
	 * here.
	 */
	private static final Way[] DIARY_TABLE = {
		way("Fairy rings without a dramen or lunar staff", null, NO_STAFF_NEEDED),
		way("Varrock Teleport can be switched to the Grand Exchange", null,
			new DiaryTier(VarbitID.VARROCK_DIARY_MEDIUM_COMPLETE, "the medium Varrock diary")),
		way("Camelot Teleport can be switched to Seers' Village bank", null,
			new DiaryTier(VarbitID.KANDARIN_DIARY_HARD_COMPLETE, "the hard Kandarin diary")),
		way("Watchtower Teleport can be switched to the centre of Yanille", null,
			new DiaryTier(VarbitID.ARDOUGNE_DIARY_HARD_COMPLETE, "the hard Ardougne diary")),
	};

	/**
	 * Teleport jewellery whose charges are part of its name ("Games necklace(3)"), so reading the name reads the
	 * charges: the names as the Wiki gives them, and the Wiki page with each one's destinations. The trimmed glory and
	 * imbued ring of wealth ("Amulet of glory (t4)", "Ring of wealth (i5)") count as the plain one's kind.
	 */
	private static final String[] JEWELLERY = {
		"Amulet of glory", "Games necklace", "Ring of dueling", "Skills necklace", "Combat bracelet", "Ring of wealth",
		"Digsite pendant", "Necklace of passage", "Burning amulet", "Slayer ring", "Ring of returning",
	};
	/** Jewellery that never runs out, by its exact name, which is also its Wiki page. */
	private static final Set<String> ETERNAL = new LinkedHashSet<>(Arrays.asList("Amulet of eternal glory",
		"Slayer ring (eternal)"));
	/** "Games necklace(3)", "Digsite pendant (5)", "Amulet of glory (t4)", "Ring of wealth (i5)". */
	private static final Pattern CHARGED = Pattern.compile("^(.+?) ?\\((?:t|i)?(\\d+)\\)$");
	/** "Amulet of glory", "Amulet of glory (t)", "Ring of wealth (i)": out of charges. */
	private static final Pattern UNCHARGED = Pattern.compile("^(.+?)(?: \\((?:t|i)\\))?$");

	/** What the tables read, so GameData asks the game for exactly that, and nothing a table doesn't use. */
	static final Set<Quest> QUESTS;
	static final Set<Integer> VARBITS;
	static final Set<Integer> VARPS;

	static
	{
		Set<Quest> quests = EnumSet.noneOf(Quest.class);
		Set<Integer> varbits = new TreeSet<>();
		Set<Integer> varps = new TreeSet<>();
		varbits.add(VarbitID.SPELLBOOK);
		List<Need> needs = new ArrayList<>(Arrays.asList(FAIRY_RING_ACCESS, NO_STAFF_NEEDED, SPIRIT_TREE_ACCESS, GLIDER_ACCESS));
		List<Way> ways = new ArrayList<>(Arrays.asList(SPIRIT_TREES_TABLE));
		ways.addAll(Arrays.asList(GLIDERS_TABLE));
		ways.addAll(Arrays.asList(DIARY_TABLE));
		for (Book b : BOOKS)
		{
			ways.addAll(Arrays.asList(b.spells));
		}
		for (Way w : ways)
		{
			needs.addAll(Arrays.asList(w.needs));
		}
		for (Need n : needs)
		{
			n.reads(quests, varbits, varps);
		}
		QUESTS = Collections.unmodifiableSet(quests);
		VARBITS = Collections.unmodifiableSet(varbits);
		VARPS = Collections.unmodifiableSet(varps);
	}

	private Transports()
	{
	}

	// ------------------------------------------------------------------
	// Writing up for the model (any thread)
	// ------------------------------------------------------------------

	/** Whether an answer for {@code category} (null for every kind) says anything about the player's items. */
	static boolean needsItems(String category)
	{
		return category == null || FAIRY_RINGS.equals(category) || ITEMS.equals(category);
	}

	/**
	 * The answer: one kind ({@code category}, one of {@link #CATEGORIES}), or every kind when it's null, cut at
	 * {@link #MAX_CHARS}. {@code now} dates the bank as last seen.
	 */
	static String text(State s, String category, long now)
	{
		return text(s, category, now, MAX_CHARS);
	}

	/** {@code maxChars}: the cut, which tests make small. Whole kinds are left out from the end, and named. */
	static String text(State s, String category, long now, int maxChars)
	{
		StringBuilder out = new StringBuilder("What the player can use to travel, read from their game just now. \"Unknown\" "
			+ "means AI Chat can't tell from the game, not that they can't: say so or ask them rather than guess either "
			+ "way. Suggest only what's available.");
		List<String> kinds = category == null ? CATEGORIES : Collections.singletonList(category);
		List<String> left = new ArrayList<>();
		String coverage = category == null ? "\n\n" + coverage() : "";
		for (String kind : kinds)
		{
			String section = "\n\n" + section(s, kind, now);
			// The first kind always goes in: each is short on its own, the jewellery a line per kind.
			boolean first = kind.equals(kinds.get(0));
			if (first || left.isEmpty() && out.length() + section.length() + coverage.length() <= maxChars)
			{
				out.append(section);
			}
			else
			{
				left.add(kind);
			}
		}
		if (!left.isEmpty())
		{
			out.append("\n\nLeft out to keep this short: ").append(String.join(", ", left))
				.append(". Ask again with one of those as the category.");
		}
		return out.append(coverage).toString();
	}

	private static String section(State s, String kind, long now)
	{
		switch (kind)
		{
			case FAIRY_RINGS:
				return fairyRings(s, now);
			case SPIRIT_TREES:
				return spiritTrees(s);
			case GLIDERS:
				return gliders(s);
			case SPELLS:
				return spells(s);
			case ITEMS:
				return items(s, now);
			default:
				return diaries(s);
		}
	}

	/** What a whole answer covers, and what it doesn't, so a gap isn't taken for "they can't". */
	private static String coverage()
	{
		return "Covered here: fairy rings, spirit trees, gnome gliders, the teleport spells on their current spellbook, "
			+ "teleport jewellery (while \"Share items and gear\" is on), and the diary unlocks listed. Not covered, so "
			+ "check with the player or the Wiki before suggesting them: agility shortcuts, home teleports, other teleport "
			+ "items (tablets, scrolls, capes, diary rewards, quest items such as the ectophial or royal seed pod), house "
			+ "portals and jewellery boxes, charter ships and boats, canoes, minecarts, quetzals, magic carpets, balloons, "
			+ "mushtrees and the other networks.";
	}

	/**
	 * The rings in one status line: "available" only when nothing more is needed (the elite Lumbridge & Draynor diary
	 * done); otherwise "unlocked", with the staff that's needed and whether and where they have one, so the model doesn't
	 * take permission alone for a ring they can use.
	 */
	private static String fairyRings(State s, long now)
	{
		Check access = FAIRY_RING_ACCESS.check(s);
		if (access.verdict == Verdict.UNAVAILABLE)
		{
			return "Fairy rings: " + status(access) + ".";
		}
		Check noStaff = NO_STAFF_NEEDED.check(s);
		boolean staffless = noStaff.verdict == Verdict.AVAILABLE;
		String staff = "a dramen or lunar staff wielded (" + staves(s.held, now) + ")"
			+ (noStaff.verdict == Verdict.UNKNOWN ? ", unless they've done the elite Lumbridge & Draynor diary, which "
				+ "couldn't be read" : "");
		StringBuilder out = new StringBuilder("Fairy rings: ");
		if (access.verdict == Verdict.AVAILABLE)
		{
			out.append(staffless ? "available: they have the Fairy Godfather's permission. No staff needed: they've done "
				+ "the elite Lumbridge & Draynor diary." : "unlocked; needs " + staff + ".");
			out.append(" Some rings need more, such as a quest or having been somewhere first, noted against their codes "
				+ "on the Wiki. For the codes and where each ring goes, read the Wiki page \"Fairy ring\": its sections "
				+ "\"A\" combinations to \"D\" combinations list the codes by first letter, with those notes, and \"Closest "
				+ "points of interest to fairy rings\" names the ring nearest a place.");
			return out.toString();
		}
		out.append(status(access)).append('.');
		return out.append(staffless ? " No staff needed: they've done the elite Lumbridge & Draynor diary."
			: " Using one also needs " + staff + ".").toString();
	}

	/**
	 * For the staff's parentheses: which staves they have and where, from their items, the bank dated; or that none was
	 * found and where AI Chat looked; or why it didn't look.
	 */
	private static String staves(Held held, long now)
	{
		if (held == null)
		{
			return "none found: \"Share items and gear\" is off, so their items weren't checked";
		}
		List<String> found = new ArrayList<>();
		for (Place p : places(held))
		{
			List<String> here = new ArrayList<>();
			for (GameData.ItemLine line : p.lines)
			{
				if (!line.noted && STAVES.contains(line.name))
				{
					here.add(line.name);
				}
			}
			if (!here.isEmpty())
			{
				// The bank is as it was when last opened, which may be a while ago: say when, as the jewellery does.
				boolean bank = held.bank != null && p.lines == held.bank.lines;
				found.add(String.join(", ", here) + " " + p.name
					+ (bank ? " as last seen " + GameData.ago(now - held.bank.seenAt) : ""));
			}
		}
		if (!found.isEmpty())
		{
			return "they have: " + String.join("; ", found);
		}
		if (held.bank == null)
		{
			return "none found in what they wear or carry; their bank hasn't been seen since AI Chat could look, so they "
				+ "can open it once";
		}
		return "none found in what they wear, carry or keep in their bank, as last seen "
			+ GameData.ago(now - held.bank.seenAt);
	}

	private static String spiritTrees(State s)
	{
		Check access = SPIRIT_TREE_ACCESS.check(s);
		StringBuilder out = new StringBuilder("Spirit trees: ").append(status(access));
		if (access.verdict != Verdict.AVAILABLE)
		{
			return out.append('.').toString();
		}
		out.append(", tree by tree:");
		lines(out, s, SPIRIT_TREES_TABLE);
		// A grown tree is a farming patch, and a patch's state is only loaded while the player is near it.
		out.append("\n- Spirit trees they've grown themselves (Port Sarim, Etceteria, Brimhaven, Hosidius, the Farming "
			+ "Guild) or planted in their house: unknown: AI Chat can only see a farming patch while the player is near "
			+ "it, so it can't tell whether they've planted one. Ask them.");
		return out.append("\nFor where each tree stands, read the Wiki page \"Spirit tree\", section \"Spirit tree "
			+ "locations\".").toString();
	}

	private static String gliders(State s)
	{
		Check access = GLIDER_ACCESS.check(s);
		StringBuilder out = new StringBuilder("Gnome gliders: ").append(status(access));
		if (access.verdict != Verdict.AVAILABLE)
		{
			return out.append('.').toString();
		}
		out.append(", glider by glider:");
		lines(out, s, GLIDERS_TABLE);
		return out.append("\nFor where each glider stands, read the Wiki page \"Gnome glider\", section "
			+ "\"Transportation\".").toString();
	}

	private static String spells(State s)
	{
		Integer id = s.varbits.get(VarbitID.SPELLBOOK);
		Book book = null;
		for (Book b : BOOKS)
		{
			if (id != null && b.id == id)
			{
				book = b;
			}
		}
		if (book == null)
		{
			return "Teleport spells: unknown: their spellbook couldn't be read, so its teleports aren't listed.";
		}
		StringBuilder out = new StringBuilder("Teleport spells: they're on ").append(book.name)
			.append(", with Magic ").append(magicNow(s)).append(". Each spell's level is compared with the level they "
				+ "have now, since boosts count for casting. Runes aren't checked, nor the other spellbooks' teleports, "
				+ "which need a trip to switch book first:");
		boolean any = lines(out, s, book.spells);
		if (any)
		{
			out.append("\nThe Wiki page \"").append(book.page).append("\" lists every spell with its runes.");
		}
		return out.toString();
	}

	/**
	 * The jewellery they hold, a line per kind ("Games necklace: (8) x3 in their bank, (4) in their inventory"), with
	 * its charges from its name and where each piece is; or why that wasn't checked. Noted pieces are counted and said
	 * to be noted, since a bank unnotes them; uncharged ones are said to be uncharged.
	 */
	private static String items(State s, long now)
	{
		StringBuilder out = new StringBuilder("Teleport jewellery: ");
		if (s.held == null)
		{
			return out.append("left out: \"Share items and gear\" is off. They can turn it on for AI Chat to check what "
				+ "they hold.").toString();
		}
		Map<String, List<Piece>> kinds = new LinkedHashMap<>();
		for (String kind : JEWELLERY)
		{
			kinds.put(kind, new ArrayList<>());
		}
		for (String kind : ETERNAL)
		{
			kinds.put(kind, new ArrayList<>());
		}
		List<Place> places = places(s.held);
		for (int place = 0; place < places.size(); place++)
		{
			Place p = places.get(place);
			for (GameData.ItemLine line : p.lines)
			{
				Jewel j = jewel(line.name);
				if (j != null)
				{
					kinds.get(j.page).add(new Piece(line, j, place, p.name));
				}
			}
		}

		List<String> lines = new ArrayList<>();
		Set<String> pages = new LinkedHashSet<>();
		boolean uncharged = false;
		boolean noted = false;
		for (Map.Entry<String, List<Piece>> kind : kinds.entrySet())
		{
			List<Piece> pieces = kind.getValue();
			if (pieces.isEmpty())
			{
				continue;
			}
			// Nearest first (worn, carried, banked), then ready to use before noted, then the most charges.
			pieces.sort(Comparator.comparingInt((Piece p) -> p.place).thenComparing(p -> p.noted)
				.thenComparing(p -> -p.charges));
			for (Piece p : pieces)
			{
				uncharged |= p.charges == 0;
				noted |= p.noted;
				if (p.charges != 0)
				{
					pages.add(kind.getKey());
				}
			}
			int more = pieces.size() - PIECES_PER_KIND;
			lines.add(kind.getKey() + ": " + said(pieces.subList(0, Math.min(pieces.size(), PIECES_PER_KIND)))
				+ (more > 0 ? ", and " + more + " more not listed" : "")
				+ (ETERNAL.contains(kind.getKey()) ? "; it never runs out" : ""));
		}

		String seen = "what they wear and carry, " + bankChecked(s.held.bank, now);
		if (lines.isEmpty())
		{
			out.append("none found in ").append(seen).append('.');
		}
		else
		{
			out.append("found in ").append(seen).append(". A line per kind, with the charges each piece's name shows, "
				+ "how many, and where:");
			for (String line : lines)
			{
				out.append("\n- ").append(line);
			}
			if (uncharged)
			{
				out.append("\nUncharged ones have to be recharged before they teleport.");
			}
			if (noted)
			{
				out.append("\nNoted ones have to be unnoted at a bank before they teleport.");
			}
		}
		if (!pages.isEmpty())
		{
			out.append("\nFor where each goes, read its Wiki page: ").append(quoted(pages)).append('.');
		}
		return out.append("\nThe jewellery AI Chat looks for: ").append(String.join(", ", JEWELLERY)).append(", ")
			.append(String.join(", ", ETERNAL)).append(". Other teleport items aren't covered; get_equipment, "
				+ "get_inventory and get_bank show everything they hold.").toString();
	}

	private static String diaries(State s)
	{
		StringBuilder out = new StringBuilder("Travel unlocks from achievement diaries:");
		lines(out, s, DIARY_TABLE);
		return out.append("\nThe diaries' reward items (the Ardougne cloak, Karamja gloves and the like) and their other "
			+ "perks aren't covered; get_achievement_diaries says which tiers are done.").toString();
	}

	/** A line per way, with what the game says of it; true when any is available. */
	private static boolean lines(StringBuilder out, State s, Way[] ways)
	{
		boolean any = false;
		for (Way w : ways)
		{
			Check c = check(s, w.needs);
			any |= c.verdict == Verdict.AVAILABLE || c.verdict == Verdict.BOOSTED;
			out.append("\n- ").append(w.name);
			if (w.magic > 0)
			{
				out.append(" (").append(w.magic).append(" Magic)");
			}
			if (w.where != null)
			{
				out.append(w.magic > 0 ? ", to " : ", ").append(w.where);
			}
			out.append(": ").append(status(c));
		}
		return any;
	}

	/**
	 * All of {@code needs} together: as good as the worst. Not available says what's missing for certain, a level
	 * included, since an unknown next to it changes nothing; a level short says what's unknown too.
	 */
	private static Check check(State s, Need[] needs)
	{
		Verdict worst = Verdict.AVAILABLE;
		List<Check> checks = new ArrayList<>();
		for (Need n : needs)
		{
			Check c = n.check(s);
			checks.add(c);
			worst = c.verdict.compareTo(worst) > 0 ? c.verdict : worst;
		}
		if (worst == Verdict.AVAILABLE)
		{
			return MET;
		}
		if (worst == Verdict.BOOSTED)
		{
			// Everything else met: what the boost does is the one thing to say.
			for (Check c : checks)
			{
				if (c.verdict == Verdict.BOOSTED)
				{
					return c;
				}
			}
		}
		// The surest reason first: a quest not done, then a level short, then what can't be seen.
		Verdict least = worst == Verdict.UNAVAILABLE ? Verdict.LEVEL : Verdict.UNKNOWN;
		List<String> why = new ArrayList<>();
		for (int v = worst.ordinal(); v >= least.ordinal(); v--)
		{
			for (Check c : checks)
			{
				if (c.verdict.ordinal() == v)
				{
					why.add(c.why);
				}
			}
		}
		return new Check(worst, String.join("; ", why));
	}

	private static String status(Check c)
	{
		switch (c.verdict)
		{
			case AVAILABLE:
				return "available";
			case BOOSTED:
				return "available while boosted (" + c.why + ")";
			case UNKNOWN:
				return "unknown: " + c.why;
			case LEVEL:
				return c.why;
			default:
				return "not available: " + c.why;
		}
	}

	// ------------------------------------------------------------------
	// Items
	// ------------------------------------------------------------------

	/** Where items are: worn, carried, banked. */
	private static final class Place
	{
		final String name;
		final List<GameData.ItemLine> lines;

		Place(String name, List<GameData.ItemLine> lines)
		{
			this.name = name;
			this.lines = lines;
		}
	}

	private static List<Place> places(Held held)
	{
		List<Place> places = new ArrayList<>();
		places.add(new Place("worn", held.worn));
		places.add(new Place("in their inventory", held.carried));
		if (held.bank != null)
		{
			places.add(new Place("in their bank", held.bank.lines));
		}
		return places;
	}

	/** "and in their bank as last seen 5 minutes ago", or why the bank wasn't. */
	private static String bankChecked(GameData.Bank bank, long now)
	{
		if (bank == null)
		{
			return "with their bank not checked: it hasn't been seen since AI Chat could look (they can open it once)";
		}
		return "and in their bank as last seen " + GameData.ago(now - bank.seenAt);
	}

	/** One piece of teleport jewellery: its Wiki page, and its charges: 0 for none left, -1 for never running out. */
	static final class Jewel
	{
		final String page;
		final int charges;

		Jewel(String page, int charges)
		{
			this.page = page;
			this.charges = charges;
		}
	}

	/** The teleport jewellery an item name is, or null for anything else (a ring of wealth scroll included). */
	static Jewel jewel(String name)
	{
		if (name == null)
		{
			return null;
		}
		if (ETERNAL.contains(name))
		{
			return new Jewel(name, -1);
		}
		Matcher charged = CHARGED.matcher(name);
		if (charged.matches() && Arrays.asList(JEWELLERY).contains(charged.group(1)))
		{
			return new Jewel(charged.group(1), Integer.parseInt(charged.group(2)));
		}
		Matcher uncharged = UNCHARGED.matcher(name);
		if (uncharged.matches() && Arrays.asList(JEWELLERY).contains(uncharged.group(1)))
		{
			return new Jewel(uncharged.group(1), 0);
		}
		return null;
	}

	/** One piece (or stack) of teleport jewellery, where it is. */
	private static final class Piece
	{
		/** What its name adds to its kind's: "(8)", "(t4)", "(i)", or nothing. */
		final String suffix;
		final long quantity;
		/** 0 for worn, 1 for carried, 2 for banked: nearest first. */
		final int place;
		final String where;
		final boolean noted;
		/** As {@link Jewel#charges}: 0 for none left, -1 for never running out. */
		final int charges;

		Piece(GameData.ItemLine line, Jewel jewel, int place, String where)
		{
			this.suffix = line.name.substring(jewel.page.length()).trim();
			this.quantity = line.quantity;
			this.place = place;
			this.where = where;
			this.noted = line.noted;
			this.charges = jewel.charges;
		}

		/** "(8) x3", "(t) uncharged", "(4) x20 noted"; nothing for one of a kind that never runs out. */
		String label()
		{
			String label = charges == 0 ? (suffix.isEmpty() ? "" : suffix + " ") + "uncharged" : suffix;
			return (label + (quantity > 1 ? " x" + quantity : "") + (noted ? " noted" : "")).trim();
		}
	}

	/**
	 * "(8) x3, (5) in their bank, (4) in their inventory": where they are said once for each run of pieces kept in the
	 * same place, noted or not.
	 */
	private static String said(List<Piece> pieces)
	{
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < pieces.size(); i++)
		{
			Piece p = pieces.get(i);
			Piece next = i + 1 < pieces.size() ? pieces.get(i + 1) : null;
			String label = p.label();
			out.append(label);
			if (next == null || next.place != p.place || next.noted != p.noted)
			{
				out.append(label.isEmpty() ? "" : " ").append(p.where);
			}
			if (next != null)
			{
				out.append(", ");
			}
		}
		return out.toString();
	}

	/** "\"Amulet of glory\", \"Games necklace\"". */
	private static String quoted(Set<String> names)
	{
		List<String> q = new ArrayList<>();
		for (String n : names)
		{
			q.add("\"" + n + "\"");
		}
		return String.join(", ", q);
	}
}
