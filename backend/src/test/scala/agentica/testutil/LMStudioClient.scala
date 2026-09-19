package agentica.testutil

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}

/**
 *  Thin client for LM Studio's local model-management endpoints.
 *
 *  Provides helpers to detect a local LM Studio server and to unload one or all
 *  loaded model instances. Calls silently swallow errors so that tests can keep
 *  running even when the server is down or the endpoint is unavailable.
 */
object LMStudioClient
{
    /**
     *  Returns true if the server URL targets a local/private LM Studio instance.
     *
     *  @param serverURL  Full server URL string.
     *  @return           `true` if the host is considered local.
     */
    def isLocal(serverURL: String): Boolean =
    {
        val host = try { URI.create(serverURL).getHost } catch { case _: Throwable => "" }
        host != null && (
            host.startsWith("192.") ||
            host.startsWith("172.") ||
            host == "localhost"      ||
            host.startsWith("127.")
        )
    }

    /**
     *  Asks LM Studio to unload the given model instance.
     *
     *  @param serverURL  Base URL of the LM Studio server.
     *  @param modelName  Model identifier to unload (used as `instance_id`).
     *  @param apiKey     Bearer token for authentication.
     */
    def unloadLocalModel(serverURL: String, modelName: String, apiKey: String): Unit =
    {
        try
        {
            val base      = serverURL.stripSuffix("/v1").stripSuffix("/")
            val unloadURL = s"$base/api/v1/models/unload"
            val body      = s"""{"instance_id": "$modelName"}"""
            val request   = HttpRequest.newBuilder()
                .uri(URI.create(unloadURL))
                .header("Authorization", s"Bearer $apiKey")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            val response  = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString())
            println(s"  [unload] $modelName -> HTTP ${response.statusCode()}")
        }
        catch
        {
            case t: Throwable =>
                println(s"  [unload] no-op (${t.getClass.getSimpleName}: ${t.getMessage})")
        }
    }

    /**
     *  Queries the LM Studio `/v1/models` endpoint and unloads every loaded model.
     *
     *  @param serverURL  Base URL of the LM Studio server.
     *  @param apiKey     Bearer token for authentication.
     */
    def unloadAllLocalModels(serverURL: String, apiKey: String): Unit =
    {
        try
        {
            val base      = serverURL.stripSuffix("/v1").stripSuffix("/")
            val modelsURL = s"$base/v1/models"
            val request   = HttpRequest.newBuilder()
                .uri(URI.create(modelsURL))
                .header("Authorization", s"Bearer $apiKey")
                .GET()
                .build()
            val response  = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString())

            if (response.statusCode() == 200)
            {
                val modelIds = """"id"\s*:\s*"([^"]+)"""".r.findAllMatchIn(response.body()).map(_.group(1)).toList
                if (modelIds.isEmpty)
                {
                    println(s"  [unload-all] No models currently loaded on $serverURL")
                }
                else
                {
                    println(s"  [unload-all] Found ${modelIds.size} loaded model(s): ${modelIds.mkString(", ")}")
                    for (id <- modelIds)
                    {
                        unloadLocalModel(serverURL, id, apiKey)
                    }
                }
            }
            else
            {
                println(s"  [unload-all] /v1/models returned HTTP ${response.statusCode()} -- skipping")
            }
        }
        catch
        {
            case t: Throwable =>
                println(s"  [unload-all] no-op (${t.getClass.getSimpleName}: ${t.getMessage})")
        }
    }
}
