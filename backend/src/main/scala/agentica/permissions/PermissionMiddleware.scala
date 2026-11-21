package agentica.permissions

import agentica.observability.TraceLogger
import agentica.shell.{CommandRegistry, ToolCall, ToolMiddleware}
import agentica.tools.{ErrorCode, PermissionTarget, Tool, ToolResult, ToolStatus}

/**
 *  First `before` slot in the tool-call middleware chain and the single
 *  permission-gating point for tool dispatch.
 *
 *  Consults the called tool's `permissionTarget` for the protected resource,
 *  checks [[ScopeStore]] for a covering grant, and prompts through the run's
 *  [[PermissionCoordinator]] when none exists. Denials short-circuit dispatch
 *  with a `permission_denied` [[ToolResult]] so the model observes and can
 *  recover. Tools declare targets via [[Tool.permissionTarget]]; tools that
 *  return `None` (the default) pass through ungated.
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
            case Some(tool) =>
                val typed = tool.asInstanceOf[Tool[Any, Any]]
                typed.validate(call.cmd.args).toOption
                    .flatMap(input => typed.permissionTarget(input, call.ctx))
                    .flatMap(target => gate(typed.name, target, call))
            case None => None
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
            TraceLogger.info(ctx.traceId, "permission_prompt",
                Map("tool" -> toolName, "path" -> target.displayPath.getOrElse("(none)")))
            ctx.permissionCoordinator.request(toolName, target.displayPath, grantOptions, ctx.onEvent) match
            {
                case granted: GrantDecision.Granted =>
                    TraceLogger.info(ctx.traceId, "permission_decision",
                        Map("tool" -> toolName, "decision" -> granted.toString))
                    ctx.scopeStore.addGrant(ctx.session.id, toolName, granted)
                    None
                case GrantDecision.Denied =>
                    TraceLogger.info(ctx.traceId, "permission_decision",
                        Map("tool" -> toolName, "decision" -> "Denied"))
                    Some(ToolResult(status = ToolStatus.Err(
                        code    = ErrorCode.PermissionDenied,
                        message = s"Permission denied for ${target.displayPath.getOrElse(toolName)}"
                    )))
            }
        }
    }
}
