package es.ubu.lsi.client;

import es.ubu.lsi.common.ChatMessage;

public interface ChatClient {
	public boolean start();
	public void sendMessage(ChatMessage msg);
	public void disconnect();

}
