package com.example;

public final class JavaGreeting {
    private JavaGreeting() {
    }

    public static String prefix() {
        return "Hello";
    }

    public static String message() {
        return GroovyGreeting.message();
    }

    public static void main(String[] args) {
        System.out.println(message());
    }
}
