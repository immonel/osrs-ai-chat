package com.aichat;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

/** What "Copy chat" puts on the clipboard. */
public class ChatTest
{
	@Test
	public void plainTextHasEachTurnUnderWhoWroteIt()
	{
		Chat chat = new Chat("Chat 1");
		chat.messages.add(new Chat.Message(Chat.Role.USER, "What should I bring to **Vorkath**?"));
		Chat.Message reply = new Chat.Message(Chat.Role.ASSISTANT, "Bring:\n\n- **Antifire**\n- [Ruby bolts (e)](https://oldschool.runescape.wiki/w/Ruby_bolts_(e))\n");
		reply.who = "Claude";
		chat.messages.add(reply);
		chat.messages.add(new Chat.Message(Chat.Role.NOTE, "Stopped."));
		chat.messages.add(new Chat.Message(Chat.Role.ERROR, "Anthropic is busy"));
		chat.messages.add(new Chat.Message(Chat.Role.USER, "thanks"));
		chat.messages.add(new Chat.Message(Chat.Role.ASSISTANT, "You're welcome"));

		assertEquals("You:\nWhat should I bring to **Vorkath**?\n\n"
				+ "Claude:\n" + Markdown.plainText(reply.text).trim() + "\n\n"
				+ "You:\nthanks\n\n"
				+ "Assistant:\nYou're welcome",
			chat.plainText());
	}

	@Test
	public void plainTextIsEmptyWithNothingToCopy()
	{
		Chat chat = new Chat("Chat 1");
		assertEquals("", chat.plainText());
		chat.messages.add(new Chat.Message(Chat.Role.ERROR, "No API key"));
		assertEquals("", chat.plainText());
	}
}
