package agentica.misctests

import org.scalatest.funsuite.AnyFunSuite

import java.nio.file.{Files, Path, Paths}
import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters.*

/** Verifies contract-style Scaladoc for published Scala members in production and test sources. */
class ScaladocCoverageTest extends AnyFunSuite
{
    private val MethodDeclaration = """^\s*(?:override\s+)?(?:(?:private|protected)(?:\[[^]]+\])?\s+)?def\s+[A-Za-z_$][A-Za-z0-9_$]*.*""".r
    private val Parameter         = """\b([A-Za-z_$][A-Za-z0-9_$]*)\s*:""".r
    private val ParamTag          = """(?m)^\s*\*\s*@param\s+([A-Za-z_$][A-Za-z0-9_$]*)\b""".r
    private val TypeParamTag      = """(?m)^\s*\*\s*@tparam\s+([A-Za-z_$][A-Za-z0-9_$]*)\b""".r

    /**
     *  Returns every Scala source file below a source root.
     *  @param root  Source directory to traverse recursively.
     *  @return      Scala source paths below `root`.
     */
    private def scalaFiles(root: Path): List[Path] =
    {
        val stream = Files.walk(root)
        try stream.iterator().asScala.filter(path => Files.isRegularFile(path) && path.toString.endsWith(".scala")).toList
        finally stream.close()
    }

    /**
     *  Reports documentation violations for published methods in one source file.
     *  @param path  Scala source file to inspect.
     *  @return      Human-readable violations including file and line number.
     */
    private def documentationViolations(path: Path): List[String] =
    {
        val lines = Files.readAllLines(path).asScala.toVector
        lines.indices.flatMap { index =>
            if !isPublishedMember(lines(index)) then Nil
            else
            {
                val location  = s"${path.toString}:${index + 1}"
                val signature = collectSignature(lines, index)
                scaladocBefore(lines, index) match
                {
                    case None      => List(s"$location: missing Scaladoc: ${lines(index).trim}")
                    case Some(doc) => validateScaladoc(location, signature, doc)
                }
            }
        }.toList
    }

    /**
     *  Determines whether a source line starts a publishable, non-overriding member method.
     *  Members indented beyond one owner level are treated as local or nested implementation details.
     *  @param line  Source line to inspect.
     *  @return      `true` when the line starts a method covered by this policy.
     */
    private def isPublishedMember(line: String): Boolean =
    {
        val indentation = line.takeWhile(_ == ' ').length
        indentation <= 4 && !line.trim.startsWith("override def") && MethodDeclaration.matches(line)
    }

    /**
     *  Collects a complete method signature, including multiline parameter clauses.
     *  @param lines             Source file lines.
     *  @param declarationIndex  Index of the line containing `def`.
     *  @return                  Normalized signature text through the top-level body delimiter.
     */
    private def collectSignature(lines: Vector[String], declarationIndex: Int): String =
    {
        val result = StringBuilder()
        var roundDepth  = 0
        var squareDepth = 0
        var index       = declarationIndex
        var complete    = false
        while index < lines.length && !complete do
        {
            val line = lines(index).trim
            if result.nonEmpty then result.append(' ')
            result.append(line)
            var charIndex = 0
            while charIndex < line.length && !complete do
            {
                line(charIndex) match
                {
                    case '(' => roundDepth += 1
                    case ')' => roundDepth -= 1
                    case '[' => squareDepth += 1
                    case ']' => squareDepth -= 1
                    case '=' | '{' if roundDepth == 0 && squareDepth == 0 => complete = true
                    case _ => ()
                }
                charIndex += 1
            }
            if !complete && roundDepth == 0 && squareDepth == 0 &&
                (line.matches(".*\\)\\s*:\\s*[^=]+") || line.matches(".*\\bdef\\s+[A-Za-z_$][A-Za-z0-9_$]*\\s*:\\s*[^=]+"))
            then complete = true
            index += 1
        }
        result.toString
    }

    /**
     *  Finds the Scaladoc block immediately preceding a declaration.
     *  Blank lines and annotations between the block and declaration are allowed.
     *  @param lines             Source file lines.
     *  @param declarationIndex  Index of the documented declaration.
     *  @return                  Full Scaladoc text when an adjacent block exists.
     */
    private def scaladocBefore(lines: Vector[String], declarationIndex: Int): Option[String] =
    {
        var end = declarationIndex - 1
        while end >= 0 && (lines(end).trim.isEmpty || lines(end).trim.startsWith("@")) do end -= 1
        if end < 0 || !lines(end).trim.endsWith("*/") then None
        else
        {
            var start = end
            while start >= 0 && !lines(start).trim.startsWith("/**") do start -= 1
            Option.when(start >= 0)(lines.slice(start, end + 1).mkString("\n"))
        }
    }

    /**
     *  Validates a Scaladoc block against its parsed source signature.
     *  @param location   File and line used in diagnostic messages.
     *  @param signature  Complete method signature.
     *  @param doc        Adjacent Scaladoc block.
     *  @return           Contract-documentation violations found in the block.
     */
    private def validateScaladoc(location: String, signature: String, doc: String): List[String] =
    {
        val violations = ListBuffer.empty[String]
        val parameters = valueParameters(signature)
        val documented = ParamTag.findAllMatchIn(doc).map(_.group(1)).toList
        parameters.filterNot(documented.contains).foreach(name => violations += s"$location: missing @param $name")
        documented.filterNot(parameters.contains).foreach(name => violations += s"$location: unknown @param $name")

        val typeParameters = methodTypeParameters(signature)
        val documentedTypes = TypeParamTag.findAllMatchIn(doc).map(_.group(1)).toList
        typeParameters.filterNot(documentedTypes.contains).foreach(name => violations += s"$location: missing @tparam $name")
        documentedTypes.filterNot(typeParameters.contains).foreach(name => violations += s"$location: unknown @tparam $name")

        explicitReturnType(signature).filterNot(_ == "Unit").foreach { _ =>
            if !doc.contains("@return") then violations += s"$location: missing @return"
        }
        if !hasDescription(doc) then violations += s"$location: missing behavioral description"
        violations.toList
    }

    /**
     *  Extracts value-parameter names from all top-level parameter clauses.
     *  @param signature  Complete method signature.
     *  @return           Declared value-parameter names in source order.
     */
    private def valueParameters(signature: String): List[String] =
    {
        val clauses = topLevelDelimited(signature, '(', ')')
        clauses.flatMap { clause =>
            splitTopLevel(clause).flatMap { parameter =>
                Parameter.findPrefixMatchOf(parameter.trim.stripPrefix("using ").stripPrefix("implicit ")).map(_.group(1))
            }
        }
    }

    /**
     *  Splits a parameter clause on commas that are not nested in delimiters.
     *  @param text  Parameter-clause content.
     *  @return      Top-level parameter declarations in source order.
     */
    private def splitTopLevel(text: String): List[String] =
    {
        val parts = ListBuffer.empty[String]
        var roundDepth  = 0
        var squareDepth = 0
        var curlyDepth  = 0
        var start       = 0
        text.indices.foreach { index =>
            text(index) match
            {
                case '(' => roundDepth += 1
                case ')' => roundDepth -= 1
                case '[' => squareDepth += 1
                case ']' => squareDepth -= 1
                case '{' => curlyDepth += 1
                case '}' => curlyDepth -= 1
                case ',' if roundDepth == 0 && squareDepth == 0 && curlyDepth == 0 =>
                    parts += text.substring(start, index)
                    start = index + 1
                case _ => ()
            }
        }
        parts += text.substring(start)
        parts.toList.filter(_.trim.nonEmpty)
    }

    /**
     *  Extracts method type-parameter names from the declaration.
     *  @param signature  Complete method signature.
     *  @return           Declared method type-parameter names.
     */
    private def methodTypeParameters(signature: String): List[String] =
    {
        val methodPrefix = signature.drop(signature.indexOf("def ") + 4)
        val open = methodPrefix.indexOf('[')
        val paren = methodPrefix.indexOf('(')
        if open < 0 || (paren >= 0 && open > paren) then Nil
        else
        {
            val close = methodPrefix.indexOf(']', open + 1)
            if close < 0 then Nil
            else methodPrefix.substring(open + 1, close).split(',').toList.map(_.trim.takeWhile(c => c.isLetterOrDigit || c == '_' || c == '$')).filter(_.nonEmpty)
        }
    }

    /**
     *  Extracts every top-level region delimited by the supplied characters.
     *  @param text   Source text to inspect.
     *  @param open   Opening delimiter.
     *  @param close  Closing delimiter.
     *  @return       Delimited region contents in source order.
     */
    private def topLevelDelimited(text: String, open: Char, close: Char): List[String] =
    {
        val regions = ListBuffer.empty[String]
        var depth = 0
        var start = -1
        text.indices.foreach { index =>
            if text(index) == open then
            {
                if depth == 0 then start = index + 1
                depth += 1
            }
            else if text(index) == close then
            {
                depth -= 1
                if depth == 0 && start >= 0 then regions += text.substring(start, index)
            }
        }
        regions.toList
    }

    /**
     *  Extracts an explicit return type from a method signature.
     *  @param signature  Complete method signature.
     *  @return           Explicit return type, or `None` for constructors and inferred returns.
     */
    private def explicitReturnType(signature: String): Option[String] =
    {
        if signature.matches(".*\\bdef\\s+this\\b.*") then None
        else
        {
            val delimiter = topLevelBodyDelimiter(signature)
            val beforeBody = signature.substring(0, delimiter.getOrElse(signature.length)).trim
            val afterParams = if beforeBody.lastIndexOf(')') >= 0 then beforeBody.substring(beforeBody.lastIndexOf(')') + 1) else beforeBody.drop(beforeBody.indexOf("def ") + 4).dropWhile(c => c != ':')
            val colon = afterParams.indexOf(':')
            Option.when(colon >= 0)(afterParams.substring(colon + 1).trim).filter(_.nonEmpty)
        }
    }

    /**
     *  Locates the first top-level method-body delimiter, ignoring defaults inside parameter clauses.
     *  @param signature  Complete method signature.
     *  @return           Index of the top-level `=` or `{`, when present.
     */
    private def topLevelBodyDelimiter(signature: String): Option[Int] =
    {
        var roundDepth  = 0
        var squareDepth = 0
        signature.indices.find { index =>
            signature(index) match
            {
                case '(' => roundDepth += 1; false
                case ')' => roundDepth -= 1; false
                case '[' => squareDepth += 1; false
                case ']' => squareDepth -= 1; false
                case '=' | '{' => roundDepth == 0 && squareDepth == 0
                case _ => false
            }
        }
    }

    /**
     *  Checks whether a Scaladoc block contains prose beyond tags and delimiters.
     *  @param doc  Scaladoc block text.
     *  @return     `true` when at least one non-tag description line is present.
     */
    private def hasDescription(doc: String): Boolean =
        doc.linesIterator.map(_.trim.stripPrefix("*").trim)
            .exists(line => line.nonEmpty && line != "/**" && line != "/" && !line.startsWith("@"))

    test("every published Scala method has Scaladoc") {
        val roots = List(Paths.get("src/main/scala"), Paths.get("src/test/scala"))
        val missing = roots.flatMap(scalaFiles).flatMap(documentationViolations)
            .filter(_.contains("missing Scaladoc")).sorted
        assert(missing.isEmpty, s"${missing.size} methods without Scaladoc:\n${missing.mkString("\n")}")
    }

    test("published Scala methods have complete contract-style Scaladoc") {
        assume(sys.props.get("strictScaladoc").contains("true"),
            "Run with -DstrictScaladoc=true while remediating existing contract-tag debt")
        val roots = List(Paths.get("src/main/scala"), Paths.get("src/test/scala"))
        val violations = roots.flatMap(scalaFiles).flatMap(documentationViolations).sorted
        assert(violations.isEmpty, s"${violations.size} Scaladoc violations:\n${violations.mkString("\n")}")
    }
}
