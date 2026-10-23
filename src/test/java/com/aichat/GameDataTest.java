package com.aichat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.Item;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** How the player's own game data is written up for the model, from plain values (no game needed). */
public class GameDataTest
{
	private static final long MINUTE = 60_000;

	private static GameData.ItemLine item(String name, long quantity, long price)
	{
		return new GameData.ItemLine(null, name, false, quantity, price);
	}

	private static GameData.ItemLine worn(String slot, String name, long quantity, long price)
	{
		return new GameData.ItemLine(slot, name, false, quantity, price);
	}

	private static int listed(String text)
	{
		int n = 0;
		for (String line : text.split("\n"))
		{
			n += line.startsWith("- ") ? 1 : 0;
		}
		return n;
	}

	@Test
	public void itemLinesShowNamesAmountsAndPrices()
	{
		assertEquals("Abyssal whip (1,402,330 gp)", GameData.itemText(item("Abyssal whip", 1, 1_402_330)));
		assertEquals("Shark x10 (1,032 gp each, 10,320 gp total)", GameData.itemText(item("Shark", 10, 1032)));
		// Coins are 1 gp each; the line says what the stack is worth instead.
		assertEquals("Coins x1,250,000 (1,250,000 gp)", GameData.itemText(item("Coins", 1_250_000, 1)));
		assertEquals("Dragon bones (noted) x500 (2,100 gp each, 1,050,000 gp total)",
			GameData.itemText(new GameData.ItemLine(null, "Dragon bones", true, 500, 2100)));
		// Untradeable (or prices not loaded yet): no price at all, rather than "0 gp".
		assertEquals("Graceful hood", GameData.itemText(item("Graceful hood", 1, 0)));
		assertEquals("Weapon: Abyssal whip (1,402,330 gp)", GameData.itemText(worn("Weapon", "Abyssal whip", 1, 1_402_330)));
		assertEquals("Ammo: Rune arrow x512 (90 gp each, 46,080 gp total)", GameData.itemText(worn("Ammo", "Rune arrow", 512, 90)));
	}

	@Test
	public void bigStacksDontOverflow()
	{
		assertEquals("Platinum token x2,147,483,647 (1,000 gp each, 2,147,483,647,000 gp total)",
			GameData.itemText(item("Platinum token", Integer.MAX_VALUE, 1000)));
	}

	@Test
	public void equipmentListsEachSlotAndTheTotal()
	{
		String text = GameData.equipmentText(Arrays.asList(
			worn("Head", "Helm of neitiznot", 1, 52_000),
			worn("Weapon", "Abyssal whip", 1, 1_400_000),
			worn("Cape", "Fire cape", 1, 0),
			worn("Ammo", "Rune arrow", 100, 90)));
		assertTrue(text, text.startsWith("The player's worn equipment, with Grand Exchange prices from RuneLite:\n"));
		assertTrue(text, text.contains("\n- Head: Helm of neitiznot (52,000 gp)\n- Weapon: Abyssal whip (1,400,000 gp)\n"
			+ "- Cape: Fire cape\n- Ammo: Rune arrow x100 (90 gp each, 9,000 gp total)\n"));
		assertTrue(text, text.endsWith("Total value at Grand Exchange prices: 1,461,000 gp (items without a price not counted)."));
	}

	@Test
	public void totalLeavesOutTheNoteWhenEverythingHasAPrice()
	{
		String text = GameData.equipmentText(Collections.singletonList(worn("Weapon", "Abyssal whip", 1, 1_400_000)));
		assertTrue(text, text.endsWith("\nTotal value at Grand Exchange prices: 1,400,000 gp."));
	}

	@Test
	public void nothingWornOrPriced()
	{
		assertEquals("The player isn't wearing or wielding anything.", GameData.equipmentText(Collections.emptyList()));
		// No total line at all when nothing has a price.
		String text = GameData.equipmentText(Collections.singletonList(worn("Cape", "Fire cape", 1, 0)));
		assertFalse(text, text.contains("Total"));
	}

	@Test
	public void inventoryAddsUpStacksAndCountsSlots()
	{
		Item[] inv = {
			new Item(385, 1),
			new Item(-1, 0),
			null,
			new Item(995, 5000),
			new Item(385, 1),
			new Item(537, 100),
		};
		Map<Integer, Long> stacks = GameData.stacks(inv);
		assertEquals(Arrays.asList(385, 995, 537), new ArrayList<>(stacks.keySet()));
		assertEquals(Long.valueOf(2), stacks.get(385));
		assertEquals(Long.valueOf(5000), stacks.get(995));
		assertEquals(4, GameData.slotsUsed(inv));

		String text = GameData.inventoryText(new GameData.Items(Arrays.asList(
			item("Shark", 2, 1000),
			item("Coins", 5000, 1),
			new GameData.ItemLine(null, "Dragon bones", true, 100, 2000)), 4));
		assertTrue(text, text.startsWith("The player's inventory (4 of 28 slots used), with Grand Exchange prices from RuneLite:\n"));
		assertTrue(text, text.contains("\n- Shark x2 (1,000 gp each, 2,000 gp total)\n- Coins x5,000 (5,000 gp)\n"
			+ "- Dragon bones (noted) x100 (2,000 gp each, 200,000 gp total)\n"));
		assertTrue(text, text.endsWith("Total value at Grand Exchange prices: 207,000 gp."));
	}

	@Test
	public void emptyInventory()
	{
		assertEquals(0, GameData.slotsUsed(new Item[28]));
		assertEquals("The player's inventory is empty (all 28 slots free).",
			GameData.inventoryText(new GameData.Items(Collections.emptyList(), 0)));
	}

	@Test
	public void bankSnapshotSkipsPlaceholdersFillersAndEmptySlots()
	{
		Item[] bank = {
			new Item(995, 1_000_000),
			// A placeholder: none of the item left.
			new Item(4151, 0),
			new Item(ItemID.BANK_FILLER, 1),
			new Item(-1, 0),
			null,
			new Item(385, 250),
		};
		GameData.BankSnapshot seen = GameData.BankSnapshot.of(bank, 42L, 1000L);
		assertTrue(Arrays.equals(new int[]{995, 385}, seen.ids));
		assertTrue(Arrays.equals(new int[]{1_000_000, 250}, seen.quantities));
		assertEquals(42L, seen.accountHash);
		assertEquals(1000L, seen.seenAt);
	}

	@Test
	public void bankSaysWhenItWasSeen()
	{
		GameData.Bank bank = new GameData.Bank(Arrays.asList(item("Coins", 1_000_000, 1), item("Shark", 250, 1000),
			item("Quest point cape", 1, 0)), 0);
		String text = GameData.bankText(bank, null, 12 * MINUTE);
		assertTrue(text, text.startsWith("The player's bank (as last seen 12 minutes ago): 3 items, placeholders left out. "
			+ "Grand Exchange prices from RuneLite:\n"));
		assertTrue(text, text.contains("\n- Coins x1,000,000 (1,000,000 gp)\n- Shark x250 (1,000 gp each, 250,000 gp total)\n"
			+ "- Quest point cape\n"));
		assertTrue(text, text.endsWith("Total value of the bank at Grand Exchange prices: 1,250,000 gp (items without a price not counted)."));
	}

	@Test
	public void emptyBank()
	{
		assertEquals("The player's bank was empty (as last seen less than a minute ago).",
			GameData.bankText(new GameData.Bank(Collections.emptyList(), 0), null, 5000));
	}

	@Test
	public void bankSearchIgnoresCase()
	{
		GameData.Bank bank = new GameData.Bank(Arrays.asList(item("Rune platebody", 1, 38_000), item("Shark", 250, 1000),
			item("Air rune", 5000, 4), item("Runite bar", 10, 12_000)), 0);
		String text = GameData.bankText(bank, "RUNE", 2 * 60 * MINUTE);
		assertTrue(text, text.startsWith("Items in the player's bank with \"RUNE\" in their name (as last seen about 2 hours ago), "
			+ "with Grand Exchange prices from RuneLite:\n"));
		assertEquals(2, listed(text));
		assertTrue(text, text.contains("\n- Rune platebody (38,000 gp)\n- Air rune x5,000 (4 gp each, 20,000 gp total)\n"));
		assertFalse(text, text.contains("Shark"));
		assertFalse(text, text.contains("Runite"));
		assertTrue(text, text.endsWith("Total value of these at Grand Exchange prices: 58,000 gp."));
	}

	@Test
	public void bankSearchWithNoMatch()
	{
		GameData.Bank bank = new GameData.Bank(Arrays.asList(item("Shark", 250, 1000), item("Coins", 10, 1)), 0);
		assertEquals("Nothing in the player's bank has \"twisted bow\" in its name (as last seen 1 minute ago; 2 items in "
			+ "all). Try a shorter or different search.", GameData.bankText(bank, "twisted bow", MINUTE));
	}

	@Test
	public void longBanksAreCutWithANote()
	{
		List<GameData.ItemLine> lines = new ArrayList<>();
		for (int i = 1; i <= 350; i++)
		{
			lines.add(item("Item " + i, 1, 10));
		}
		String text = GameData.bankText(new GameData.Bank(lines, 0), null, 0);
		assertEquals(GameData.BANK_LINES, listed(text));
		assertTrue(text, text.contains("\n- Item 300 (10 gp)\n...and 50 more items not listed. Ask again with search to "
			+ "find particular items.\n"));
		assertFalse(text, text.contains("Item 301"));
		// The total still covers the whole bank.
		assertTrue(text, text.endsWith("Total value of the bank at Grand Exchange prices: 3,500 gp."));

		String found = GameData.bankText(new GameData.Bank(lines, 0), "item 1", 0);
		// "Item 1", "Item 10".."Item 19", "Item 100".."Item 199": 111, all listed.
		assertEquals(111, listed(found));
		assertFalse(found, found.contains("not listed"));
	}

	@Test
	public void agoReadsNaturally()
	{
		assertEquals("less than a minute ago", GameData.ago(59_999));
		assertEquals("less than a minute ago", GameData.ago(-5000));
		assertEquals("1 minute ago", GameData.ago(MINUTE));
		assertEquals("59 minutes ago", GameData.ago(59 * MINUTE + 59_000));
		assertEquals("about 1 hour ago", GameData.ago(60 * MINUTE));
		assertEquals("about 3 hours ago", GameData.ago(200 * MINUTE));
	}

	@Test
	public void slayerTask()
	{
		assertEquals("The player's Slayer task: Abyssal demons, 87 left of 150. Slayer points: 1,234. Task streak: 56 "
				+ "(Krystilia's and Mortimer's tasks have their own streaks, not counted here).",
			GameData.slayerText(new GameData.SlayerTask("Abyssal demons", 87, 150, null, 1234, 56)));
		assertTrue(GameData.slayerText(new GameData.SlayerTask("Kalphite", 30, 0, "Kalphite Lair", 10, 2))
			.startsWith("The player's Slayer task: Kalphite, 30 left, to be done in Kalphite Lair. Slayer points: 10."));
		assertTrue(GameData.slayerText(new GameData.SlayerTask(null, 0, 0, null, 0, 0))
			.startsWith("The player has no Slayer task right now. Slayer points: 0. Task streak: 0"));
		assertTrue(GameData.slayerText(new GameData.SlayerTask(null, 12, 40, null, 0, 0))
			.startsWith("The player has a Slayer task with 12 left, but which monster it is couldn't be read."));
	}

	@Test
	public void diariesFollowTheGamesOwnCheck()
	{
		Map<Integer, Integer> varbits = new HashMap<>();
		varbits.put(VarbitID.ARDOUGNE_DIARY_EASY_COMPLETE, 1);
		varbits.put(VarbitID.ARDOUGNE_DIARY_MEDIUM_COMPLETE, 1);
		varbits.put(VarbitID.ARDOUGNE_DIARY_HARD_COMPLETE, 1);
		varbits.put(VarbitID.ARDOUGNE_DIARY_ELITE_COMPLETE, 1);
		varbits.put(VarbitID.VARROCK_DIARY_EASY_COMPLETE, 1);
		varbits.put(VarbitID.VARROCK_DIARY_HARD_COMPLETE, 1);
		// Karamja's first three tiers are 2 when done; 1 there only means started.
		varbits.put(VarbitID.ATJUN_EASY_DONE, 2);
		varbits.put(VarbitID.ATJUN_MED_DONE, 1);
		varbits.put(VarbitID.KARAMJA_DIARY_ELITE_COMPLETE, 0);
		List<GameData.Diary> diaries = GameData.diaries(id -> varbits.getOrDefault(id, 0));
		assertEquals(12, diaries.size());

		String text = GameData.diaryText(diaries);
		assertTrue(text, text.startsWith("The player's achievement diaries, by tier:\n- Ardougne: all done\n- Desert: none done\n"));
		assertTrue(text, text.contains("\n- Karamja: Easy done; Medium, Hard, Elite not done\n"));
		assertTrue(text, text.contains("\n- Varrock: Easy, Hard done; Medium, Elite not done\n"));
		assertTrue(text, text.contains("\n- Kourend & Kebos: none done\n"));
		assertTrue(text, text.contains("\n- Wilderness: none done\n"));
		assertTrue(text, text.endsWith("\nCompleted 7 of 48 tiers."));
	}

	private static GameData.QuestLine quest(String name, QuestState state)
	{
		return new GameData.QuestLine(name, state);
	}

	/** Two completed, one started, one untouched. */
	private static List<GameData.QuestLine> someQuests()
	{
		return Arrays.asList(quest("Cook's Assistant", QuestState.FINISHED),
			quest("Dragon Slayer I", QuestState.FINISHED),
			quest("Dragon Slayer II", QuestState.IN_PROGRESS),
			quest("Desert Treasure II - The Fallen Empire", QuestState.NOT_STARTED));
	}

	/** Every quest the client knows, by its real names, none of them started. */
	private static List<GameData.QuestLine> everyQuest()
	{
		List<GameData.QuestLine> quests = new ArrayList<>();
		for (Quest q : Quest.values())
		{
			quests.add(quest(q.getName(), QuestState.NOT_STARTED));
		}
		return quests;
	}

	/** The names a filter picks out of the client's real quest list. */
	private static List<String> found(String filter)
	{
		List<String> names = new ArrayList<>();
		for (String line : GameData.questText(everyQuest(), filter).split("\n"))
		{
			if (line.startsWith("- "))
			{
				names.add(line.substring(2, line.lastIndexOf(": ")));
			}
		}
		return names;
	}

	private static final String COUNTED = " These counts take in miniquests and each part of Recipe for Disaster, so "
		+ "they won't match the game's own quest count.";

	@Test
	public void questsAreCountedBeforeTheyAreListed()
	{
		// The counts come first however the filter went, so the answer says where the player stands either way, and
		// they say what they count: the client's list isn't the game's quest count.
		String all = GameData.questText(someQuests(), null);
		assertTrue(all, all.startsWith("The player's quests: 2 of 4 completed, 1 in progress, 1 not started." + COUNTED
			+ "\nEvery quest:\n- Cook's Assistant: completed\n- Dragon Slayer I: completed\n- Dragon Slayer II: in progress"
			+ "\n- Desert Treasure II - The Fallen Empire: not started"));
		assertEquals(4, listed(all));
		assertEquals("The player's quests: 0 of 0 completed, 0 in progress, 0 not started." + COUNTED + "\nNo quests "
			+ "could be read, which shouldn't happen; answer without them.", GameData.questText(Collections.emptyList(), null));
	}

	@Test
	public void everyQuestTheClientKnowsFits()
	{
		// The client lists its newest quests last: a cut anywhere short of them all would drop those first. The bound is
		// far above the client's list; were the list ever to pass it, the answer says so, and a new quest in a RuneLite
		// release mustn't fail the build either way.
		int known = Quest.values().length;
		int shown = Math.min(known, GameData.QUEST_LINES);
		String all = GameData.questText(everyQuest(), null);
		assertEquals(shown, listed(all));
		assertEquals(all, known > GameData.QUEST_LINES, all.contains("not listed"));
		assertTrue(all, all.contains("\n- While Guthix Sleeps: not started\n"));
		assertTrue(all, all.contains("\n- Desert Treasure II - The Fallen Empire: not started\n"));
		assertEquals(shown, listed(GameData.questText(everyQuest(), "incomplete")));
		assertEquals(shown, listed(GameData.questText(everyQuest(), "not started")));
	}

	/**
	 * {@code filter} finds each of {@code names} in the client's real quest list, and none of {@code not}. The list
	 * grows with every RuneLite release, so this never says nothing else is found.
	 */
	private static void finds(String filter, List<String> names, String... not)
	{
		List<String> found = found(filter);
		assertTrue(filter + " found " + found, found.containsAll(names));
		for (String n : not)
		{
			assertFalse(filter + " found " + n, found.contains(n));
		}
	}

	@Test
	public void questNamesAreMatchedLoosely()
	{
		String found = GameData.questText(someQuests(), "DRAGON slayer");
		assertTrue(found, found.contains("\nThe 2 quests matching \"DRAGON slayer\":"
			+ "\n- Dragon Slayer I: completed\n- Dragon Slayer II: in progress"));
		// Part of a word in the middle of a name counts too.
		finds("fallen", List.of("Desert Treasure II - The Fallen Empire", "Fallen From Grace"));

		// Written other ways than the game writes them: "&", numerals, punctuation, apostrophes, spaces.
		finds("Romeo and Juliet", List.of("Romeo & Juliet"));
		finds("Dragon Slayer 2", List.of("Dragon Slayer II"), "Dragon Slayer I");
		finds("dragon slayer 1", List.of("Dragon Slayer I"), "Dragon Slayer II");
		finds("desert treasure", List.of("Desert Treasure I", "Desert Treasure II - The Fallen Empire"));
		finds("Another Slice of HAM", List.of("Another Slice of H.A.M."));
		finds("Cook’s Assistant", List.of("Cook's Assistant"));
		finds("cooks assistant", List.of("Cook's Assistant"));
		finds("shades of mortton", List.of("Shades of Mort'ton"));
		finds("Fairy Tale 1", List.of("Fairytale I - Growing Pains"), "Fairytale II - Cure a Queen");
		finds("Mourning's End: Part 2", List.of("Mourning's End Part II"), "Mourning's End Part I");
		finds("wartface and bentnoze", List.of("Recipe for Disaster - Wartface & Bentnoze"),
			"Recipe for Disaster - Mountain Dwarf");
		// The "X" of "X Marks the Spot" isn't a numeral.
		finds("x marks", List.of("X Marks the Spot"));
		// A Wiki page title: the part before the "/" is the quest, and for Recipe for Disaster that's every part.
		finds("Dragon Slayer II/Quick guide", List.of("Dragon Slayer II"), "Dragon Slayer I");
		finds("Recipe for Disaster/Another Cook's Quest", List.of("Recipe for Disaster",
			"Recipe for Disaster - Another Cook's Quest", "Recipe for Disaster - Mountain Dwarf",
			"Recipe for Disaster - Culinaromancer"));

		String missing = GameData.questText(someQuests(), "shield of arrav");
		assertTrue(missing, missing.endsWith("No quest name matches \"shield of arrav\". Try a shorter or different "
			+ "filter, or \"incomplete\" for every quest they haven't finished."));
		assertEquals(0, listed(missing));
		// Nothing left to match on is every quest, not a list headed by a name it never matched.
		assertTrue(GameData.questText(someQuests(), "?!").contains("\nEvery quest:\n"));
	}

	@Test
	public void questFiltersForAStateSayWhatTheyFound()
	{
		assertTrue(GameData.questText(someQuests(), "in progress")
			.endsWith("The 1 quest they've started and not finished:\n- Dragon Slayer II: in progress"));
		assertTrue(GameData.questText(someQuests(), "incomplete")
			.endsWith("The 2 quests they haven't finished:\n- Dragon Slayer II: in progress"
				+ "\n- Desert Treasure II - The Fallen Empire: not started"));
		assertTrue(GameData.questText(someQuests(), "not started")
			.endsWith("The 1 quest they haven't started:\n- Desert Treasure II - The Fallen Empire: not started"));
		assertTrue(GameData.questText(someQuests(), "Completed")
			.endsWith("The 2 quests they've completed:\n- Cook's Assistant: completed\n- Dragon Slayer I: completed"));
		// Each state word the list is written in can be asked for, however it's written.
		assertEquals(GameData.questText(someQuests(), "in progress"), GameData.questText(someQuests(), "In-Progress"));
		assertEquals(GameData.questText(someQuests(), "not started"), GameData.questText(someQuests(), "Not started."));

		// An account at either end: nothing to report, said as such rather than as an empty list.
		List<GameData.QuestLine> fresh = Collections.singletonList(quest("Cook's Assistant", QuestState.NOT_STARTED));
		assertTrue(GameData.questText(fresh, "in progress").endsWith("They have no quest in progress."));
		assertTrue(GameData.questText(fresh, "completed").endsWith("They haven't completed a quest yet."));
		List<GameData.QuestLine> finished = Collections.singletonList(quest("Cook's Assistant", QuestState.FINISHED));
		assertTrue(GameData.questText(finished, "incomplete").endsWith("They've completed every quest."));
		assertTrue(GameData.questText(finished, "not started").endsWith("They've started every quest."));
	}

	@Test
	public void otherWordsForCompletedPickTheCompletedQuests()
	{
		String completed = GameData.questText(someQuests(), "completed");
		for (String word : Arrays.asList("complete", "Finished", "done", "Done."))
		{
			assertEquals(word, completed, GameData.questText(someQuests(), word));
		}
		// "Started" could mean in progress, or in progress or completed: a name like any other, which tells the model
		// what it can ask for instead.
		assertTrue(GameData.questText(someQuests(), "started").endsWith("No quest name matches \"started\". Try a "
			+ "shorter or different filter, or \"incomplete\" for every quest they haven't finished."));
	}

	@Test
	public void aWikiSubpageTitleFindsTheQuestItBelongsTo()
	{
		// The part before the "/" is the name.
		assertEquals(GameData.questText(everyQuest(), "Recipe for Disaster"),
			GameData.questText(everyQuest(), "Recipe for Disaster/Mountain Dwarf").replace("\"Recipe for Disaster/Mountain "
				+ "Dwarf\"", "\"Recipe for Disaster\""));
	}

	@Test
	public void miniquestsAreMarked()
	{
		List<GameData.QuestLine> quests = Arrays.asList(quest("Cook's Assistant", QuestState.FINISHED),
			new GameData.QuestLine("Mage Arena I", QuestState.NOT_STARTED, true));
		String all = GameData.questText(quests, null);
		assertTrue(all, all.endsWith("\nEvery quest:\n- Cook's Assistant: completed\n- Mage Arena I (miniquest): not started"));
		// The mark isn't part of the name: a filter finds it by its name alone.
		assertTrue(GameData.questText(quests, "mage arena").endsWith("\n- Mage Arena I (miniquest): not started"));
		assertTrue(GameData.questText(quests, "miniquest").contains("No quest name matches"));
	}

	@Test
	public void miniquestsAreReadFromTheGamesQuestTable()
	{
		// The table's type column, by each quest's id: 1 for a miniquest. A row that can't be read (gone after a game
		// update) leaves the quest listed, only unmarked, and so does a row that isn't in the quest table, whatever its
		// type column holds: the game itself reads a quest's columns only from a row in the quest table.
		Map<Integer, Object[]> types = new HashMap<>();
		types.put(Quest.MAGE_ARENA_I.getId(), new Object[]{GameData.MINIQUEST_TYPE});
		types.put(Quest.DRAGON_SLAYER_I.getId(), new Object[]{0});
		types.put(Quest.SHIELD_OF_ARRAV.getId(), new Object[0]);
		types.put(Quest.LOST_CITY.getId(), new Object[]{"1"});
		types.put(Quest.BIOHAZARD.getId(), new Object[]{GameData.MINIQUEST_TYPE});
		List<Integer> questRows = new ArrayList<>();
		for (Quest q : Quest.values())
		{
			if (q != Quest.BIOHAZARD)
			{
				questRows.add(q.getId());
			}
		}
		List<GameData.QuestLine> quests = GameData.quests(q -> q == Quest.COOKS_ASSISTANT ? QuestState.FINISHED
			: QuestState.NOT_STARTED, () -> questRows, row ->
		{
			if (row == Quest.MONKEY_MADNESS_I.getId())
			{
				throw new IllegalArgumentException("no such row");
			}
			return types.get(row);
		});

		assertEquals(Quest.values().length, quests.size());
		Map<String, GameData.QuestLine> byName = new HashMap<>();
		for (GameData.QuestLine q : quests)
		{
			byName.put(q.name, q);
		}
		assertTrue(byName.get("Mage Arena I").miniquest);
		for (Quest q : Arrays.asList(Quest.DRAGON_SLAYER_I, Quest.SHIELD_OF_ARRAV, Quest.LOST_CITY, Quest.MONKEY_MADNESS_I,
			Quest.COOKS_ASSISTANT, Quest.BIOHAZARD))
		{
			assertFalse(q.getName(), byName.get(q.getName()).miniquest);
		}
		assertEquals(QuestState.FINISHED, byName.get("Cook's Assistant").state);
		assertEquals(QuestState.NOT_STARTED, byName.get("Monkey Madness I").state);

		// Without the table's rows, every quest is still listed, only none is marked.
		List<GameData.QuestLine> unread = GameData.quests(q -> QuestState.NOT_STARTED, () ->
		{
			throw new IllegalStateException("no table");
		}, types::get);
		assertEquals(Quest.values().length, unread.size());
		for (GameData.QuestLine q : unread)
		{
			assertFalse(q.name, q.miniquest);
		}
	}

	@Test
	public void aQuestListPastTheBoundIsCutWithANote()
	{
		List<GameData.QuestLine> quests = new ArrayList<>();
		for (int i = 1; i <= GameData.QUEST_LINES + 40; i++)
		{
			quests.add(quest("Quest " + i, QuestState.NOT_STARTED));
		}
		String text = GameData.questText(quests, null);
		assertEquals(GameData.QUEST_LINES, listed(text));
		assertTrue(text, text.endsWith("\n- Quest " + GameData.QUEST_LINES + ": not started\n...and 40 more quests not "
			+ "listed. Ask again with a filter for the quests the question is about."));
	}

	@Test
	public void slotNamesReadAsWords()
	{
		assertEquals("Head", GameData.slotName(EquipmentInventorySlot.HEAD));
		assertEquals("Weapon", GameData.slotName(EquipmentInventorySlot.WEAPON));
		assertEquals("Ammo", GameData.slotName(EquipmentInventorySlot.AMMO));
	}

	@Test
	public void accountTypeGoesInTheCharacterNote()
	{
		assertEquals("a regular account (not an ironman)", CharacterInfo.accountType(0));
		assertEquals("an ironman", CharacterInfo.accountType(1));
		assertEquals("an ultimate ironman", CharacterInfo.accountType(2));
		assertEquals("a hardcore ironman", CharacterInfo.accountType(3));
		assertEquals("a group ironman", CharacterInfo.accountType(4));
		assertEquals("a hardcore group ironman", CharacterInfo.accountType(5));
		assertEquals("an unranked group ironman", CharacterInfo.accountType(6));
		assertNull(CharacterInfo.accountType(7));

		String s = CharacterInfo.format("Zezima", CharacterInfo.accountType(3), 126, 2277, Arrays.asList("Attack 99", "Sailing 1"),
			300, 1, Collections.emptyList(), 206);
		assertTrue(s, s.startsWith("[Character: Zezima, a hardcore ironman, combat level 126, total level 2277. "
			+ "Levels: Attack 99, Sailing 1. Quest points: 300."));
		// An account type the plugin doesn't know yet is left out rather than guessed.
		String unknown = CharacterInfo.format("Zezima", CharacterInfo.accountType(9), 126, 2277, Collections.emptyList(),
			0, 0, Collections.emptyList(), 206);
		assertTrue(unknown, unknown.startsWith("[Character: Zezima, combat level 126,"));
	}
}
