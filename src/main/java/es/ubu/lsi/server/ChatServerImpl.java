package es.ubu.lsi.server;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

import es.ubu.lsi.common.ChatMessage;
import es.ubu.lsi.common.ChatMessage.MessageType;

/***
 * 
 * @author Ignacio Dávila y Pablo Santiago
 *
 */

public class ChatServerImpl implements ChatServer {
    private int DEFAULT_PORT = 1500;
    private int clientId;
    private SimpleDateFormat sdf;
    private int port;
    private boolean alive;
    private List<ServerThreadForClient> clientes;
    private ServerSocket serverSocket;
    private Map<String, Set<String>> bannedUsersMap;

    public ChatServerImpl(int port) {
        this.port = port;
        this.alive = true;
        this.clientId = -1;
        this.clientes = new ArrayList<>();
        this.sdf = new SimpleDateFormat("d MMM yyyy HH:mm:ss");
        this.bannedUsersMap = new HashMap<>();
    }

    @Override
    public void startup() {
        try {
            //Creamos el socket del servidor y escuchamos peticiones de los clientes.
            serverSocket = new ServerSocket(port);
            while (alive) {
                // Aceptamos la conexión y almacenamos el cliente
                Socket clientSocket = serverSocket.accept();
                clientId += 1;
                System.out.println("Nuevo Cliente con id: " + clientId);
                ServerThreadForClient hilo = new ServerThreadForClient(clientSocket, this, clientId);
                clientes.add(hilo);
                hilo.start();
            }
        }catch (IOException e) {
            System.out.println("El servidor se ha cerrado.");
        }finally {
            if (serverSocket != null) {
                try {
                    serverSocket.close();
                } catch (IOException e) {
                    System.out.println(e.getMessage());
                }
            }
        }
    }

    @Override
    public void shutdown() {
        try {
            //Envia un mensaje a los clientes indicando que el servidor se va a cerrar para que cierren sus conexiones.
            ChatMessage msg = new ChatMessage(-1, ChatMessage.MessageType.SHUTDOWN, "El servidor va a cerrar");
            broadcast(msg);

            // Recorremos los clientes, los deconectamos y los eliminamos de la lista
            Iterator<ServerThreadForClient> iterator = clientes.iterator();
            while (iterator.hasNext()) {
                ServerThreadForClient cliente = iterator.next();
                cliente.disconnect();
                iterator.remove();
            }

            // Con todos los clientes desconectados cerramos el servidor
            this.alive = false;
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException e) {
            System.out.println("Error I/O");
            System.out.println(e.getMessage());
        }

    }

    @Override
    public void broadcast(ChatMessage message) {
        Calendar now = new GregorianCalendar();
        Date nowDate = now.getTime();
        System.out.println("["+sdf.format(nowDate)+"] "+message.getMessage());

        for (ServerThreadForClient cliente : clientes) {
            try {
                if (cliente.id != message.getId()) {
                    Set<String> bannedUsers = bannedUsersMap.get(cliente.username);
                    if (bannedUsers != null && bannedUsers.contains(message.getMessage().split(":")[0].trim())) {
                        continue; // Skip sending the message to clients who have banned the sender
                    }
                    cliente.output.writeObject(message);
                }
            } catch (IOException e) {
                System.out.println("Error al difundir el mensaje");
                System.out.println(e.getMessage());
            }
        }
    }

    @Override
    public void remove(int id) {
        Iterator<ServerThreadForClient> iterator = clientes.iterator();
        while (iterator.hasNext()) {
            ServerThreadForClient cliente = iterator.next();
            if (cliente.id == id) {
                ChatMessage msg = new ChatMessage(-1, ChatMessage.MessageType.LOGOUT, "Desconectado del servidor");
                try {
                    cliente.output.writeObject(msg);
                } catch (IOException e) {
                    System.out.println("Error al enviar el mensaje");
                }
                cliente.disconnect();
                iterator.remove();
            }
        }
    }

    public boolean drop(String nombre) {
        for (ServerThreadForClient cliente : clientes) {
            if (cliente.username.equalsIgnoreCase(nombre)) {
                remove(cliente.id);
                return true;
            }
        }
        return false;
    }

    public boolean banUser(String currentUser, String userToBan) {
        bannedUsersMap.computeIfAbsent(currentUser, k -> new HashSet<>()).add(userToBan);
        return true;
    }

    public boolean unbanUser(String currentUser, String userToUnban) {
        Set<String> bannedUsers = bannedUsersMap.get(currentUser);
        if (bannedUsers != null) {
            bannedUsers.remove(userToUnban);
            return true;
        }
        return false;
    }

    public static void main(String[] args) {
        ChatServerImpl server = new ChatServerImpl(1500);
        server.startup();
    }

    public class ServerThreadForClient extends Thread {

        private int id;
        private String username;
        private Socket clientSocket;
        private ObjectInputStream input;
        private ObjectOutputStream output;
        boolean primero = true;
        boolean activo = true;

        public ServerThreadForClient(Socket socket, ChatServerImpl server, int clientId) throws IOException {
            this.clientSocket = socket;
            this.id = clientId;
        }

        public void disconnect() {
            try {
                if (output != null) {
                    output.close();
                }
                if (input != null) {
                    input.close();
                }
                if (clientSocket != null) {
                    clientSocket.close();
                }
                System.out.println(username + " se ha desconectado");
            } catch (IOException e) {
                System.err.println("Error al cerrar la conexión del cliente: " + e.getMessage());
            }
        }

        private void tratamientoMensaje(ChatMessage mensaje) {
            try {
                if (mensaje.getType() == ChatMessage.MessageType.MESSAGE) {
                    String[] datos = mensaje.getMessage().split(" ");
                    if (datos.length != 1 && datos[1].equalsIgnoreCase("drop")) {
                        boolean respuesta = drop(datos[2]);
                        if (respuesta) {
                            ChatMessage msg = new ChatMessage(id, MessageType.MESSAGE, "El usuario " + datos[2] + " ha sido desconectado.");
                            output.writeObject(msg);
                        } else {
                            ChatMessage msg = new ChatMessage(id, MessageType.MESSAGE,
                                    "El usuario " + datos[2] + " no existe.");
                            output.writeObject(msg);
                        }
                    } else {
                        broadcast(mensaje);
                    }
                } else if (mensaje.getType() == ChatMessage.MessageType.BAN) {
                    String userToBan = mensaje.getMessage().split(":")[1].trim();
                    banUser(username, userToBan);
                    ChatMessage msg = new ChatMessage(id, MessageType.MESSAGE, username + " ha baneado a " + userToBan);
                    broadcast(msg);
                } else if (mensaje.getType() == ChatMessage.MessageType.UNBAN) {
                    String userToUnban = mensaje.getMessage().split(":")[1].trim();
                    unbanUser(username, userToUnban);
                    ChatMessage msg = new ChatMessage(id, MessageType.MESSAGE, username + " ha desbaneado a " + userToUnban);
                    broadcast(msg);
                } else if (mensaje.getType() == ChatMessage.MessageType.LOGOUT) {
                    remove(mensaje.getId());
                    activo = false;
                } else if (mensaje.getType() == ChatMessage.MessageType.SHUTDOWN) {
                    shutdown();
                    alive = false;
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void run() {
            try {
                input = new ObjectInputStream(clientSocket.getInputStream());
                output = new ObjectOutputStream(clientSocket.getOutputStream());

                ChatMessage msg = new ChatMessage(id, MessageType.MESSAGE, "Servidor: Conexión realizada");
                output.writeObject(msg);

                ChatMessage objeto;
                while (activo && (objeto = (ChatMessage) input.readObject()) != null && alive) {
                    if (primero) {
                        this.username = objeto.getMessage();
                        primero = false;
                    } else {
                        tratamientoMensaje(objeto);
                    }
                }
            } catch (IOException e) {
                System.out.println(e.getMessage());
            } catch (ClassNotFoundException e) {
                e.printStackTrace();
            }
        }

    }
}
