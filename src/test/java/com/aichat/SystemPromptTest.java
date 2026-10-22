package com.aichat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * What the assistant is told about AI Chat's settings: their names as the settings panel shows them, and what each
 * covers.
 */
public class SystemPromptTest
{
	@Test
	public void eachSettingListsWhatItCoversUnderTheNameThePlayerSees()
	{
		// So the assistant names the setting that would share what it's missing, as the settings panel spells it.
		assertEquals(Arrays.asList("equipment", "inventory", "bank"), covered("shareItems"));
		assertEquals(Arrays.asList(
			"a [Character: ...] note at the start of their message: name, account type, levels and quest progress",
			"Slayer task",
			"achievement diaries"), covered("sendCharacter"));
		assertTrue(AiChatPlugin.SYSTEM_PROMPT.contains("say which setting would allow it"));
		assertFalse("the old name", AiChatPlugin.SYSTEM_PROMPT.contains("Send character info"));
	}

	@Test
	public void itExplainsFightsWithoutCallingThemLive()
	{
		// Jagex's Third Party Client Guidelines don't allow telling a player mid-fight which prayer to use, when attacks
		// come or where to stand. A question the player asks, answered like the Wiki would, is fine.
		assertTrue(AiChatPlugin.SYSTEM_PROMPT.contains("Explaining a fight's strategy, gear and mechanics is fine"));
		assertTrue(AiChatPlugin.SYSTEM_PROMPT.contains("don't act as a live caller"));
	}

	@Test
	public void itKnowsThePanelShowsTables()
	{
		// The panel draws tables (and turns wide ones into cards): the assistant mustn't refuse to make one.
		assertFalse(AiChatPlugin.SYSTEM_PROMPT.contains("don't use tables"));
		assertTrue(AiChatPlugin.SYSTEM_PROMPT.contains("small tables are fine"));
	}

	/**
	 * What the prompt lists under a setting, found by its name as the settings panel shows it on a line of its own: one
	 * list per setting, ended by a blank line.
	 */
	private static List<String> covered(String keyName)
	{
		String prompt = AiChatPlugin.SYSTEM_PROMPT;
		String heading = "\n\"" + SettingName.of(keyName) + "\":\n";
		int start = prompt.indexOf(heading);
		assertTrue("no list for " + heading.trim(), start >= 0);
		assertEquals("one list for " + heading.trim(), start, prompt.lastIndexOf(heading));
		start += heading.length();
		int end = prompt.indexOf("\n\n", start);
		assertTrue("no blank line after the list for " + keyName, end >= 0);
		List<String> lines = new ArrayList<>();
		for (String line : prompt.substring(start, end).split("\n"))
		{
			assertTrue("not a list line: " + line, line.startsWith("- "));
			lines.add(line.substring(2));
		}
		return lines;
	}
}
