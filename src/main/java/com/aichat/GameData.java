package com.aichat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.IntUnaryOperator;
import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.gameval.DBTableID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.game.ItemEquipmentStats;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStats;

/**
 * The player's own account, for the game-data tools: what they wear and carry, their bank as last seen, the combat
 * bonuses of their items, their Slayer task and their achievement diaries. Only ever their own: nothing about where
 * they are, what's around them, or other players. Reading happens on the client thread, where the game's data lives,
 * and gives plain values; writing those up for the model is separate (static, any thread), so it can be tested
 * without a game.
 */
final class GameData implements GameDataTools.Game
{
	static final int INVENTORY_SLOTS = 28;
	/** Bank items listed per call: a big bank would otherwise be tens of thousands of tokens. */
	static final int BANK_LINES = 300;
	/** Items a stats search lists: a whole set of bonuses to a line, and a short search matches much of a big bank. */
	static final int STAT_LINES = 25;
	/** Items named in the note on what a slot listing couldn't place: enough to search for, not a list of its own. */
	private static final int UNPLACED_NAMES = 5;
	/** The attack and defence styles, in the order the game's own equipment screen lists them. */
	private static final String[] STYLES = {"stab", "slash", "crush", "magic", "ranged"};
	/** Stab, slash and crush: the first three styles, the only ones that can be weighed against each other. */
	private static final int MELEE_STYLES = 3;
	/**
	 * The slots a player can wear something in, for saying which ones they have nothing in and for the slots a stats
	 * search takes. (RuneLite's slot list also has the arms, hair and jaw, left out because nothing worn there has
	 * bonuses.)
	 */
	private static final EquipmentInventorySlot[] GEAR_SLOTS = {
		EquipmentInventorySlot.HEAD, EquipmentInventorySlot.CAPE, EquipmentInventorySlot.AMULET,
		EquipmentInventorySlot.WEAPON, EquipmentInventorySlot.BODY, EquipmentInventorySlot.SHIELD,
		EquipmentInventorySlot.LEGS, EquipmentInventorySlot.GLOVES, EquipmentInventorySlot.BOOTS,
		EquipmentInventorySlot.RING, EquipmentInventorySlot.AMMO,
	};
	/**
	 * Said with every set of bonuses, because each figure on its own invites a conclusion the data can't support. A
	 * magic or ranged bonus is used with a different level from a melee one, so the biggest number isn't the best
	 * style; and attack speed is the weapon's own, before rapid. Spells don't go at it: the Wiki's Attack speed page
	 * has every combat spell at 5 ticks, except a powered staff's own spell, at the staff's speed (which is what
	 * RuneLite's data gives the staff), and standard spells autocast with a harmonised nightmare staff, at 4. A
	 * salamander has no rapid.
	 */
	private static final String READING_NOTE = "The best melee style weighs stab, slash and crush against each other "
		+ "only: magic and ranged attack bonuses can't be compared with melee ones, since each goes with its own level, "
		+ "and which style works best also depends on the target's defences. Attack speeds are a weapon's base speed, "
		+ "and most ranged weapons attack a tick faster on rapid. Spells don't go at the weapon's speed: combat spells "
		+ "cast every 5 ticks (3.0 seconds), except a powered staff's own spell (a trident's, for one), which goes at the "
		+ "speed given for that staff, and standard spells autocast with a harmonised nightmare staff (4 ticks).";
	/**
	 * Said with the totals: they're a plain sum of each item's own figures, so setups whose strength comes from
	 * somewhere else would otherwise look worse (or better) than they are when the model works out an upgrade. The
	 * ammunition slot is added in whatever the weapon is, since nothing here says which weapons fire it. Tumeken's
	 * shadow multiplies magic attack as well as magic damage (the Wiki's page for it, "Passive effect").
	 */
	private static final String TOTALS_NOTE = "These totals are each item's own bonuses added up, whatever is in the "
		+ "ammunition slot included, even with a weapon that doesn't fire it. They leave out ammunition a weapon holds "
		+ "inside it (a blowpipe's darts), multipliers such as Tumeken's shadow's (for its own spell it multiplies the "
		+ "magic attack and magic damage of what's worn), set effects, and bonuses against particular targets, such as a "
		+ "slayer helmet's on task or a salve amulet's against the undead.";
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
	/** What the stats reads need to know about an item, from {@link #itemManager}. */
	private final ItemLookup lookup;
	/** The bank as last seen, or null before that. Written on the client thread, read anywhere. */
	private volatile BankSnapshot bank;

	/** Keep one for as long as RuneLite runs: the bank seen so far would be forgotten with it. */
	GameData(Client client, ItemManager itemManager)
	{
		this.client = client;
		this.itemManager = itemManager;
		this.lookup = new ItemLookup()
		{
			@Override
			public String name(int id)
			{
				// The members name: on a free world, getName adds " (Members)" to members' items.
				return itemManager.getItemComposition(id).getMembersName();
			}

			@Override
			public boolean placeholder(int id)
			{
				return itemManager.getItemComposition(id).getPlaceholderTemplateId() != -1;
			}

			@Override
			public int canonicalize(int id)
			{
				return itemManager.canonicalize(id);
			}

			@Override
			public ItemStats stats(int id)
			{
				return itemManager.getItemStats(id);
			}
		};
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

	/**
	 * What an item adds when it's worn, exactly as RuneLite's own item data has it. That data carries no level
	 * requirements, so neither does this: these are bonuses only, never what it takes to use the item.
	 */
	static final class Bonuses
	{
		/** The slot it goes in, or null when the data gives a slot number this plugin doesn't name. */
		final EquipmentInventorySlot slot;
		final boolean twoHanded;
		/** Attack bonuses, in the game's own order: stab, slash, crush, magic, ranged. */
		final int[] attack;
		/** Defence bonuses, in the same order. */
		final int[] defence;
		final int strength;
		final int rangedStrength;
		/** Magic damage, as the percentage the equipment screen shows. */
		final float magicDamage;
		final int prayer;
		/**
		 * A weapon's base attack speed in game ticks (0.6 seconds each), before rapid; spells mostly go at their own (see
		 * {@link GameData#READING_NOTE}). 0 for most things that aren't weapons, though an off-hand can have one too.
		 */
		final int attackSpeed;

		Bonuses(EquipmentInventorySlot slot, boolean twoHanded, int[] attack, int[] defence, int strength,
			int rangedStrength, float magicDamage, int prayer, int attackSpeed)
		{
			this.slot = slot;
			this.twoHanded = twoHanded;
			this.attack = attack;
			this.defence = defence;
			this.strength = strength;
			this.rangedStrength = rangedStrength;
			this.magicDamage = magicDamage;
			this.prayer = prayer;
			this.attackSpeed = attackSpeed;
		}
	}

	/** Where the player keeps an item, for a line that says which of their own things it is. */
	enum Where
	{
		WORN("worn"),
		INVENTORY("in their inventory"),
		BANK("in their bank");

		private final String words;

		Where(String words)
		{
			this.words = words;
		}
	}

	/**
	 * What RuneLite's item data says about an item's bonuses. Kept apart from the bonuses themselves because "none" and
	 * "not known" read the same as a row of zeroes, and only one of them is true.
	 */
	enum Data
	{
		/** It has bonuses, in {@link StatLine#bonuses}. */
		BONUSES,
		/** It isn't equipment at all: food, runes, a quest item. */
		NOT_EQUIPMENT,
		/**
		 * An equipment entry with no bonuses and slot 0, the head's. RuneLite's data has those both for hats that really
		 * add nothing ({@code {"slot": 0}}) and, as empty entries ({@code {}}), for items it has no real figures for (some
		 * weapon variants among them); through ItemManager the two read the same, so it's taken as not known rather than
		 * as none. Except on the head: none of the empty entries can be worn there, so one being worn in the head slot is
		 * a hat that adds nothing, and has its bonuses (all 0) instead.
		 */
		EMPTY,
		/**
		 * No bonuses at all: RuneLite fetches its item data once, when it starts, and has none for the session if that
		 * failed; and an item newer than the data isn't in it. Also an item with no equipment entry that's being worn,
		 * or that the data itself calls equipable, which can't be right.
		 */
		MISSING,
	}

	/** One item and what it adds: a line of a stats look-up. */
	static final class StatLine
	{
		/** The slot it's being worn in; null for anything not being worn. */
		final EquipmentInventorySlot wornIn;
		/** The same slot as the player sees it named ("Head"), for the list of what they have on; null likewise. */
		final String slot;
		final Where where;
		final String name;
		final Data data;
		/** Only with {@link Data#BONUSES}; null otherwise. */
		final Bonuses bonuses;

		StatLine(EquipmentInventorySlot wornIn, Where where, String name, Data data, Bonuses bonuses)
		{
			this.wornIn = wornIn;
			this.slot = wornIn == null ? null : slotName(wornIn);
			this.where = where;
			this.name = name;
			this.data = data;
			this.bonuses = bonuses;
		}

		/**
		 * The slot it goes in: the one it's being worn in, or else the one RuneLite's data gives it. Null when that isn't
		 * known, as for anything not being worn whose bonuses aren't known.
		 */
		EquipmentInventorySlot goesIn()
		{
			return wornIn != null ? wornIn : bonuses == null ? null : bonuses.slot;
		}
	}

	/**
	 * What the stats reads need to know about an item id: {@link ItemManager} in the game, canned data in tests.
	 * Client thread.
	 */
	interface ItemLookup
	{
		String name(int id);

		/** A bank placeholder: only the bank remembering where an item the player no longer has used to sit. */
		boolean placeholder(int id);

		/** The item a note or placeholder stands for, a worn version's own item, or the id itself. */
		int canonicalize(int id);

		/** Null when RuneLite has no stats for the item (see {@link Data#MISSING}). */
		ItemStats stats(int id);
	}

	/** What a search found among the player's things: worn first, then the inventory, then the bank as last seen. */
	static final class OwnedStats
	{
		final List<StatLine> lines;
		/**
		 * For a search by slot: the names of the items that matched otherwise but whose slot isn't known, because
		 * RuneLite's data has no bonuses for them. Not listed under the slot, which they may not go in, but named, so
		 * nothing is left out unsaid. Empty for a search by name alone.
		 */
		final List<String> unplaced;
		/** False until the bank has been seen on this account: a search then can't look there, and says so. */
		final boolean bankSeen;
		/** When it was seen, in {@link System#currentTimeMillis()} time; means nothing without {@link #bankSeen}. */
		final long bankSeenAt;

		OwnedStats(List<StatLine> lines, boolean bankSeen, long bankSeenAt)
		{
			this(lines, new ArrayList<>(), bankSeen, bankSeenAt);
		}

		OwnedStats(List<StatLine> lines, List<String> unplaced, boolean bankSeen, long bankSeenAt)
		{
			this.lines = lines;
			this.unplaced = unplaced;
			this.bankSeen = bankSeen;
			this.bankSeenAt = bankSeenAt;
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

	@Override
	public List<StatLine> equipmentBonuses()
	{
		return wornStats(slots(InventoryID.WORN), null, lookup);
	}

	@Override
	public OwnedStats itemStats(String search, EquipmentInventorySlot slot)
	{
		BankSnapshot seen = bank;
		// A bank seen earlier on another account isn't this player's.
		if (seen != null && seen.accountHash != client.getAccountHash())
		{
			seen = null;
		}
		return ownedStats(slots(InventoryID.WORN), slots(InventoryID.INV), seen, search, slot, lookup);
	}

	/** A container's slots, or none when the game doesn't have it. */
	private Item[] slots(int container)
	{
		ItemContainer c = client.getItemContainer(container);
		return c == null ? new Item[0] : c.getItems();
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
	 * What's worn and what each piece adds, from the worn-items container's slots (where each item sits at its slot's
	 * index), or only the pieces whose names contain {@code search} when it isn't null. Labelled with the slot each is
	 * actually in, which is what the player sees.
	 */
	static List<StatLine> wornStats(Item[] worn, String search, ItemLookup lookup)
	{
		List<StatLine> lines = new ArrayList<>();
		for (EquipmentInventorySlot slot : EquipmentInventorySlot.values())
		{
			int i = slot.getSlotIdx();
			Item item = i < worn.length ? worn[i] : null;
			if (item != null && item.getId() > 0 && item.getQuantity() > 0)
			{
				addIfFound(lines, statLine(slot, Where.WORN, item.getId(), search, lookup));
			}
		}
		return lines;
	}

	/**
	 * The items whose names contain {@code search} (null for any name) and that go in {@code slot} (null for any, and
	 * then one of the two is given) among what the player wears, carries, and has in {@code bank} (null when it hasn't
	 * been seen on this account). Names are checked first, so a search by name through a big bank looks up stats only
	 * for what it finds; a search by slot alone has to look up every item's, since the slot comes with them.
	 */
	static OwnedStats ownedStats(Item[] worn, Item[] inventory, BankSnapshot bank, String search,
		EquipmentInventorySlot slot, ItemLookup lookup)
	{
		List<StatLine> lines = new ArrayList<>();
		Set<String> unplaced = new LinkedHashSet<>();
		for (StatLine line : wornStats(worn, search, lookup))
		{
			keep(lines, unplaced, line, slot);
		}
		for (Integer id : stacks(inventory).keySet())
		{
			keep(lines, unplaced, statLine(null, Where.INVENTORY, id, search, lookup), slot);
		}
		if (bank == null)
		{
			return new OwnedStats(lines, new ArrayList<>(unplaced), false, 0);
		}
		for (int id : bank.ids)
		{
			keep(lines, unplaced, statLine(null, Where.BANK, id, search, lookup), slot);
		}
		return new OwnedStats(lines, new ArrayList<>(unplaced), true, bank.seenAt);
	}

	/**
	 * Keeps {@code line} if it goes in {@code slot} (any line, with none). One whose slot isn't known is named in
	 * {@code unplaced} instead: listing it under the slot could put it where it doesn't go, and leaving it out unsaid
	 * would read as the player not having it. What isn't equipment goes in no slot, and is simply left out.
	 */
	private static void keep(List<StatLine> lines, Set<String> unplaced, StatLine line, EquipmentInventorySlot slot)
	{
		if (line == null)
		{
			return;
		}
		if (slot == null || line.goesIn() == slot)
		{
			lines.add(line);
		}
		else if (line.goesIn() == null && line.data != Data.NOT_EQUIPMENT && line.name != null)
		{
			unplaced.add(line.name);
		}
	}

	private static void addIfFound(List<StatLine> lines, StatLine line)
	{
		if (line != null)
		{
			lines.add(line);
		}
	}

	/**
	 * One item's line: null for a bank placeholder, or for a name that doesn't contain {@code search} (null for any
	 * name). {@code wornIn}: the slot it's being worn in, null when it isn't. A note is looked up as the item it stands
	 * for, since it has no stats of its own.
	 */
	static StatLine statLine(EquipmentInventorySlot wornIn, Where where, int id, String search, ItemLookup lookup)
	{
		String name = lookup.name(id);
		if ((search != null && !matches(name, search)) || lookup.placeholder(id))
		{
			return null;
		}
		ItemStats stats = lookup.stats(lookup.canonicalize(id));
		if (stats == null)
		{
			return new StatLine(wornIn, where, name, Data.MISSING, null);
		}
		ItemEquipmentStats equipment = stats.getEquipment();
		if (equipment == null)
		{
			// Something being worn is equipment whatever the data says, and so is anything the data calls equipable:
			// their bonuses just aren't known.
			return new StatLine(wornIn, where, name, wornIn != null || stats.isEquipable() ? Data.MISSING
				: Data.NOT_EQUIPMENT, null);
		}
		Bonuses bonuses = bonuses(equipment);
		if (bonuses == null && wornIn == EquipmentInventorySlot.HEAD)
		{
			// An entry with nothing in it is a hat that adds nothing or an item the data has no figures for (one of its
			// empty entries), and none of those can be worn on the head: being worn there makes it a hat with no
			// bonuses. Not so for one that isn't being worn, whose slot nothing here can tell (see Data.EMPTY).
			bonuses = new Bonuses(EquipmentInventorySlot.HEAD, false, new int[STYLES.length], new int[STYLES.length],
				0, 0, 0, 0, 0);
		}
		return new StatLine(wornIn, where, name, bonuses == null ? Data.EMPTY : Data.BONUSES, bonuses);
	}

	/**
	 * An entry's bonuses, or null when it has nothing in it at all. Every piece of equipment RuneLite has figures for
	 * names its slot, and only the head's is 0, so an entry that's all zeroes with slot 0 is either a hat that adds
	 * nothing or an item with no figures; neither is written up here as a head item with no bonuses (see
	 * {@link Data#EMPTY}, and {@link #statLine} for one being worn on the head).
	 */
	static Bonuses bonuses(ItemEquipmentStats e)
	{
		int[] attack = {e.getAstab(), e.getAslash(), e.getAcrush(), e.getAmagic(), e.getArange()};
		int[] defence = {e.getDstab(), e.getDslash(), e.getDcrush(), e.getDmagic(), e.getDrange()};
		if (e.getSlot() == 0 && zero(attack) && zero(defence) && e.getStr() == 0 && e.getRstr() == 0
			&& e.getMdmg() == 0 && e.getPrayer() == 0 && e.getAspeed() == 0 && !e.isTwoHanded())
		{
			return null;
		}
		return new Bonuses(slot(e.getSlot()), e.isTwoHanded(), attack, defence, e.getStr(), e.getRstr(), e.getMdmg(),
			e.getPrayer(), e.getAspeed());
	}

	/** Whether {@code name} contains {@code search}, ignoring case. */
	static boolean matches(String name, String search)
	{
		return name != null && name.toLowerCase(Locale.ROOT).contains(search.toLowerCase(Locale.ROOT));
	}

	/** The slots a stats search takes, as the tool's spec lists them: "head", "cape" and so on, in the game's order. */
	static List<String> slotWords()
	{
		List<String> words = new ArrayList<>();
		for (EquipmentInventorySlot s : GEAR_SLOTS)
		{
			words.add(slotName(s).toLowerCase(Locale.ROOT));
		}
		return words;
	}

	/** The slot a stats search names ("weapon", any case), or null for a word that isn't one of {@link #slotWords}. */
	static EquipmentInventorySlot gearSlot(String word)
	{
		for (EquipmentInventorySlot s : GEAR_SLOTS)
		{
			if (slotName(s).equalsIgnoreCase(word.trim()))
			{
				return s;
			}
		}
		return null;
	}

	/** The slot RuneLite's item data means by a number: the worn-items container's numbering. Null for one it isn't. */
	static EquipmentInventorySlot slot(int slot)
	{
		for (EquipmentInventorySlot s : EquipmentInventorySlot.values())
		{
			if (s.getSlotIdx() == slot)
			{
				return s;
			}
		}
		return null;
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

	/**
	 * What the player is wearing adds up to. The totals come first: whether a swap is an upgrade, and what they're set
	 * up to fight with, are then arithmetic on numbers from the game rather than a guess from an item's name. Only what
	 * RuneLite's data has bonuses for is added up; anything it hasn't is named, never counted as adding nothing.
	 * {@code wiki}: whether the model can look things up on the Wiki, so it's only pointed there when it can.
	 */
	static String bonusesText(List<StatLine> worn, boolean wiki)
	{
		if (worn.isEmpty())
		{
			return "The player isn't wearing or wielding anything, so they have no equipment bonuses at all.";
		}
		int[] attack = new int[STYLES.length];
		int[] defence = new int[STYLES.length];
		int strength = 0;
		int rangedStrength = 0;
		float magicDamage = 0;
		int prayer = 0;
		List<String> unknown = new ArrayList<>();
		for (StatLine line : worn)
		{
			Bonuses b = line.bonuses;
			if (b == null)
			{
				unknown.add(line.name);
				continue;
			}
			for (int i = 0; i < STYLES.length; i++)
			{
				attack[i] += b.attack[i];
				defence[i] += b.defence[i];
			}
			strength += b.strength;
			rangedStrength += b.rangedStrength;
			magicDamage += b.magicDamage;
			prayer += b.prayer;
		}
		StringBuilder out = new StringBuilder("The player's equipment bonuses from RuneLite's own item data. ")
			.append(requirements(wiki));
		if (unknown.size() == worn.size())
		{
			out.append("\nNo totals: RuneLite's data has no bonuses for anything the player is wearing (it may not have "
				+ "loaded this session), so don't work any out.");
		}
		else
		{
			out.append("\nTotals for everything worn:")
				.append("\n- Attack: ").append(attack(attack))
				.append("\n- Defence: ").append(styles(defence))
				.append("\n- Strength ").append(signed(strength))
				.append(", ranged strength ").append(signed(rangedStrength))
				.append(", magic damage ").append(percent(magicDamage))
				.append(", prayer ").append(signed(prayer));
			if (!unknown.isEmpty())
			{
				out.append("\n- Incomplete: these leave out ").append(String.join(", ", unknown))
					.append(", whose bonuses RuneLite's data doesn't have, so the real totals may be higher or lower.");
			}
			out.append('\n').append(TOTALS_NOTE);
		}
		out.append("\nEach slot:");
		List<String> filled = new ArrayList<>();
		for (StatLine line : worn)
		{
			out.append("\n- ").append(line.slot).append(": ").append(line.name).append(" - ").append(describe(line, wiki));
			filled.add(line.slot);
		}
		List<String> empty = new ArrayList<>();
		for (EquipmentInventorySlot slot : GEAR_SLOTS)
		{
			if (!filled.contains(slotName(slot)))
			{
				empty.add(slotName(slot));
			}
		}
		if (!empty.isEmpty())
		{
			out.append("\nNothing worn in: ").append(String.join(", ", empty)).append('.');
		}
		return out.append('\n').append(READING_NOTE).toString();
	}

	/**
	 * The player's own items whose names contain {@code search} and that go in {@code slot} (either may be null, not
	 * both), with their bonuses. Only their own, because that's what the Wiki can't tell the model; an item they don't
	 * have is a Wiki look-up, which has its level requirements too. Long lists are cut at {@link #STAT_LINES}: every
	 * line is a whole set of bonuses.
	 * <p>
	 * By name alone they come as found: what they're wearing, then their inventory, then the bank as last seen. With a
	 * slot, the listing is what they could put there, so the useful ones go first, and the cut leaves the rest:
	 * see {@link #slotOrder}.
	 */
	static String itemStatsText(OwnedStats owned, String search, EquipmentInventorySlot slot, long now, boolean wiki)
	{
		List<StatLine> lines = new ArrayList<>(owned.lines);
		if (slot != null)
		{
			// A stable sort: lines that tie keep the order they were found in.
			lines.sort(GameData::slotOrder);
		}
		List<StatLine> found = new ArrayList<>();
		List<String> described = new ArrayList<>();
		Set<String> listed = new HashSet<>();
		Map<String, Integer> named = new HashMap<>();
		for (StatLine line : lines)
		{
			String about = describe(line, wiki);
			// Only a line that would read exactly the same goes: two items can share a name and differ, like a charged
			// and an uncharged shield, and dropping one of those would hide the one the player meant.
			if ((search == null || matches(line.name, search)) && listed.add(line.where + "\n" + line.name + "\n" + about))
			{
				found.add(line);
				described.add(about);
				named.merge(line.where + "\n" + line.name, 1, Integer::sum);
			}
		}
		String slotWord = slot == null ? null : slotName(slot).toLowerCase(Locale.ROOT);
		String bank = owned.bankSeen ? "their bank as last seen " + ago(now - owned.bankSeenAt) : null;
		if (found.isEmpty())
		{
			// "has "x" in its name", "goes in the weapon slot", or both.
			String what = (slot == null ? "" : "goes in the " + slotWord + " slot" + (search == null ? "" : " and "))
				+ (search == null ? "" : "has \"" + search + "\" in its name");
			if (bank == null)
			{
				// Not "they don't have it": the bank, where it would most likely be, wasn't searched.
				return "Nothing the player is wearing or carrying " + what + ". Their bank hasn't been open since AI Chat "
					+ "could see it, so it wasn't searched: ask them to open it once, then search again."
					+ unplacedNote(owned.unplaced);
			}
			return "Nothing the player is wearing or carrying, or has in " + bank + ", " + what + "."
				+ (wiki ? " For an item they don't own, look it up on the Wiki." : "") + unplacedNote(owned.unplaced);
		}
		StringBuilder out = new StringBuilder("The player's items ")
			.append(slot == null ? "" : "that go in the " + slotWord + " slot" + (search == null ? "" : " and have \""
				+ search + "\" in their name"))
			.append(slot == null ? "with \"" + search + "\" in their name" : "")
			.append(", with the bonuses RuneLite's own item data gives them. Searched ")
			.append(bank == null ? "what they're wearing and their inventory; their bank hasn't been open since AI Chat "
				+ "could see it, so it wasn't searched (ask them to open it once to include it)"
				: "what they're wearing, their inventory, and " + bank)
			.append(". ");
		if (slot != null)
		{
			out.append(slot == EquipmentInventorySlot.AMMO
				? "What's worn there comes first, then the rest by ranged strength, highest first. "
				: "What's worn there comes first, then the rest by their largest attack bonus in any one style, highest "
					+ "first, then by their defence bonuses added up: an order, not a ranking, since magic and ranged "
					+ "bonuses can't be weighed against melee ones. ");
		}
		out.append(requirements(wiki));
		for (int i = 0; i < found.size() && i < STAT_LINES; i++)
		{
			StatLine line = found.get(i);
			out.append("\n- ");
			if (line.slot != null)
			{
				out.append(line.slot).append(": ");
			}
			out.append(line.name).append(" (").append(line.where.words);
			int same = named.get(line.where + "\n" + line.name);
			if (same > 1)
			{
				out.append(", one of ").append(same).append(" different items with this name");
			}
			out.append(") - ").append(described.get(i));
		}
		if (found.size() > STAT_LINES)
		{
			int more = found.size() - STAT_LINES;
			if (search != null)
			{
				out.append("\n...and ").append(count(more, "more matching item"))
					.append(" not listed. Search for more of the name to see them.");
			}
			else
			{
				out.append("\n...and ").append(count(more, "more item")).append(" for this slot not listed, further down "
					+ "the order").append(slot == EquipmentInventorySlot.WEAPON ? " (magic attack bonuses run lower than "
					+ "melee and ranged ones, so a staff or wand may be among them)" : "")
					.append(". Give part of a name as well to see others.");
			}
		}
		return out.append(unplacedNote(owned.unplaced)).append('\n').append(setEffects(wiki)).append('\n')
			.append(READING_NOTE).toString();
	}

	/**
	 * The order of a listing by slot. What's worn there comes first, since it's what anything else is weighed against.
	 * Then ammunition goes by ranged strength, which is what it's for, and everything else by its largest attack bonus
	 * in any one style: for a weapon that's the bonus of the style it's made for, and it puts the gear with something
	 * to give ahead of the many items that give nothing. It's an order, not a ranking: a magic bonus can't be weighed
	 * against a melee one, which the listing says. Ties go to the one with more defence, all styles added up.
	 */
	static int slotOrder(StatLine a, StatLine b)
	{
		if ((a.wornIn != null) != (b.wornIn != null))
		{
			return a.wornIn != null ? -1 : 1;
		}
		int byOffence = Integer.compare(offence(b), offence(a));
		return byOffence != 0 ? byOffence : Integer.compare(defence(b), defence(a));
	}

	/** What {@link #slotOrder} goes by first; lowest for an item whose bonuses aren't known. */
	private static int offence(StatLine line)
	{
		Bonuses b = line.bonuses;
		if (b == null)
		{
			return Integer.MIN_VALUE;
		}
		if (b.slot == EquipmentInventorySlot.AMMO)
		{
			return b.rangedStrength;
		}
		int top = Integer.MIN_VALUE;
		for (int value : b.attack)
		{
			top = Math.max(top, value);
		}
		return top;
	}

	/** Every defence bonus added up; lowest for an item whose bonuses aren't known. */
	private static int defence(StatLine line)
	{
		if (line.bonuses == null)
		{
			return Integer.MIN_VALUE;
		}
		int total = 0;
		for (int value : line.bonuses.defence)
		{
			total += value;
		}
		return total;
	}

	/** What a listing by slot left out because its slot isn't known: a few names, so the model can search for them. */
	private static String unplacedNote(List<String> unplaced)
	{
		if (unplaced.isEmpty())
		{
			return "";
		}
		List<String> names = unplaced.subList(0, Math.min(unplaced.size(), UNPLACED_NAMES));
		String more = unplaced.size() > names.size() ? " and " + count(unplaced.size() - names.size(), "more") : "";
		return "\nNot listed, because RuneLite's data has no bonuses for them and so doesn't say which slot they go in: "
			+ String.join(", ", names) + more + ". Search for one by name to see what's known about it.";
	}

	/**
	 * Everything a line says about an item after its name: the slot it goes in (unless it's being worn there), what
	 * kind of weapon it is, and every bonus. What isn't known is said in words: a row of zeroes would read as an item
	 * with no bonuses, which is how made-up stats get in.
	 */
	static String describe(StatLine line, boolean wiki)
	{
		List<String> parts = new ArrayList<>();
		switch (line.data)
		{
			case BONUSES:
				addBonuses(parts, line);
				break;
			case NOT_EQUIPMENT:
				parts.add("not equipment, so it has no combat bonuses at all");
				break;
			case EMPTY:
				parts.add("bonuses not known: RuneLite's data gives it no bonuses at all, which is how it has both hats "
					+ "that add nothing and items it has no figures for, so don't take it as having none"
					+ (wiki ? ", check the Wiki" : ""));
				break;
			default:
				parts.add("bonuses not known: RuneLite's data has none for it (it may not have loaded this session, or "
					+ "the item is newer than the data), so don't guess them" + (wiki ? ", look them up on the Wiki" : ""));
				break;
		}
		return String.join("; ", parts) + ".";
	}

	private static void addBonuses(List<String> parts, StatLine line)
	{
		Bonuses b = line.bonuses;
		// The slot is already the label when the item is being worn.
		if (line.slot == null && b.slot != null)
		{
			parts.add(slotName(b.slot).toLowerCase(Locale.ROOT) + " slot");
		}
		// Weapons only: an off-hand can carry an attack speed too, and isn't what attacks.
		if (b.slot == EquipmentInventorySlot.WEAPON)
		{
			String hands = b.twoHanded ? "two-handed" : "one-handed";
			parts.add(b.attackSpeed <= 0 ? hands : hands + ", base attack speed " + b.attackSpeed + " ticks ("
				+ seconds(b.attackSpeed) + " seconds)");
		}
		parts.add(zero(b.attack) ? "no attack bonuses at all" : "attack " + attack(b.attack));
		parts.add(zero(b.defence) ? "no defence bonuses" : "defence " + styles(b.defence));
		List<String> extras = new ArrayList<>();
		if (b.strength != 0)
		{
			extras.add("strength " + signed(b.strength));
		}
		if (b.rangedStrength != 0)
		{
			extras.add("ranged strength " + signed(b.rangedStrength));
		}
		if (b.magicDamage != 0)
		{
			extras.add("magic damage " + percent(b.magicDamage));
		}
		if (b.prayer != 0)
		{
			extras.add("prayer " + signed(b.prayer));
		}
		if (!extras.isEmpty())
		{
			parts.add(String.join(", ", extras));
		}
	}

	/**
	 * Said with every set of bonuses. RuneLite's item data has no level requirements at all, so a model that wants one
	 * has to look it up or say it doesn't know: working one out from an item's bonuses or its name is how gear advice
	 * goes wrong. A bonus left out is 0 only on a line that gives bonuses: one that says they aren't known means it.
	 */
	private static String requirements(boolean wiki)
	{
		return "These are bonuses only: RuneLite's item data carries no level requirements, so none are given here"
			+ (wiki ? "; look any up on the Wiki." : ", and none should be guessed.")
			+ " Where an item's bonuses are given, one not listed is 0.";
	}

	/**
	 * Said with every item search that finds something. Each line is one item's own figures, so a set worn together
	 * (Barrows armour, say) shows no sign of the effect it has as a set, and a model took that for having none. No list
	 * of sets: it would go stale, and the Wiki has each one.
	 */
	private static String setEffects(boolean wiki)
	{
		return "These are each item's own figures: some sets have an effect when every piece is worn together, which "
			+ "isn't in them, so don't conclude a set has none" + (wiki ? " without checking its Wiki page." : "; that "
			+ "can't be checked here.");
	}

	/**
	 * The melee style an item does best with, and by how much: "slash +82", "stab or slash +67", "stab, slash and crush
	 * alike +10". Null when none of the three is positive. Magic and ranged are left out on purpose: their bonuses go
	 * with other levels, so a staff's crush bonus being bigger than its magic one says nothing about how to use it.
	 */
	static String bestMelee(int[] attack)
	{
		int top = 0;
		for (int i = 0; i < MELEE_STYLES; i++)
		{
			top = Math.max(top, attack[i]);
		}
		if (top <= 0)
		{
			return null;
		}
		List<String> names = new ArrayList<>();
		for (int i = 0; i < MELEE_STYLES; i++)
		{
			if (attack[i] == top)
			{
				names.add(STYLES[i]);
			}
		}
		if (names.size() == MELEE_STYLES)
		{
			return "stab, slash and crush alike " + signed(top);
		}
		return String.join(" or ", names) + " " + signed(top);
	}

	/** "stab +8, slash +67, crush -2 (best melee style: slash +67), magic 0, ranged 0". */
	private static String attack(int[] values)
	{
		String best = bestMelee(values);
		StringBuilder s = new StringBuilder();
		for (int i = 0; i < STYLES.length; i++)
		{
			s.append(i == 0 ? "" : ", ").append(STYLES[i]).append(' ').append(signed(values[i]));
			if (i == MELEE_STYLES - 1 && best != null)
			{
				s.append(" (best melee style: ").append(best).append(')');
			}
		}
		return s.toString();
	}

	/** "stab +52, slash +55, crush +58, magic 0, ranged +50": every style, named, in the game's order. */
	private static String styles(int[] values)
	{
		StringBuilder s = new StringBuilder();
		for (int i = 0; i < STYLES.length; i++)
		{
			s.append(i == 0 ? "" : ", ").append(STYLES[i]).append(' ').append(signed(values[i]));
		}
		return s.toString();
	}

	private static boolean zero(int[] values)
	{
		for (int value : values)
		{
			if (value != 0)
			{
				return false;
			}
		}
		return true;
	}

	/** "+67", "0", "-2": the sign is always there, so a penalty can't be read as a bonus. */
	private static String signed(long value)
	{
		return value > 0 ? "+" + number(value) : number(value);
	}

	/** "+15%", "+0.5%", "0%": magic damage, with the fractions some rings have. */
	private static String percent(float value)
	{
		String written = value == Math.rint(value) ? String.format(Locale.ROOT, "%.0f", value)
			: String.format(Locale.ROOT, "%.1f", value);
		return (value > 0 ? "+" : "") + written + "%";
	}

	/** A weapon's attack speed in seconds: four ticks is "2.4". */
	private static String seconds(int ticks)
	{
		return String.format(Locale.ROOT, "%.1f", ticks * 0.6);
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
