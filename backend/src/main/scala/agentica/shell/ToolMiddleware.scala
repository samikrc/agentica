package agentica.shell

import agentica.tools.{ExecutionContext, ToolResult}

/**
 *  A tool dispatch as seen by the middleware chain.
 *  @param cmd  Parsed command being dispatched.
 *  @param ctx  Runtime execution context for this agent run.
 */
case class ToolCall(cmd: Command, ctx: ExecutionContext)

/**
 *  Ordered before/after interceptor around tool dispatch.
 *
 *  Cross-cutting components — permission gating, the failure-loop guard,
 *  tool-result compression, telemetry — slot into the chain without touching
 *  [[VirtualShell]] or individual tools. `before` hooks run in chain order and
 *  may short-circuit; `after` hooks run in reverse chain order over the result.
 */
trait ToolMiddleware
{
    /**
     *  Runs before dispatch in chain order.
     *  Returning `Some(result)` short-circuits: dispatch is skipped, later
     *  `before` hooks do not run, and no `after` hook runs for this call.
     *  @param call  The pending tool call.
     *  @return      A short-circuit result, or `None` to continue the chain.
     */
    def before(call: ToolCall): Option[ToolResult] = None

    /**
     *  Runs after dispatch in reverse chain order; may transform the result.
     *  Not invoked when a `before` hook short-circuited the call.
     *  @param call    The dispatched tool call.
     *  @param result  Result produced by dispatch or by an inner `after` hook.
     *  @return        The (possibly transformed) result.
     */
    def after(call: ToolCall, result: ToolResult): ToolResult = result
}
