package com.example;

public final class JavaGreeting {
    private JavaGreeting() {
    }

    public static String message() {
        return "Hello from Java and " + Main.kotlinWord();
    }
}
