package agentica.shell

/**
 *  Structured parse error returned when a command string cannot be tokenized.
 *  @param message  Human-readable description of the parse failure.
 *  @param input    The original input string, for logging context.
 */
case class ParseError(message: String, input: String)

/**
 *  Hand-written tokenizer for the Agentica DSL.
 *
 *  Input grammar (informal):
 *  {{{
 *    command    ::= family '.' verb { ' ' arg }
 *    arg        ::= key '=' value
 *    value      ::= bare-word | '"' quoted-string '"'
 *    bare-word  ::= [^ \t=]+
 *    quoted-string ::= any chars, with \" as the only escape sequence
 *  }}}
 *
 *  Examples:
 *  {{{
 *    files.read path=data/report.txt
 *    files.search query="total revenue" path=reports/ ignore_case=true
 *    llm.summarize text="some inline text with spaces"
 *  }}}
 *
 *  Thread-safe: stateless object.
 */
object Tokenizer
{
    /**
     *  Parses a raw command string into a [[Command]] AST.
     *
     *  @param raw  Raw command string, already stripped of any `run(command="...")` wrapper.
     *  @return     `Right(Command)` on success, `Left(ParseError)` on failure.
     */
    def parse(raw: String): Either[ParseError, Command] =
    {
        val trimmed = raw.trim
        if (trimmed.isEmpty)
        {
            return Left(ParseError("empty command", raw))
        }

        // ── 1. Extract family.verb head ──────────────────────────────────────
        val spaceIdx = trimmed.indexWhere(c => c == ' ' || c == '\t')
        val head     = if (spaceIdx < 0) then trimmed else trimmed.substring(0, spaceIdx)
        val rest     = if (spaceIdx < 0) then "" else trimmed.substring(spaceIdx + 1).trim

        val dotIdx = head.indexOf('.')
        if (dotIdx <= 0 || dotIdx == head.length - 1)
        {
            return Left(ParseError(s"expected family.verb, got: '$head'", raw))
        }

        val family = head.substring(0, dotIdx)
        val verb   = head.substring(dotIdx + 1)

        if (family.isEmpty || verb.isEmpty)
        {
            return Left(ParseError(s"family and verb must be non-empty, got: '$head'", raw))
        }

        // ── 2. Parse key=value arguments ─────────────────────────────────────
        parseArgs(rest, raw) match
        {
            case Left(err)   => Left(err)
            case Right(args) => Right(Command(family, verb, args))
        }
    }

    // ── Internal arg parser ───────────────────────────────────────────────────

    private def parseArgs(s: String, rawInput: String): Either[ParseError, Map[String, String]] =
    {
        if (s.isEmpty)
        {
            return Right(Map.empty)
        }

        val result = scala.collection.mutable.LinkedHashMap.empty[String, String]
        var pos    = 0

        while (pos < s.length)
        {
            // Skip whitespace between args
            while (pos < s.length && (s(pos) == ' ' || s(pos) == '\t')) { pos += 1 }
            if (pos >= s.length)
            {
                // trailing whitespace — done
                pos = s.length
            }
            else
            {
                // Find '='
                val eqIdx = s.indexOf('=', pos)
                if (eqIdx < 0)
                    return Left(ParseError(s"expected key=value, got trailing text: '${s.substring(pos)}'", rawInput))

                val key = s.substring(pos, eqIdx).trim
                if (key.isEmpty)
                    return Left(ParseError(s"empty key before '=' at position $pos", rawInput))
                if (key.contains(' ') || key.contains('\t'))
                    return Left(ParseError(s"key must not contain whitespace: '$key'", rawInput))

                pos = eqIdx + 1  // move past '='

                // Read value: quoted or bare
                if (pos < s.length && s(pos) == '"')
                {
                    // Quoted value
                    parseQuoted(s, pos + 1, rawInput) match
                    {
                        case Left(err)              => return Left(err)
                        case Right((value, nextPos)) =>
                            result.put(key, value)
                            pos = nextPos
                    }
                }
                else
                {
                    // Bare value — ends at next whitespace
                    val start = pos
                    while (pos < s.length && s(pos) != ' ' && s(pos) != '\t') { pos += 1 }
                    val value = s.substring(start, pos)
                    if (value.isEmpty)
                    {
                        return Left(ParseError(s"empty value for key '$key'", rawInput))
                    }
                    result.put(key, value)
                }
            }
        }

        Right(result.toMap)
    }

    /**
     *  Parses a quoted string value starting *after* the opening `"`.
     *  Supports `\"` as the only escape sequence.
     *
     *  @param s         Full argument string.
     *  @param start     Index of the first character inside the opening quote.
     *  @param rawInput  Original input for error messages.
     *  @return          `Right((unescapedValue, posAfterClosingQuote))` or `Left(ParseError)`.
     */
    private def parseQuoted(s: String, start: Int, rawInput: String): Either[ParseError, (String, Int)] =
    {
        val sb     = new StringBuilder
        var pos    = start
        var closed = false

        while (pos < s.length && !closed)
        {
            val c = s(pos)
            if (c == '\\' && pos + 1 < s.length && s(pos + 1) == '"')
            {
                sb.append('"')
                pos += 2
            }
            else if (c == '"')
            {
                closed = true
                pos += 1    // move past closing '"'
            }
            else
            {
                sb.append(c)
                pos += 1
            }
        }

        if (!closed)
        {
            Left(ParseError(s"unclosed quoted string starting at position $start", rawInput))
        }
        else
        {
            // After the closing '"' there must be whitespace or end-of-string
            if (pos < s.length && s(pos) != ' ' && s(pos) != '\t')
            {
                Left(ParseError(s"unexpected character '${s(pos)}' after closing quote at position $pos", rawInput))
            }
            else
            {
                Right((sb.toString, pos))
            }
        }
    }
}
