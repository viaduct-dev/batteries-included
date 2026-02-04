package com.example

import io.ktor.server.application.Application
import io.ktor.server.netty.EngineMain
import com.example.plugins.configureContentNegotiation
import com.example.plugins.configureRouting
import com.example.plugins.configureGraphQL

fun main(args: Array<String>) {
    EngineMain.main(args)
}

fun Application.module() {
    configureContentNegotiation()
    configureRouting()
    configureGraphQL()
}
