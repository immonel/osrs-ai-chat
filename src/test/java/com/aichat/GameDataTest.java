package com.aichat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.Item;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.game.ItemEquipmentStats;
import net.runelite.client.game.ItemStats;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * How the player's own game data is written up for the model, from plain values (no game needed), and the item-stats
 * reads, with a stand-in for RuneLite's item lookups.
 */
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

	// ------------------------------------------------------------------
	// Item stats: real items with RuneLite's own figures (see ItemFixtures)
	// ------------------------------------------------------------------

	private static GameData.StatLine worn(String slot, String name, ItemStats stats)
	{
		return ItemFixtures.line(slot, GameData.Where.WORN, name, stats);
	}

	private static GameData.StatLine in(GameData.Where where, String name, ItemStats stats)
	{
		return ItemFixtures.line(null, where, name, stats);
	}

	private static int[] attackOf(ItemStats stats)
	{
		return GameData.bonuses(stats.getEquipment()).attack;
	}

	@Test
	public void theBestMeleeStyleIsWeighedAmongMeleeStylesOnly()
	{
		assertEquals("slash +82", GameData.bestMelee(attackOf(ItemFixtures.ABYSSAL_WHIP)));
		assertEquals("slash +67", GameData.bestMelee(attackOf(ItemFixtures.DRAGON_SCIMITAR)));
		assertEquals("stab, slash and crush alike +10", GameData.bestMelee(attackOf(ItemFixtures.AMULET_OF_FURY)));
		// A staff's crush bonus is bigger than its magic one, which says nothing about how to use it: magic isn't weighed.
		assertEquals("crush +40", GameData.bestMelee(attackOf(ItemFixtures.ANCIENT_STAFF)));
		assertNull(GameData.bestMelee(attackOf(ItemFixtures.MAGIC_SHORTBOW)));
		assertNull(GameData.bestMelee(attackOf(ItemFixtures.RUNE_PLATEBODY)));
		// Made-up bonuses, for a tie of two.
		assertEquals("stab or slash +5", GameData.bestMelee(new int[]{5, 5, 0, 0, 0}));
	}

	@Test
	public void theItemsTheModelGotWrongReadAsTheyAre()
	{
		// "+15 attack": it has no melee attack bonus at all, and a penalty to magic and ranged.
		assertEquals("attack stab 0, slash 0, crush 0, magic -5, ranged -5; defence stab +52, slash +55, crush +58, "
			+ "magic 0, ranged +50; strength +5.",
			GameData.describe(worn("Head", "Serpentine helm", ItemFixtures.SERPENTINE_HELM), true));
		// "Boosts attack": strength and a little crush defence, and no attack bonus.
		assertEquals("ring slot; no attack bonuses at all; defence stab 0, slash 0, crush +4, magic 0, ranged 0; "
			+ "strength +4.",
			GameData.describe(in(GameData.Where.BANK, "Berserker ring", ItemFixtures.BERSERKER_RING), true));
		// "For crush damage": its crush bonus is a penalty.
		assertEquals("weapon slot; one-handed, base attack speed 4 ticks (2.4 seconds); attack stab +8, slash +67, "
			+ "crush -2 (best melee style: slash +67), magic 0, ranged 0; defence stab 0, slash +1, crush 0, magic 0, "
			+ "ranged 0; strength +66.",
			GameData.describe(in(GameData.Where.INVENTORY, "Dragon scimitar", ItemFixtures.DRAGON_SCIMITAR), true));
	}

	@Test
	public void aStaffsMagicBonusIsListedApartFromItsMeleeBest()
	{
		assertEquals("weapon slot; one-handed, base attack speed 4 ticks (2.4 seconds); attack stab +10, slash -1, "
			+ "crush +40 (best melee style: crush +40), magic +15, ranged 0; defence stab +2, slash +3, crush +1, "
			+ "magic +15, ranged 0; strength +50, prayer -1.",
			GameData.describe(in(GameData.Where.BANK, "Ancient staff", ItemFixtures.ANCIENT_STAFF), true));
	}

	@Test
	public void weaponsHaveABaseSpeedAndOffHandsDont()
	{
		assertEquals("weapon slot; two-handed, base attack speed 4 ticks (2.4 seconds); attack stab 0, slash 0, crush 0, "
			+ "magic 0, ranged +69; no defence bonuses.",
			GameData.describe(in(GameData.Where.BANK, "Magic shortbow", ItemFixtures.MAGIC_SHORTBOW), true));
		assertEquals("weapon slot; two-handed, base attack speed 3 ticks (1.8 seconds); attack stab 0, slash 0, crush 0, "
			+ "magic 0, ranged +30; no defence bonuses; ranged strength +20.",
			GameData.describe(in(GameData.Where.INVENTORY, "Toxic blowpipe", ItemFixtures.TOXIC_BLOWPIPE), true));
		// The data gives this off-hand an attack speed; it isn't what attacks, so none is said.
		assertEquals("shield slot; no attack bonuses at all; no defence bonuses.",
			GameData.describe(in(GameData.Where.BANK, "Demonic sceptre", ItemFixtures.DEMONIC_SCEPTRE), true));
	}

	@Test
	public void magicDamageKeepsItsFractions()
	{
		assertEquals("body slot; attack stab 0, slash 0, crush 0, magic +35, ranged -8; defence stab +42, slash +31, "
			+ "crush +51, magic +28, ranged 0; magic damage +3%.",
			GameData.describe(in(GameData.Where.BANK, "Ancestral robe top", ItemFixtures.ANCESTRAL_ROBE_TOP), true));
		assertEquals("amulet slot; attack stab 0, slash 0, crush 0, magic +12, ranged 0; no defence bonuses; "
			+ "magic damage +5%, prayer +2.",
			GameData.describe(in(GameData.Where.BANK, "Occult necklace", ItemFixtures.OCCULT_NECKLACE), true));
		assertEquals("ring slot; attack stab 0, slash 0, crush 0, magic +6, ranged 0; defence stab 0, slash 0, crush 0, "
			+ "magic +6, ranged 0; magic damage +0.2%.",
			GameData.describe(in(GameData.Where.BANK, "Seers ring", ItemFixtures.SEERS_RING), true));
	}

	@Test
	public void bonusesThatArentKnownAreSaidNotZeroed()
	{
		// No stats at all, as for every item when RuneLite couldn't fetch its item data at startup.
		String missing = "bonuses not known: RuneLite's data has none for it (it may not have loaded this session, or "
			+ "the item is newer than the data), so don't guess them";
		assertEquals(missing + ", look them up on the Wiki.",
			GameData.describe(worn("Weapon", "Abyssal whip", null), true));
		assertEquals(missing + ".", GameData.describe(worn("Weapon", "Abyssal whip", null), false));
		// Whatever the data says, something being worn is equipment. (A made-up item: no real one is like this.)
		assertEquals(GameData.Data.MISSING,
			worn("Weapon", "Test toy", new ItemStats(false, 0, 0, null)).data);
		// Nor is an item the data itself calls equipable "not equipment" for want of an entry. (Made up as well.)
		assertEquals(GameData.Data.MISSING,
			in(GameData.Where.BANK, "Test toy", new ItemStats(true, 0, 0, null)).data);
		// An empty entry, "equipment": {}, which this godsword has in the data.
		String empty = "bonuses not known: RuneLite's data gives it no bonuses at all, which is how it has both hats "
			+ "that add nothing and items it has no figures for, so don't take it as having none";
		assertEquals(empty + ", check the Wiki.",
			GameData.describe(in(GameData.Where.BANK, "Armadyl godsword", ItemFixtures.ARMADYL_GODSWORD_EMPTY), true));
		// A hat that really adds nothing reads the same through ItemManager, so it's not called "none" either, unless
		// it's being worn on the head (see wornHatsAddNothingAndOnlyWornOnes).
		assertEquals(empty + ".",
			GameData.describe(in(GameData.Where.BANK, "Red partyhat", ItemFixtures.RED_PARTYHAT), false));
		// A slot and nothing else, in any slot but the head's, is real: a cape with no bonuses.
		assertEquals("cape slot; no attack bonuses at all; no defence bonuses.",
			GameData.describe(in(GameData.Where.BANK, "Saradomin cloak", ItemFixtures.SARADOMIN_CLOAK), true));
		// Not equipment at all, which the data does say.
		assertEquals("not equipment, so it has no combat bonuses at all.",
			GameData.describe(in(GameData.Where.INVENTORY, "Shark", ItemFixtures.SHARK), true));
	}

	@Test
	public void wornBonusesAddUpAndNameTheEmptySlots()
	{
		String text = GameData.bonusesText(Arrays.asList(
			worn("Head", "Helm of Neitiznot", ItemFixtures.HELM_OF_NEITIZNOT),
			worn("Cape", "Fire cape", ItemFixtures.FIRE_CAPE),
			worn("Weapon", "Abyssal whip", ItemFixtures.ABYSSAL_WHIP),
			worn("Ring", "Berserker ring", ItemFixtures.BERSERKER_RING)), true);
		// No level requirements anywhere in RuneLite's data: said, rather than left to be guessed at.
		assertTrue(text, text.startsWith("The player's equipment bonuses from RuneLite's own item data. These are bonuses "
			+ "only: RuneLite's item data carries no level requirements, so none are given here; look any up on the Wiki. "
			+ "Where an item's bonuses are given, one not listed is 0.\n"));
		assertTrue(text, text.contains("\nTotals for everything worn:\n"
			+ "- Attack: stab +1, slash +83, crush +1 (best melee style: slash +83), magic +1, ranged +1\n"
			+ "- Defence: stab +42, slash +40, crush +49, magic +14, ranged +41\n"
			+ "- Strength +93, ranged strength 0, magic damage 0%, prayer +5\n"
			+ "These totals are each item's own bonuses added up, whatever is in the ammunition slot included, even with a "
			+ "weapon that doesn't fire it. They leave out ammunition a weapon holds inside it (a blowpipe's darts), "
			+ "multipliers such as Tumeken's shadow's (for its own spell it multiplies the magic attack and magic damage "
			+ "of what's worn), set effects, and bonuses against particular targets, such as a slayer helmet's on task or "
			+ "a salve amulet's against the undead.\n"));
		assertFalse(text, text.contains("Incomplete"));
		assertTrue(text, text.contains("\n- Head: Helm of Neitiznot - no attack bonuses at all; defence stab +31, "
			+ "slash +29, crush +34, magic +3, ranged +30; strength +3, prayer +3.\n"));
		assertTrue(text, text.contains("\n- Weapon: Abyssal whip - one-handed, base attack speed 4 ticks (2.4 seconds); "
			+ "attack stab 0, slash +82, crush 0 (best melee style: slash +82), magic 0, ranged 0; no defence bonuses; "
			+ "strength +82.\n"));
		assertTrue(text, text.contains("\nNothing worn in: Amulet, Body, Shield, Legs, Gloves, Boots, Ammo.\n"));
		// A wand's or staff's speed isn't its spells': the Wiki's Attack speed page has combat spells at 5 ticks.
		assertTrue(text, text.endsWith("\nThe best melee style weighs stab, slash and crush against each other only: magic "
			+ "and ranged attack bonuses can't be compared with melee ones, since each goes with its own level, and which "
			+ "style works best also depends on the target's defences. Attack speeds are a weapon's base speed, and most "
			+ "ranged weapons attack a tick faster on rapid. Spells don't go at the weapon's speed: combat spells cast every "
			+ "5 ticks (3.0 seconds), except a powered staff's own spell (a trident's, for one), which goes at the speed "
			+ "given for that staff, and standard spells autocast with a harmonised nightmare staff (4 ticks)."));
	}

	@Test
	public void magicDamageAddsUpWithItsFractions()
	{
		String text = GameData.bonusesText(Arrays.asList(
			worn("Amulet", "Occult necklace", ItemFixtures.OCCULT_NECKLACE),
			worn("Body", "Ancestral robe top", ItemFixtures.ANCESTRAL_ROBE_TOP),
			worn("Ring", "Seers ring", ItemFixtures.SEERS_RING)), true);
		assertTrue(text, text.contains("\n- Attack: stab 0, slash 0, crush 0, magic +53, ranged -8\n"));
		assertTrue(text, text.contains("\n- Strength 0, ranged strength 0, magic damage +8.2%, prayer +2\n"));
	}

	@Test
	public void totalsSayWhatTheyLeaveOut()
	{
		String part = GameData.bonusesText(Arrays.asList(
			worn("Head", "Helm of Neitiznot", ItemFixtures.HELM_OF_NEITIZNOT),
			worn("Weapon", "Armadyl godsword", ItemFixtures.ARMADYL_GODSWORD_EMPTY)), true);
		assertTrue(part, part.contains("\n- Attack: stab 0, slash 0, crush 0, magic 0, ranged 0\n"));
		assertTrue(part, part.contains("\n- Incomplete: these leave out Armadyl godsword, whose bonuses RuneLite's data "
			+ "doesn't have, so the real totals may be higher or lower.\n"));
		assertTrue(part, part.contains("\n- Weapon: Armadyl godsword - bonuses not known: "));

		// RuneLite's item data didn't load: every piece unknown, and no totals rather than a set of zeroes.
		String none = GameData.bonusesText(Arrays.asList(worn("Head", "Helm of Neitiznot", null),
			worn("Weapon", "Abyssal whip", null)), false);
		assertTrue(none, none.contains("\nNo totals: RuneLite's data has no bonuses for anything the player is wearing "
			+ "(it may not have loaded this session), so don't work any out.\n"));
		assertFalse(none, none.contains("Totals for everything worn"));
		assertFalse(none, none.contains("not equipment"));
		assertTrue(none, none.contains("\n- Weapon: Abyssal whip - bonuses not known: "));
		assertFalse(none, none.contains("Wiki"));
	}

	@Test
	public void nothingWornHasNoBonuses()
	{
		assertEquals("The player isn't wearing or wielding anything, so they have no equipment bonuses at all.",
			GameData.bonusesText(Collections.emptyList(), true));
	}

	@Test
	public void aSearchKeepsSameNamedItemsWhoseStatsDiffer()
	{
		// The uncharged shield first, as it might be in a bank, then the charged one, and the uncharged one again under
		// another id (identical, so listed once).
		List<GameData.StatLine> bank = Arrays.asList(
			in(GameData.Where.BANK, "Dragonfire shield", ItemFixtures.DRAGONFIRE_SHIELD_UNCHARGED),
			in(GameData.Where.BANK, "Dragonfire shield", ItemFixtures.DRAGONFIRE_SHIELD),
			in(GameData.Where.BANK, "Dragonfire shield", ItemFixtures.DRAGONFIRE_SHIELD_UNCHARGED));
		String text = GameData.itemStatsText(new GameData.OwnedStats(bank, true, 0), "dragonfire", null, MINUTE, true);
		assertEquals(2, listed(text));
		assertTrue(text, text.contains("\n- Dragonfire shield (in their bank, one of 2 different items with this name) - "
			+ "shield slot; attack stab 0, slash 0, crush 0, magic -10, ranged -5; defence stab +20, slash +25, crush +22, "
			+ "magic +10, ranged +22; strength +7.\n"));
		assertTrue(text, text.contains("\n- Dragonfire shield (in their bank, one of 2 different items with this name) - "
			+ "shield slot; attack stab 0, slash 0, crush 0, magic -10, ranged -5; defence stab +70, slash +75, crush +72, "
			+ "magic +10, ranged +72; strength +7.\n"));

		// The same item worn and in the bank is two things the player has, and says where each is.
		String both = GameData.itemStatsText(new GameData.OwnedStats(Arrays.asList(
			worn("Shield", "Dragonfire shield", ItemFixtures.DRAGONFIRE_SHIELD),
			in(GameData.Where.BANK, "Dragonfire shield", ItemFixtures.DRAGONFIRE_SHIELD)), true, 0), "shield", null, 0, true);
		assertEquals(2, listed(both));
		assertTrue(both, both.contains("\n- Shield: Dragonfire shield (worn) - attack stab 0,"));
		assertTrue(both, both.contains("\n- Dragonfire shield (in their bank) - shield slot;"));
	}

	/**
	 * Asked about Verac's set, a model read four items' own figures, found no sign of the set's effect in them, and
	 * told the player the set had none. Every search that finds something now says a set's effect isn't in the figures.
	 */
	@Test
	public void itemStatsSayASetsEffectIsntInThem()
	{
		List<GameData.StatLine> bank = Arrays.asList(
			in(GameData.Where.BANK, "Dragonfire shield", ItemFixtures.DRAGONFIRE_SHIELD));
		String text = GameData.itemStatsText(new GameData.OwnedStats(bank, true, 0), "dragonfire", null, MINUTE, true);
		assertTrue(text, text.contains("\nThese are each item's own figures: some sets have an effect when every piece is "
			+ "worn together, which isn't in them, so don't conclude a set has none without checking its Wiki page.\n"));
		// Without the Wiki tools there's nowhere to check, so the model isn't sent there.
		String noWiki = GameData.itemStatsText(new GameData.OwnedStats(bank, true, 0), "dragonfire", null, MINUTE, false);
		assertTrue(noWiki, noWiki.contains("so don't conclude a set has none; that can't be checked here.\n"));
		assertFalse(noWiki, noWiki.contains("Wiki page"));
		// Nothing found, nothing to read wrongly.
		String none = GameData.itemStatsText(new GameData.OwnedStats(bank, true, 0), "twisted bow", null, MINUTE, true);
		assertFalse(none, none.contains("each item's own figures"));
	}

	@Test
	public void itemStatsAreCutWithANote()
	{
		List<GameData.StatLine> lines = new ArrayList<>();
		for (int i = 1; i <= GameData.STAT_LINES + 1; i++)
		{
			// Made-up items, with made-up bonuses.
			lines.add(in(GameData.Where.BANK, "Test blade " + i, new ItemStats(true, 0, 0,
				ItemEquipmentStats.builder().slot(3).aslash(i).aspeed(4).build())));
		}
		String text = GameData.itemStatsText(new GameData.OwnedStats(lines, true, 0), "test blade", null, 0, true);
		assertEquals(GameData.STAT_LINES, listed(text));
		assertTrue(text, text.contains("\n...and 1 more matching item not listed. Search for more of the name to see "
			+ "them.\n"));
	}

	@Test
	public void aSearchThatFindsNothingSaysWhereItLooked()
	{
		List<GameData.StatLine> shark = Collections.singletonList(in(GameData.Where.INVENTORY, "Shark", ItemFixtures.SHARK));
		assertEquals("Nothing the player is wearing or carrying, or has in their bank as last seen 2 minutes ago, has "
			+ "\"twisted bow\" in its name. For an item they don't own, look it up on the Wiki.",
			GameData.itemStatsText(new GameData.OwnedStats(shark, true, 0), "twisted bow", null, 2 * MINUTE, true));
		// No Wiki tools with this request: nowhere to send the model.
		assertEquals("Nothing the player is wearing or carrying, or has in their bank as last seen 2 minutes ago, has "
			+ "\"twisted bow\" in its name.",
			GameData.itemStatsText(new GameData.OwnedStats(shark, true, 0), "twisted bow", null, 2 * MINUTE, false));
		// The bank wasn't searched, so it isn't "they don't have it".
		assertEquals("Nothing the player is wearing or carrying has \"twisted bow\" in its name. Their bank hasn't been "
			+ "open since AI Chat could see it, so it wasn't searched: ask them to open it once, then search again.",
			GameData.itemStatsText(new GameData.OwnedStats(shark, false, 0), "twisted bow", null, 0, true));

		String carried = GameData.itemStatsText(new GameData.OwnedStats(shark, false, 0), "shark", null, 0, false);
		assertTrue(carried, carried.startsWith("The player's items with \"shark\" in their name, with the bonuses "
			+ "RuneLite's own item data gives them. Searched what they're wearing and their inventory; their bank hasn't "
			+ "been open since AI Chat could see it, so it wasn't searched (ask them to open it once to include it). These "
			+ "are bonuses only: RuneLite's item data carries no level requirements, so none are given here, and none "
			+ "should be guessed. Where an item's bonuses are given, one not listed is 0.\n"));
		assertFalse(carried, carried.contains("Wiki"));
	}

	// The reads, with a stand-in for RuneLite's item lookups

	@Test
	public void aNoteIsLookedUpAsTheItemItStandsFor()
	{
		// The note's id is made up here: only its link to the item matters.
		ItemFixtures.Lookup lookup = new ItemFixtures.Lookup()
			.add(ItemFixtures.RUNE_PLATEBODY_ID, "Rune platebody", ItemFixtures.RUNE_PLATEBODY)
			.note(90_001, ItemFixtures.RUNE_PLATEBODY_ID);
		GameData.StatLine line = GameData.statLine(null, GameData.Where.INVENTORY, 90_001, null, lookup);
		assertEquals("Rune platebody", line.name);
		assertEquals(GameData.Data.BONUSES, line.data);
		assertEquals(82, line.bonuses.defence[0]);
		assertEquals(Collections.singletonList(ItemFixtures.RUNE_PLATEBODY_ID), lookup.statsAsked);
	}

	@Test
	public void placeholdersAreLeftOutAndMissingStatsAreMissing()
	{
		ItemFixtures.Lookup lookup = new ItemFixtures.Lookup()
			.add(ItemFixtures.ABYSSAL_WHIP_ID, "Abyssal whip", null)
			.placeholder(90_002, ItemFixtures.ABYSSAL_WHIP_ID)
			.add(ItemFixtures.SHARK_ID, "Shark", ItemFixtures.SHARK);
		assertNull(GameData.statLine(null, GameData.Where.BANK, 90_002, null, lookup));
		assertTrue(lookup.statsAsked.isEmpty());

		GameData.StatLine noStats = GameData.statLine(null, GameData.Where.BANK, ItemFixtures.ABYSSAL_WHIP_ID, null, lookup);
		assertEquals(GameData.Data.MISSING, noStats.data);
		assertNull(noStats.bonuses);

		GameData.StatLine food = GameData.statLine(null, GameData.Where.INVENTORY, ItemFixtures.SHARK_ID, null, lookup);
		assertEquals(GameData.Data.NOT_EQUIPMENT, food.data);

		GameData.StatLine empty = in(GameData.Where.BANK, "Armadyl godsword", ItemFixtures.ARMADYL_GODSWORD_EMPTY);
		assertEquals(GameData.Data.EMPTY, empty.data);
		assertNull(empty.bonuses);
	}

	@Test
	public void everyFieldOfRuneLitesBonusesIsCarriedAcross()
	{
		// Made-up figures, all different, so a field read from the wrong place shows.
		GameData.Bonuses b = GameData.bonuses(ItemEquipmentStats.builder().slot(3).isTwoHanded(true)
			.astab(1).aslash(2).acrush(3).amagic(4).arange(5).dstab(6).dslash(7).dcrush(8).dmagic(9).drange(10)
			.str(11).rstr(12).mdmg(13.5f).prayer(14).aspeed(15).build());
		assertEquals(EquipmentInventorySlot.WEAPON, b.slot);
		assertTrue(b.twoHanded);
		assertTrue(Arrays.equals(new int[]{1, 2, 3, 4, 5}, b.attack));
		assertTrue(Arrays.equals(new int[]{6, 7, 8, 9, 10}, b.defence));
		assertEquals(11, b.strength);
		assertEquals(12, b.rangedStrength);
		assertEquals(13.5f, b.magicDamage, 0);
		assertEquals(14, b.prayer);
		assertEquals(15, b.attackSpeed);

		// Slot numbers are the worn-items container's; one this plugin doesn't name is no slot, not the head.
		assertEquals(EquipmentInventorySlot.HEAD, GameData.slot(0));
		assertEquals(EquipmentInventorySlot.AMMO, GameData.slot(EquipmentInventorySlot.AMMO.getSlotIdx()));
		assertNull(GameData.slot(99));
		assertNull(GameData.bonuses(ItemEquipmentStats.builder().slot(99).astab(1).build()).slot);
		// Empty, in the head's slot 0: not known. A slot and nothing else anywhere else: a real item with none.
		assertNull(GameData.bonuses(ItemEquipmentStats.builder().build()));
		assertEquals(EquipmentInventorySlot.CAPE, GameData.bonuses(ItemEquipmentStats.builder().slot(1).build()).slot);
	}

	@Test
	public void wornItemsAreReadSlotBySlot()
	{
		ItemFixtures.Lookup lookup = new ItemFixtures.Lookup()
			.add(ItemFixtures.ABYSSAL_WHIP_ID, "Abyssal whip", ItemFixtures.ABYSSAL_WHIP)
			.add(10_828, "Helm of Neitiznot", ItemFixtures.HELM_OF_NEITIZNOT);
		Item[] worn = new Item[14];
		worn[EquipmentInventorySlot.WEAPON.getSlotIdx()] = new Item(ItemFixtures.ABYSSAL_WHIP_ID, 1);
		worn[EquipmentInventorySlot.HEAD.getSlotIdx()] = new Item(10_828, 1);
		worn[EquipmentInventorySlot.RING.getSlotIdx()] = new Item(-1, 0);
		List<GameData.StatLine> lines = GameData.wornStats(worn, null, lookup);
		assertEquals(2, lines.size());
		assertEquals("Head", lines.get(0).slot);
		assertEquals("Helm of Neitiznot", lines.get(0).name);
		assertEquals("Weapon", lines.get(1).slot);
		assertEquals(GameData.Where.WORN, lines.get(1).where);
		// A container shorter than the slots, as a stand-in might give, is read as far as it goes.
		assertEquals(1, GameData.wornStats(new Item[]{new Item(10_828, 1)}, null, lookup).size());
	}

	@Test
	public void aSearchLooksUpStatsOnlyForWhatItFinds()
	{
		ItemFixtures.Lookup lookup = new ItemFixtures.Lookup()
			.add(ItemFixtures.ABYSSAL_WHIP_ID, "Abyssal whip", ItemFixtures.ABYSSAL_WHIP)
			.add(ItemFixtures.DRAGON_SCIMITAR_ID, "Dragon scimitar", ItemFixtures.DRAGON_SCIMITAR)
			.add(ItemFixtures.RUNE_PLATEBODY_ID, "Rune platebody", ItemFixtures.RUNE_PLATEBODY)
			.add(ItemFixtures.SHARK_ID, "Shark", ItemFixtures.SHARK)
			.note(90_001, ItemFixtures.RUNE_PLATEBODY_ID);
		Item[] worn = new Item[14];
		worn[EquipmentInventorySlot.WEAPON.getSlotIdx()] = new Item(ItemFixtures.ABYSSAL_WHIP_ID, 1);
		Item[] inventory = {new Item(ItemFixtures.SHARK_ID, 1), new Item(90_001, 3), new Item(ItemFixtures.SHARK_ID, 1)};
		GameData.BankSnapshot bank = GameData.BankSnapshot.of(new Item[]{new Item(ItemFixtures.DRAGON_SCIMITAR_ID, 1),
			new Item(ItemFixtures.RUNE_PLATEBODY_ID, 2), new Item(ItemFixtures.SHARK_ID, 100)}, 42L, 1000L);

		GameData.OwnedStats found = GameData.ownedStats(worn, inventory, bank, "RUNE", null, lookup);
		assertEquals(2, found.lines.size());
		assertEquals(GameData.Where.INVENTORY, found.lines.get(0).where);
		assertEquals(GameData.Where.BANK, found.lines.get(1).where);
		assertTrue(found.bankSeen);
		assertEquals(1000L, found.bankSeenAt);
		assertEquals(Arrays.asList(ItemFixtures.RUNE_PLATEBODY_ID, ItemFixtures.RUNE_PLATEBODY_ID), lookup.statsAsked);

		// A bank not seen on this account: the rest is still searched, and it says the bank wasn't.
		GameData.OwnedStats noBank = GameData.ownedStats(worn, inventory, null, "whip", null, lookup);
		assertFalse(noBank.bankSeen);
		assertEquals(1, noBank.lines.size());
		assertEquals("Weapon", noBank.lines.get(0).slot);
	}

	@Test
	public void aHatWornOnTheHeadAddsNothingButOneNotWornIsntKnown()
	{
		ItemFixtures.Lookup lookup = new ItemFixtures.Lookup()
			.add(ItemFixtures.RED_PARTYHAT_ID, "Red partyhat", ItemFixtures.RED_PARTYHAT)
			.add(ItemFixtures.ABYSSAL_WHIP_ID, "Abyssal whip", ItemFixtures.ABYSSAL_WHIP);
		Item[] worn = new Item[14];
		worn[EquipmentInventorySlot.HEAD.getSlotIdx()] = new Item(ItemFixtures.RED_PARTYHAT_ID, 1);
		worn[EquipmentInventorySlot.WEAPON.getSlotIdx()] = new Item(ItemFixtures.ABYSSAL_WHIP_ID, 1);
		List<GameData.StatLine> lines = GameData.wornStats(worn, null, lookup);
		// None of the data's empty entries can be worn on the head, so a head entry with nothing in it, worn there, is
		// a hat that adds nothing.
		assertEquals(GameData.Data.BONUSES, lines.get(0).data);
		assertEquals(EquipmentInventorySlot.HEAD, lines.get(0).goesIn());
		String text = GameData.bonusesText(lines, true);
		assertTrue(text, text.contains("\n- Head: Red partyhat - no attack bonuses at all; no defence bonuses.\n"));
		// So it doesn't make the totals incomplete.
		assertFalse(text, text.contains("Incomplete"));
		assertTrue(text, text.contains("\n- Attack: stab 0, slash +82, crush 0 (best melee style: slash +82), magic 0, "
			+ "ranged 0\n"));

		// Not worn, nothing here can tell it goes on the head: not known.
		assertEquals(GameData.Data.EMPTY,
			GameData.statLine(null, GameData.Where.BANK, ItemFixtures.RED_PARTYHAT_ID, null, lookup).data);
		// Worn anywhere else, an entry with nothing in it is still not known: the data's empty entries read like that.
		assertEquals(GameData.Data.EMPTY, ItemFixtures.line("Weapon", GameData.Where.WORN, "Armadyl godsword",
			ItemFixtures.ARMADYL_GODSWORD_EMPTY).data);
		// And worn on the head with no stats at all, nothing is known: "no bonuses" only ever comes from the data.
		assertEquals(GameData.Data.MISSING, ItemFixtures.line("Head", GameData.Where.WORN, "Red partyhat", null).data);
	}

	/** A worn scimitar, a staff and a blowpipe carried, and a bank with a bow, a whip and things that aren't weapons. */
	private static GameData.OwnedStats weapons(String search, EquipmentInventorySlot slot)
	{
		ItemFixtures.Lookup lookup = new ItemFixtures.Lookup()
			.add(ItemFixtures.ABYSSAL_WHIP_ID, "Abyssal whip", ItemFixtures.ABYSSAL_WHIP)
			.add(ItemFixtures.DRAGON_SCIMITAR_ID, "Dragon scimitar", ItemFixtures.DRAGON_SCIMITAR)
			.add(4675, "Ancient staff", ItemFixtures.ANCIENT_STAFF)
			.add(861, "Magic shortbow", ItemFixtures.MAGIC_SHORTBOW)
			.add(12_926, "Toxic blowpipe", ItemFixtures.TOXIC_BLOWPIPE)
			.add(ItemFixtures.RUNE_PLATEBODY_ID, "Rune platebody", ItemFixtures.RUNE_PLATEBODY)
			.add(ItemFixtures.SHARK_ID, "Shark", ItemFixtures.SHARK)
			.add(ItemFixtures.RED_PARTYHAT_ID, "Red partyhat", ItemFixtures.RED_PARTYHAT)
			.add(22_665, "Armadyl godsword", ItemFixtures.ARMADYL_GODSWORD_EMPTY)
			// Made up: an item RuneLite's data doesn't have.
			.add(90_003, "Test relic", null);
		Item[] worn = new Item[14];
		worn[EquipmentInventorySlot.WEAPON.getSlotIdx()] = new Item(ItemFixtures.DRAGON_SCIMITAR_ID, 1);
		Item[] inventory = {new Item(ItemFixtures.SHARK_ID, 1), new Item(4675, 1), new Item(12_926, 1)};
		GameData.BankSnapshot bank = GameData.BankSnapshot.of(new Item[]{new Item(861, 1),
			new Item(ItemFixtures.RUNE_PLATEBODY_ID, 1), new Item(ItemFixtures.RED_PARTYHAT_ID, 1), new Item(22_665, 1),
			new Item(90_003, 1), new Item(ItemFixtures.ABYSSAL_WHIP_ID, 1)}, 42L, 0L);
		return GameData.ownedStats(worn, inventory, bank, search, slot, lookup);
	}

	@Test
	public void aSlotListsWhatGoesThereWornFirstThenByTheLargestAttackBonus()
	{
		GameData.OwnedStats owned = weapons(null, EquipmentInventorySlot.WEAPON);
		// What isn't equipment, or goes in another slot, is left out; what has no known slot is named instead.
		assertEquals(5, owned.lines.size());
		assertEquals(Arrays.asList("Red partyhat", "Armadyl godsword", "Test relic"), owned.unplaced);

		String text = GameData.itemStatsText(owned, null, EquipmentInventorySlot.WEAPON, 2 * MINUTE, true);
		assertTrue(text, text.startsWith("The player's items that go in the weapon slot, with the bonuses RuneLite's own "
			+ "item data gives them. Searched what they're wearing, their inventory, and their bank as last seen 2 "
			+ "minutes ago. What's worn there comes first, then the rest by their largest attack bonus in any one style, "
			+ "highest first, then by their defence bonuses added up: an order, not a ranking, since magic and ranged "
			+ "bonuses can't be weighed against melee ones. These are bonuses only:"));
		// Worn first, then slash +82, ranged +69, crush +40, ranged +30: found as staff, blowpipe, bow, whip.
		List<String> order = new ArrayList<>();
		for (String line : text.split("\n"))
		{
			if (line.startsWith("- "))
			{
				order.add(line.substring(2, line.indexOf(" (")));
			}
		}
		assertEquals(Arrays.asList("Weapon: Dragon scimitar", "Abyssal whip", "Magic shortbow", "Ancient staff",
			"Toxic blowpipe"), order);
		assertTrue(text, text.contains("\nNot listed, because RuneLite's data has no bonuses for them and so doesn't say "
			+ "which slot they go in: Red partyhat, Armadyl godsword, Test relic. Search for one by name to see what's "
			+ "known about it.\nThese are each item's own figures:"));
		assertTrue(text, text.contains("without checking its Wiki page.\nThe best melee style weighs"));
		assertFalse(text, text.contains("Rune platebody"));
		assertFalse(text, text.contains("Shark"));

		// A name as well narrows it.
		String staff = GameData.itemStatsText(weapons("staff", EquipmentInventorySlot.WEAPON), "staff",
			EquipmentInventorySlot.WEAPON, 2 * MINUTE, true);
		assertTrue(staff, staff.startsWith("The player's items that go in the weapon slot and have \"staff\" in their "
			+ "name, with the bonuses"));
		assertEquals(1, listed(staff));
		assertFalse(staff, staff.contains("Not listed"));
	}

	@Test
	public void ammunitionGoesByRangedStrength()
	{
		ItemFixtures.Lookup lookup = new ItemFixtures.Lookup()
			.add(892, "Rune arrow", ItemFixtures.RUNE_ARROW)
			.add(11_212, "Dragon arrow", ItemFixtures.DRAGON_ARROW);
		GameData.BankSnapshot bank = GameData.BankSnapshot.of(new Item[]{new Item(892, 500), new Item(11_212, 100)},
			42L, 0L);
		GameData.OwnedStats owned = GameData.ownedStats(new Item[14], new Item[0], bank, null,
			EquipmentInventorySlot.AMMO, lookup);
		String text = GameData.itemStatsText(owned, null, EquipmentInventorySlot.AMMO, 0, false);
		assertTrue(text, text.contains("What's worn there comes first, then the rest by ranged strength, highest first. "));
		assertTrue(text, text.indexOf("\n- Dragon arrow") < text.indexOf("\n- Rune arrow"));
		assertTrue(text, text.contains("\n- Dragon arrow (in their bank) - ammo slot; no attack bonuses at all; no defence "
			+ "bonuses; ranged strength +60.\n"));
	}

	@Test
	public void aSlotWithNothingInItSaysWhereItLooked()
	{
		GameData.OwnedStats rings = weapons(null, EquipmentInventorySlot.RING);
		assertTrue(rings.lines.isEmpty());
		assertEquals("Nothing the player is wearing or carrying, or has in their bank as last seen 2 minutes ago, goes in "
			+ "the ring slot. For an item they don't own, look it up on the Wiki.\nNot listed, because RuneLite's data has "
			+ "no bonuses for them and so doesn't say which slot they go in: Red partyhat, Armadyl godsword, Test relic. "
			+ "Search for one by name to see what's known about it.",
			GameData.itemStatsText(rings, null, EquipmentInventorySlot.RING, 2 * MINUTE, true));
		// The bank not seen: not "they have none", as with a search by name.
		assertEquals("Nothing the player is wearing or carrying goes in the ring slot and has \"seers\" in its name. Their "
			+ "bank hasn't been open since AI Chat could see it, so it wasn't searched: ask them to open it once, then "
			+ "search again.",
			GameData.itemStatsText(new GameData.OwnedStats(new ArrayList<>(), false, 0), "seers",
				EquipmentInventorySlot.RING, 0, true));
	}

	@Test
	public void aSlotListingIsCutWithANote()
	{
		List<GameData.StatLine> lines = new ArrayList<>();
		for (int i = 1; i <= GameData.STAT_LINES + 2; i++)
		{
			// Made-up items, with made-up bonuses.
			lines.add(in(GameData.Where.BANK, "Test blade " + i, new ItemStats(true, 0, 0,
				ItemEquipmentStats.builder().slot(3).aslash(i).aspeed(4).build())));
		}
		String text = GameData.itemStatsText(new GameData.OwnedStats(lines, true, 0), null, EquipmentInventorySlot.WEAPON,
			0, true);
		assertEquals(GameData.STAT_LINES, listed(text));
		// The biggest first, so the two cut are the smallest.
		assertTrue(text, text.contains("\n- Test blade 27 (in their bank)"));
		assertFalse(text, text.contains("\n- Test blade 2 (in their bank)"));
		assertTrue(text, text.contains("\n...and 2 more items for this slot not listed, further down the order (magic "
			+ "attack bonuses run lower than melee and ranged ones, so a staff or wand may be among them). Give part of a "
			+ "name as well to see others.\n"));
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
			300, Collections.singletonList("Dragon Slayer I"), Collections.emptyList());
		assertTrue(s, s.startsWith("[Character: Zezima, a hardcore ironman, combat level 126, total level 2277. "
			+ "Levels: Attack 99, Sailing 1. Quest points: 300."));
		// An account type the plugin doesn't know yet is left out rather than guessed.
		String unknown = CharacterInfo.format("Zezima", CharacterInfo.accountType(9), 126, 2277, Collections.emptyList(),
			0, Collections.emptyList(), Collections.emptyList());
		assertTrue(unknown, unknown.startsWith("[Character: Zezima, combat level 126,"));
	}
}
