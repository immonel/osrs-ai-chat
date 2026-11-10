package com.aichat;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

/** How much a look-up hands back, by provider. */
public class LookupSizeTest
{
	@Test
	public void autoIsFullForClaudeAndChatGpt()
	{
		assertEquals(LookupSize.FULL, LookupSize.of(AiChatConfig.Provider.CLAUDE, AiChatConfig.LookupSizeChoice.AUTO));
		assertEquals(LookupSize.FULL, LookupSize.of(AiChatConfig.Provider.CHATGPT, AiChatConfig.LookupSizeChoice.AUTO));
	}

	@Test
	public void autoIsShortForOtherServices()
	{
		// A small model on the player's own computer may not have room for a whole bank.
		assertEquals(LookupSize.SHORT, LookupSize.of(AiChatConfig.Provider.OPENAI_COMPATIBLE, AiChatConfig.LookupSizeChoice.AUTO));
	}

	@Test
	public void aChosenSizeHoldsForEveryProvider()
	{
		for (AiChatConfig.Provider provider : AiChatConfig.Provider.values())
		{
			assertEquals(LookupSize.FULL, LookupSize.of(provider, AiChatConfig.LookupSizeChoice.FULL));
			assertEquals(LookupSize.SHORT, LookupSize.of(provider, AiChatConfig.LookupSizeChoice.SHORT));
		}
	}

	@Test
	public void autoIsTheDefault()
	{
		assertEquals(AiChatConfig.LookupSizeChoice.AUTO, new AiChatConfig() {}.lookupSize());
	}

	@Test
	public void theShortSizesAreTheOldOnes()
	{
		assertEquals(GameData.BANK_LINES, LookupSize.SHORT.bankLines);
		assertEquals(12_000, LookupSize.SHORT.pageChars);
		assertEquals(GameData.STAT_LINES, LookupSize.SHORT.statLines);
	}
}
