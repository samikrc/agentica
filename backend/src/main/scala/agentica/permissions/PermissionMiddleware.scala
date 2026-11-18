package agentica.permissions

import agentica.shell.{CommandRegistry, ToolCall, ToolMiddleware}
import agentica.tools.{ErrorCode, PermissionSensitive, PermissionTarget, Tool, ToolResult, ToolStatus}

/**
 *  First `before` slot in the tool-call middleware chain.
 *
 *  Gates tools implementing [[PermissionSensitive]] on a [[ScopeStore]] grant,
 *  prompting through the run's [[PermissionCoordinator]] when none exists.
 *  Denials short-circuit dispatch with a `permission_denied` [[ToolResult]] so
 *  the model observes and can recover. The in-tool grant check remains as the
 *  last-line defense for callers that bypass the middleware chain.
 *
 *  @param registry  Command registry used to resolve the called tool.
 */
class PermissionMiddleware(registry: CommandRegistry) extends ToolMiddleware
{
    private val grantOptions = List("Allow once", "Allow for session", "Allow always", "Deny")

    /**
     *  Checks the called tool's [[PermissionTarget]] and prompts for a grant
     *  when none covers it.
     *  @param call  The pending tool call.
     *  @return      A `permission_denied` result on denial, or `None` when the
     *               call may proceed (no target, already granted, or granted now).
     */
    override def before(call: ToolCall): Option[ToolResult] =
        registry.lookup(call.cmd.fullName) match
        {
            case Some(tool: PermissionSensitive[?]) =>
                val typed = tool.asInstanceOf[Tool[Any, Any] with PermissionSensitive[Any]]
                typed.validate(call.cmd.args).toOption
                    .flatMap(input => typed.permissionTarget(input, call.ctx))
                    .flatMap(target => gate(typed.name, target, call))
            case _ => None
        }

    /**
     *  Checks the grant store, then prompts the user if no grant covers the target.
     *  @param toolName  Canonical tool name for the grant record.
     *  @param target    Resource the grant must cover.
     *  @param call      The pending tool call carrying the run's context.
     *  @return          Denial result, or `None` when a grant permits the call.
     */
    private def gate(toolName: String, target: PermissionTarget, call: ToolCall): Option[ToolResult] =
    {
        val ctx = call.ctx
        if (ctx.scopeStore.hasGrant(ctx.session.id, toolName, target.grantKey))
        {
            None
        }
        else
        {
            ctx.permissionCoordinator.request(toolName, target.displayPath, grantOptions, ctx.onEvent) match
            {
                case granted: GrantDecision.Granted =>
                    ctx.scopeStore.addGrant(ctx.session.id, toolName, granted)
                    None
                case GrantDecision.Denied =>
                    Some(ToolResult(status = ToolStatus.Err(
                        code    = ErrorCode.PermissionDenied,
                        message = s"Permission denied for ${target.displayPath.getOrElse(toolName)}"
                    )))
            }
        }
    }
}
