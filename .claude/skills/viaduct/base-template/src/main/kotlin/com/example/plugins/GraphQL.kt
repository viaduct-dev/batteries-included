package com.example.plugins

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.future.await
import viaduct.service.BasicViaductFactory
import viaduct.service.TenantRegistrationInfo
import viaduct.service.api.ExecutionInput

private val viaduct by lazy {
    BasicViaductFactory.create(
        tenantRegistrationInfo = TenantRegistrationInfo(
            tenantPackagePrefix = "com.example"
        )
    )
}

fun Application.configureGraphQL() {
    routing {
        post("/graphql") {
            @Suppress("UNCHECKED_CAST")
            val request = call.receive<Map<String, Any?>>() as Map<String, Any>

            val query = request["query"] as? String
            if (query == null) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("errors" to listOf(mapOf("message" to "Query parameter is required")))
                )
                return@post
            }

            @Suppress("UNCHECKED_CAST")
            val executionInput = ExecutionInput.create(
                operationText = query,
                variables = (request["variables"] as? Map<String, Any>) ?: emptyMap(),
            )

            val result = viaduct.executeAsync(executionInput).await()
            val statusCode = if (result.errors.isNotEmpty()) HttpStatusCode.BadRequest else HttpStatusCode.OK
            call.respond(statusCode, result.toSpecification())
        }

        get("/graphiql") {
            call.respondText(graphiqlHtml(), ContentType.Text.Html)
        }
    }
}

private fun graphiqlHtml(): String = """
<!DOCTYPE html>
<html>
<head>
    <title>GraphiQL</title>
    <link href="https://unpkg.com/graphiql/graphiql.min.css" rel="stylesheet" />
</head>
<body style="margin: 0;">
    <div id="graphiql" style="height: 100vh;"></div>
    <script crossorigin src="https://unpkg.com/react/umd/react.production.min.js"></script>
    <script crossorigin src="https://unpkg.com/react-dom/umd/react-dom.production.min.js"></script>
    <script crossorigin src="https://unpkg.com/graphiql/graphiql.min.js"></script>
    <script>
        const fetcher = GraphiQL.createFetcher({ url: '/graphql' });
        ReactDOM.render(
            React.createElement(GraphiQL, { fetcher: fetcher }),
            document.getElementById('graphiql'),
        );
    </script>
</body>
</html>
""".trimIndent()
