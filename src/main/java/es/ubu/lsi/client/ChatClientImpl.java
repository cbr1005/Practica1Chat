package es.ubu.lsi.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.net.UnknownHostException;
import es.ubu.lsi.common.ChatMessage;
import es.ubu.lsi.common.ChatMessage.MessageType;

/***
 * 
 * @author Ignacio Dávila y Pablo Santiago
 *
 */

public class ChatClientImpl implements ChatClient {
    private String server;
    private String username;
    private int port;
    private boolean carryOn = true;
    private int id;
    private ObjectInputStream input;
    private ObjectOutputStream output;
    private Socket socket;
    private BufferedReader stdIn;

    public ChatClientImpl(String server, int port, String username) {
        this.server = server;
        this.username = username;
        this.port = port;
    }

    @Override
    public boolean start() {
        //Inicializamos el socket y el output
        try {
            socket = new Socket(server, port);
            output = new ObjectOutputStream(socket.getOutputStream());
        } catch (UnknownHostException e) {
            System.err.println("Host desconocido: " + server);
            return false;
        } catch (IOException e) {
            System.err.println("I/O error: " + e.getMessage());
            return false;
        }

        //Arrancamos el hilo adicional
        Thread listener = new Thread(new ChatClientListener());
        listener.start();

        return true;
    }

    @Override
    public void sendMessage(ChatMessage msg) {
        try {
            output.writeObject(msg);
        } catch (IOException e) {
            System.err.println("Error al enviar el mensaje: " + e.getMessage());
        }
    }

    @Override
    public void disconnect() {
        try {
            //Cerramos los recursos y ponemos a false carryOn
            output.close();
            input.close();
            socket.close();            
            stdIn.close();
            carryOn = false;
        } catch (IOException e) {
            System.err.println("Error al desconectar: " + e.getMessage());
        }

    }

    public static void main(String[] args) {
        String server = "localhost";
        String username = args[0];

        if (args.length == 2) {
            server = args[0];
            username = args[1];
        }

        //Instancia del cliente
        ChatClientImpl cliente = new ChatClientImpl(server, 1500, username);

        //Arrancamos el hilo adicional
        if (!cliente.start()) {
            System.err.println("Error al arrancar el cliente");
            System.exit(1);
        }

        //Enviamos un primer mensaje con el nombre de usuario al servidor.
        ChatMessage registro = new ChatMessage(cliente.id, MessageType.MESSAGE, cliente.username);
        cliente.sendMessage(registro);

        /*
         * En este bloque de código lo que hacemos es inicializar el buffer de lectura que nos servirá para
         * leer lo que se escribe en la consola. Esta información será utilizada para crear un objeto ChatMessage
         * que será enviado al servidor.
         * 
         * Según el tipo de mensaje escrito, realizamos una acción u otra.
         * 
         */
        try{
            cliente.stdIn = new BufferedReader(new InputStreamReader(System.in));
            String message;
            while ((message = cliente.stdIn.readLine()) != null && cliente.carryOn) {
                System.out.print("> ");
                if (message.equalsIgnoreCase("logout")) {
                    ChatMessage chatMsg = new ChatMessage(cliente.id, MessageType.LOGOUT, username + ": "+message);
                    cliente.sendMessage(chatMsg);
                    cliente.carryOn = false;
                } else if(message.equalsIgnoreCase("shutdown")) {
                    ChatMessage chatMsg = new ChatMessage(cliente.id, MessageType.SHUTDOWN, username + ": "+message);
                    cliente.sendMessage(chatMsg);
                    cliente.carryOn = false;
                } else if (message.startsWith("ban ")) {
                    String userToBan = message.substring(4).trim();
                    ChatMessage chatMsg = new ChatMessage(cliente.id, MessageType.BAN, username + ": " + userToBan);
                    cliente.sendMessage(chatMsg);
                } else if (message.startsWith("unban ")) {
                    String userToUnban = message.substring(6).trim();
                    ChatMessage chatMsg = new ChatMessage(cliente.id, MessageType.UNBAN, username + ": " + userToUnban);
                    cliente.sendMessage(chatMsg);
                } else {
                    ChatMessage chatMsg = new ChatMessage(cliente.id, MessageType.MESSAGE, username + ": " +message);
                    cliente.sendMessage(chatMsg);
                }                
            }            
        }catch(IOException e) {
            System.err.println("Error al leer el mensaje de la consola: " + e.getMessage());
        }
    }

    public class ChatClientListener implements Runnable {
        boolean primero = true;

        public ChatClientListener() {
        }

        @Override
        public void run() {
            try {
                input =  new ObjectInputStream(socket.getInputStream());
                ChatMessage line;

                //Vamos leyendo lo que llega a la entrada y lo mostramos por pantalla
                while (carryOn && (line = (ChatMessage) input.readObject()) != null) {
                    System.out.println("\n" + line.getMessage());
                    System.out.print("> ");

                    //Si es el primer mensaje que se recibe, guardamos el id del cliente que devuelve el servidor.
                    if(primero) {
                        id = line.getId();
                        primero = false;
                    }

                    //Si se recibe un mensaje de tipo shutdown o logout, desconectamos al cliente.
                    if(line.getType() == ChatMessage.MessageType.SHUTDOWN || line.getType() == ChatMessage.MessageType.LOGOUT) {
                        carryOn = false;
                        disconnect();
                    }
                }
            } catch (IOException e) {
                System.err.println("Error al leer mensaje del servidor");
            } catch (ClassNotFoundException e) {
                e.printStackTrace();
            }
        }
    }
}
