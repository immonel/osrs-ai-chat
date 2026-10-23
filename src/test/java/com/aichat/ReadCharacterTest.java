package com.aichat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

/** The plugin's read of the character note on the client thread, before a message goes. */
public class ReadCharacterTest
{
	/** What the stand-in for RuneLite's client thread caught from the work queued on it. */
	private final List<Throwable> clientThreadCaught = new ArrayList<>();
	/** Frames the work asked to be run again on, as RuneLite's client thread does until it returns true. */
	private int framesWaited;

	/** Runs the work a frame at a time until it's done and, as RuneLite's does, keeps whatever it throws. */
	private final Consumer<BooleanSupplier> clientThread = r ->
	{
		try
		{
			while (!r.getAsBoolean())
			{
				framesWaited++;
			}
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
		AiChatPlugin.readCharacter(clientThread, () -> false, () ->
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
		AiChatPlugin.readCharacter(clientThread, () -> false, () ->
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
		AiChatPlugin.readCharacter(clientThread, () -> false, () -> "[Character: Zezima]", sent::add);
		assertEquals(Collections.singletonList("[Character: Zezima]"), sent);
		assertEquals(0, framesWaited);
	}

	/** Just after a login, the read waits for this session's stats rather than send the message without them. */
	@Test
	public void itWaitsAFrameAtATimeBeforeReading()
	{
		int[] framesLeft = {2};
		List<String> sent = new ArrayList<>();
		AiChatPlugin.readCharacter(clientThread, () -> framesLeft[0]-- > 0, () -> "[Character: Zezima]", sent::add);
		assertEquals(Collections.singletonList("[Character: Zezima]"), sent);
		assertEquals(2, framesWaited);
	}

	@Test
	public void aFailedWaitSendsTheMessageWithoutTheDetails()
	{
		List<String> sent = new ArrayList<>();
		AiChatPlugin.readCharacter(clientThread, () ->
		{
			throw new IllegalStateException("no game state");
		}, () -> "[Character: Zezima]", sent::add);
		assertEquals(Collections.singletonList(null), sent);
	}
}
