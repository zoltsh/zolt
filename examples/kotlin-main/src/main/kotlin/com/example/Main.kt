package com.example

object Main {
    @JvmStatic
    fun kotlinWord(): String = "Kotlin"

    @JvmStatic
    fun main(args: Array<String>) {
        println(JavaGreeting.message())
    }
}
