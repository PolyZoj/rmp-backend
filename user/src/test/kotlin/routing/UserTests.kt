@file:Suppress("RemoveExplicitTypeArguments")

package routing

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import common.DataPayload
import common.kafka.RequestProcessor
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.*
import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import org.apache.kafka.clients.consumer.ConsumerRecords
import org.apache.kafka.clients.consumer.MockConsumer
import org.apache.kafka.clients.consumer.OffsetResetStrategy
import org.apache.kafka.clients.producer.KafkaProducer
import io.mockk.mockk
import kotlinx.serialization.json.Json
import ru.polyZoj.routing.configureRouting
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * A RequestProcessor that **immediately** completes every request
 * by invoking the supplied on-success callback.
 * This means the Ktor handler sees a 200 OK right away,
 * so we can assert on the HTTP layer without spinning Kafka or timers.
 */
private class DirectRespondProcessor : RequestProcessor() {
    override suspend fun processRequest(
        payload: DataPayload,
        topic: String,
        call: ApplicationCall,
        producer: KafkaProducer<String, DataPayload>,
        responses: ConcurrentHashMap<String, CompletableDeferred<DataPayload>>,
        mutex: Mutex,
        handleSuccessfulResponse: suspend (String, DataPayload, ApplicationCall) -> Unit
    ) {
        handleSuccessfulResponse(payload.message, payload, call)
    }
}

/** A Kafka Consumer stub that never yields any records. */
private class DummyConsumer :
    MockConsumer<String, DataPayload>(OffsetResetStrategy.EARLIEST) {

    // the default implementation already returns an empty map,
    // but overriding makes the intent explicit
    override fun poll(timeout: Duration?): ConsumerRecords<String, DataPayload> =
        ConsumerRecords.empty()
}

private val dummyProducer =
    mockk<KafkaProducer<String, DataPayload>>(relaxed = true)

class UserRoutingTest {

    private val jwtAlg = Algorithm.HMAC256("secret-for-tests")

    private val validToken: String = JWT.create()
        .withClaim("userId", "123") // <- must match what routing expects
        .sign(jwtAlg)

    /**
     * Registers minimal JWT auth (`auth-jwt`) required by the routes
     * and calls the real `configureRouting` with stubbed dependencies.
     */
    private fun Application.installModule(
        processor: RequestProcessor = DirectRespondProcessor()
    ) {
        install(Authentication) {
            jwt("auth-jwt") {
                verifier(JWT.require(jwtAlg).build())
                validate { JWTPrincipal(it.payload) }
            }
        }

        install(ContentNegotiation) {
            json(Json {
                prettyPrint = true
                isLenient = true
                ignoreUnknownKeys = true
            })
        }

        configureRouting(
            processor,
            dummyProducer,
            DummyConsumer()
        )
    }

    @Test
    fun `GET users _id_ returns 200 when authorised`() = testApplication {
        application { installModule() }

        val response = client.get("/api/v1/users/123") {
            header(HttpHeaders.Authorization, "Bearer $validToken")
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `GET users _id_ returns 401 without JWT`() = testApplication {
        application { installModule() }

        val response = client.get("/api/v1/users/123")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `PUT update goals rejects malformed JSON with 400`() = testApplication {
        application { installModule() }

        val response = client.put("/api/v1/users/goals/update") {
            header(HttpHeaders.Authorization, "Bearer $validToken")
            setBody("{")           // broken JSON
            contentType(ContentType.Application.Json)
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `POST accept friend request without body returns 400`() = testApplication {
        application { installModule() }

        val response = client.post("/api/v1/users/friends/notifications/accept") {
            header(HttpHeaders.Authorization, "Bearer $validToken")
            // <- required "friend_id" JSON field is missing
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `POST friends find with correct payload yields 200`() = testApplication {
        application { installModule() }

        val response = client.post("/api/v1/users/friends/find") {
            header(HttpHeaders.Authorization, "Bearer $validToken")
            contentType(ContentType.Application.Json)
            setBody("""{ "find_username": "alice" }""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }
}
