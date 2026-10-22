package com.aichat;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.IntUnaryOperator;
import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.gameval.DBTableID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.game.ItemManager;

/**
 * The player's own account, for the game-data tools: what they wear and carry, their bank as last seen, their Slayer
 * task, their achievement diaries and their quests. Only ever their own: nothing about where they are, what's around
 * them, or other players. Reading happens on the client thread, where the game's data lives, and gives plain values;
 * writing those up for the model is separate (static, any thread), so it can be tested without a game.
 */
final class GameData implements GameDataTools.Game
{
	static final int INVENTORY_SLOTS = 28;
	/** Bank items listed per call: a big bank would otherwise be tens of thousands of tokens. */
	static final int BANK_LINES = 300;
	/**
	 * Quests listed per call. Only a safety bound, well above the 214 the client knows of: the list is in the client's
	 * order, the newest quests last, so a bound any nearer would quietly leave those out of "every quest". All of them
	 * come to some 7,000 characters, asked for, against the bank's tens of thousands.
	 */
	static final int QUEST_LINES = 400;
	/**
	 * The filters that pick a state rather than a name, as {@link GameDataTools} offers them to the model, and the
	 * words each quest's state is written in. Compared by {@link #questKey}, so "In-progress" is understood too.
	 */
	static final String QUESTS_IN_PROGRESS = "in progress";
	static final String QUESTS_INCOMPLETE = "incomplete";
	static final String QUESTS_NOT_STARTED = "not started";
	static final String QUESTS_COMPLETED = "completed";
	/**
	 * Other words a model writes for {@link #QUESTS_COMPLETED}, read as that state rather than as part of a name (no
	 * quest's name has any of them in it). "Started" isn't one: it could mean in progress, or in progress or completed.
	 */
	private static final List<String> COMPLETED_TOO = List.of("complete", "finished", "done");
	/**
	 * The type column of the game's quest table ({@link DBTableID.Quest#COL_TYPE}) for a miniquest. The quest list's own
	 * "hide miniquests" setting hides the rows with 1 ([proc,questlist_hide_quest]). In the game's data the rows it names
	 * miniquest_ have 1 and its quest_ rows 0, as do Recipe for Disaster's parts (its subquest_ rows, which name it in
	 * their parent_quest column instead).
	 */
	static final int MINIQUEST_TYPE = 1;
	/** Whole words only, and only the numerals quest names use: the "X" of "X Marks the Spot" stays a letter. */
	private static final Map<String, String> ROMAN = Map.of("i", "1", "ii", "2", "iii", "3", "iv", "4");
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

	private final Client client;
	private final ItemManager itemManager;
	/** The bank as last seen, or null before that. Written on the client thread, read anywhere. */
	private volatile BankSnapshot bank;

	/** Keep one for as long as RuneLite runs: the bank seen so far would be forgotten with it. */
	GameData(Client client, ItemManager itemManager)
	{
		this.client = client;
		this.itemManager = itemManager;
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

	/** One quest and how far the player has got with it. */
	static final class QuestLine
	{
		/** As the quest list names it ("Dragon Slayer II"). */
		final String name;
		final QuestState state;
		/** A miniquest, by the game's quest table; false when that can't be read. */
		final boolean miniquest;

		QuestLine(String name, QuestState state)
		{
			this(name, state, false);
		}

		QuestLine(String name, QuestState state, boolean miniquest)
		{
			this.name = name;
			this.state = state;
			this.miniquest = miniquest;
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
	 * Every quest the client knows, with the player's state for each, and which are miniquests by the game's quest
	 * table. Each state runs a game script, so this is only safe where no other script is running: queued on the client
	 * thread, never inside one (see {@link GameDataTools}).
	 */
	@Override
	public List<QuestLine> quests()
	{
		return quests(q -> q.getState(client), row -> client.getDBRowConfig(row).getTableID(),
			row -> client.getDBTableField(row, DBTableID.Quest.COL_TYPE, 0));
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
	 * Every quest the client knows, from a state reader and two readers of the game's data: the table a row is in, and
	 * the quest table's type column. A quest's id is its row in that table, the row {@link Quest#getState} hands the
	 * game's quest status script.
	 */
	static List<QuestLine> quests(Function<Quest, QuestState> state, IntUnaryOperator table, IntFunction<Object[]> type)
	{
		List<QuestLine> quests = new ArrayList<>();
		for (Quest q : Quest.values())
		{
			quests.add(new QuestLine(q.getName(), state.apply(q), miniquest(q.getId(), table, type)));
		}
		return quests;
	}

	/**
	 * Whether the quest table's row says miniquest; false when the row isn't in that table or says nothing readable. The
	 * game checks a row's table the same way before it reads a quest from it ([proc,quest_is_valid]): another table's
	 * row has its own columns, and its type column could hold a 1 that means something else.
	 */
	private static boolean miniquest(int row, IntUnaryOperator table, IntFunction<Object[]> type)
	{
		Object[] value;
		try
		{
			if (table.applyAsInt(row) != DBTableID.Quest.ID)
			{
				// Gone from the game's data after an update, or another table's row: still listed, only without the mark.
				return false;
			}
			value = type.apply(row);
		}
		catch (RuntimeException e)
		{
			return false;
		}
		return value != null && value.length > 0 && value[0] instanceof Integer && (Integer) value[0] == MINIQUEST_TYPE;
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
	 * The quests the model asked about, with the player's state for each: the ones whose names match {@code filter}
	 * (see {@link #questKey}), the ones in a state when the filter names one ({@link #QUESTS_IN_PROGRESS},
	 * {@link #QUESTS_INCOMPLETE}, {@link #QUESTS_NOT_STARTED} or {@link #QUESTS_COMPLETED}) or several ("completed/in
	 * progress", "in progress, not started"), or every quest when it's null. How many are completed, in progress and not
	 * started comes first whatever was asked for, so a filter that matched nothing still answers something. Miniquests
	 * are marked as such.
	 */
	static String questText(List<QuestLine> quests, String filter)
	{
		int completed = 0;
		int started = 0;
		for (QuestLine q : quests)
		{
			completed += q.state == QuestState.FINISHED ? 1 : 0;
			started += q.state == QuestState.IN_PROGRESS ? 1 : 0;
		}
		// The client's list has the miniquests and Recipe for Disaster's parts in it too, where the game counts quests
		// on their own: said, so the model doesn't take these totals for the game's.
		StringBuilder out = new StringBuilder("The player's quests: ").append(number(completed))
			.append(" of ").append(number(quests.size())).append(" completed, ").append(number(started))
			.append(" in progress, ").append(number(quests.size() - completed - started))
			.append(" not started. These counts take in miniquests and each part of Recipe for Disaster, so they won't "
				+ "match the game's own quest count.");

		Picked picked = filter == null ? null : pickedStates(filter);
		String key = "";
		if (picked == null)
		{
			key = filter == null ? "" : questKey(filter);
			picked = picked(key);
		}
		List<QuestLine> matching = new ArrayList<>();
		for (QuestLine q : quests)
		{
			if (matches(q, picked, key))
			{
				matching.add(q);
			}
		}
		if (matching.isEmpty())
		{
			return out.append('\n').append(nothingMatched(picked, filter)).toString();
		}
		out.append('\n').append(questHeading(picked, filter, matching.size())).append(':');
		for (int i = 0; i < matching.size() && i < QUEST_LINES; i++)
		{
			QuestLine q = matching.get(i);
			out.append("\n- ").append(q.name).append(q.miniquest ? " (miniquest)" : "").append(": ")
				.append(questStateText(q.state));
		}
		if (matching.size() > QUEST_LINES)
		{
			out.append("\n...and ").append(count(matching.size() - QUEST_LINES, "more quest"))
				.append(" not listed. Ask again with a filter for the quests the question is about.");
		}
		return out.toString();
	}

	/** The same words the state filters use, so what the model reads is what it can ask for. */
	private static String questStateText(QuestState state)
	{
		if (state == QuestState.FINISHED)
		{
			return QUESTS_COMPLETED;
		}
		return state == QuestState.IN_PROGRESS ? QUESTS_IN_PROGRESS : QUESTS_NOT_STARTED;
	}

	/**
	 * A quest's name, or the model's filter, as the two are matched: lower case, with "&" as "and", roman numerals as
	 * digits, and only letters and digits kept, so the spaces between words go too ("Fairy tale" finds "Fairytale"). A
	 * filter in other words than the game's ("Romeo and Juliet", "Dragon Slayer 2", "Another Slice of HAM", a curly
	 * apostrophe) would otherwise miss, and each miss costs the reply a round of look-ups. Anything after a "/" is left
	 * out: the Wiki names a quest's parts and guides that way ("Recipe for Disaster/Mountain Dwarf", "Dragon Slayer
	 * II/Quick guide"), so a subpage title finds the quest it belongs to, or every part of Recipe for Disaster. (State
	 * words on both sides of a "/" are read before this, by {@link #pickedStates}.)
	 */
	static String questKey(String text)
	{
		int slash = text.indexOf('/');
		String words = (slash < 0 ? text : text.substring(0, slash)).toLowerCase(Locale.ROOT)
			.replace("&", " and ")
			// Dropped rather than split on, so "Cook's" is one word and never leaves a stray "s" or "i" behind.
			.replaceAll("['‘’ʼ`]", "");
		StringBuilder key = new StringBuilder();
		for (String word : words.split("[^\\p{L}\\p{N}]+"))
		{
			key.append(ROMAN.getOrDefault(word, word));
		}
		return key.toString();
	}

	/**
	 * What a filter asks for: the quests in some states, part of a name, or every quest when there's no filter. Every
	 * set of states has one, so state words put together ("completed/in progress") are written up as one ask.
	 */
	private enum Picked
	{
		EVERY(QuestState.IN_PROGRESS, QuestState.NOT_STARTED, QuestState.FINISHED),
		IN_PROGRESS(QuestState.IN_PROGRESS),
		INCOMPLETE(QuestState.IN_PROGRESS, QuestState.NOT_STARTED),
		NOT_STARTED(QuestState.NOT_STARTED),
		COMPLETED(QuestState.FINISHED),
		COMPLETED_OR_IN_PROGRESS(QuestState.FINISHED, QuestState.IN_PROGRESS),
		COMPLETED_OR_NOT_STARTED(QuestState.FINISHED, QuestState.NOT_STARTED),
		NAME();

		/** The states picked; none for a name. */
		final Set<QuestState> states;

		Picked(QuestState... states)
		{
			this.states = states.length == 0 ? EnumSet.noneOf(QuestState.class) : EnumSet.of(states[0], states);
		}
	}

	/**
	 * From the filter's {@link #questKey}, so a state is understood however it's written ("In-progress", "not started",
	 * "Done"). A filter with nothing left to match on, such as "?", is every quest.
	 */
	private static Picked picked(String key)
	{
		if (key.isEmpty())
		{
			return Picked.EVERY;
		}
		if (key.equals(questKey(QUESTS_IN_PROGRESS)))
		{
			return Picked.IN_PROGRESS;
		}
		if (key.equals(questKey(QUESTS_INCOMPLETE)))
		{
			return Picked.INCOMPLETE;
		}
		if (key.equals(questKey(QUESTS_NOT_STARTED)))
		{
			return Picked.NOT_STARTED;
		}
		if (key.equals(questKey(QUESTS_COMPLETED)))
		{
			return Picked.COMPLETED;
		}
		for (String word : COMPLETED_TOO)
		{
			if (key.equals(questKey(word)))
			{
				return Picked.COMPLETED;
			}
		}
		return Picked.NAME;
	}

	/**
	 * State words between "/" or "," ("completed/in progress", "in progress, not started"): the quests in any of those
	 * states. One state word with a stray "/" or "," on either side is that state. Null for anything with a name in it,
	 * such as a Wiki subpage title ("Recipe for Disaster/Mountain Dwarf", which {@link #questKey} reads), or with
	 * nothing to match on.
	 */
	private static Picked pickedStates(String filter)
	{
		Set<QuestState> states = EnumSet.noneOf(QuestState.class);
		int parts = 0;
		for (String part : filter.split("[/,]"))
		{
			String key = questKey(part);
			if (key.isEmpty())
			{
				continue;
			}
			Picked one = picked(key);
			if (one == Picked.NAME)
			{
				return null;
			}
			states.addAll(one.states);
			parts++;
		}
		if (parts == 0)
		{
			return null;
		}
		for (Picked p : Picked.values())
		{
			if (p != Picked.NAME && p.states.equals(states))
			{
				return p;
			}
		}
		return null;
	}

	private static boolean matches(QuestLine quest, Picked picked, String key)
	{
		if (picked == Picked.NAME)
		{
			return quest.name != null && questKey(quest.name).contains(key);
		}
		return picked == Picked.EVERY || picked.states.contains(quest.state);
	}

	/** The line over the list: what it holds, and how many. */
	private static String questHeading(Picked picked, String filter, int matching)
	{
		String many = count(matching, "quest");
		switch (picked)
		{
			case IN_PROGRESS:
				return "The " + many + " they've started and not finished";
			case INCOMPLETE:
				return "The " + many + " they haven't finished";
			case NOT_STARTED:
				return "The " + many + " they haven't started";
			case COMPLETED:
				return "The " + many + " they've completed";
			case COMPLETED_OR_IN_PROGRESS:
				return "The " + many + " they've completed or started";
			case COMPLETED_OR_NOT_STARTED:
				return "The " + many + " they've completed or haven't started";
			case NAME:
				return "The " + many + " matching \"" + filter + "\"";
			default:
				return "Every quest";
		}
	}

	/** Nothing matched: what that says about the account, or what to try instead of a name that isn't used. */
	private static String nothingMatched(Picked picked, String filter)
	{
		switch (picked)
		{
			case IN_PROGRESS:
				return "They have no quest in progress.";
			case INCOMPLETE:
				return "They've completed every quest.";
			case NOT_STARTED:
				return "They've started every quest.";
			case COMPLETED:
				return "They haven't completed a quest yet.";
			case COMPLETED_OR_IN_PROGRESS:
				return "They haven't started a quest yet.";
			case COMPLETED_OR_NOT_STARTED:
				return "Every one of their quests is in progress.";
			case NAME:
				return "No quest name matches \"" + filter + "\". Try a shorter or different filter, or \""
					+ QUESTS_INCOMPLETE + "\" for every quest they haven't finished.";
			default:
				return "No quests could be read, which shouldn't happen; answer without them.";
		}
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
