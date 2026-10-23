package com.aichat;

import net.runelite.api.GameState;
import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** When the client's levels are this session's, and so can be told to the model. */
public class SessionStatsTest
{
	private static void ticks(SessionStats stats, int n)
	{
		for (int i = 0; i < n; i++)
		{
			stats.gameTick();
		}
	}

	@Test
	public void theStatsAreInAFewTicksAfterLoggingIn()
	{
		SessionStats stats = new SessionStats();
		stats.gameStateChanged(GameState.LOGGING_IN);
		stats.gameStateChanged(GameState.LOGGED_IN);
		ticks(stats, SessionStats.TICKS_TO_TRUST - 1);
		assertFalse(stats.arrived());
		stats.gameTick();
		assertTrue(stats.arrived());
	}

	/** A logout on the way to another account, a login and a hop each start over: their stats are still to come. */
	@Test
	public void aNewSessionStartsOver()
	{
		for (GameState state : new GameState[]{GameState.LOGIN_SCREEN, GameState.LOGGING_IN, GameState.HOPPING})
		{
			SessionStats stats = new SessionStats();
			ticks(stats, SessionStats.TICKS_TO_TRUST);
			assertTrue(stats.arrived());
			stats.gameStateChanged(state);
			assertFalse(state.name(), stats.arrived());
		}
	}

	/** A reconnection resumes the same account, and loading a new area is the same session. */
	@Test
	public void aLostConnectionOrALoadingScreenDoesntStartOver()
	{
		SessionStats stats = new SessionStats();
		ticks(stats, SessionStats.TICKS_TO_TRUST);
		for (GameState state : new GameState[]{GameState.CONNECTION_LOST, GameState.LOADING, GameState.LOGGED_IN})
		{
			stats.gameStateChanged(state);
			assertTrue(state.name(), stats.arrived());
		}
	}

	/** AI Chat turned on: whatever happened while it was off went unheard. */
	@Test
	public void startingOverWaitsForMoreTicks()
	{
		SessionStats stats = new SessionStats();
		ticks(stats, SessionStats.TICKS_TO_TRUST);
		stats.startOver();
		assertFalse(stats.arrived());
		ticks(stats, SessionStats.TICKS_TO_TRUST);
		assertTrue(stats.arrived());
	}
}
