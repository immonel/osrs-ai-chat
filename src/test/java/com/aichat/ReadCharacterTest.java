package com.aichat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

/** The plugin's read of the character note on the client thread, before a message goes. */
public class ReadCharacterTest
{
	/** What the stand-in for RuneLite's client thread caught from the work queued on it. */
	private final List<Throwable> clientThreadCaught = new ArrayList<>();

	/** Runs the work at once and, as RuneLite's does, logs (here, keeps) whatever it throws and carries on. */
	private final Consumer<Runnable> clientThread = r ->
	{
		try
		{
			r.run();
		}
		catch (Throwable t)
		{
			clientThreadCaught.add(t);
		}
	};

	@Test
	public void anErrorFromTheGameStillSendsTheMessage()
	{
		Error error = new NoSuchMethodError("net.runelite.api.Client.getLocalPlayer()Lnet/runelite/api/Player;");
		List<String> sent = new ArrayList<>();
		AiChatPlugin.readCharacter(clientThread, () ->
		{
			throw error;
		}, sent::add);
		// The message goes, once, without the details.
		assertEquals(Collections.singletonList(null), sent);
		// RuneLite still gets the Error, and logs it as it would any other.
		assertEquals(Collections.singletonList(error), clientThreadCaught);
	}

	@Test
	public void aFailedReadSendsTheMessageWithoutTheDetails()
	{
		List<String> sent = new ArrayList<>();
		AiChatPlugin.readCharacter(clientThread, () ->
		{
			throw new IllegalStateException("the game's data wasn't there");
		}, sent::add);
		assertEquals(Collections.singletonList(null), sent);
		assertEquals(Collections.emptyList(), clientThreadCaught);
	}

	@Test
	public void aReadNoteGoesWithTheMessage()
	{
		List<String> sent = new ArrayList<>();
		AiChatPlugin.readCharacter(clientThread, () -> "[Character: Zezima]", sent::add);
		assertEquals(Collections.singletonList("[Character: Zezima]"), sent);
		assertEquals(Collections.emptyList(), clientThreadCaught);
	}
}
