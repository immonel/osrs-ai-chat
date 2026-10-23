package com.aichat;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.runelite.api.EquipmentInventorySlot;
import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The game-data tools: what they offer, when they share, and that every call ends exactly once, off the game's thread. */
public class GameDataToolsTest
{
	private static final String CLIENT_THREAD = "test client thread";
	/** The settings that let the assistant see the player's items, and their character, as the settings panel names them. */
	private static final String ITEMS = SettingName.of("shareItems");
	private static final String CHARACTER = SettingName.of("sendCharacter");

	private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
	/** Stands in for the game's client thread. */
	private final ExecutorService client = Executors.newSingleThreadExecutor(r -> new Thread(r, CLIENT_THREAD));
	private final FakeGame game = new FakeGame();
	private final List<String> activity = new CopyOnWriteArrayList<>();
	private boolean shareItems = true;
	private boolean shareCharacter = true;
	/** Whether the request has the Wiki tools. */
	private boolean wiki = true;

	@After
	public void stop()
	{
		executor.shutdownNow();
		client.shutdownNow();
	}

	/** The bank the stats search has seen, far enough back to be said in whole minutes. */
	private static final long BANK_SEEN_AT = System.currentTimeMillis() - 5 * 60_000;

	/**
	 * A worn set with two of the items the model kept making stats up for: a helm with no melee attack bonus, and a
	 * scimitar whose crush bonus is a penalty. Real items with RuneLite's own figures (see {@link ItemFixtures}).
	 */
	private static final List<GameData.StatLine> WORN = Arrays.asList(
		ItemFixtures.line("Head", GameData.Where.WORN, "Serpentine helm", ItemFixtures.SERPENTINE_HELM),
		ItemFixtures.line("Weapon", GameData.Where.WORN, "Dragon scimitar", ItemFixtures.DRAGON_SCIMITAR),
		ItemFixtures.line("Body", GameData.Where.WORN, "Fighter torso", ItemFixtures.FIGHTER_TORSO));

	/** Canned game data; counts reads and remembers which thread they ran on. */
	private static final class FakeGame implements GameDataTools.Game
	{
		boolean loggedIn = true;
		GameData.Bank bank;
		/** What the player is wearing, for the bonuses and the stats search. */
		List<GameData.StatLine> worn = WORN;
		/** Whether the stats search can see the bank: a bank it hasn't seen leaves the rest searchable. */
		boolean bankSeen = true;
		/** The search the stats read was given. */
		volatile String searched;
		/** The slot it was given. */
		volatile EquipmentInventorySlot searchedSlot;
		RuntimeException failure;
		final AtomicInteger reads = new AtomicInteger();
		volatile String readOn;

		private void read()
		{
			reads.incrementAndGet();
			readOn = Thread.currentThread().getName();
			if (failure != null)
			{
				throw failure;
			}
		}

		@Override
		public boolean loggedIn()
		{
			return loggedIn;
		}

		@Override
		public List<GameData.ItemLine> equipment()
		{
			read();
			return Collections.singletonList(new GameData.ItemLine("Weapon", "Abyssal whip", false, 1, 1_400_000));
		}

		@Override
		public GameData.Items inventory()
		{
			read();
			return new GameData.Items(Collections.singletonList(new GameData.ItemLine(null, "Shark", false, 3, 1000)), 3);
		}

		@Override
		public GameData.Bank bank()
		{
			read();
			return bank;
		}

		@Override
		public List<GameData.StatLine> equipmentBonuses()
		{
			read();
			return new ArrayList<>(worn);
		}

		/**
		 * What's worn, a shark in the inventory, and a second scimitar and an abyssal whip in the bank (when it's been
		 * seen).
		 */
		@Override
		public GameData.OwnedStats itemStats(String search, EquipmentInventorySlot slot)
		{
			read();
			searched = search;
			searchedSlot = slot;
			List<GameData.StatLine> all = new ArrayList<>(worn);
			all.add(ItemFixtures.line(null, GameData.Where.INVENTORY, "Shark", ItemFixtures.SHARK));
			if (bankSeen)
			{
				all.add(ItemFixtures.line(null, GameData.Where.BANK, "Dragon scimitar", ItemFixtures.DRAGON_SCIMITAR));
				all.add(ItemFixtures.line(null, GameData.Where.BANK, "Abyssal whip", ItemFixtures.ABYSSAL_WHIP));
			}
			List<GameData.StatLine> found = new ArrayList<>();
			for (GameData.StatLine line : all)
			{
				if ((search == null || GameData.matches(line.name, search)) && (slot == null || line.goesIn() == slot))
				{
					found.add(line);
				}
			}
			return new GameData.OwnedStats(found, bankSeen, BANK_SEEN_AT);
		}

		@Override
		public GameData.SlayerTask slayerTask()
		{
			read();
			return new GameData.SlayerTask("Abyssal demons", 87, 150, null, 1234, 56);
		}

		@Override
		public List<GameData.Diary> diaries()
		{
			read();
			return GameData.diaries(id -> 1);
		}
	}

	private GameDataTools tools(Consumer<Runnable> clientThread, long timeoutMillis)
	{
		return new GameDataTools(game, clientThread, executor, () -> shareItems, () -> shareCharacter, wiki,
			activity::add, timeoutMillis);
	}

	private GameDataTools tools()
	{
		return tools(client::execute, GameDataTools.READ_TIMEOUT_MILLIS);
	}

	/** The result of one call, and the thread it came back on. */
	private static final class Ended
	{
		ChatApi.ToolResult result;
		String thread;
		final AtomicInteger times = new AtomicInteger();
	}

	private Ended run(GameDataTools tools, String name, JsonObject input) throws InterruptedException
	{
		Ended ended = new Ended();
		CountDownLatch done = new CountDownLatch(1);
		tools.run(name, input, r ->
		{
			ended.result = r;
			ended.thread = Thread.currentThread().getName();
			ended.times.incrementAndGet();
			done.countDown();
		});
		assertTrue("no result", done.await(10, TimeUnit.SECONDS));
		return ended;
	}

	private static List<String> names(List<ChatApi.ToolSpec> specs)
	{
		List<String> names = new ArrayList<>();
		for (ChatApi.ToolSpec s : specs)
		{
			names.add(s.name);
		}
		return names;
	}

	@Test
	public void offersOnlyWhatTheSettingsShare()
	{
		assertEquals(Arrays.asList("get_equipment", "get_inventory", "get_bank", "get_equipment_bonuses",
			"get_item_stats", "get_slayer_task", "get_achievement_diaries"), names(tools().specs()));
		shareCharacter = false;
		assertEquals(Arrays.asList("get_equipment", "get_inventory", "get_bank", "get_equipment_bonuses",
			"get_item_stats"), names(tools().specs()));
		shareItems = false;
		shareCharacter = true;
		assertEquals(Arrays.asList("get_slayer_task", "get_achievement_diaries"), names(tools().specs()));
		shareCharacter = false;
		assertTrue(tools().specs().isEmpty());

		for (String name : Arrays.asList("get_equipment", "get_inventory", "get_bank", "get_equipment_bonuses",
			"get_item_stats", "get_slayer_task", "get_achievement_diaries"))
		{
			assertTrue(name, GameDataTools.handles(name));
		}
		assertFalse(GameDataTools.handles("wiki_search"));
	}

	@Test
	public void specsAreTheSameEveryTime()
	{
		// They're part of the cached prompt, so a new set for the next reply must match byte for byte.
		List<ChatApi.ToolSpec> a = tools().specs();
		List<ChatApi.ToolSpec> b = tools().specs();
		for (int i = 0; i < a.size(); i++)
		{
			assertEquals(a.get(i).description, b.get(i).description);
			assertEquals(a.get(i).inputSchema.toString(), b.get(i).inputSchema.toString());
			assertEquals("object", a.get(i).inputSchema.get("type").getAsString());
			assertTrue(a.get(i).inputSchema.get("properties").isJsonObject());
		}
		JsonObject bank = a.get(2).inputSchema.getAsJsonObject("properties").getAsJsonObject("search");
		assertEquals("string", bank.get("type").getAsString());
		assertFalse(a.get(2).inputSchema.has("required"));
		// A stats look-up takes a name, a slot or both: neither is required on its own, and the slots are the ones the
		// tool's answers name, in the game's order.
		assertEquals("get_item_stats", a.get(4).name);
		assertFalse(a.get(4).inputSchema.has("required"));
		JsonObject slot = a.get(4).inputSchema.getAsJsonObject("properties").getAsJsonObject("slot");
		assertEquals("[\"head\",\"cape\",\"amulet\",\"weapon\",\"body\",\"shield\",\"legs\",\"gloves\",\"boots\","
			+ "\"ring\",\"ammo\"]", slot.getAsJsonArray("enum").toString());
		assertTrue(a.get(4).description, a.get(4).description.contains("A slot lists what they have for it: what's worn "
			+ "there first, then the rest by their largest attack bonus in any one style (ammunition by ranged strength), "
			+ "an order rather than a ranking."));
		// Nothing about buy limits in either: they're not what gear questions need, and untradeable items have them.
		assertFalse(a.get(3).description, a.get(3).description.contains("limit"));
		assertFalse(a.get(4).description, a.get(4).description.contains("limit"));
	}

	@Test
	public void theItemToolsPointAtTheWikiOnlyWhenTheRequestHasIt()
	{
		String with = tools().specs().get(4).description;
		assertTrue(with, with.endsWith("; for an item the player doesn't own, use the Wiki instead."));
		// No claim that an item's biggest bonus is the style it's best with: magic and ranged aren't weighed with melee.
		assertTrue(with, with.contains("each weapon's best melee style and base attack speed"));
		assertFalse(with, with.contains("accurate"));
		wiki = false;
		List<ChatApi.ToolSpec> specs = tools().specs();
		assertTrue(specs.get(4).description, specs.get(4).description.endsWith("; it has nothing on items the player "
			+ "doesn't own."));
		assertFalse(specs.get(3).description.contains("Wiki"));
		assertFalse(specs.get(4).description.contains("Wiki"));
	}

	@Test
	public void sharesEquipmentReadOnTheClientThreadAndAnswersOffIt() throws Exception
	{
		Ended e = run(tools(), "get_equipment", new JsonObject());
		assertFalse(e.result.error);
		assertTrue(e.result.content, e.result.content.contains("- Weapon: Abyssal whip (1,400,000 gp)"));
		assertEquals(CLIENT_THREAD, game.readOn);
		assertNotEquals(CLIENT_THREAD, e.thread);
		assertEquals(Collections.singletonList("Shared your equipment"), activity);
	}

	@Test
	public void sharesInventorySlayerTaskAndDiaries() throws Exception
	{
		Ended inv = run(tools(), "get_inventory", null);
		assertTrue(inv.result.content, inv.result.content.startsWith("The player's inventory (3 of 28 slots used)"));
		Ended slayer = run(tools(), "get_slayer_task", new JsonObject());
		assertTrue(slayer.result.content, slayer.result.content.startsWith("The player's Slayer task: Abyssal demons, 87 left of 150."));
		Ended diaries = run(tools(), "get_achievement_diaries", new JsonObject());
		// Every varbit at 1: all done but Karamja's first three tiers, which need 2.
		assertTrue(diaries.result.content, diaries.result.content.endsWith("Completed 45 of 48 tiers."));
		assertEquals(Arrays.asList("Shared your inventory", "Shared your Slayer task", "Shared your achievement diaries"), activity);
		// The panel knows these lines by how they start (PanelText.summary), and names what was shared over the reply.
		assertEquals("Shared your inventory, Slayer task and achievement diaries", PanelText.summary(activity).sharing);
	}

	@Test
	public void sharesNothingWithTheSettingOff() throws Exception
	{
		// Offered earlier in the reply, then turned off: the next call is turned down without reading the game.
		GameDataTools tools = tools();
		shareItems = false;
		Ended e = run(tools, "get_bank", new JsonObject());
		assertTrue(e.result.error);
		// Each setting by the name the settings panel shows.
		assertEquals("The player hasn't turned on \"" + ITEMS + "\" in AI Chat's settings, so their bank can't be read. "
			+ "They can turn it on if they'd like you to see it.", e.result.content);
		shareCharacter = false;
		Ended slayer = run(tools, "get_slayer_task", new JsonObject());
		assertTrue(slayer.result.content, slayer.result.content.contains("\"" + CHARACTER + "\""));
		assertEquals(0, game.reads.get());
		assertEquals(Arrays.asList("Didn't share your bank: \"" + ITEMS + "\" is off",
			"Didn't share your Slayer task: \"" + CHARACTER + "\" is off"), activity);
	}

	@Test
	public void needsThePlayerLoggedIn() throws Exception
	{
		game.loggedIn = false;
		Ended e = run(tools(), "get_inventory", new JsonObject());
		assertTrue(e.result.error);
		assertEquals("The player isn't logged in, so their inventory can't be read. Ask them to log in first.", e.result.content);
		assertEquals(0, game.reads.get());
		assertEquals(Collections.singletonList("Couldn't share your inventory: you're not logged in"), activity);
	}

	@Test
	public void bankNotSeenYet() throws Exception
	{
		Ended e = run(tools(), "get_bank", new JsonObject());
		assertTrue(e.result.error);
		assertEquals("The bank can only be read while it's open, and the player hasn't opened it since AI Chat or \"" + ITEMS
			+ "\" was turned on, or they logged in to this account. Ask them to open their bank once, then ask again.",
			e.result.content);
		assertEquals(Collections.singletonList("Couldn't share your bank: open it once so AI Chat can see it"), activity);
	}

	@Test
	public void bankSearch() throws Exception
	{
		game.bank = new GameData.Bank(Arrays.asList(new GameData.ItemLine(null, "Shark", false, 250, 1000),
			new GameData.ItemLine(null, "Rune platebody", false, 1, 38_000)), System.currentTimeMillis());
		JsonObject input = new JsonObject();
		input.addProperty("search", "  rune\n ");
		Ended e = run(tools(), "get_bank", input);
		assertFalse(e.result.error);
		assertTrue(e.result.content, e.result.content.startsWith("Items in the player's bank with \"rune\" in their name"));
		assertTrue(e.result.content, e.result.content.contains("- Rune platebody (38,000 gp)"));
		assertFalse(e.result.content, e.result.content.contains("Shark"));

		// A blank search is the whole bank.
		input.addProperty("search", " ");
		Ended all = run(tools(), "get_bank", input);
		assertTrue(all.result.content, all.result.content.contains("- Shark x250"));
		assertEquals(Arrays.asList("Searched your bank for \"rune\"", "Shared your bank"), activity);
		assertEquals("Shared your bank", PanelText.summary(activity).sharing);
		assertEquals("Searched your bank", PanelText.summary(activity.subList(0, 1)).sharing);
	}

	@Test
	public void sharesEquipmentBonusesAddedUpOverEverythingWorn() throws Exception
	{
		Ended e = run(tools(), "get_equipment_bonuses", new JsonObject());
		assertFalse(e.result.error);
		String text = e.result.content;
		assertTrue(text, text.contains("\n- Attack: stab +8, slash +67, crush -2 (best melee style: slash +67), "
			+ "magic -45, ranged -5\n"));
		assertTrue(text, text.contains("\n- Defence: stab +114, slash +141, crush +120, magic -10, ranged +117\n"));
		assertTrue(text, text.contains("\n- Strength +75, ranged strength 0, magic damage 0%, prayer 0\n"));
		// The helm the model kept giving "+15 attack": no melee attack bonus, and penalties to magic and ranged.
		assertTrue(text, text.contains("\n- Head: Serpentine helm - attack stab 0, slash 0, crush 0, magic -5, ranged -5; "
			+ "defence stab +52, slash +55, crush +58, magic 0, ranged +50; strength +5.\n"));
		assertTrue(text, text.contains("\n- Body: Fighter torso - attack stab 0, slash 0, crush 0, magic -40, ranged 0; "
			+ "defence stab +62, slash +85, crush +62, magic -10, ranged +67; strength +4.\n"));
		assertTrue(text, text.contains("\nNothing worn in: Cape, Amulet, Shield, Legs, Gloves, Boots, Ring, Ammo.\n"));
		assertEquals(CLIENT_THREAD, game.readOn);
		assertNotEquals(CLIENT_THREAD, e.thread);
		assertEquals(Collections.singletonList("Shared your equipment bonuses"), activity);
		assertEquals("Shared your equipment bonuses", PanelText.summary(activity).sharing);
	}

	@Test
	public void searchesTheStatsOfTheItemsThePlayerHas() throws Exception
	{
		JsonObject input = new JsonObject();
		input.addProperty("search", "  scimitar\n ");
		Ended e = run(tools(), "get_item_stats", input);
		assertFalse(e.result.error);
		String text = e.result.content;
		assertTrue(text, text.startsWith("The player's items with \"scimitar\" in their name, with the bonuses "
			+ "RuneLite's own item data gives them. Searched what they're wearing, their inventory, and their bank as "
			+ "last seen 5 minutes ago."));
		// The slash weapon the model recommended for crush: its crush bonus is a penalty.
		assertTrue(text, text.contains("\n- Weapon: Dragon scimitar (worn) - one-handed, base attack speed 4 ticks "
			+ "(2.4 seconds); attack stab +8, slash +67, crush -2 (best melee style: slash +67), magic 0, ranged 0; "
			+ "defence stab 0, slash +1, crush 0, magic 0, ranged 0; strength +66.\n"));
		// The one in the bank says which slot it goes in, since it isn't being worn in one.
		assertTrue(text, text.contains("\n- Dragon scimitar (in their bank) - weapon slot; one-handed, base attack speed "
			+ "4 ticks (2.4 seconds); attack stab +8,"));
		assertFalse(text, text.contains("Serpentine helm"));
		// The read was given the search, so it looks up stats only for what matches.
		assertEquals("scimitar", game.searched);
		// The panel says the bank was part of it, as it does for a bank search.
		assertEquals(Collections.singletonList("Shared your item stats for \"scimitar\", bank included"), activity);
		assertEquals("Shared your item stats for \"scimitar\", bank included", PanelText.summary(activity).sharing);
		assertEquals("Read your item stats for \"scimitar\", bank included, but didn't share it: the request had stopped",
			GameDataTools.unshared(activity.get(0)));
	}

	@Test
	public void saysWhenAnItemIsntEquipmentOrIsntTheirs() throws Exception
	{
		JsonObject food = new JsonObject();
		food.addProperty("search", "shark");
		Ended carried = run(tools(), "get_item_stats", food);
		assertFalse(carried.result.error);
		// A row of zeroes would read like a terrible piece of gear.
		assertTrue(carried.result.content, carried.result.content.contains(
			"\n- Shark (in their inventory) - not equipment, so it has no combat bonuses at all."));

		JsonObject missing = new JsonObject();
		missing.addProperty("search", "twisted bow");
		Ended none = run(tools(), "get_item_stats", missing);
		assertFalse(none.result.error);
		assertEquals("Nothing the player is wearing or carrying, or has in their bank as last seen 5 minutes ago, has "
			+ "\"twisted bow\" in its name. For an item they don't own, look it up on the Wiki.", none.result.content);
		assertEquals(Arrays.asList("Shared your item stats for \"shark\", bank included",
			"Shared your item stats for \"twisted bow\", bank included"), activity);

		// Without the Wiki tools there's nowhere to send the model.
		wiki = false;
		Ended noWiki = run(tools(), "get_item_stats", missing);
		assertFalse(noWiki.result.content, noWiki.result.content.contains("Wiki"));
	}

	@Test
	public void itemStatsStillAnswerBeforeTheBankHasBeenSeen() throws Exception
	{
		// Unlike get_bank: what they're wearing and carrying can be searched whether the bank has been open or not.
		game.bankSeen = false;
		JsonObject input = new JsonObject();
		input.addProperty("search", "scimitar");
		Ended e = run(tools(), "get_item_stats", input);
		assertFalse(e.result.error);
		assertTrue(e.result.content, e.result.content.contains("Searched what they're wearing and their inventory; "
			+ "their bank hasn't been open since AI Chat could see it, so it wasn't searched (ask them to open it once "
			+ "to include it)."));
		assertTrue(e.result.content, e.result.content.contains("\n- Weapon: Dragon scimitar (worn) - one-handed"));
		assertFalse(e.result.content, e.result.content.contains("in their bank)"));

		// Finding nothing isn't "they don't have it" when the bank wasn't searched.
		input.addProperty("search", "twisted bow");
		Ended none = run(tools(), "get_item_stats", input);
		assertEquals("Nothing the player is wearing or carrying has \"twisted bow\" in its name. Their bank hasn't been "
			+ "open since AI Chat could see it, so it wasn't searched: ask them to open it once, then search again.",
			none.result.content);
		assertEquals(Arrays.asList("Shared your item stats for \"scimitar\" (bank not seen yet)",
			"Shared your item stats for \"twisted bow\" (bank not seen yet)"), activity);
	}

	@Test
	public void bonusesRuneLiteDoesntHaveAreNamedNotCountedAsNothing() throws Exception
	{
		// RuneLite fetches its item data once, at startup; when that fails, a worn whip has no stats at all.
		game.worn = Arrays.asList(ItemFixtures.line("Weapon", GameData.Where.WORN, "Abyssal whip", null),
			ItemFixtures.line("Head", GameData.Where.WORN, "Serpentine helm", ItemFixtures.SERPENTINE_HELM));
		Ended e = run(tools(), "get_equipment_bonuses", new JsonObject());
		assertFalse(e.result.error);
		String text = e.result.content;
		assertTrue(text, text.contains("\n- Incomplete: these leave out Abyssal whip, whose bonuses RuneLite's data "
			+ "doesn't have, so the real totals may be higher or lower.\n"));
		assertTrue(text, text.contains("\n- Weapon: Abyssal whip - bonuses not known: RuneLite's data has none for it (it "
			+ "may not have loaded this session, or the item is newer than the data), so don't guess them, look them up "
			+ "on the Wiki.\n"));
		assertFalse(text, text.contains("not equipment"));
		// The totals are the helm's alone, not a whip counted as nothing.
		assertTrue(text, text.contains("\n- Attack: stab 0, slash 0, crush 0, magic -5, ranged -5\n"));
	}

	@Test
	public void itemStatsNeedSomethingToLookUp() throws Exception
	{
		JsonObject input = new JsonObject();
		input.addProperty("search", "  ");
		Ended e = run(tools(), "get_item_stats", input);
		assertTrue(e.result.error);
		assertEquals("Say what to look up: \"search\" takes part of an item's name, \"slot\" an equipment slot, and "
			+ "either will do.", e.result.content);
		assertEquals(0, game.reads.get());
		assertEquals(Collections.singletonList("Skipped an item look-up with nothing to look up"), activity);
	}

	@Test
	public void listsWhatThePlayerHasForASlot() throws Exception
	{
		JsonObject input = new JsonObject();
		// Any case: the spec offers lower case, and a model may write it as the answers do.
		input.addProperty("slot", " Weapon ");
		Ended e = run(tools(), "get_item_stats", input);
		assertFalse(e.result.error);
		assertNull(game.searched);
		assertEquals(EquipmentInventorySlot.WEAPON, game.searchedSlot);
		String text = e.result.content;
		assertTrue(text, text.startsWith("The player's items that go in the weapon slot, with the bonuses RuneLite's own "
			+ "item data gives them. Searched what they're wearing, their inventory, and their bank as last seen 5 "
			+ "minutes ago. What's worn there comes first, then the rest by their largest attack bonus in any one style, "
			+ "highest first, then by their defence bonuses added up: an order, not a ranking, since magic and ranged "
			+ "bonuses can't be weighed against melee ones."));
		// The scimitar worn, then the whip in the bank (slash +82) ahead of the scimitar there (slash +67).
		int worn = text.indexOf("\n- Weapon: Dragon scimitar (worn)");
		int whip = text.indexOf("\n- Abyssal whip (in their bank)");
		int banked = text.indexOf("\n- Dragon scimitar (in their bank)");
		assertTrue(text, worn > 0 && worn < whip && whip < banked);
		assertFalse(text, text.contains("Serpentine helm"));
		assertFalse(text, text.contains("Shark"));
		assertEquals(Collections.singletonList("Shared your item stats for the weapon slot, bank included"), activity);

		// A name and a slot together.
		input.addProperty("search", "whip");
		Ended both = run(tools(), "get_item_stats", input);
		assertTrue(both.result.content, both.result.content.startsWith("The player's items that go in the weapon slot "
			+ "and have \"whip\" in their name,"));
		assertEquals("whip", game.searched);
		assertEquals("Shared your item stats for \"whip\" in the weapon slot, bank included", activity.get(1));
	}

	@Test
	public void aSlotThatIsntOneIsTurnedDown() throws Exception
	{
		JsonObject input = new JsonObject();
		input.addProperty("slot", "hands");
		input.addProperty("search", "gloves");
		Ended e = run(tools(), "get_item_stats", input);
		assertTrue(e.result.error);
		assertEquals("There's no \"hands\" slot: \"slot\" takes one of head, cape, amulet, weapon, body, shield, legs, "
			+ "gloves, boots, ring, ammo.", e.result.content);
		assertEquals(0, game.reads.get());
		assertEquals(Collections.singletonList("Skipped an item look-up for an unknown slot \"hands\""), activity);
	}

	@Test
	public void bonusesAndItemStatsNeedTheItemsSettingAndThePlayerLoggedIn() throws Exception
	{
		// Both read the player's own items, so both go by "Share items and gear": neither has a setting of its own.
		shareItems = false;
		Ended bonuses = run(tools(), "get_equipment_bonuses", new JsonObject());
		assertTrue(bonuses.result.error);
		assertEquals("The player hasn't turned on \"" + ITEMS + "\" in AI Chat's settings, so their equipment bonuses "
			+ "can't be read. They can turn it on if they'd like you to see it.", bonuses.result.content);
		JsonObject input = new JsonObject();
		input.addProperty("search", "scimitar");
		Ended stats = run(tools(), "get_item_stats", input);
		assertTrue(stats.result.error);
		assertTrue(stats.result.content, stats.result.content.contains("so their item stats can't be read"));

		shareItems = true;
		game.loggedIn = false;
		Ended loggedOut = run(tools(), "get_equipment_bonuses", new JsonObject());
		assertTrue(loggedOut.result.error);
		assertEquals("The player isn't logged in, so their equipment bonuses can't be read. Ask them to log in first.",
			loggedOut.result.content);
		assertEquals(0, game.reads.get());
		assertEquals(Arrays.asList("Didn't share your equipment bonuses: \"" + ITEMS + "\" is off",
			"Didn't share your item stats: \"" + ITEMS + "\" is off",
			"Couldn't share your equipment bonuses: you're not logged in"), activity);
	}

	@Test
	public void aFailedReadIsAnErrorNotACrash() throws Exception
	{
		game.failure = new IllegalStateException("no item data");
		Ended e = run(tools(), "get_equipment", new JsonObject());
		assertTrue(e.result.error);
		assertEquals("Reading the game failed inside AI Chat. Answer without it.", e.result.content);
		assertNotEquals(CLIENT_THREAD, e.thread);
		assertEquals(Collections.singletonList("Couldn't read your equipment"), activity);
	}

	@Test
	public void givesUpWhenTheClientThreadNeverRuns() throws Exception
	{
		List<Runnable> neverRun = new CopyOnWriteArrayList<>();
		GameDataTools tools = tools(neverRun::add, 100);
		Ended e = run(tools, "get_inventory", new JsonObject());
		assertTrue(e.result.error);
		assertTrue(e.result.content, e.result.content.startsWith("The game didn't answer in time"));
		assertEquals(Collections.singletonList("Couldn't read your inventory: the game didn't answer"), activity);

		// If the game gets to it after all, the call has already ended: no second result.
		neverRun.get(0).run();
		executor.submit(() -> { }).get(5, TimeUnit.SECONDS);
		assertEquals(1, e.times.get());
		assertEquals(1, activity.size());
	}

	@Test
	public void clientThreadUnavailable() throws Exception
	{
		Ended e = run(tools(r ->
		{
			throw new IllegalStateException("closing");
		}, GameDataTools.READ_TIMEOUT_MILLIS), "get_equipment", new JsonObject());
		assertTrue(e.result.error);
		assertEquals(Collections.singletonList("Couldn't read your equipment"), activity);
	}

	@Test
	public void unknownTool() throws Exception
	{
		Ended e = run(tools(), "get_location", new JsonObject());
		assertTrue(e.result.error);
		assertEquals("There's no tool called get_location.", e.result.content);
		assertEquals(0, game.reads.get());
		assertEquals(Collections.singletonList("Skipped an unknown look-up \"get_location\""), activity);
	}

	@Test
	public void aBrokenActivityLineStillEndsTheCall() throws Exception
	{
		GameDataTools tools = new GameDataTools(game, client::execute, executor, () -> true, () -> true, true, line ->
		{
			throw new IllegalStateException("panel gone");
		});
		Ended e = run(tools, "get_equipment", new JsonObject());
		assertFalse(e.result.error);
	}

	@Test
	public void aResultThatWasNeverSentIsntCalledShared()
	{
		assertEquals("Read your equipment, but didn't share it: the request had stopped",
			GameDataTools.unshared("Shared your equipment"));
		// Nothing was shared anyway.
		String off = "Didn't share your bank: \"" + ITEMS + "\" is off";
		assertEquals(off, GameDataTools.unshared(off));
		// Nor does the panel say so over the reply.
		assertEquals(null, PanelText.summary(Arrays.asList(GameDataTools.unshared("Shared your equipment"),
			GameDataTools.unshared("Searched your bank for \"rune\""), off)).sharing);
	}
}
