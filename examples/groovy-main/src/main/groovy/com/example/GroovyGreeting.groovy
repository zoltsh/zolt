package com.example

final class GroovyGreeting {
    static String message() {
        "${JavaGreeting.prefix()} from joint Java/Groovy compilation"
    }
}
