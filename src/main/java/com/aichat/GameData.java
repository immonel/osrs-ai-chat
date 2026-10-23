package com.aichat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntUnaryOperator;
import java.util.function.ToIntFunction;
import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.Skill;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.gameval.DBTableID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.game.ItemManager;

/**
 * The player's own account, for the game-data tools: what they wear and carry, their bank as last seen, their Slayer
 * task, their achievement diaries and how they're doing this minute. Only ever their own: nothing about where they
 * are, what's around them, or other players. Reading happens on the client thread, where the game's data lives, and
 * gives plain values; writing those up for the model is separate (static, any thread), so it can be tested without a
 * game.
 */
final class GameData implements GameDataTools.Game
{
	static final int INVENTORY_SLOTS = 28;
	/** Bank items listed per call: a big bank would otherwise be tens of thousands of tokens. */
	static final int BANK_LINES = 300;
	/** A boss task names its boss in a second table. From SlayerPlugin ([proc,helper_slayer_current_assignment]). */
	private static final int BOSS_TASK = 98;

	/**
	 * Each region's diary tiers, easy to elite: the "complete" varbit and the value it has once that tier is done. The
	 * game's own check ([proc,area_task_complete]) wants 1 everywhere except Karamja's first three tiers, which are 2
	 * when done (1 there means started).
	 */
	private static final Region[] REGIONS = {
		new Region("Ardougne", VarbitID.ARDOUGNE_DIARY_EASY_COMPLETE, VarbitID.ARDOUGNE_DIARY_MEDIUM_COMPLETE,
			VarbitID.ARDOUGNE_DIARY_HARD_COMPLETE, VarbitID.ARDOUGNE_DIARY_ELITE_COMPLETE),
		new Region("Desert", VarbitID.DESERT_DIARY_EASY_COMPLETE, VarbitID.DESERT_DIARY_MEDIUM_COMPLETE,
			VarbitID.DESERT_DIARY_HARD_COMPLETE, VarbitID.DESERT_DIARY_ELITE_COMPLETE),
		new Region("Falador", VarbitID.FALADOR_DIARY_EASY_COMPLETE, VarbitID.FALADOR_DIARY_MEDIUM_COMPLETE,
			VarbitID.FALADOR_DIARY_HARD_COMPLETE, VarbitID.FALADOR_DIARY_ELITE_COMPLETE),
		new Region("Fremennik", VarbitID.FREMENNIK_DIARY_EASY_COMPLETE, VarbitID.FREMENNIK_DIARY_MEDIUM_COMPLETE,
			VarbitID.FREMENNIK_DIARY_HARD_COMPLETE, VarbitID.FREMENNIK_DIARY_ELITE_COMPLETE),
		new Region("Kandarin", VarbitID.KANDARIN_DIARY_EASY_COMPLETE, VarbitID.KANDARIN_DIARY_MEDIUM_COMPLETE,
			VarbitID.KANDARIN_DIARY_HARD_COMPLETE, VarbitID.KANDARIN_DIARY_ELITE_COMPLETE),
		new Region("Karamja", new int[]{VarbitID.ATJUN_EASY_DONE, VarbitID.ATJUN_MED_DONE, VarbitID.ATJUN_HARD_DONE,
			VarbitID.KARAMJA_DIARY_ELITE_COMPLETE}, new int[]{2, 2, 2, 1}),
		new Region("Kourend & Kebos", VarbitID.KOUREND_DIARY_EASY_COMPLETE, VarbitID.KOUREND_DIARY_MEDIUM_COMPLETE,
			VarbitID.KOUREND_DIARY_HARD_COMPLETE, VarbitID.KOUREND_DIARY_ELITE_COMPLETE),
		new Region("Lumbridge & Draynor", VarbitID.LUMBRIDGE_DIARY_EASY_COMPLETE, VarbitID.LUMBRIDGE_DIARY_MEDIUM_COMPLETE,
			VarbitID.LUMBRIDGE_DIARY_HARD_COMPLETE, VarbitID.LUMBRIDGE_DIARY_ELITE_COMPLETE),
		new Region("Morytania", VarbitID.MORYTANIA_DIARY_EASY_COMPLETE, VarbitID.MORYTANIA_DIARY_MEDIUM_COMPLETE,
			VarbitID.MORYTANIA_DIARY_HARD_COMPLETE, VarbitID.MORYTANIA_DIARY_ELITE_COMPLETE),
		new Region("Varrock", VarbitID.VARROCK_DIARY_EASY_COMPLETE, VarbitID.VARROCK_DIARY_MEDIUM_COMPLETE,
			VarbitID.VARROCK_DIARY_HARD_COMPLETE, VarbitID.VARROCK_DIARY_ELITE_COMPLETE),
		new Region("Western Provinces", VarbitID.WESTERN_DIARY_EASY_COMPLETE, VarbitID.WESTERN_DIARY_MEDIUM_COMPLETE,
			VarbitID.WESTERN_DIARY_HARD_COMPLETE, VarbitID.WESTERN_DIARY_ELITE_COMPLETE),
		new Region("Wilderness", VarbitID.WILDERNESS_DIARY_EASY_COMPLETE, VarbitID.WILDERNESS_DIARY_MEDIUM_COMPLETE,
			VarbitID.WILDERNESS_DIARY_HARD_COMPLETE, VarbitID.WILDERNESS_DIARY_ELITE_COMPLETE),
	};
	private static final String[] TIERS = {"Easy", "Medium", "Hard", "Elite"};
	/**
	 * The two skills that are also meters: what's left of them is their boosted level, so they're written up as "20 of
	 * 99" instead of being listed among the boosted and drained skills.
	 */
	private static final String HITPOINTS = Skill.HITPOINTS.getName();
	private static final String PRAYER = Skill.PRAYER.getName();

	private final Client client;
	private final ItemManager itemManager;
	/** The plugin's own, which the character note waits on too: whether the levels the client has are this session's. */
	private final SessionStats sessionStats;
	/** The bank as last seen, or null before that. Written on the client thread, read anywhere. */
	private volatile BankSnapshot bank;

	/** Keep one for as long as RuneLite runs: the bank seen so far would be forgotten with it. */
	GameData(Client client, ItemManager itemManager, SessionStats sessionStats)
	{
		this.client = client;
		this.itemManager = itemManager;
		this.sessionStats = sessionStats;
	}

	// ------------------------------------------------------------------
	// Plain values: what the reading below gives, and what the writing up takes
	// ------------------------------------------------------------------

	/** One kind of item: a name, how many, and the GE price of one. */
	static final class ItemLine
	{
		/** The equipment slot ("Weapon"), or null for items that aren't worn. */
		final String slot;
		final String name;
		final boolean noted;
		/** One stack, or every stack of the item added up. */
		final long quantity;
		/** RuneLite's GE price for one, or 0 when it has none (untradeable, or the prices haven't loaded yet). */
		final long price;

		ItemLine(String slot, String name, boolean noted, long quantity, long price)
		{
			this.slot = slot;
			this.name = name;
			this.noted = noted;
			this.quantity = quantity;
			this.price = price;
		}
	}

	static final class Items
	{
		final List<ItemLine> lines;
		/** Slots with something in them. */
		final int slotsUsed;

		Items(List<ItemLine> lines, int slotsUsed)
		{
			this.lines = lines;
			this.slotsUsed = slotsUsed;
		}
	}

	static final class Bank
	{
		/** In the bank's own order, placeholders left out. */
		final List<ItemLine> lines;
		/** When it was seen, in {@link System#currentTimeMillis()} time. */
		final long seenAt;

		Bank(List<ItemLine> lines, long seenAt)
		{
			this.lines = lines;
			this.seenAt = seenAt;
		}
	}

	static final class SlayerTask
	{
		/** The monster ("Abyssal demons"), or null when there's no task or it can't be read. */
		final String name;
		/** How many are left; 0 means no task. */
		final int remaining;
		/** How many the task was for, or 0 when that isn't known. */
		final int assigned;
		/** Where the task has to be done (Konar's tasks), or null. */
		final String area;
		final int points;
		final int streak;

		SlayerTask(String name, int remaining, int assigned, String area, int points, int streak)
		{
			this.name = name;
			this.remaining = remaining;
			this.assigned = assigned;
			this.area = area;
			this.points = points;
			this.streak = streak;
		}
	}

	static final class Diary
	{
		final String region;
		/** Easy, medium, hard, elite. */
		final boolean[] done;

		Diary(String region, boolean[] done)
		{
			this.region = region;
			this.done = done;
		}
	}

	/** One skill as the game has it now and as the player trained it: the same number unless something changed it. */
	static final class SkillLevel
	{
		/** The skill's own name, "Attack". */
		final String name;
		/** The level the game is going by right now, a boost or a drain included. */
		final int boosted;
		/**
		 * The level the player has trained to. 0 means the client has never been sent it since RuneLite started: every
		 * skill is at least level 1 once it has, and hitpoints at least 10. Anything else may still be the last
		 * session's, or another account's after a switch; telling those apart is {@link SessionStats}' job, not this.
		 */
		final int real;

		SkillLevel(String name, int boosted, int real)
		{
			this.name = name;
			this.boosted = boosted;
			this.real = real;
		}
	}

	/**
	 * How the player is doing this minute, as against the levels that go with their messages: wounded or healthy, out
	 * of prayer or not, boosted or drained. None of it lasts, which is why it's read when it's asked for rather than
	 * sent along with everything else.
	 */
	static final class Stats
	{
		/** Every skill in the game's own order; hitpoints and prayer are among them. */
		final List<SkillLevel> skills;
		/**
		 * Run energy as a whole percent of full, rounded down as the game shows it. Nothing marks it as not sent yet: 0
		 * is a real reading, and it has no event, so {@link SessionStats} can't see it arrive either. It's taken as
		 * this session's once the levels are, since a login sends it in the same burst; that's likely, not proven.
		 */
		final int runEnergy;

		Stats(List<SkillLevel> skills, int runEnergy)
		{
			this.skills = skills;
			this.runEnergy = runEnergy;
		}

		/**
		 * Whether the client has a hitpoints level at all: a backstop behind {@link SessionStats#arrived()}, for a
		 * RuneLite that has had no stats since it started. A level never sent is 0, which {@link GameData#statsText}
		 * says wasn't read, but run energy has nothing to mark it, and its 0 would go to the model as fact. Every account
		 * has hitpoints, at level 10 or more.
		 */
		boolean loaded()
		{
			for (SkillLevel s : skills)
			{
				if (HITPOINTS.equals(s.name))
				{
					return s.real > 0;
				}
			}
			return false;
		}
	}

	/** The bank's items as ids and amounts: cheap to take on the client thread every time the bank changes. */
	static final class BankSnapshot
	{
		final int[] ids;
		final int[] quantities;
		/** Whose bank it is: a bank seen earlier in the session may be another account's. */
		final long accountHash;
		final long seenAt;

		private BankSnapshot(int[] ids, int[] quantities, long accountHash, long seenAt)
		{
			this.ids = ids;
			this.quantities = quantities;
			this.accountHash = accountHash;
			this.seenAt = seenAt;
		}

		/**
		 * Placeholders (none of the item left) and bank fillers left out. Item objects are immutable, but the array
		 * isn't ours to keep.
		 */
		static BankSnapshot of(Item[] items, long accountHash, long seenAt)
		{
			int[] ids = new int[items.length];
			int[] quantities = new int[items.length];
			int n = 0;
			for (Item item : items)
			{
				if (item != null && item.getId() > 0 && item.getQuantity() > 0 && item.getId() != ItemID.BANK_FILLER)
				{
					ids[n] = item.getId();
					quantities[n] = item.getQuantity();
					n++;
				}
			}
			int[] keptIds = new int[n];
			int[] keptQuantities = new int[n];
			System.arraycopy(ids, 0, keptIds, 0, n);
			System.arraycopy(quantities, 0, keptQuantities, 0, n);
			return new BankSnapshot(keptIds, keptQuantities, accountHash, seenAt);
		}
	}

	private static final class Region
	{
		final String name;
		final int[] varbits;
		final int[] doneAt;

		Region(String name, int easy, int medium, int hard, int elite)
		{
			this(name, new int[]{easy, medium, hard, elite}, new int[]{1, 1, 1, 1});
		}

		Region(String name, int[] varbits, int[] doneAt)
		{
			this.name = name;
			this.varbits = varbits;
			this.doneAt = doneAt;
		}
	}

	// ------------------------------------------------------------------
	// Reading (client thread)
	// ------------------------------------------------------------------

	/**
	 * Client thread: the plugin's ItemContainerChanged subscriber passes every change on. The bank can only be read
	 * while it's open, so its contents are kept each time it changes; ids and amounts only, to keep this cheap (names
	 * and prices are looked up when the model asks).
	 */
	void itemContainerChanged(ItemContainerChanged e)
	{
		ItemContainer container = e.getItemContainer();
		if (e.getContainerId() == InventoryID.BANK && container != null)
		{
			bank = BankSnapshot.of(container.getItems(), client.getAccountHash(), System.currentTimeMillis());
		}
	}

	/** "Share items and gear" (or AI requests) turned off: the bank as last seen isn't kept where it can't be shared. */
	void forgetBank()
	{
		bank = null;
	}

	@Override
	public boolean loggedIn()
	{
		return client.getGameState() == GameState.LOGGED_IN;
	}

	@Override
	public List<ItemLine> equipment()
	{
		List<ItemLine> lines = new ArrayList<>();
		ItemContainer worn = client.getItemContainer(InventoryID.WORN);
		if (worn == null)
		{
			return lines;
		}
		for (EquipmentInventorySlot slot : EquipmentInventorySlot.values())
		{
			Item item = worn.getItem(slot.getSlotIdx());
			if (item != null && item.getId() > 0 && item.getQuantity() > 0)
			{
				lines.add(line(slotName(slot), item.getId(), item.getQuantity()));
			}
		}
		return lines;
	}

	@Override
	public Items inventory()
	{
		ItemContainer inv = client.getItemContainer(InventoryID.INV);
		Item[] items = inv == null ? new Item[0] : inv.getItems();
		List<ItemLine> lines = new ArrayList<>();
		for (Map.Entry<Integer, Long> stack : stacks(items).entrySet())
		{
			lines.add(line(null, stack.getKey(), stack.getValue()));
		}
		return new Items(lines, slotsUsed(items));
	}

	@Override
	public Bank bank()
	{
		BankSnapshot seen = bank;
		// A bank seen earlier on another account isn't this player's.
		if (seen == null || seen.accountHash != client.getAccountHash())
		{
			return null;
		}
		List<ItemLine> lines = new ArrayList<>();
		for (int i = 0; i < seen.ids.length; i++)
		{
			ItemComposition item = itemManager.getItemComposition(seen.ids[i]);
			if (item.getPlaceholderTemplateId() == -1)
			{
				lines.add(line(null, seen.ids[i], item, seen.quantities[i]));
			}
		}
		return new Bank(lines, seen.seenAt);
	}

	/** The same reads as the core Slayer plugin: the task is in varps, its name in the game's database tables. */
	@Override
	public SlayerTask slayerTask()
	{
		int points = client.getVarbitValue(VarbitID.SLAYER_POINTS);
		// Krystilia's and Mortimer's tasks keep their own streaks; this is the one every other master counts.
		int streak = client.getVarbitValue(VarbitID.SLAYER_TASKS_COMPLETED);
		int remaining = client.getVarpValue(VarPlayerID.SLAYER_COUNT);
		if (remaining <= 0)
		{
			return new SlayerTask(null, 0, 0, null, points, streak);
		}

		int taskId = client.getVarpValue(VarPlayerID.SLAYER_TARGET);
		Integer taskRow = null;
		if (taskId == BOSS_TASK)
		{
			List<Integer> bossRows = client.getDBRowsByValue(DBTableID.SlayerTaskSublist.ID,
				DBTableID.SlayerTaskSublist.COL_TASK_SUBTABLE_ID, 0, client.getVarbitValue(VarbitID.SLAYER_TARGET_BOSSID));
			if (!bossRows.isEmpty())
			{
				taskRow = (Integer) client.getDBTableField(bossRows.get(0), DBTableID.SlayerTaskSublist.COL_TASK, 0)[0];
			}
		}
		else
		{
			List<Integer> taskRows = client.getDBRowsByValue(DBTableID.SlayerTask.ID, DBTableID.SlayerTask.COL_ID, 0, taskId);
			if (!taskRows.isEmpty())
			{
				taskRow = taskRows.get(0);
			}
		}
		String name = taskRow == null ? null
			: capitalised((String) client.getDBTableField(taskRow, DBTableID.SlayerTask.COL_NAME_UPPERCASE, 0)[0]);

		String area = null;
		int areaId = client.getVarpValue(VarPlayerID.SLAYER_AREA);
		if (areaId > 0)
		{
			List<Integer> areaRows = client.getDBRowsByValue(DBTableID.SlayerArea.ID, DBTableID.SlayerArea.COL_AREA_ID, 0, areaId);
			if (!areaRows.isEmpty())
			{
				area = (String) client.getDBTableField(areaRows.get(0), DBTableID.SlayerArea.COL_AREA_NAME_IN_HELPER, 0)[0];
			}
		}

		int assigned = client.getVarpValue(VarPlayerID.SLAYER_COUNT_ORIGINAL);
		// A task extended (or shortened) after it was given.
		if (client.getVarbitValue(VarbitID.SLAYER_MODIFIER_ID) == 2)
		{
			int change = client.getVarbitValue(VarbitID.SLAYER_MODIFIER_VALUE);
			assigned += client.getVarbitValue(VarbitID.SLAYER_MODIFIER_NEGATIVE) == 1 ? -change : change;
		}
		return new SlayerTask(name, remaining, assigned, area, points, streak);
	}

	@Override
	public List<Diary> diaries()
	{
		return diaries(client::getVarbitValue);
	}

	/**
	 * Every skill's levels and the run energy, as the client holds them this tick (see {@link #stats(ToIntFunction,
	 * ToIntFunction, int)}). Special attack energy is left out on purpose, though the game holds it in a plain varp:
	 * some content hides the special attack bar, Jagex's rules for third-party clients forbid unhiding it, and whether
	 * the game is showing it this moment (the minimap orb the player can turn off, a combat tab that isn't always open)
	 * can't be told reliably from here.
	 */
	@Override
	public Stats stats()
	{
		return stats(client::getBoostedSkillLevel, client::getRealSkillLevel, client.getEnergy());
	}

	@Override
	public boolean statsArrived()
	{
		return sessionStats.arrived();
	}

	/** Client thread. */
	private ItemLine line(String slot, int id, long quantity)
	{
		return line(slot, id, itemManager.getItemComposition(id), quantity);
	}

	/** Client thread. getItemPrice follows RuneLite's "Use actively traded price" and prices notes as the item. */
	private ItemLine line(String slot, int id, ItemComposition item, long quantity)
	{
		// The members name: on a free world, getName adds " (Members)" to members' items.
		return new ItemLine(slot, item.getMembersName(), item.getNote() != -1, quantity, itemManager.getItemPrice(id));
	}

	// ------------------------------------------------------------------
	// Plain-data steps of the reading, kept apart so they can be tested
	// ------------------------------------------------------------------

	/** Each item's id with all its stacks added up, in the order they first appear; empty slots left out. */
	static Map<Integer, Long> stacks(Item[] items)
	{
		Map<Integer, Long> stacks = new LinkedHashMap<>();
		for (Item item : items)
		{
			if (item != null && item.getId() > 0 && item.getQuantity() > 0)
			{
				stacks.merge(item.getId(), (long) item.getQuantity(), Long::sum);
			}
		}
		return stacks;
	}

	static int slotsUsed(Item[] items)
	{
		int used = 0;
		for (Item item : items)
		{
			if (item != null && item.getId() > 0 && item.getQuantity() > 0)
			{
				used++;
			}
		}
		return used;
	}

	/** Every region's diary, from a varbit reader. */
	static List<Diary> diaries(IntUnaryOperator varbit)
	{
		List<Diary> diaries = new ArrayList<>();
		for (Region r : REGIONS)
		{
			boolean[] done = new boolean[r.varbits.length];
			for (int i = 0; i < done.length; i++)
			{
				done[i] = varbit.applyAsInt(r.varbits[i]) == r.doneAt[i];
			}
			diaries.add(new Diary(r.name, done));
		}
		return diaries;
	}

	/**
	 * Both levels for every skill, so the writing up can say which are boosted or drained, from the client's own
	 * getters. Hitpoints and prayer need nothing of their own: a skill's boosted level is what the player has left of
	 * it, which is why 20 of 99 hitpoints is a boosted level of 20. {@code runEnergy} is in the client's units,
	 * hundredths of a percent.
	 */
	static Stats stats(ToIntFunction<Skill> boosted, ToIntFunction<Skill> real, int runEnergy)
	{
		List<SkillLevel> levels = new ArrayList<>();
		// Every skill the client knows, in its own order; Overall isn't one of them any more.
		for (Skill skill : Skill.values())
		{
			levels.add(new SkillLevel(skill.getName(), boosted.applyAsInt(skill), real.applyAsInt(skill)));
		}
		return new Stats(levels, runEnergy / 100);
	}

	/** "Weapon" for WEAPON. */
	static String slotName(EquipmentInventorySlot slot)
	{
		return capitalised(slot.name().toLowerCase(Locale.ROOT));
	}

	private static String capitalised(String s)
	{
		return s == null || s.isEmpty() ? s : s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
	}

	// ------------------------------------------------------------------
	// Writing up for the model (any thread)
	// ------------------------------------------------------------------

	static String equipmentText(List<ItemLine> worn)
	{
		if (worn.isEmpty())
		{
			return "The player isn't wearing or wielding anything.";
		}
		StringBuilder out = new StringBuilder("The player's worn equipment, with Grand Exchange prices from RuneLite:");
		appendItems(out, worn, worn.size());
		appendTotal(out, worn, "Total value");
		return out.toString();
	}

	static String inventoryText(Items inventory)
	{
		if (inventory.lines.isEmpty())
		{
			return "The player's inventory is empty (all " + INVENTORY_SLOTS + " slots free).";
		}
		StringBuilder out = new StringBuilder("The player's inventory (")
			.append(inventory.slotsUsed).append(" of ").append(INVENTORY_SLOTS)
			.append(" slots used), with Grand Exchange prices from RuneLite:");
		appendItems(out, inventory.lines, inventory.lines.size());
		appendTotal(out, inventory.lines, "Total value");
		return out.toString();
	}

	/**
	 * The bank as last seen, or just the items whose names contain {@code search} (ignoring case) when it isn't null.
	 * Long lists are cut at {@link #BANK_LINES}, with a note; the total counts everything that matched.
	 */
	static String bankText(Bank bank, String search, long now)
	{
		String seen = "as last seen " + ago(now - bank.seenAt);
		if (search == null)
		{
			if (bank.lines.isEmpty())
			{
				return "The player's bank was empty (" + seen + ").";
			}
			StringBuilder out = new StringBuilder("The player's bank (").append(seen).append("): ")
				.append(count(bank.lines.size(), "item")).append(", placeholders left out. Grand Exchange prices from RuneLite:");
			appendItems(out, bank.lines, BANK_LINES);
			appendTotal(out, bank.lines, "Total value of the bank");
			return out.toString();
		}

		String wanted = search.toLowerCase(Locale.ROOT);
		List<ItemLine> matching = new ArrayList<>();
		for (ItemLine line : bank.lines)
		{
			if (line.name != null && line.name.toLowerCase(Locale.ROOT).contains(wanted))
			{
				matching.add(line);
			}
		}
		if (matching.isEmpty())
		{
			return "Nothing in the player's bank has \"" + search + "\" in its name (" + seen + "; "
				+ count(bank.lines.size(), "item") + " in all). Try a shorter or different search.";
		}
		StringBuilder out = new StringBuilder("Items in the player's bank with \"").append(search).append("\" in their name (")
			.append(seen).append("), with Grand Exchange prices from RuneLite:");
		appendItems(out, matching, BANK_LINES);
		appendTotal(out, matching, "Total value of these");
		return out.toString();
	}

	static String slayerText(SlayerTask task)
	{
		StringBuilder out = new StringBuilder();
		if (task.remaining <= 0)
		{
			out.append("The player has no Slayer task right now.");
		}
		else if (task.name == null)
		{
			out.append("The player has a Slayer task with ").append(number(task.remaining))
				.append(" left, but which monster it is couldn't be read.");
		}
		else
		{
			out.append("The player's Slayer task: ").append(task.name).append(", ").append(number(task.remaining)).append(" left");
			if (task.assigned > 0)
			{
				out.append(" of ").append(number(task.assigned));
			}
			if (task.area != null)
			{
				out.append(", to be done in ").append(task.area);
			}
			out.append('.');
		}
		out.append(" Slayer points: ").append(number(task.points))
			.append(". Task streak: ").append(number(task.streak))
			.append(" (Krystilia's and Mortimer's tasks have their own streaks, not counted here).");
		return out.toString();
	}

	static String diaryText(List<Diary> diaries)
	{
		StringBuilder out = new StringBuilder("The player's achievement diaries, by tier:");
		int done = 0;
		int tiers = 0;
		for (Diary d : diaries)
		{
			List<String> finished = new ArrayList<>();
			List<String> unfinished = new ArrayList<>();
			for (int i = 0; i < d.done.length; i++)
			{
				(d.done[i] ? finished : unfinished).add(TIERS[i]);
			}
			done += finished.size();
			tiers += d.done.length;
			out.append("\n- ").append(d.region).append(": ");
			if (unfinished.isEmpty())
			{
				out.append("all done");
			}
			else if (finished.isEmpty())
			{
				out.append("none done");
			}
			else
			{
				out.append(String.join(", ", finished)).append(" done; ")
					.append(String.join(", ", unfinished)).append(" not done");
			}
		}
		out.append("\nCompleted ").append(done).append(" of ").append(tiers).append(" tiers.");
		return out.toString();
	}

	/**
	 * What the player can do this minute, which their real levels can't say: someone on 20 of 99 hitpoints is in a very
	 * different place from someone at full health, and the two look the same in a character note. Only the skills that
	 * aren't at their real level are listed, since the other twenty-odd would be identical pairs, and how fast each
	 * kind of number comes back is said, because an answer written around a boost is wrong once it wears off. The
	 * result is replayed with later turns, so it says when it was true rather than calling itself current.
	 * <p>
	 * A level of 0 (never sent) is said to be missing, never left out: it would otherwise read as "at its real level"
	 * or "no prayer left". For stats read once {@link SessionStats#arrived() this session's are in} and that have
	 * {@link Stats#loaded() loaded}; run energy is reported then too, so one skill missing besides doesn't hide it.
	 */
	static String statsText(Stats stats)
	{
		StringBuilder out = new StringBuilder("The player's state when this was read. It changes as they play, so for a "
			+ "later question read it again rather than reuse this.");
		// Above the maximum, hitpoints wear off a level a minute like any boost, but extra prayer points stay until
		// prayers use them.
		out.append("\n- Hitpoints: ").append(meter(level(stats, HITPOINTS), "comes back about 1 a minute on its own",
			"comes back down by 1 a minute"));
		out.append("\n- Prayer points: ").append(meter(level(stats, PRAYER), "doesn't come back on its own",
			"the extra points stay until prayers use them"));
		out.append("\n- Run energy: ").append(number(stats.runEnergy)).append("% of full");

		List<String> changed = new ArrayList<>();
		List<String> unsent = new ArrayList<>();
		int others = 0;
		for (SkillLevel s : stats.skills)
		{
			// Hitpoints and prayer are the two meters above, boosted or not.
			if (HITPOINTS.equals(s.name) || PRAYER.equals(s.name))
			{
				continue;
			}
			others++;
			if (s.real <= 0)
			{
				unsent.add(s.name);
			}
			else if (s.boosted != s.real)
			{
				int change = s.boosted - s.real;
				changed.add(s.name + ": " + number(s.boosted) + " now, real level " + number(s.real)
					+ " (" + (change > 0 ? "+" : "-") + number(Math.abs(change)) + ")");
			}
		}
		if (!changed.isEmpty())
		{
			// Some potions hold or renew their boost instead, and nothing here tells which kind a boost is: the model is
			// told both, and where to look, rather than a list of potions that goes stale.
			out.append("\n- Boosted or drained skills. Most boosts wear off, and drains recover, about 1 level a minute, "
				+ "but some potions hold or keep renewing theirs instead (check the Wiki for a particular one); which kind "
				+ "a boost is can't be read here:");
			for (String line : changed)
			{
				out.append("\n  - ").append(line);
			}
		}
		else if (unsent.isEmpty())
		{
			// "Besides hitpoints and prayer" read as though those two were boosted; they're the meters above.
			out.append("\n- Of the other skills, none is boosted or drained.");
		}
		else if (unsent.size() < others)
		{
			out.append("\n- Of the other skills that were read, none is boosted or drained.");
		}
		if (!unsent.isEmpty())
		{
			out.append("\n- Not read, because the game hadn't sent them yet: ").append(String.join(", ", unsent));
		}
		out.append("\nBoosted levels count for skilling, spells and most quest and achievement diary skill requirements; "
			+ "wielding or wearing equipment needs the real level, and so do some other requirements (check the Wiki for "
			+ "a particular one).");
		return out.toString();
	}

	/** The skill by that name, or null when it isn't there. */
	private static SkillLevel level(Stats stats, String name)
	{
		for (SkillLevel s : stats.skills)
		{
			if (name.equals(s.name))
			{
				return s;
			}
		}
		return null;
	}

	/**
	 * "20 of 99" for hitpoints or prayer: what's left, then the maximum, and after it how it comes back when it's below
	 * the maximum ({@code whenLow}) or what happens to the extra when it's above ({@code whenHigh}). Said to be missing
	 * when the game hasn't sent the level, so a zero can't read as "none left".
	 */
	private static String meter(SkillLevel skill, String whenLow, String whenHigh)
	{
		if (skill == null || skill.real <= 0)
		{
			return "not read, because the game hadn't sent it yet";
		}
		String left = number(skill.boosted) + " of " + number(skill.real);
		if (skill.boosted > skill.real)
		{
			// Brews and some food push hitpoints past the maximum, and some altars and special attacks (the ancient
			// mace's) do prayer: worth saying, so it doesn't read as a mistake.
			return left + " (boosted above the maximum; " + whenHigh + ")";
		}
		return skill.boosted < skill.real ? left + " (" + whenLow + ")" : left;
	}

	/**
	 * "Weapon: Abyssal whip (1,402,330 gp)", "Shark x10 (1,032 gp each, 10,320 gp total)", "Coins x5,000 (5,000 gp)".
	 * No price for untradeable items.
	 */
	static String itemText(ItemLine line)
	{
		StringBuilder s = new StringBuilder();
		if (line.slot != null)
		{
			s.append(line.slot).append(": ");
		}
		s.append(line.name);
		if (line.noted)
		{
			s.append(" (noted)");
		}
		if (line.quantity != 1)
		{
			s.append(" x").append(number(line.quantity));
		}
		if (line.price > 0)
		{
			long total = line.price * line.quantity;
			// Coins are worth 1 each: "1 gp each" would add nothing.
			if (line.quantity == 1 || line.price == 1)
			{
				s.append(" (").append(gp(total)).append(')');
			}
			else
			{
				s.append(" (").append(gp(line.price)).append(" each, ").append(gp(total)).append(" total)");
			}
		}
		return s.toString();
	}

	/** "less than a minute ago", "5 minutes ago", "about 2 hours ago". */
	static String ago(long millis)
	{
		long minutes = Math.max(0, millis) / 60_000;
		if (minutes < 1)
		{
			return "less than a minute ago";
		}
		if (minutes < 60)
		{
			return count(minutes, "minute") + " ago";
		}
		return "about " + count(minutes / 60, "hour") + " ago";
	}

	private static void appendItems(StringBuilder out, List<ItemLine> lines, int max)
	{
		for (int i = 0; i < lines.size() && i < max; i++)
		{
			out.append("\n- ").append(itemText(lines.get(i)));
		}
		if (lines.size() > max)
		{
			out.append("\n...and ").append(count(lines.size() - max, "more item"))
				.append(" not listed. Ask again with search to find particular items.");
		}
	}

	private static void appendTotal(StringBuilder out, List<ItemLine> lines, String label)
	{
		long total = 0;
		boolean unpriced = false;
		for (ItemLine line : lines)
		{
			total += line.price * line.quantity;
			unpriced |= line.price <= 0;
		}
		if (total > 0)
		{
			out.append('\n').append(label).append(" at Grand Exchange prices: ").append(gp(total))
				.append(unpriced ? " (items without a price not counted)." : ".");
		}
	}

	/** "1 item", "300 items". */
	private static String count(long n, String thing)
	{
		return number(n) + " " + thing + (n == 1 ? "" : "s");
	}

	private static String gp(long amount)
	{
		return number(amount) + " gp";
	}

	private static String number(long n)
	{
		return String.format(Locale.ROOT, "%,d", n);
	}
}
