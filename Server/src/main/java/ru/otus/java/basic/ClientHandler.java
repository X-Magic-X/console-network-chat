package ru.otus.java.basic;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.time.Duration;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;

public class ClientHandler {
    private Socket socket;
    private Server server;
    private DataInputStream in;
    private DataOutputStream out;

    private String username;
    private UserRole role;
    private int userId;
    private boolean authenticated;
    private volatile boolean running = true;
    private volatile long lastActivityTime = System.currentTimeMillis();

    public ClientHandler(Socket socket, Server server) throws IOException {
        this.socket = socket;
        this.server = server;
        in = new DataInputStream(socket.getInputStream());
        out = new DataOutputStream(socket.getOutputStream());

        new Thread(() -> {
            try {
                System.out.println("Клиент подключился " + socket.getPort());
                //Цикл аутентификации
                while (running) {
                    sendSystemMsg("Перед работой с чатом необходимо выполнить " +
                            "аутентификацию '/auth login password' \n" +
                            "или регистрацию '/reg login password username'");
                    String message = in.readUTF();
                    updateActivityTime();
                    if (message.startsWith("/")) {
                        if (message.equals("/exit")) {
                            sendSystemMsg("/exitok");
                            break;
                        }
                        ///auth login password
                        if (message.startsWith("/auth ")) {
                            String[] token = message.split(" ");
                            if (token.length != 3) {
                                sendSystemMsg("Неверный формат команды /auth");
                                continue;
                            }
                            if (server.getAuthenticatedProvider()
                                    .authenticate(this, token[1], token[2])) {
                                authenticated = true;
                                break;
                            }
                        }
                        ///reg login password username
                        if (message.startsWith("/reg ")) {
                            String[] token = message.split(" ");
                            if (token.length != 4) {
                                sendSystemMsg("Неверный формат команды /reg");
                                continue;
                            }
                            if (server.getAuthenticatedProvider()
                                    .registration(this, token[1], token[2], token[3])) {
                                authenticated = true;
                                break;
                            }
                        }
                    }
                }

                //Цикл работы
                while (authenticated && running) {
                    String message = in.readUTF();
                    updateActivityTime();
                    if (message.startsWith("/")) {
                        if (message.equals("/exit")) {
                            server.broadcastMessage("Пользователь " + username + " вышел из чата");
                            sendSystemMsg("/exitok");
                            break;
                        }
                        if (message.startsWith("/kick ")) {
                            String[] parts = message.substring(6).split(" ", 2);
                            if (parts.length == 2) {
                                String usernameToKick = parts[0];
                                server.kickUser(this, usernameToKick, parts[1]);

                            } else {
                                sendSystemMsg("Использование: /kick username reason");
                            }
                        }
                        if (message.startsWith("/ban ")) {
                            String[] parts = message.substring(5).split(" ", 3);
                            if (parts.length == 3) {
                                try {
                                    Duration duration = Duration.ofMinutes(Long.parseLong(parts[1]));
                                    server.banUser(this, parts[0], parts[2], duration);
                                } catch (NumberFormatException e) {
                                    sendSystemMsg("Использование: /ban username minutes reason");
                                }
                            } else {
                                sendSystemMsg("Использование: /ban username minutes reason");
                            }
                        }
                        if (message.startsWith("/shutdown")) {
                            server.shutdown(this);
                        }
                        if (message.startsWith("/activelist")) {
                            sendSystemMsg("Список активных пользователей:\n" + server.activeList());
                        }
                        if (message.startsWith("/w ")) {
                            sendPrivateMsg(message);
                        }
                        if (message.startsWith("/changenick ")) {
                            String newUsername = message.substring(11).trim();
                            if (newUsername.length() < 3 || newUsername.length() > 30) {
                                sendMsg("Никнейм должен быть не менее 3 символов и не более 30");
                            } else if (server.changeUsername(this, newUsername)) {
                                sendSystemMsg("/nickchanged " + newUsername);
                            } else {
                                sendMsg("Ошибка: никнейм уже занят или недопустим");
                            }
                        }
                    } else {
                        server.broadcastMessage(username + ": " + message);
                    }
                }
            } catch (IOException e) {
                if (running) {
                    e.printStackTrace();
                }
            } finally {
                disconnect();
            }
        }).start();
    }

    public void sendSystemMsg(String message) {
        try {
            out.writeUTF(message);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void sendMsg(String message) {
        try {
            out.writeUTF("[" + LocalTime.now().truncatedTo(ChronoUnit.SECONDS) + "] " + message);

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void sendPrivateMsg(String message) {
        String[] privateMessage = message.substring(3).split(" ", 2);
        if (privateMessage.length < 2) {
            sendSystemMsg("Используйте: /w <username> <message>");
        } else if (privateMessage[0].equals(username)) {
            sendSystemMsg("Вы не можете писать себе");
        } else {
            ClientHandler client = server.findClientByUsername(privateMessage[0]);
            if (client != null) {
                sendMsg("я -> " + privateMessage[0] + ": " + privateMessage[1]);
                client.sendMsg(username + " -> я: " + privateMessage[1]);
            } else {
                sendSystemMsg("Пользователь " + privateMessage[0] + " не найден");
            }
        }
    }

    private void updateActivityTime() {
        lastActivityTime = System.currentTimeMillis();
    }

    public long getInactiveMillis() {
        return System.currentTimeMillis() - lastActivityTime;
    }

    public String getUsername() {
        return username;
    }

    public UserRole getRole() {
        return role;
    }

    public int getUserId() {
        return userId;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public void setUserId(int userId) {
        this.userId = userId;
    }

    public void setRole(UserRole role) {
        this.role = role;
    }

    public void disconnect() {
        if (running) {
            server.unsubscribe(this);
            try {
                if (in != null) {
                    in.close();
                }
            } catch (IOException e) {
                if (running) e.printStackTrace();
            }
            try {
                if (out != null) {
                    out.close();
                }
            } catch (IOException e) {
                if (running) e.printStackTrace();
            }
            try {
                if (socket != null) {
                    socket.close();
                }
            } catch (IOException e) {
                if (running) e.printStackTrace();
            }
            running = false;
        }
    }
}
