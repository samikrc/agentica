package agentica.tools

/**
 *  The protected resource a tool call needs a permission grant for.
 *  @param grantKey     Key checked and recorded in
 *                    [[agentica.permissions.ScopeStore]] — e.g. an absolute file
 *                    path or directory prefix the grant covers.
 *  @param displayPath  Path shown to the user in the permission prompt.
 */
case class PermissionTarget(grantKey: String, displayPath: Option[String])

/**
 *  Mixin for tools that require a user permission grant before execution.
 *
 *  [[agentica.permissions.PermissionMiddleware]] consults [[permissionTarget]]
 *  before dispatch so the permission prompt happens in the middleware chain
 *  rather than inside `execute`. Tools still treat a missing grant as a denial
 *  in `execute` itself — the in-tool check is the last-line defense for callers
 *  that bypass the middleware chain (e.g. direct `execute` calls in tests).
 *
 *  @tparam I  The tool's validated input type.
 */
trait PermissionSensitive[I]
{
    self: Tool[I, ?] =>

    /**
     *  Declares the resource needing a grant for this call.
     *
     *  Must mirror `execute`'s preconditions: return `None` when no permission
     *  applies — including when the call will fail or short-circuit before
     *  reaching the protected operation (e.g. cached output, missing provider),
     *  so the user is never prompted for work that cannot proceed.
     *
     *  @param input  Validated input produced by `validate`.
     *  @param ctx    Runtime execution context.
     *  @return       The [[PermissionTarget]] needing a grant, or `None` when
     *                the call does not reach a protected operation.
     */
    def permissionTarget(input: I, ctx: ExecutionContext): Option[PermissionTarget]
}
