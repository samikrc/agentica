package agentica.llm

import ujson.*
import requests.*
import java.util.concurrent.{Executors, TimeUnit, TimeoutException}

/** Manual integration test against a locally running LM Studio instance.
 *  Run with:
 *   LLM_BASE_URL=http://172.23.64.1:1234 \
 *   LLM_MODEL=mistralai/ministral-3-14b-reasoning \
 *   mvn compile test-compile exec:java -Dexec.mainClass=agentica.llm.LLMRequestsTest -Dexec.classpathScope=test
 *
 *  Each attempt runs in a separate thread with a 10s timeout.
 *  Hangs are killed and reported as TIMEOUT, then the next attempt runs.
 */
object LLMRequestsTest extends App
{
    val baseUrl   = sys.env.getOrElse("LLM_BASE_URL", "http://172.23.64.1:1234")
    val modelName = sys.env.getOrElse("LLM_MODEL",    "mistralai/ministral-3-14b-reasoning")
    val apiKey    = sys.env.getOrElse("LLM_API_KEY",  "lm-studio")
    val timeoutMs = 10000

    val body = ujson.Obj(
        "model"    -> modelName,
        "messages" -> ujson.Arr(ujson.Obj("role" -> "user", "content" -> "say hi in one word")),
        "stream"   -> false
    )

    System.err.println(s"=== OpenAIProvider test ===")
    val t0       = System.currentTimeMillis()
    val provider = OpenAIProvider(baseUrl = baseUrl, modelName = modelName, apiKey = apiKey)
    val msgs     = List(agentica.session.Message(id = "test-1", sessionId = "test-session", role = "user", content = "say hi in one word", timestamp = "2024-01-01T00:00:00Z"))
    try
    {
        val usage = provider.stream(msgs, tok => System.err.println(s"TOKEN: $tok"))
        System.err.println(s"SUCCESS in ${System.currentTimeMillis() - t0}ms — $usage")
    }
    catch { case e: Exception => System.err.println(s"FAILED: ${e.getClass.getSimpleName}: ${e.getMessage}") }

    System.err.println("\n=== All done ===")
    sys.exit(0)

    // println(s"Sending request to $baseUrl/v1/chat/completions, body: $body, headers: ${Map("Content-Type" -> "application/json", "Authorization" -> s"Bearer $apiKey")}")
    // requests.post(
    //     url     = s"$baseUrl/v1/chat/completions",
    //     data    = ujson.write(body).getBytes("UTF-8"),
    //     headers = Map("Content-Type" -> "application/json", "Authorization" -> s"Bearer $apiKey"),
    //     readTimeout = 8000,
    //     connectTimeout = 3000
    // ).text()

    // // 1: bare minimum — no extra headers, default (infinite) timeout
    // attempt("1. bare minimum") {
    //     println(s"Sending request to $baseUrl/v1/chat/completions, body: $body, headers: ${Map("Content-Type" -> "application/json", "Authorization" -> s"Bearer $apiKey")}")
    //     requests.post(
    //         url     = s"$baseUrl/v1/chat/completions",
    //         data    = body,
    //         headers = Map("Content-Type" -> "application/json", "Authorization" -> s"Bearer $apiKey")
    //     ).text()
    // }

    // // 2: Connection: close header only
    // attempt("2. Connection: close header") {
    //     requests.post(
    //         url     = s"$baseUrl/v1/chat/completions",
    //         data    = body,
    //         headers = Map(
    //             "Content-Type"  -> "application/json",
    //             "Authorization" -> s"Bearer $apiKey",
    //             "Connection"    -> "close"
    //         ),
    //         check   = false
    //     ).text()
    // }

    // // 3: readTimeout only
    // attempt("3. readTimeout=8000ms") {
    //     requests.post(
    //         url         = s"$baseUrl/v1/chat/completions",
    //         data        = body,
    //         headers     = Map("Content-Type" -> "application/json", "Authorization" -> s"Bearer $apiKey"),
    //         check       = false,
    //         readTimeout = 8000
    //     ).text()
    // }

    // // 4: Connection: close + readTimeout
    // attempt("4. Connection: close + readTimeout=8000ms") {
    //     requests.post(
    //         url         = s"$baseUrl/v1/chat/completions",
    //         data        = body,
    //         headers     = Map(
    //             "Content-Type"  -> "application/json",
    //             "Authorization" -> s"Bearer $apiKey",
    //             "Connection"    -> "close"
    //         ),
    //         check       = false,
    //         readTimeout = 8000
    //     ).text()
    // }

    // // 5: keepAlive=false (requests-scala native keep-alive control)
    // attempt("5. keepAlive=false") {
    //     requests.post(
    //         url       = s"$baseUrl/v1/chat/completions",
    //         data      = body,
    //         headers   = Map("Content-Type" -> "application/json", "Authorization" -> s"Bearer $apiKey"),
    //         check     = false,
    //         keepAlive = false
    //     ).text()
    // }

    // // 6: keepAlive=false + readTimeout
    // attempt("6. keepAlive=false + readTimeout=8000ms") {
    //     requests.post(
    //         url         = s"$baseUrl/v1/chat/completions",
    //         data        = body,
    //         headers     = Map("Content-Type" -> "application/json", "Authorization" -> s"Bearer $apiKey"),
    //         check       = false,
    //         keepAlive   = false,
    //         readTimeout = 8000
    //     ).text()
    // }

    // // 7: streaming read — read bytes until we have valid JSON with "choices", then stop
    // attempt("7. requests.post.stream — read until choices found") {
    //     val sb = StringBuilder()
    //     requests.post.stream(
    //         url     = s"$baseUrl/v1/chat/completions",
    //         data    = body,
    //         headers = Map("Content-Type" -> "application/json", "Authorization" -> s"Bearer $apiKey", "Connection" -> "close"),
    //         check   = false
    //     ).readBytesThrough { is =>
    //         val reader = java.io.BufferedReader(java.io.InputStreamReader(is, "UTF-8"))
    //         var line   = reader.readLine()
    //         while line != null && !sb.toString.contains("\"finish_reason\"") do
    //         {
    //             sb.append(line).append("\n")
    //             line = reader.readLine()
    //         }
    //     }
    //     sb.toString
    // }

    System.err.println("\n=== All done ===")
}
