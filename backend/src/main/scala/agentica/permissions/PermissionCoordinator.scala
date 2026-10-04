package agentica.permissions

import agentica.agent.AgentEvent
import java.util.UUID
import java.util.concurrent.{CompletableFuture, ExecutionException, TimeUnit, TimeoutException}
import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.concurrent.TrieMap

/**
 *  Coordinates the one-shot permission decisions for a single agent run.
 *
 *  A decision is one-shot, so `CompletableFuture` models it better than a queue and avoids
 *  `SynchronousQueue`'s lost-offer race. Scala `Promise`/`Future` was not selected because
 *  this backend intentionally blocks directly on JDK virtual threads; `Promise`/`Await`
 *  would add Scala async machinery without benefit, and Scala has no Loom thread primitive.
 */
final class PermissionCoordinator private[permissions] (
    val runId: String,
    timeout:   Long,
    timeoutUnit: TimeUnit
)
{
    /**
     *  Creates a coordinator with the default sixty-second decision timeout.
     *  @param runId  Identifier of the run that owns permission requests.
     */
    def this(runId: String) = this(runId, 60L, TimeUnit.SECONDS)

    private val closed = new AtomicBoolean(false)



    /** Emits a uniquely identified request and waits for its decision. */
    def request(
        tool:    String,
        path:    Option[String],
        options: List[String],
        onEvent: AgentEvent => Unit
    ): GrantDecision =
    {
        if (closed.get()) return GrantDecision.Denied

        val requestId = UUID.randomUUID().toString
        val future    = CompletableFuture[GrantDecision]()
        PermissionCoordinator.register(runId, requestId, future)

        try
        {
            // Re-check closed after registering: if close() raced between the first check and
            // registration, complete this future ourselves so the request cannot block.
            if (closed.get())
            {
                future.complete(GrantDecision.Denied)
                return GrantDecision.Denied
            }

            onEvent(AgentEvent.PermissionRequired(requestId, tool, path, options))
            future.get(timeout, timeoutUnit)
        }
        catch
        {
            case _: TimeoutException | _: ExecutionException => GrantDecision.Denied
            case _: InterruptedException =>
                Thread.currentThread().interrupt()
                GrantDecision.Denied
        }
        finally PermissionCoordinator.unregister(runId, requestId, future)
    }

    /** Resolves one request owned by this run; primarily used by HTTP adapters and tests. */
    def resolve(requestId: String, decision: GrantDecision): PermissionCoordinator.ResolveResult =
        PermissionCoordinator.resolve(runId, requestId, decision)

    /** Marks this coordinator closed and denies every outstanding request owned by this run. */
    def close(): Unit =
        if (closed.compareAndSet(false, true))
            PermissionCoordinator.close(runId)
}

object PermissionCoordinator
{
    enum ResolveResult
    {
        case Completed, Unknown, AlreadyCompleted
    }

    // TrieMap is Scala stdlib's concrete concurrent Map and provides Option-based lookups;
    // requests are shared by HTTP and agent threads, so the registry must be concurrent.
    private val pending = TrieMap.empty[(String, String), CompletableFuture[GrantDecision]]

    /**
     *  Registers a pending permission request in the global lookup.
     *  @param runId      Identifier of the run that owns the request.
     *  @param requestId  Identifier of the permission request.
     *  @param future     Future completed when the user submits a decision.
     */
    private def register(
        runId: String,
        requestId: String,
        future: CompletableFuture[GrantDecision]
    ): Unit = pending.put((runId, requestId), future)

    /**
     *  Removes a completed permission request from the global lookup.
     *  @param runId      Identifier of the run that owns the request.
     *  @param requestId  Identifier of the permission request.
     *  @param future     Future originally registered for the request.
     */
    private def unregister(
        runId: String,
        requestId: String,
        future: CompletableFuture[GrantDecision]
    ): Unit = pending.remove((runId, requestId), future)

    /**
     *  Resolves a pending permission request with the supplied user decision.
     *  @param runId      Identifier of the run that owns the request.
     *  @param requestId  Identifier of the permission request.
     *  @param decision   User decision used to complete the pending request.
     *  @return           Resolution status indicating success or why no request was completed.
     */
    def resolve(runId: String, requestId: String, decision: GrantDecision): ResolveResult =
        pending.get((runId, requestId)) match
        {
            case None => ResolveResult.Unknown
            case Some(future) if future.complete(decision) => ResolveResult.Completed
            case Some(_) => ResolveResult.AlreadyCompleted
        }

    /** Package-private so callers must go through the per-instance [[PermissionCoordinator.close]],
     *  which preserves the instance's closed state. */
    private[permissions] def close(runId: String): Unit =
        pending.foreach { case (key @ (`runId`, _), future) =>
            future.complete(GrantDecision.Denied)
            pending.remove(key, future)
        case _ => () }
}
