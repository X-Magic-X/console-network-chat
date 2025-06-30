package ru.otus.java.basic;

public enum UserRole {
    USER(1),
    ADMIN(2);

    private final int ID;

    UserRole(int id) {
        this.ID = id;
    }

    public int getRoleId() {
        return ID;
    }
}
