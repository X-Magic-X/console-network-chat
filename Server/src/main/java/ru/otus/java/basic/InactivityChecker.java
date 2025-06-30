package ru.otus.java.basic;

public class InactivityChecker {
    Server server;
    private final long timeoutMillis;
    private volatile boolean running = false;
    private Thread thread = null;


    public InactivityChecker(long timeoutMillis, Server server) {
        this.timeoutMillis = timeoutMillis;
        this.server = server;
    }

    public void start() {
        if (running) throw new RuntimeException("Повторный запуск некорректно");
        running = true;
        thread = new Thread(() -> {
            while (running) {
                try {
                    Thread.sleep(60000);
                    checkInactiveUsers();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        });
        thread.start();
    }

    private void checkInactiveUsers() {
        for (ClientHandler c : server.getClients()) {
            if (c.getInactiveMillis() > timeoutMillis) {
                server.broadcastMessage(c.getUsername() + " был отключен за неактивность");
                c.sendSystemMsg("/kickok бездействие");
                c.disconnect();
            }
        }

    }

    public void stop() {
        running = false;
        thread.stop();
    }
}

