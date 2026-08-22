package agentica.agent

import agentica.observability.TraceLogger

/**
 *  A single `run(command="...")` call extracted from an LLM response.
 *  @param rawCommand  Unescaped value of the `command=` argument, ready for `Tokenizer.parse()`.
 *  @param startOffset Character offset of the `run(` prefix in the source text.
 *  @param endOffset   Character offset immediately after the closing `)`.
 */
case class ParsedToolCall(rawCommand: String, startOffset: Int, endOffset: Int)

/**
 *  A `run(...)` occurrence that could not be parsed.
 *  Returned in-position so [[AgentLoop]] can inject a structured error into the
 *  `[TOOL RESULT]` block, allowing the model to observe and self-correct.
 *  @param rawSnippet  The raw text fragment starting at `run(` up to where parsing failed.
 *  @param reason      Human-readable explanation of why the call could not be parsed.
 *  @param startOffset Character offset of the `run(` prefix in the source text.
 */
case class ParseFailure(rawSnippet: String, reason: String, startOffset: Int)

/**
 *  Sum type returned by [[ToolCallParser.parse]] for every `run(` occurrence.
 */
enum ToolCallResult
{
    /**
     *  A successfully parsed tool call.
     *  @param call  The extracted [[ParsedToolCall]].
     */
    case Success(call: ParsedToolCall)

    /**
     *  A `run(...)` occurrence that failed to parse.
     *  @param err  The [[ParseFailure]] carrying the snippet and reason.
     */
    case Failure(err: ParseFailure)
}

/**
 *  Scans an LLM response string for all `run(command="...")` occurrences in order.
 *  Returns a [[ToolCallResult]] for every occurrence — both successes and failures.
 *  Malformed calls are never silently dropped: each yields a [[ToolCallResult.Failure]]
 *  so [[AgentLoop]] can inject a `parse_failed` error into the `[TOOL RESULT]` block.
 *  Never throws; always returns a (possibly empty) list.
 *
 *  '''Why not regex?'''
 *  The `family.verb` head and bare-word values are trivially regex-able, but the
 *  quoted `command=` value allows `\"` as an escape sequence — meaning the delimiter
 *  can appear inside the string itself. Handling this correctly with regex requires
 *  possessive/atomic quantifiers or lookbehind, which makes the pattern hard to read
 *  and harder to produce precise, position-aware error messages from (regex does not
 *  tell you ''why'' it failed to match). The hand-written scanner is ~160 lines,
 *  clearly structured, handles the escaped-quote case explicitly, and reports exact
 *  byte offsets in every warning. The complexity is warranted.
 */
object ToolCallParser
{

    /**
     *  Extracts all `run(command="...")` occurrences from `text` in document order.
     *  Returns a [[ToolCallResult]] for every occurrence: [[ToolCallResult.Success]] for
     *  well-formed calls and [[ToolCallResult.Failure]] for malformed ones.
     *  @param text     Full LLM response text to scan.
     *  @param traceId  Trace ID used for logging parse failures.
     *  @return         List of [[ToolCallResult]] in document order; empty if no `run(` found.
     */
    def parse(text: String, traceId: String): List[ToolCallResult] =
    {
        val prefix   = "run(command="
        val result   = scala.collection.mutable.ListBuffer.empty[ToolCallResult]
        var pos      = 0

        while (pos < text.length)
        {
            val runIdx = text.indexOf(prefix, pos)
            if (runIdx < 0)
            {
                pos = text.length
            }
            else
            {
                val afterPrefix = runIdx + prefix.length
                if (afterPrefix >= text.length)
                {
                    val snippet = text.substring(runIdx).take(40)
                    TraceLogger.warn(traceId, "tool_parse_truncated",
                        Map("offset" -> runIdx.toString, "fragment" -> snippet)
                    )
                    result += ToolCallResult.Failure(ParseFailure(
                        rawSnippet  = snippet,
                        reason      = "truncated: text ended inside run( prefix",
                        startOffset = runIdx
                    ))
                    pos = text.length
                }
                else
                {
                    val quoteChar = text.charAt(afterPrefix)
                    if (quoteChar != '"' && quoteChar != '\'')
                    {
                        val snippet = text.substring(runIdx).take(60)
                        TraceLogger.warn(traceId, "tool_parse_no_quote",
                            Map("offset" -> afterPrefix.toString, "char" -> quoteChar.toString)
                        )
                        result += ToolCallResult.Failure(ParseFailure(
                            rawSnippet  = snippet,
                            reason      = s"expected opening quote after command=, got: '${quoteChar}'",
                            startOffset = runIdx
                        ))
                        pos = runIdx + prefix.length
                    }
                    else
                    {
                        extractQuotedValue(text, afterPrefix + 1, quoteChar, traceId) match
                        {
                            case None =>
                                val snippet = text.substring(runIdx).take(80)
                                result += ToolCallResult.Failure(ParseFailure(
                                    rawSnippet  = snippet,
                                    reason      = "unclosed quoted string in command= value",
                                    startOffset = runIdx
                                ))
                                pos = runIdx + prefix.length
                            case Some((rawCommand, closingQuotePos)) =>
                                val afterClose = closingQuotePos + 1
                                val closeParen = text.indexOf(')', afterClose)
                                if (closeParen < 0)
                                {
                                    val snippet = text.substring(runIdx).take(80)
                                    TraceLogger.warn(traceId, "tool_parse_no_close_paren",
                                        Map("offset" -> afterClose.toString)
                                    )
                                    result += ToolCallResult.Failure(ParseFailure(
                                        rawSnippet  = snippet,
                                        reason      = "missing closing ) after command= value",
                                        startOffset = runIdx
                                    ))
                                    pos = runIdx + prefix.length
                                }
                                else
                                {
                                    result += ToolCallResult.Success(ParsedToolCall(
                                        rawCommand  = rawCommand,
                                        startOffset = runIdx,
                                        endOffset   = closeParen + 1
                                    ))
                                    pos = closeParen + 1
                                }
                        }
                    }
                }
            }
        }

        result.toList
    }

    /**
     *  Extracts the content of a quoted string starting at `startPos` (after the opening quote).
     *  Handles `\"` escape sequences.  Returns `None` if the closing quote is never found.
     *  @param text       Source text.
     *  @param startPos   Position immediately after the opening quote character.
     *  @param quoteChar  The quote character (`"` or `'`) that opened the string.
     *  @param traceId    Trace ID used for logging failures.
     *  @return           `Some((unescapedContent, posOfClosingQuote))` or `None`.
     */
    private def extractQuotedValue(
        text:      String,
        startPos:  Int,
        quoteChar: Char,
        traceId:   String
    ): Option[(String, Int)] =
    {
        val buf   = StringBuilder()
        var i     = startPos
        var found = false
        var result: Option[(String, Int)] = None

        while (i < text.length && !found)
        {
            val ch = text.charAt(i)
            if (ch == '\\' && i + 1 < text.length && text.charAt(i + 1) == quoteChar)
            {
                // explicit escape sequence \" — consume as literal quote
                buf.append(quoteChar)
                i += 2
            }
            else if (ch == '\\' && i + 1 < text.length && text.charAt(i + 1) == '\\')
            {
                buf.append('\\')
                i += 2
            }
            else if (ch == quoteChar)
            {
                result = Some((buf.toString, i))
                found = true
            }
            else
            {
                buf.append(ch)
                i += 1
            }
        }

        if (!found)
        {
            TraceLogger.warn(traceId, "tool_parse_unclosed_quote",
                Map("offset" -> startPos.toString, "fragment" -> text.substring(startPos).take(60))
            )
        }

        result
    }
}
