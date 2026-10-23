package com.aichat;

import java.util.Collections;
import java.util.List;
import net.runelite.api.QuestState;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The character note sent with a message when the player shares their character. */
public class CharacterInfoTest
{
	@Test
	public void characterInfoReadsLikeANote()
	{
		String s = CharacterInfo.format("Zezima", CharacterInfo.accountType(1), 126, 2277, List.of("Attack 99", "Sailing 1"),
			300, 158, List.of("Dragon Slayer II", "Song of the Elves"), 206);
		assertTrue(s, s.startsWith("[Character: Zezima, an ironman, combat level 126, total level 2277. "
			+ "Levels: Attack 99, Sailing 1."));
		assertTrue(s, s.contains("Quest points: 300. Quests: 158 of 206 completed, 2 in progress, 46 not started. "
			+ "These counts take in miniquests"));
		assertTrue(s, s.endsWith("won't match the game's own quest count. In progress: Dragon Slayer II, Song of the Elves.]"));
	}

	/** Completed quests are only counted: get_quests names them, when a question is about them. */
	@Test
	public void withNothingInProgressNoQuestIsNamed()
	{
		String s = CharacterInfo.format("Zezima", CharacterInfo.accountType(0), 126, 2277, List.of("Attack 99"),
			300, 206, Collections.emptyList(), 206);
		assertTrue(s, s.contains("Quests: 206 of 206 completed, 0 in progress, 0 not started."));
		assertTrue(s, s.endsWith("won't match the game's own quest count.]"));
		assertFalse(s, s.contains("In progress:"));
	}

	/** The model sees both, so they must agree: the same counts in the same words. */
	@Test
	public void theNoteCountsQuestsAsGetQuestsDoes()
	{
		List<GameData.QuestLine> quests = List.of(
			new GameData.QuestLine("Cook's Assistant", QuestState.FINISHED, false),
			new GameData.QuestLine("Dragon Slayer II", QuestState.IN_PROGRESS, false),
			new GameData.QuestLine("Desert Treasure II", QuestState.NOT_STARTED, false),
			new GameData.QuestLine("Alfred Grimhand's Barcrawl", QuestState.FINISHED, true));
		String counts = GameData.questText(quests, null).split("\n")[0].substring("The player's quests: ".length());
		String s = CharacterInfo.format("Zezima", null, 3, 32, List.of("Attack 1"), 0, 2, List.of("Dragon Slayer II"), 4);
		assertTrue(s, s.contains("Quests: " + counts + " In progress: Dragon Slayer II.]"));
	}

	@Test
	public void theAccountTypeIsLeftOutWhenItIsntKnown()
	{
		assertEquals("a regular account (not an ironman)", CharacterInfo.accountType(0));
		assertEquals("an unranked group ironman", CharacterInfo.accountType(6));
		assertNull(CharacterInfo.accountType(7));
		String s = CharacterInfo.format("Zezima", null, 3, 32, List.of("Attack 1"), 0, 0, List.of("Cook's Assistant"), 206);
		assertTrue(s, s.startsWith("[Character: Zezima, combat level 3, total level 32."));
		assertTrue(s, s.contains("Quests: 0 of 206 completed, 1 in progress, 205 not started."));
		assertTrue(s, s.endsWith(" In progress: Cook's Assistant.]"));
	}
}
