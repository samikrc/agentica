package agentica.permissions

/**
 *  How long a permission grant remains valid.
 */
enum GrantTTL
{
    /** Consumed on first successful use, then deleted. */
    case Once
    /** Valid for the lifetime of the current session. */
    case ForSession
    /** Persisted indefinitely across sessions. */
    case Always
}

/**
 *  A persisted permission grant for a sensitive tool.
 *  @param id          Unique grant identifier (UUID).
 *  @param sessionId   Owning session; `None` means global scope.
 *  @param toolSet     Tool or tool-set this grant covers, e.g. `"files.write"` or `"files.*"`.
 *  @param pathPrefix  Path prefix restriction; `None` means any path within the sandbox.
 *  @param ttl         Expiry policy for this grant.
 */
case class Grant(
    id:         String,
    sessionId:  Option[String],
    toolSet:    String,
    pathPrefix: Option[String],
    ttl:        GrantTTL
)

/**
 *  Decision returned from the UI permission modal to the suspended agent run.
 */
enum GrantDecision
{
    /**
     *  User approved the operation.
     *  @param ttl         How long the grant should last.
     *  @param pathPrefix  Optional path restriction chosen by the user.
     */
    case Granted(ttl: GrantTTL, pathPrefix: Option[String])
    /** User denied the operation, or the 60-second prompt timeout elapsed. */
    case Denied
}

/**
 *  Persistent store for scoped permission grants.
 *  Backed by the SQLite `permission_grants` table (created in Step 3).
 *  The trait is defined here so [[agentica.tools.ExecutionContext]] can reference it
 *  in Step 1; the full implementation is wired in Step 3.
 */
trait ScopeStore
{
    /**
     *  Checks whether a valid grant exists for the given tool and path.
     *  @param sessionId  Current session identifier.
     *  @param toolName   Canonical tool name, e.g. `"files.write"`.
     *  @param path       Absolute resolved path the tool intends to write.
     *  @return           `true` if a non-expired grant covers this tool and path.
     */
    def hasGrant(sessionId: String, toolName: String, path: String): Boolean

    /**
     *  Records a new grant returned from the permission modal.
     *  @param sessionId  Owning session.
     *  @param toolName   Canonical tool name.
     *  @param decision   Approved decision carrying TTL and optional path prefix.
     */
    def addGrant(sessionId: String, toolName: String, decision: GrantDecision.Granted): Unit

    /**
     *  Removes all `Once`-scoped grants consumed by this tool call.
     *  Called immediately after a successful `Once`-scoped execution.
     *  @param sessionId  Owning session.
     *  @param toolName   Canonical tool name.
     *  @param path       Absolute resolved path that was used.
     */
    def consumeOnce(sessionId: String, toolName: String, path: String): Unit

    /**
     *  Removes all grants owned by the given session (called on session delete).
     *  @param sessionId  Session whose grants should be purged.
     */
    def deleteForSession(sessionId: String): Unit
}
