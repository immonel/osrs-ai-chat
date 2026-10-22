package com.aichat;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.callback.ClientThread;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

/**
 * The plugin's read of the character note on the client thread, before a message goes. It's a private part of
 * {@link AiChatPlugin}, made for RuneLite to inject, so it's reached by reflection here.
 */
public class ReadCharacterTest
{
	/** What the stand-in for RuneLite's client thread caught from the work queued on it. */
	private final List<Throwable> clientThreadCaught = new ArrayList<>();

	@Test
	public void anErrorFromTheGameStillSendsTheMessage() throws Exception
	{
		Error error = new NoSuchMethodError("net.runelite.api.Client.getLocalPlayer()Lnet/runelite/api/Player;");
		// Logged in, and the first read of the character throws; anything else gets nothing back.
		Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
			(proxy, method, args) ->
			{
				if (method.getName().equals("getGameState"))
				{
					return GameState.LOGGED_IN;
				}
				if (method.getName().equals("getLocalPlayer"))
				{
					throw error;
				}
				Class<?> type = method.getReturnType();
				return type.isPrimitive() && type != void.class ? Array.get(Array.newInstance(type, 1), 0) : null;
			});
		// Runs the work at once and, as RuneLite's does, logs (here, keeps) whatever it throws and carries on.
		ClientThread clientThread = new ClientThread()
		{
			@Override
			public void invokeLater(Runnable r)
			{
				try
				{
					r.run();
				}
				catch (Throwable t)
				{
					clientThreadCaught.add(t);
				}
			}
		};
		List<String> sent = new ArrayList<>();
		host(client, clientThread).readCharacter(sent::add);
		// The message goes, once, without the details.
		assertEquals(Collections.singletonList(null), sent);
		// RuneLite still gets the Error, and logs it as it would any other.
		assertEquals(Collections.singletonList(error), clientThreadCaught);
	}

	private static RequestRunner.Host host(Client client, ClientThread clientThread) throws ReflectiveOperationException
	{
		AiChatPlugin plugin = new AiChatPlugin();
		inject(plugin, "client", client);
		inject(plugin, "clientThread", clientThread);
		Constructor<?> requests = Class.forName(AiChatPlugin.class.getName() + "$Requests")
			.getDeclaredConstructor(AiChatPlugin.class);
		requests.setAccessible(true);
		return (RequestRunner.Host) requests.newInstance(plugin);
	}

	private static void inject(AiChatPlugin plugin, String name, Object value) throws ReflectiveOperationException
	{
		Field f = AiChatPlugin.class.getDeclaredField(name);
		f.setAccessible(true);
		f.set(plugin, value);
	}
}
