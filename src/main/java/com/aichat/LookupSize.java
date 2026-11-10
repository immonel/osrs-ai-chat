package com.aichat;

/**
 * How much the tools hand back in one answer. Claude and ChatGPT models have room for a whole bank and long Wiki
 * pages; a small model on the player's own computer (Ollama, LM Studio) may have only a few thousand tokens, so for
 * the OpenAI-compatible provider the short sizes stay unless "Full look-ups" is on. Whatever the size, a cut list or
 * page says so.
 */
enum LookupSize
{
	/** For models with little room: the sizes AI Chat has always used. */
	SHORT(300, 6, 12_000, 6_000, 5),
	/** For large models: the whole bank, and much more of the Wiki. */
	FULL(Integer.MAX_VALUE, 10, 40_000, 20_000, 15);

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

	LookupSize(int bankLines, int searchResults, int pageChars, int longPageChars, int priceResults)
	{
		this.bankLines = bankLines;
		this.searchResults = searchResults;
		this.pageChars = pageChars;
		this.longPageChars = longPageChars;
		this.priceResults = priceResults;
	}

	/** The size for the chosen provider: full for Claude and ChatGPT, and for any other service when asked for. */
	static LookupSize of(AiChatConfig.Provider provider, boolean fullLookups)
	{
		return provider == AiChatConfig.Provider.OPENAI_COMPATIBLE && !fullLookups ? SHORT : FULL;
	}
}
