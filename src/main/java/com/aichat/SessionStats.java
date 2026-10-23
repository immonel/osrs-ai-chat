package com.aichat;

import net.runelite.api.GameState;

/**
 * Whether the levels the client has are this session's. The client keeps its skill levels, experience and run energy
 * from one session to the next, and only the server's updates write them, so for a moment after logging in or hopping
 * worlds they still hold the last session's numbers: after switching accounts, another account's. Anything that reads
 * levels for the model checks {@link #arrived()} first. One for as long as RuneLite runs; client thread only, like the
 * events that drive it.
 * <p>
 * A login sends every stat within its first game ticks, so they're taken as in {@link #TICKS_TO_TRUST} ticks after the
 * last login or hop, as RuneLite's XpTrackerPlugin takes them after two. A game tick is the server's tick packet, in
 * the same stream as its stat updates, so lag can't put the stats behind it. A lost connection doesn't start over: a
 * reconnection resumes the same account, and one that fails goes to the login screen, which does.
 */
final class SessionStats
{
	/** Game ticks after a login or hop: one more than XpTrackerPlugin's two, to spare. */
	static final int TICKS_TO_TRUST = 3;

	private int ticks;

	/** Whether this session's stats are in, so the client's levels are this player's. */
	boolean arrived()
	{
		return ticks >= TICKS_TO_TRUST;
	}

	/**
	 * GameStateChanged. The login screen (after a logout, and on the way to another account), logging in and a world
	 * hop each start a session whose stats are still to come.
	 */
	void gameStateChanged(GameState state)
	{
		if (state == GameState.LOGIN_SCREEN || state == GameState.LOGGING_IN || state == GameState.HOPPING)
		{
			ticks = 0;
		}
	}

	/** GameTick, which only comes while logged in. */
	void gameTick()
	{
		if (ticks < TICKS_TO_TRUST)
		{
			ticks++;
		}
	}

	/**
	 * AI Chat was turned on: a login, hop or account switch while it was off went unheard, so the stats are taken as in
	 * after {@link #TICKS_TO_TRUST} more ticks.
	 */
	void startOver()
	{
		ticks = 0;
	}
}
