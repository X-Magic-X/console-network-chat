package ru.otus.java.basic;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.Scanner;

public class Client {
    private Scanner scanner;
    private Socket socket;
    private DataOutputStream out;
    private DataInputStream in;
    private volatile boolean running = true;

    public Client() throws IOException {
        scanner = new Scanner(System.in);
        socket = new Socket("localhost", 8189);
        out = new DataOutputStream(socket.getOutputStream());
        in = new DataInputStream(socket.getInputStream());

        try {
            new Thread(() -> {
                try {
                    while (true) {
                        String message = in.readUTF();
                        if (message.startsWith("/")) {
                            if (message.equals("/exitok")) {
                                break;
                            }
                            if (message.startsWith("/kickok ")) {
                                System.out.println("Вы были отключены по причине: " + message.split(" ", 2)[1]);
                                break;
                            }
                            if (message.startsWith("/authok ")) {
                                System.out.println("Вы подключились под ником: " + message.split(" ")[1]);
                                continue;
                            }
                            if (message.startsWith("/regok ")) {
                                System.out.println("Вы успешно зарегистрировались и подключились под ником: "
                                        + message.split(" ")[1]);
                                continue;
                            }
                            if (message.startsWith("/nickchanged ")) {
                                System.out.println("Никнейм успешно изменён на: " +
                                        message.substring(13));
                                continue;
                            }
                        }
                        System.out.println(message);
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                } finally {
                    disconnect();
                }
            }).start();

            while (true) {
                String message = scanner.nextLine();
                if (!running) {
                    disconnect();
                    break;
                }
                out.writeUTF(message);
                if (message.equals("/exit")) {
                    break;
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void disconnect() {
        running = false;
        try {
            if (in != null) {
                in.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        try {
            if (out != null) {
                out.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
