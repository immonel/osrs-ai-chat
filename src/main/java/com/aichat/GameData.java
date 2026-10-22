package com.aichat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.IntUnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.WorldEntity;
import net.runelite.api.WorldType;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.gameval.DBTableID;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.game.ItemManager;

/**
 * The player's own account, for the game-data tools: what they wear and carry, their bank as last seen, their Slayer
 * task, their achievement diaries, and where they're standing with whether the game says they can be attacked there.
 * Only ever their own: nothing about what's around them, or other players. Reading happens on the client thread, where
 * the game's data lives, and gives plain values; writing those up for the model is separate (static, any thread), so it
 * can be tested without a game.
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
	 * The game maps most underground areas 6400 tiles north of the land above them, so a tile that far north is below
	 * ground. Not in a private instance, though: those are copied from templates kept anywhere on the map, a player-owned
	 * house among them, so there it would say nothing.
	 */
	private static final int UNDERGROUND_Y = 6400;
	/**
	 * Between this and {@link #UNDERGROUND_Y} the map holds places of their own (Prifddinas, Mor Ul Rek, Zanaris, the
	 * raids), set apart from each other in the numbering, so nearness there means nothing.
	 */
	private static final int SURFACE_TOP_Y = 4160;
	private static final int SURFACE = 0;
	private static final int SET_APART = 1;
	private static final int UNDERGROUND = 2;
	/** How near a landmark has to be before "near the west bank" is worth saying, and true. */
	private static final int SPOT_TILES = 15;
	/**
	 * How far to look for a named place from somewhere unnamed. Further than this, "the nearest place is 400 tiles
	 * south-east" misleads more than it helps: that's another part of the world.
	 */
	private static final int NEAREST_TILES = 120;
	/** A distance said as it is below this, and rounded to the nearest ten above it: "about 80 tiles west". */
	private static final int ROUND_TILES = 20;
	/** What the game shows in the Wilderness, "Level: 23", read from the start: another plugin may add to the end. */
	private static final Pattern SHOWN_LEVEL = Pattern.compile("Level: (\\d{1,3})(?!\\d)");
	/** The one named region north of {@link #UNDERGROUND_Y} that isn't underground. */
	private static final String UNDERWATER = "Underwater";
	/** What {@link #pvpWorld} calls a Deadman world, whose rules differ from a PvP world's. */
	private static final String DEADMAN_WORLD = "a Deadman world";

	/**
	 * The Wilderness on the map, as boxes of {x from, x to, y from, y to}, for a second opinion where the game says the
	 * player isn't in it. Never to say that they are: that's the game's to say. The surface, from the ditch (y 3520,
	 * where the levels start) to the Wilderness's northern edge (y 3967), and from x 2944 to 3391. Below it, the same
	 * box 6400 tiles north, the way the game maps underground areas (y 9920 to 10367, y 9920 being where the dungeons'
	 * levels start): the Edgeville Dungeon past its Wilderness gate, the Revenant Caves, the Wilderness God Wars, Lava
	 * Maze and Deep Wilderness dungeons, Scorpia's cave. But only from x 3008 there: the Wilderness's dungeons start
	 * further east (the Wilderness God Wars Dungeon at about x 3022), and Keldagrim, whose railway station is at x
	 * 2941, lies just west. And the east end of the Wilderness Slayer Cave, which runs past x 3391 (RuneLite's own
	 * shortcuts and cannon spots there lie at x 3333 to 3434, y 10056 to 10149): only from y 10048, so that the Slayer
	 * Tower's basement in Morytania (x 3392 to 3455, y 9920 to 9983) isn't taken for it.
	 */
	private static final int[][] WILDERNESS = {
		{2944, 3391, 3520, 3967},
		{3008, 3391, 9920, 10367},
		{3392, 3455, 10048, 10175},
	};

	/**
	 * Landmarks worth naming after the place: "Varrock, near the west bank". Only banks and the like, where being near
	 * one answers half the questions a player asks on their way somewhere. Each is said with its town's name unless the
	 * player is in the region it stands in, so one town's bank is never taken for another's; the tests check every one
	 * against the name RuneLite gives its region.
	 */
	static final Spot[] SPOTS = {
		new Spot("Varrock", "the west bank", "Varrock West Bank", 3185, 3436),
		new Spot("Varrock", "the east bank", "Varrock East Bank", 3253, 3420),
		new Spot("Lumbridge", "the castle", "Lumbridge Castle", 3222, 3218),
		new Spot("Lumbridge", "the general store", "the Lumbridge general store", 3214, 3246),
		new Spot("Draynor", "the bank", "Draynor Village bank", 3093, 3243),
		new Spot("Falador", "the east bank", "Falador East Bank", 3013, 3355),
		new Spot("Falador", "the west bank", "Falador West Bank", 2946, 3368),
		new Spot("Falador", "the party room", "the Falador Party Room", 3046, 3377),
		new Spot("Edgeville", "the bank", "Edgeville bank", 3094, 3491),
		new Spot("Al Kharid", "the bank", "Al Kharid bank", 3270, 3167),
		new Spot("Al Kharid", "the palace", "Al Kharid palace", 3295, 3168),
		new Spot("Port Sarim", "the docks", "Port Sarim docks", 3029, 3217),
		new Spot("Seers' Village", "the bank", "Seers' Village bank", 2725, 3493),
		new Spot("Kandarin", "Camelot Castle", "Camelot Castle", 2757, 3477),
		new Spot("Catherby", "the bank", "Catherby bank", 2809, 3441),
		new Spot("Ardougne", "the north bank", "Ardougne North Bank", 2616, 3332),
		new Spot("Ardougne", "the south bank", "Ardougne South Bank", 2655, 3283),
		new Spot("Yanille", "the bank", "Yanille bank", 2613, 3093),
		new Spot("Canifis", "the bank", "Canifis bank", 3511, 3480),
		new Spot("Tree Gnome Stronghold", "the bank", "the Tree Gnome Stronghold bank", 2446, 3425),
		new Spot("Tree Gnome Stronghold", "the Grand Tree", "the Grand Tree", 2466, 3496),
	};

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

	/**
	 * Where the player is standing, and what the game itself says about the danger there, before any of it is put into
	 * words. The coordinates never leave the plugin: some content hides the minimap on purpose (the Barrows tunnels are
	 * a maze because of it), and the exact spot would give away what it hides, so only what's made from them goes: the
	 * region's name, a landmark's, which floor, and for a spot with no name the nearest named region with roughly how
	 * far and which way.
	 */
	static final class Location
	{
		final int x;
		final int y;
		/** 0 on the ground, 1 to 3 for the floors above it. */
		final int plane;
		/** In a private instance (a raid, a boss room, a player-owned house): the coordinates are its template's. */
		final boolean instanced;
		/** On something that moves over the map, as a boat does when Sailing: the coordinates are the sea's under it. */
		final boolean aboard;
		/** Whether the game counts the player as in the Wilderness: its own flag, never worked out from coordinates. */
		final boolean inWilderness;
		/** The Wilderness level the game shows on screen, or 0 when it shows none that can be read. */
		final int shownLevel;
		/** Whether the game marks the spot as one where players can attack each other, in the Wilderness or not. */
		final boolean pvpArea;
		/** "a PvP world", "a Deadman world": a world where players can attack each other almost anywhere; else null. */
		final String pvpWorld;
		/** A High Risk world, PvP or not: the Protect Item prayer doesn't work there. */
		final boolean highRisk;

		Location(int x, int y, int plane, boolean instanced, boolean aboard, boolean inWilderness, int shownLevel,
			boolean pvpArea, String pvpWorld, boolean highRisk)
		{
			this.x = x;
			this.y = y;
			this.plane = plane;
			this.instanced = instanced;
			this.aboard = aboard;
			this.inWilderness = inWilderness;
			this.shownLevel = shownLevel;
			this.pvpArea = pvpArea;
			this.pvpWorld = pvpWorld;
			this.highRisk = highRisk;
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

	/** A landmark: the detail after a place's name, once the player is within {@link #SPOT_TILES} of it. */
	static final class Spot
	{
		/** The name RuneLite gives the region it stands in. */
		final String area;
		/** What it's called by someone in that region: "the west bank". */
		final String here;
		/** What it's called from anywhere else: "Varrock West Bank". */
		final String elsewhere;
		final int x;
		final int y;

		Spot(String area, String here, String elsewhere, int x, int y)
		{
			this.area = area;
			this.here = here;
			this.elsewhere = elsewhere;
			this.x = x;
			this.y = y;
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
	 * Null when the player has no position to read, as on a loading screen. On a boat the player stands in the boat's
	 * own small world, which moves over the real one, so their spot is carried over to the map beneath it first, the
	 * way RuneLite's own minimap code does it. In a private instance the coordinates read are its template's: the place
	 * it was copied from. Whether they're in the Wilderness, and at what level, is the game's own word: the Wilderness's
	 * edges are too ragged, and its safe spots too many, to draw here, and a wrong guess there could tell a player
	 * they're safe when they aren't. Its rough borders on the map ({@link #WILDERNESS}) only ever add a warning to the
	 * game's "no", when the words are written.
	 */
	@Override
	public Location location()
	{
		Player me = client.getLocalPlayer();
		WorldView top = client.getTopLevelWorldView();
		LocalPoint here = me == null ? null : me.getLocalLocation();
		if (here == null || top == null)
		{
			return null;
		}
		boolean aboard = here.getWorldView() != WorldView.TOPLEVEL;
		if (aboard)
		{
			WorldEntity vessel = top.worldEntities().byIndex(here.getWorldView());
			here = vessel == null ? null : vessel.transformToMainWorld(here);
			if (here == null)
			{
				return null;
			}
		}
		WorldPoint at = WorldPoint.fromLocalInstance(client, here);
		boolean inWilderness = client.getVarbitValue(VarbitID.INSIDE_WILDERNESS) == 1;
		Widget level = inWilderness ? client.getWidget(InterfaceID.PvpIcons.WILDERNESSLEVEL) : null;
		Set<WorldType> world = client.getWorldType();
		return new Location(at.getX(), at.getY(), at.getPlane(), top.isInstance(), aboard, inWilderness,
			level == null || level.isHidden() ? 0 : shownLevel(level.getText()),
			client.getVarbitValue(VarbitID.PVP_AREA_CLIENT) == 1, pvpWorld(world),
			world != null && world.contains(WorldType.HIGH_RISK));
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
	 * The level in the game's "Level: 23", or 0 for anything else: on a PvP world, say, the same spot shows the combat
	 * levels that can attack instead. Read from the start, since RuneLite's Combat Level plugin adds a line after it.
	 */
	static int shownLevel(String text)
	{
		Matcher m = text == null ? null : SHOWN_LEVEL.matcher(text);
		return m != null && m.lookingAt() ? Integer.parseInt(m.group(1)) : 0;
	}

	/**
	 * What kind of world lets players attack each other outside the Wilderness, or null for an ordinary one: PvP worlds,
	 * and Deadman worlds, where everyone outside the safe zones is fair game (as RuneLite's
	 * {@link WorldType#isPvpWorld} has it). A High Risk world is only a PvP world when it's marked PvP as well: worlds
	 * like 365 are High Risk but not PvP, and are as safe as any other outside the Wilderness.
	 */
	static String pvpWorld(Set<WorldType> types)
	{
		if (types == null)
		{
			return null;
		}
		if (types.contains(WorldType.DEADMAN))
		{
			return DEADMAN_WORLD;
		}
		if (!types.contains(WorldType.PVP))
		{
			return null;
		}
		return types.contains(WorldType.HIGH_RISK) ? "a High Risk PvP world" : "a PvP world";
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
	 * Where the player is, in words the assistant can work with: the place, a landmark if one is close, which floor, and
	 * then, always, what the game says about whether other players can attack them there. That last part is never left
	 * out, even where the answer is no: a missing warning would read as "safe", and where AI Chat can't be sure (a safe
	 * zone on a PvP world), it says so instead. No coordinates: see {@link Location}.
	 */
	static String locationText(Location at)
	{
		String name = AreaNames.at(at.x, at.y);
		StringBuilder out = new StringBuilder("Place: ");
		if (name != null)
		{
			out.append(name);
			String spot = spot(name, at.x, at.y);
			if (spot != null)
			{
				out.append(", near ").append(spot);
			}
		}
		else if (at.inWilderness)
		{
			// RuneLite names no regions there; the game's own flag says which part of the map this is.
			out.append("the Wilderness");
		}
		else if (at.instanced)
		{
			out.append("a private instance AI Chat has no name for (a raid, a boss room, a minigame or a quest scene, "
				+ "for example)");
		}
		else
		{
			out.append("somewhere AI Chat has no name for");
		}
		String floor = floorText(at, name);
		if (floor != null)
		{
			out.append(" (").append(floor).append(')');
		}
		boolean amidWilderness = amidWilderness(at);
		if (name == null && amidWilderness)
		{
			out.append(", inside the Wilderness's borders on the map");
		}
		out.append('.');
		// Not amid the Wilderness: "Edgeville, 110 tiles south" from Ferox Enclave invites a walk across it.
		if (name == null && !at.inWilderness && !at.instanced && !amidWilderness)
		{
			out.append(' ').append(nearestText(at.x, at.y));
		}
		if (at.instanced && name != null)
		{
			out.append(" This is a private instance (a raid, a boss room or a player-owned house, for example), named "
				+ "after the area it was copied from.");
		}
		out.append(' ').append(dangerText(at));
		return out.append(" Place names are RuneLite's names for the map's 64-tile regions, so they give the area rather "
			+ "than the exact spot.").toString();
	}

	/**
	 * The landmark to name after the place, or null for none near enough: by its short name in its own region, and by its
	 * full one from anywhere else, so that "near the bank" always means the bank of the place just named.
	 */
	private static String spot(String area, int x, int y)
	{
		Spot best = null;
		int nearest = SPOT_TILES;
		for (Spot s : SPOTS)
		{
			int away = Math.max(Math.abs(s.x - x), Math.abs(s.y - y));
			if (away <= nearest)
			{
				best = s;
				nearest = away;
			}
		}
		if (best == null)
		{
			return null;
		}
		return best.area.equals(area) ? best.here : best.elsewhere;
	}

	/**
	 * "underground", "one floor up", "aboard a boat", or any of them together; null on the ground, which goes unsaid. No
	 * floor in a private instance: the floor read there is the template's, which needn't be the one the player is on
	 * (a player-owned house's ground floor, say), and it hasn't been checked in the game.
	 */
	private static String floorText(Location at, String name)
	{
		List<String> parts = new ArrayList<>();
		if (layer(at.y) == UNDERGROUND && !at.instanced && !UNDERWATER.equals(name))
		{
			parts.add("underground");
		}
		if (at.plane > 0 && !at.instanced)
		{
			parts.add(at.plane == 1 ? "one floor up" : at.plane == 2 ? "two floors up" : "three floors up");
		}
		if (at.aboard)
		{
			parts.add("aboard a boat");
		}
		return parts.isEmpty() ? null : String.join(", ", parts);
	}

	/** Which part of the map's numbering a tile is in: see {@link #SURFACE_TOP_Y} and {@link #UNDERGROUND_Y}. */
	private static int layer(int y)
	{
		return y >= UNDERGROUND_Y ? UNDERGROUND : y >= SURFACE_TOP_Y ? SET_APART : SURFACE;
	}

	/**
	 * For somewhere without a name: the nearest named region and which way it lies, but only on the same layer of the
	 * map and within {@link #NEAREST_TILES}. Past that, or across layers, the numbers stop meaning distance on the
	 * ground, and a confident "400 tiles south-east of the Motherlode Mine" would send a player the wrong way.
	 */
	private static String nearestText(int x, int y)
	{
		int layer = layer(y);
		if (layer == SET_APART)
		{
			return "It's in one of the map's areas set apart from the rest, where nothing nearby has a name either.";
		}
		String best = null;
		int nearest = NEAREST_TILES + 1;
		int east = 0;
		int north = 0;
		for (Map.Entry<Integer, String> e : AreaNames.all().entrySet())
		{
			int west = (e.getKey() >> 8) << 6;
			int south = (e.getKey() & 0xFF) << 6;
			if (layer(south) != layer)
			{
				continue;
			}
			// How far past the region's edges the tile is: east and north as positive, 0 where it's level with it.
			int dx = x < west ? x - west : x > west + 63 ? x - (west + 63) : 0;
			int dy = y < south ? y - south : y > south + 63 ? y - (south + 63) : 0;
			int away = Math.max(Math.abs(dx), Math.abs(dy));
			if (away < nearest)
			{
				best = e.getValue();
				nearest = away;
				east = dx;
				north = dy;
			}
		}
		if (best == null)
		{
			return "Nothing within about " + NEAREST_TILES + " tiles has a name either.";
		}
		// Which way the place lies from the player: the opposite of how far past its edges the player is.
		String way = direction(-east, -north);
		return "The nearest named place is " + best + ", " + tiles(nearest) + (way == null ? " away" : " " + way) + ".";
	}

	/** "north", "north-east": which way one spot lies from another, given how far east and north of it it is. */
	static String direction(int east, int north)
	{
		StringBuilder way = new StringBuilder();
		// Each way is named only where it's more than half of the other: a step north of due east isn't north-east.
		if (Math.abs(north) * 2 > Math.abs(east))
		{
			way.append(north > 0 ? "north" : "south");
		}
		if (Math.abs(east) * 2 > Math.abs(north))
		{
			way.append(way.length() > 0 ? "-" : "").append(east > 0 ? "east" : "west");
		}
		return way.length() == 0 ? null : way.toString();
	}

	/** "12 tiles", "about 80 tiles": a long way is rounded, the regions it was measured from being rough anyway. */
	static String tiles(int away)
	{
		return away < ROUND_TILES ? count(away, "tile") : "about " + count(Math.round(away / 10.0) * 10, "tile");
	}

	/**
	 * Whether other players can attack them here, as the game says: in the Wilderness (and how deep), in another PvP
	 * area, or on a world where that's true almost everywhere. A "no" is said as plainly as a "yes", and as the game's
	 * word rather than AI Chat's; where the game's flags can't settle it, the text says so. Where the game says they
	 * aren't in the Wilderness but the spot is inside its borders on the map (Ferox Enclave, or a flag the game hasn't
	 * updated yet, as just after logging in), that's said too: the map only ever adds a warning, never takes one away.
	 */
	private static String dangerText(Location at)
	{
		StringBuilder out = new StringBuilder();
		if (at.pvpWorld != null)
		{
			out.append("World: ").append(at.pvpWorld)
				.append(", where players can attack each other anywhere outside the safe zones. ");
			if (!DEADMAN_WORLD.equals(at.pvpWorld))
			{
				// From the OSRS Wiki's Wilderness page, on PvP worlds: outside a safe zone counts as level 15
				// Wilderness, inside the Wilderness 15 is added to its own level, and what happens on death still goes
				// by the actual Wilderness level. Not said of Deadman worlds, whose rules are their own.
				out.append("On a PvP world, anywhere outside a safe zone counts as level 15 Wilderness for who can attack "
					+ "whom, and in the Wilderness 15 is added to its own level (level 20 acts as level 35), though what's "
					+ "lost on death still goes by the Wilderness's own level. ");
			}
		}
		else if (at.highRisk)
		{
			out.append("World: a High Risk world, which isn't a PvP world. ");
		}
		if (at.highRisk)
		{
			out.append("On High Risk worlds the Protect Item prayer doesn't work, and a player killed by another player "
				+ "loses all their items. ");
		}
		if (at.inWilderness)
		{
			out.append("In the Wilderness: yes, ").append(levelText(at)).append(". Other players can attack them here.");
		}
		else if (amidWilderness(at))
		{
			out.append("In the Wilderness: the game doesn't count this spot as Wilderness (a safe zone such as Ferox "
				+ "Enclave, or the game hasn't updated yet), but it's inside the Wilderness's borders on the map: the "
				+ "Wilderness is all around it, so leaving this spot can mean entering it, where other players can attack "
				+ "them. ");
			if (at.pvpArea)
			{
				out.append("The game marks this spot as a PvP area, though: other players can attack them here.");
			}
			else if (at.pvpWorld != null)
			{
				out.append("The game doesn't mark this spot as a PvP area, which on a PvP world usually means a safe "
					+ "zone, but AI Chat can't confirm that, so don't assume they're safe from other players.");
			}
			else
			{
				out.append("The game doesn't mark this spot as a PvP area either.");
			}
		}
		else if (at.pvpArea)
		{
			out.append("In the Wilderness: no, but the game marks this spot as a PvP area. Other players can attack them "
				+ "here.");
		}
		else if (at.pvpWorld != null)
		{
			out.append("In the Wilderness: no. The game doesn't mark this spot as a PvP area, which on a PvP world "
				+ "usually means a safe zone, but AI Chat can't confirm that, so don't assume they're safe from other "
				+ "players.");
		}
		else
		{
			out.append("In the Wilderness: no, and the game doesn't mark this spot as a PvP area.");
		}
		return out.toString();
	}

	/**
	 * Whether the game says the player isn't in the Wilderness while the spot is inside its borders on the map: see
	 * {@link #WILDERNESS}. Not in a private instance, whose coordinates are its template's, not where the player is.
	 */
	private static boolean amidWilderness(Location at)
	{
		if (at.inWilderness || at.instanced)
		{
			return false;
		}
		for (int[] box : WILDERNESS)
		{
			if (at.x >= box[0] && at.x <= box[1] && at.y >= box[2] && at.y <= box[3])
			{
				return true;
			}
		}
		return false;
	}

	/** The level as the game shows it; failing that, worked out from the tile and said to be; failing that, unknown. */
	private static String levelText(Location at)
	{
		if (at.shownLevel > 0)
		{
			return "level " + at.shownLevel + " as the game shows it";
		}
		int worked = wildernessLevel(at.y);
		if (worked > 0)
		{
			return "about level " + worked + ", worked out from where they are (the game's own figure wasn't on screen)";
		}
		return "at a level AI Chat couldn't read";
	}

	/**
	 * The Wilderness level worked out from how far north a tile is: eight tiles to a level, from y 3520 on the surface
	 * and y 9920 in the dungeons below. Only ever the number, for when the game's own figure can't be read; whether the
	 * player is in the Wilderness at all is the game's to say. Some parts read differently in the game, so the text calls
	 * it "about". 0 outside both bands.
	 */
	private static int wildernessLevel(int y)
	{
		if (y >= 3520 && y < 4000)
		{
			return (y - 3520) / 8 + 1;
		}
		return y >= 9920 && y < 10400 ? (y - 9920) / 8 + 1 : 0;
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
