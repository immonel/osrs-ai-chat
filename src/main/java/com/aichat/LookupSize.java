package com.aichat;

/**
 * How much the tools hand back in one answer. Claude and ChatGPT models have room for a whole bank and long Wiki
 * pages; a small model on the player's own computer (Ollama, LM Studio) may have only a few thousand tokens, so for
 * the OpenAI-compatible provider the short sizes stay unless "Look-up size" says Full. Whatever the size, a cut list or
 * page says so.
 */
enum LookupSize
{
	/** For models with little room: the sizes AI Chat has always used. */
	SHORT(300, 6, 12_000, 6_000, 5, 25),
	/** For large models: the whole bank, much more of the Wiki, and every item for a slot most players own. */
	FULL(Integer.MAX_VALUE, 10, 40_000, 20_000, 15, 100);

	/** Most items a bank answer lists. */
	final int bankLines;
	/** Most pages a Wiki search lists. */
	final int searchResults;
	/** Most characters of a Wiki page... */
	final int pageChars;
	/** ...and past this many, a whole page ends with its sections, so the model can ask for one next time. */
	final int longPageChars;
	/** Most items a GE price answer lists. */
	final int priceResults;
	/** Most items an item-stats answer lists: each line carries every bonus, so it's about four times a bank line. */
	final int statLines;

	LookupSize(int bankLines, int searchResults, int pageChars, int longPageChars, int priceResults, int statLines)
	{
		this.statLines = statLines;
		this.bankLines = bankLines;
		this.searchResults = searchResults;
		this.pageChars = pageChars;
		this.longPageChars = longPageChars;
		this.priceResults = priceResults;
	}

	/** The size the player chose; Auto is full for Claude and ChatGPT and short for any other service. */
	static LookupSize of(AiChatConfig.Provider provider, AiChatConfig.LookupSizeChoice choice)
	{
		switch (choice)
		{
			case FULL:
				return FULL;
			case SHORT:
				return SHORT;
			default:
				return provider == AiChatConfig.Provider.OPENAI_COMPATIBLE ? SHORT : FULL;
		}
	}
}
