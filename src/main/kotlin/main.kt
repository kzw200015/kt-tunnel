package com.github.kzw200015.kttunnel

import org.slf4j.Logger
import org.slf4j.LoggerFactory

fun main() {

}

inline fun <reified T : Any> T.logger(): Logger = LoggerFactory.getLogger(this::class.java)
