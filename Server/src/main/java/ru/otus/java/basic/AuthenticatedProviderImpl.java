package ru.otus.java.basic;


import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class AuthenticatedProviderImpl implements AuthenticatedProvider {


    private class User {
        private int id;
        private String login;
        private String password;
        private String username;
        private UserRole role;

        public User(int id, String login, String password, String username) {
            this.id = id;
            this.login = login;
            this.password = password;
            this.username = username;

        }

        public User(int id, String login, String password, String username, UserRole role) {
            this.id = id;
            this.login = login;
            this.password = password;
            this.username = username;
            this.role = role;
        }

        public void setRole(UserRole role) {
            this.role = role;
        }

        public int getId() {
            return id;
        }
    }

    private List<User> users;

    private Server server;

    private static String DATABASE_URL = null;
    private static String DATABASE_USER = null;
    private static String DATABASE_PASSWORD = null;
    private static final String USER_ADD_QUERY = """
            insert into users (username, login, password)
            values (?, ?, ?)
            RETURNING user_id
            """;
    private static final String USER_ROLE_ADD_QUERY = """
            insert into user_roles(user_id, role_id)
            values (?, 1)
            """;

    private final Connection connection;

    public AuthenticatedProviderImpl(Server server, String DATABASE_URL, String DATABASE_USER, String DATABASE_PASSWORD) {
        this.server = server;
        this.DATABASE_URL = DATABASE_URL;
        this.DATABASE_USER = DATABASE_USER;
        AuthenticatedProviderImpl.DATABASE_PASSWORD = DATABASE_PASSWORD;
        this.users = new CopyOnWriteArrayList<>();
        try {
            connection = DriverManager.getConnection(DATABASE_URL, DATABASE_USER, DATABASE_PASSWORD);
            initialize();
        } catch (SQLException e) {
            System.out.println("Соединение с БД не установлено");
            throw new RuntimeException(e);
        }
    }

    public void initialize() {
        System.out.println("Соединение с БД установлено");
        users = getAll();
    }

    public List<User> getAll() {
        List<User> result = new ArrayList();
        UserRole currentRoles = null;
        final String USERS_QUERY = "select * from users;";
        final String USER_ROLES_QUERY = """
                select r.role_id, r."role_name" from roles r
                join user_roles ur on r.role_id = ur.role_id
                where ur.user_id = ?;
                """;
        try (Statement statement = connection.createStatement()) {
            try (ResultSet rs = statement.executeQuery(USERS_QUERY)) {
                while (rs.next()) {
                    int id = rs.getInt("user_id");
                    String username = rs.getString("username");
                    String password = rs.getString("password");
                    String login = rs.getString("login");
                    User currentUser = new User(id, login, password, username);
                    result.add(currentUser);
                }
            }

        } catch (SQLException e) {
            throw new RuntimeException(e);
        }


        try (PreparedStatement ps = connection.prepareStatement(USER_ROLES_QUERY)) {
            for (User user : result) {
                ps.setInt(1, user.getId());
                try (ResultSet resultSet = ps.executeQuery()) {
                    while (resultSet.next()) {
                        int id = resultSet.getInt("role_id");
                        currentRoles = switch (id) {
                            case 1 -> UserRole.USER;
                            case 2 -> UserRole.ADMIN;
                            default -> throw new RuntimeException("Некорректный id роли");
                        };
                    }
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
                user.setRole(currentRoles);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        return result;
    }

    private boolean isLoginAlreadyExists(String login) {
        for (User user : users) {
            if (user.login.equals(login.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private boolean isUsernameAlreadyExists(String username) {
        for (User user : users) {
            if (user.username.equalsIgnoreCase(username)) {
                return true;
            }
        }
        return false;
    }

    private boolean isBanned(int userId) {
        String BAN_QUERY = """
                SELECT 1 FROM bans 
                WHERE user_id = ? 
                AND (ban_end IS NULL OR ban_end > CURRENT_TIMESTAMP) 
                LIMIT 1""";

        try (PreparedStatement ps = connection.prepareStatement(BAN_QUERY)) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private UserRole getRoleByLoginAndPassword(String login, String password) {
        for (User user : users) {
            if (user.login.equals(login.toLowerCase()) && user.password.equals(password)) {
                return user.role;
            }
        }
        return null;
    }

    private int getIdByLoginAndPassword(String login, String password) {
        for (User user : users) {
            if (user.login.equals(login.toLowerCase()) && user.password.equals(password)) {
                return user.id;
            }
        }
        return -1;
    }

    private String getUsernameByLoginAndPassword(String login, String password) {
        for (User user : users) {
            if (user.login.equals(login.toLowerCase()) && user.password.equals(password)) {
                return user.username;
            }
        }
        return null;
    }


    @Override
    public boolean authenticate(ClientHandler clientHandler, String login, String password) {
        String authUsername = getUsernameByLoginAndPassword(login, password);
        UserRole role = getRoleByLoginAndPassword(login, password);
        int id = getIdByLoginAndPassword(login, password);
        if (authUsername == null || role == null || id == -1) {
            clientHandler.sendSystemMsg("Некорректный логин/пароль");
            return false;
        }
        if (server.isUsernameBusy(authUsername)) {
            clientHandler.sendSystemMsg("Указанная учетная запись уже занята");
            return false;
        }
        if (isBanned(id)) {
            final String BAN_QUERY = """
                    SELECT reason, ban_start, ban_end
                    FROM bans
                    WHERE user_id = ?
                    AND (ban_end IS NULL OR ban_end > CURRENT_TIMESTAMP)
                    ORDER BY ban_start DESC LIMIT 1""";
            try (PreparedStatement ps = connection.prepareStatement(BAN_QUERY)) {
                ps.setInt(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        String reason = rs.getString("reason");
                        String startTime = rs.getTimestamp("ban_start").toString().split("\\.", 2)[0];
                        String endTime = rs.getTimestamp("ban_end") != null ?
                                rs.getTimestamp("ban_end").toString().split("\\.", 2)[0] : "никогда";
                        clientHandler.sendSystemMsg("/kickok " + reason + "\nДата окончания блокировки: " + endTime);
                        clientHandler.disconnect();
                        return false;
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }


        clientHandler.setUsername(authUsername);
        clientHandler.setRole(role);
        clientHandler.setUserId(id);
        server.subscribe(clientHandler);
        clientHandler.sendSystemMsg("/authok " + authUsername);
        return true;
    }


    public boolean registration(ClientHandler clientHandler, String login, String password, String username) {
        int userId = -1;
        if (login.length() < 3) {
            clientHandler.sendSystemMsg("Логин должен быть 3+ символа");
            return false;
        }
        if (username.length() < 3) {
            clientHandler.sendSystemMsg("Имя пользователя должна быть 3+ символа");
            return false;
        }
        if (password.length() < 3) {
            clientHandler.sendSystemMsg("Пароль должен быть 3+ символа");
            return false;
        }
        if (isLoginAlreadyExists(login)) {
            clientHandler.sendSystemMsg("Такой логин уже занят");
            return false;
        }
        if (isUsernameAlreadyExists(username)) {
            clientHandler.sendSystemMsg("Такое имя пользователя уже занято");
            return false;
        }
        try (PreparedStatement ps = connection.prepareStatement(USER_ADD_QUERY)) {
            ps.setString(1, username);
            ps.setString(2, login);
            ps.setString(3, password);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    userId = rs.getInt("user_id");
                    try (PreparedStatement rps = connection.prepareStatement(USER_ROLE_ADD_QUERY)) {
                        rps.setInt(1, userId);
                        rps.executeUpdate();
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }


        users.add(new User(userId, login, password, username, UserRole.USER));

        clientHandler.setUsername(username);
        clientHandler.setUserId(userId);
        server.subscribe(clientHandler);
        clientHandler.sendSystemMsg("/regok " + username);

        return true;
    }
}
