package com.aichat;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

/** How much a look-up hands back, by provider. */
public class LookupSizeTest
{
	@Test
	public void claudeAndChatGptAlwaysGetFullLookUps()
	{
		assertEquals(LookupSize.FULL, LookupSize.of(AiChatConfig.Provider.CLAUDE, false));
		assertEquals(LookupSize.FULL, LookupSize.of(AiChatConfig.Provider.CHATGPT, false));
	}

	@Test
	public void otherServicesStayShortUntilAskedForFull()
	{
		// A small model on the player's own computer may not have room for a whole bank.
		assertEquals(LookupSize.SHORT, LookupSize.of(AiChatConfig.Provider.OPENAI_COMPATIBLE, false));
		assertEquals(LookupSize.FULL, LookupSize.of(AiChatConfig.Provider.OPENAI_COMPATIBLE, true));
	}

	@Test
	public void theShortSizesAreTheOldOnes()
	{
		assertEquals(GameData.BANK_LINES, LookupSize.SHORT.bankLines);
		assertEquals(12_000, LookupSize.SHORT.pageChars);
	}
}
