package com.aichat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.client.game.ItemEquipmentStats;
import net.runelite.client.game.ItemStats;

/**
 * Real items, with their stats exactly as RuneLite's item data has them: static.runelite.net/item/stats.ids.min.json,
 * the file ItemManager loads, checked on 2026-10-22. Ids and names come from RuneLite's ItemID constants, and for
 * tradeable items the name and buy limit were checked again against the Wiki's prices API (prices.runescape.wiki
 * /api/v1/osrs/mapping). The item tools exist because a model made item stats up, so every real name here carries
 * only figures from that data; anything else a test needs is given a name that's plainly made up.
 */
final class ItemFixtures
{
	static final int ABYSSAL_WHIP_ID = 4151;
	static final ItemStats ABYSSAL_WHIP = gear(70, ItemEquipmentStats.builder().slot(3).aslash(82).str(82).aspeed(4));
	static final int DRAGON_SCIMITAR_ID = 4587;
	static final ItemStats DRAGON_SCIMITAR = gear(70, ItemEquipmentStats.builder().slot(3).astab(8).aslash(67).acrush(-2)
		.dslash(1).str(66).aspeed(4));
	/** 12931, charged: untradeable, so no buy limit. */
	static final ItemStats SERPENTINE_HELM = gear(0, ItemEquipmentStats.builder().slot(0).amagic(-5).arange(-5).dstab(52)
		.dslash(55).dcrush(58).drange(50).str(5));
	/** 10551. */
	static final ItemStats FIGHTER_TORSO = gear(0, ItemEquipmentStats.builder().slot(4).amagic(-40).dstab(62).dslash(85)
		.dcrush(62).dmagic(-10).drange(67).str(4));
	/** 6737. */
	static final ItemStats BERSERKER_RING = gear(8, ItemEquipmentStats.builder().slot(12).dcrush(4).str(4));
	/** 861. */
	static final ItemStats MAGIC_SHORTBOW = gear(18_000, ItemEquipmentStats.builder().slot(3).isTwoHanded(true)
		.arange(69).aspeed(4));
	/** 12926, charged. */
	static final ItemStats TOXIC_BLOWPIPE = gear(0, ItemEquipmentStats.builder().slot(3).isTwoHanded(true).arange(30)
		.rstr(20).aspeed(3));
	/** 33296: an off-hand with an attack speed in the data. */
	static final ItemStats DEMONIC_SCEPTRE = gear(0, ItemEquipmentStats.builder().slot(5).aspeed(4));
	/** 4675. */
	static final ItemStats ANCIENT_STAFF = gear(70, ItemEquipmentStats.builder().slot(3).astab(10).aslash(-1).acrush(40)
		.amagic(15).dstab(2).dslash(3).dcrush(1).dmagic(15).str(50).prayer(-1).aspeed(4));
	/** 21021. */
	static final ItemStats ANCESTRAL_ROBE_TOP = gear(8, ItemEquipmentStats.builder().slot(4).amagic(35).arange(-8)
		.dstab(42).dslash(31).dcrush(51).dmagic(28).mdmg(3f));
	/** 12002. */
	static final ItemStats OCCULT_NECKLACE = gear(8, ItemEquipmentStats.builder().slot(2).amagic(12).prayer(2).mdmg(5f));
	/** 6731. */
	static final ItemStats SEERS_RING = gear(8, ItemEquipmentStats.builder().slot(12).amagic(6).dmagic(6).mdmg(0.2f));
	/** 6585. */
	static final ItemStats AMULET_OF_FURY = gear(8, ItemEquipmentStats.builder().slot(2).astab(10).aslash(10).acrush(10)
		.amagic(10).arange(10).dstab(15).dslash(15).dcrush(15).dmagic(15).drange(15).str(8).prayer(5));
	/** 6570: untradeable. */
	static final ItemStats FIRE_CAPE = gear(0, ItemEquipmentStats.builder().slot(1).astab(1).aslash(1).acrush(1).amagic(1)
		.arange(1).dstab(11).dslash(11).dcrush(11).dmagic(11).drange(11).str(4).prayer(2));
	/** 10828. */
	static final ItemStats HELM_OF_NEITIZNOT = gear(70, ItemEquipmentStats.builder().slot(0).dstab(31).dslash(29)
		.dcrush(34).dmagic(3).drange(30).str(3).prayer(3));
	static final int RED_PARTYHAT_ID = 1038;
	static final int RUNE_PLATEBODY_ID = 1127;
	static final ItemStats RUNE_PLATEBODY = gear(70, ItemEquipmentStats.builder().slot(4).amagic(-30).arange(-15)
		.dstab(82).dslash(80).dcrush(72).dmagic(-6).drange(80));
	/**
	 * 11283, charged. Its uncharged form, 11284, has the same name and less defence. Untradeable (the Wiki's
	 * tradeable2 = No), though the data gives it the uncharged one's buy limit.
	 */
	static final ItemStats DRAGONFIRE_SHIELD = gear(8, ItemEquipmentStats.builder().slot(5).amagic(-10).arange(-5)
		.dstab(70).dslash(75).dcrush(72).dmagic(10).drange(72).str(7));
	/** 11284. */
	static final ItemStats DRAGONFIRE_SHIELD_UNCHARGED = gear(8, ItemEquipmentStats.builder().slot(5).amagic(-10)
		.arange(-5).dstab(20).dslash(25).dcrush(22).dmagic(10).drange(22).str(7));
	/**
	 * 4041, the Castle Wars cloak: a cape entry with its slot and nothing else, which is a real cape with no bonuses.
	 * Untradeable, though the data gives it a buy limit.
	 */
	static final ItemStats SARADOMIN_CLOAK = gear(8, ItemEquipmentStats.builder().slot(1));
	/** 1038: a head entry with its slot (0) and nothing else, which reads the same as an empty entry. */
	static final ItemStats RED_PARTYHAT = gear(5, ItemEquipmentStats.builder().slot(0));
	/** 892. */
	static final ItemStats RUNE_ARROW = gear(11_000, ItemEquipmentStats.builder().slot(13).rstr(49));
	/** 11212. */
	static final ItemStats DRAGON_ARROW = gear(11_000, ItemEquipmentStats.builder().slot(13).rstr(60));
	/** 22665: one of the entries that are empty, "equipment": {}, in the data. */
	static final ItemStats ARMADYL_GODSWORD_EMPTY = gear(8, ItemEquipmentStats.builder());
	static final int SHARK_ID = 385;
	/** Not equipment. */
	static final ItemStats SHARK = new ItemStats(false, 0.65, 10_000, null);

	private ItemFixtures()
	{
	}

	/** Weight isn't used, so it's left at 0 rather than copied. The buy limit isn't read either, but is as the data has it. */
	private static ItemStats gear(int geLimit, ItemEquipmentStats.ItemEquipmentStatsBuilder equipment)
	{
		return new ItemStats(true, 0, geLimit, equipment.build());
	}

	/**
	 * One item's line as the reads write it, through the same mapping from RuneLite's data. {@code slot}: the one it's
	 * being worn in, as the player sees it named ("Head"), or null.
	 */
	static GameData.StatLine line(String slot, GameData.Where where, String name, ItemStats stats)
	{
		return GameData.statLine(wornIn(slot), where, 1, null, new Lookup().add(1, name, stats));
	}

	private static EquipmentInventorySlot wornIn(String slot)
	{
		if (slot == null)
		{
			return null;
		}
		for (EquipmentInventorySlot s : EquipmentInventorySlot.values())
		{
			if (GameData.slotName(s).equals(slot))
			{
				return s;
			}
		}
		throw new IllegalArgumentException("no slot " + slot);
	}

	/**
	 * Item ids as RuneLite would answer for them. Ids not added have no name and no stats; notes and placeholders are
	 * added with the item they stand for.
	 */
	static final class Lookup implements GameData.ItemLookup
	{
		private final Map<Integer, String> names = new HashMap<>();
		private final Map<Integer, ItemStats> stats = new HashMap<>();
		private final Map<Integer, Integer> standsFor = new HashMap<>();
		private final Set<Integer> placeholders = new HashSet<>();
		/** Every id stats were asked for, in order. */
		final List<Integer> statsAsked = new ArrayList<>();

		Lookup add(int id, String name, ItemStats itemStats)
		{
			names.put(id, name);
			if (itemStats != null)
			{
				stats.put(id, itemStats);
			}
			return this;
		}

		/** A note of {@code item}: the same name, no stats of its own. */
		Lookup note(int id, int item)
		{
			names.put(id, names.get(item));
			standsFor.put(id, item);
			return this;
		}

		Lookup placeholder(int id, int item)
		{
			note(id, item);
			placeholders.add(id);
			return this;
		}

		@Override
		public String name(int id)
		{
			return names.get(id);
		}

		@Override
		public boolean placeholder(int id)
		{
			return placeholders.contains(id);
		}

		@Override
		public int canonicalize(int id)
		{
			return standsFor.getOrDefault(id, id);
		}

		@Override
		public ItemStats stats(int id)
		{
			statsAsked.add(id);
			return stats.get(id);
		}
	}
}
