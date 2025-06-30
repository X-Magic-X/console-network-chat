package ru.otus.java.basic;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.sql.*;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class Server {
    private int port;
    private List<ClientHandler> clients;
    private AuthenticatedProvider authenticatedProvider;
    private final InactivityChecker inactivityChecker;
    private static final String DATABASE_URL = "jdbc:postgresql://0.0.0.0:1234/postgres";
    private static final String DATABASE_USER = "postgres";
    private static final String DATABASE_PASSWORD = "pass123";
    private boolean running = true;
    ServerSocket serverSocket;


    public Server(int port) {
        this.port = port;
        clients = new CopyOnWriteArrayList<>();
        authenticatedProvider = new AuthenticatedProviderImpl(this, DATABASE_URL, DATABASE_USER, DATABASE_PASSWORD);
        inactivityChecker = new InactivityChecker(1_200_000, this);
    }

    public void start() {
        inactivityChecker.start();
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            this.serverSocket = serverSocket;
            System.out.println("Сервер запущен на порту " + port);
            while (running) {
                Socket socket = serverSocket.accept();
                new ClientHandler(socket, this);
            }
        } catch (IOException e) {
            if (running) throw new RuntimeException(e);
        }
    }

    public void subscribe(ClientHandler clientHandler) {
        clients.add(clientHandler);
    }

    public void unsubscribe(ClientHandler clientHandler) {
        System.out.println("Клиент " + clientHandler.getUsername() + " вышел из чата");
        clients.remove(clientHandler);
    }

    public void broadcastMessage(String message) {
        for (ClientHandler c : clients) {
            c.sendMsg(message);
        }
    }

    public boolean isUsernameBusy(String username) {
        for (ClientHandler c : clients) {
            if (c.getUsername().equals(username)) {
                return true;
            }
        }
        return false;
    }

    public void shutdown(ClientHandler admin) {
        if (admin.getRole() != UserRole.ADMIN) {
            admin.sendSystemMsg("Ошибка: недостаточно прав");
            return;
        }
        broadcastMessage("Выключение сервера");
        running = false;
        inactivityChecker.stop();
        for (ClientHandler c : clients) {
            c.sendSystemMsg("/exitok");
            c.disconnect();
        }
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                admin.sendSystemMsg("Ошибка: закрытия сокета");
            }
        }
        System.exit(0);
    }

    public String activeList() {
        StringBuilder result = new StringBuilder();
        for (ClientHandler c : clients) {
            result.append(c.getUsername() + "\n");
        }
        return result.toString();
    }

    public void kickUser(ClientHandler admin, String usernameToKick, String reason) {
        if (admin.getRole() != UserRole.ADMIN) {
            admin.sendSystemMsg("Ошибка: недостаточно прав");
            return;
        }

        for (ClientHandler client : clients) {
            if (client.getUsername().equals(usernameToKick)) {
                client.sendSystemMsg("/kickok " + reason);
                client.disconnect();
                broadcastMessage("Пользователь " + usernameToKick + " был отключен администратором " + admin.getUsername());
                return;
            }
        }
        admin.sendSystemMsg("Пользователь " + usernameToKick + " не найден");
    }

    public void banUser(ClientHandler admin, String usernameToBan, String reason, Duration duration) {
        if (admin.getRole() != UserRole.ADMIN) {
            admin.sendSystemMsg("Ошибка: недостаточно прав");
            return;
        }
        final String BAN_QUERY = """
                INSERT INTO bans (user_id, banned_by, reason, ban_start, ban_end)
                SELECT u.user_id, ?, ?, CURRENT_TIMESTAMP, 
                       CASE WHEN ? > 0 THEN CURRENT_TIMESTAMP + ? * INTERVAL '1 second' ELSE NULL END
                FROM users u WHERE u.username = ?""";

        try (Connection con = DriverManager.getConnection(DATABASE_URL, DATABASE_USER, DATABASE_PASSWORD);
             PreparedStatement ps = con.prepareStatement(BAN_QUERY)) {

            ps.setInt(1, admin.getUserId());
            ps.setString(2, reason);
            ps.setLong(3, duration.getSeconds());
            ps.setLong(4, duration.getSeconds());
            ps.setString(5, usernameToBan);

            int affectedRows = ps.executeUpdate();
            if (affectedRows > 0) {
                for (ClientHandler client : clients) {
                    if (client.getUsername().equals(usernameToBan)) {
                        client.sendSystemMsg("/kickok " + reason);
                        client.disconnect();
                        break;
                    }
                }
                broadcastMessage("Пользователь " + usernameToBan + " заблокирован администратором " + admin.getUsername());
            } else {
                admin.sendSystemMsg("Пользователь " + usernameToBan + " не найден");
            }
        } catch (SQLException e) {
            admin.sendSystemMsg("Ошибка при блокировке пользователя");
            throw new RuntimeException(e);
        }

    }

    public ClientHandler findClientByUsername(String username) {
        for (ClientHandler client : clients) {
            if (client.getUsername().equals(username)) {
                return client;
            }
        }
        return null;
    }

    public boolean changeUsername(ClientHandler client, String newUsername) {
        final String CHANGE_NICK_QUERY = """
                UPDATE users 
                SET username = ? 
                WHERE user_id = (SELECT user_id FROM users WHERE username = ? LIMIT 1)
                AND NOT EXISTS (
                    SELECT 1 FROM users 
                    WHERE username = ? AND user_id != (SELECT user_id FROM users WHERE username = ? LIMIT 1)
                )
                RETURNING username""";

        try (Connection con = DriverManager.getConnection(DATABASE_URL, DATABASE_USER, DATABASE_PASSWORD);
             PreparedStatement ps = con.prepareStatement(CHANGE_NICK_QUERY)) {
            String currentUsername = client.getUsername();

            ps.setString(1, newUsername);
            ps.setString(2, currentUsername);
            ps.setString(3, newUsername);
            ps.setString(4, currentUsername);

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    broadcastMessage("Пользователь " + currentUsername + " изменил никнейм на " + newUsername);
                    client.setUsername(newUsername);
                    return true;
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return false;
    }

    public List<ClientHandler> getClients() {
        return clients;
    }

    public AuthenticatedProvider getAuthenticatedProvider() {
        return authenticatedProvider;
    }
}
