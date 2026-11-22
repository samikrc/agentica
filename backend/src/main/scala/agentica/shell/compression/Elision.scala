package agentica.shell.compression

import scala.collection.mutable
import scala.util.matching.Regex

/**
 *  Shared keep/drop machinery for the structural compressors.
 *
 *  Every line-oriented compressor follows the same skeleton: pick the units
 *  (lines, rows, sections, array elements) worth keeping — head/tail anchors,
 *  lines carrying error signal, numeric extrema, query-relevant units, and a
 *  representative of every distinct kind of content — then collapse runs of
 *  dropped units into honest `… N <noun> elided: <facts> …` markers.
 *
 *  [[keepNonRedundant]] is the guard that separates eliding repetition from
 *  truncating a document: a dropped unit survives anyway when no already-kept
 *  unit resembles it, so every distinct kind of content keeps at least one
 *  representative. Past [[MaxPromotions]] distinct kinds the payload is a
 *  document, not a repetitive stream, and the rest is kept wholesale — the
 *  compressor's not-smaller check then passes the original through.
 *
 *  The keep-selection skeleton — head/tail anchors, important-unit regexes,
 *  the non-redundant representative guard, and the invariant summaries carried
 *  in elision markers — is adapted from Caveman's engine helpers
 *  (https://github.com/JuliusBrussee/caveman).
 */
private[compression] object Elision
{
    /** Signal words force-kept in any unit they appear in. */
    val ImportantRE: Regex =
        "\\b(?i:ERROR|FAIL|FAILED|FATAL|PANIC|EXCEPTION|WARNING|DENIED|REJECTED|CRITICAL|SECURITY)\\b".r

    /** Recognises elision markers emitted by [[shapedRuns]], so a second pass never re-compresses them. */
    val MarkerRE: Regex = "…\\s*\\d+\\s+(?:lines|rows|sections|elements)\\s+elided".r

    /** Field names whose values must never appear in a marker summary. */
    private val SecretFieldRE: Regex =
        "(?i)(secret|passw|token|api[_-]?key|authoriz|credential|cookie|session)".r

    /** Extracts `name=value` / `name: value` pairs from a free-text unit. */
    private val FieldRE: Regex = "([A-Za-z_][A-Za-z0-9_.-]*)[=:]\\s*([^\\s,;|]+)".r

    /** Smallest dropped run worth collapsing into a marker; smaller runs stay verbatim. */
    private val MinElideUnits = 3

    /** Distinct content kinds promoted before the payload is treated as a document and kept wholesale. */
    private val MaxPromotions = 128

    /** Kept units sampled to seed the resemblance-class list. */
    private val MaxResemblanceClasses = 64

    /** Share of a dropped unit's vocabulary a kept unit must cover for the drop to count as represented. */
    private val ContainmentRatio = 0.9

    /** Vocabulary built per unit before the profile is considered unbounded. */
    private val MaxUnitTokens = 128

    private val SummaryMaxBytes   = 160
    private val SummaryShareDenom = 4
    private val SummaryMinBudget  = 96
    private val MaxEnumValues     = 5
    private val MaxEnumValueBytes = 24
    private val MaxValueBytes     = 40
    private val MaxNameBytes      = 32
    private val MaxFieldsPerUnit  = 24

    /** One emitted slot in the compressed sequence. */
    sealed trait Shaped

    object Shaped
    {
        /** A surviving unit; value is its index in the original unit list. */
        case class Kept(index: Int) extends Shaped

        /** An elided run; the compressor renders the marker in its own dialect. */
        case class Marker(run: Vector[Int]) extends Shaped
    }

    /**
     *  Promotes dropped units so every distinct kind of content survives once.
     *
     *  A dropped unit is kept when no already-kept unit covers [[ContainmentRatio]]
     *  of its vocabulary. Digit runs mask to a single `#` so `replicas: 2` and
     *  `replicas: 3` recognise each other as the same line, while units too
     *  numeric to compare masked keep their digits — the numbers ARE the
     *  content there. Sampling seeds classes from already-kept units, so the
     *  gate costs the same on a 200-line and a 20 000-line payload.
     *
     *  @param units  All units in document order.
     *  @param keep   Keep flags; promoted units are set to `true` in place.
     */
    def keepNonRedundant(units: Vector[String], keep: Array[Boolean]): Unit =
    {
        if (units.size != keep.size) return
        val keptIdx  = keep.indices.filter(keep(_))
        val classes  = mutable.ArrayBuffer.empty[Profile]
        val step     = math.max(1, keptIdx.size / MaxResemblanceClasses)
        var n        = 0
        while (n < keptIdx.size && classes.size < MaxResemblanceClasses)
        {
            classes += vocabulary(units(keptIdx(n)))
            n += step
        }
        var promotions = 0
        for (i <- units.indices if !keep(i))
        {
            if (promotions >= MaxPromotions)
            {
                // Too many distinct kinds — a document, not a repetitive stream.
                keep(i) = true
            }
            else
            {
                val vocab = vocabulary(units(i))
                if (!representedBy(vocab, classes))
                {
                    keep(i) = true
                    promotions += 1
                    classes += vocab
                }
            }
        }
    }

    /**
     *  Keeps the units most relevant to a query, scored by distinct-term
     *  coverage. Existing force-keeps are never removed. Deterministic: score
     *  descending, source order for ties.
     *
     *  @param keep    Keep flags updated in place.
     *  @param docs    Canonical text of every unit (same indexing as `keep`).
     *  @param query   Raw query text; empty query is a no-op.
     *  @param limit   Maximum units this pass may promote.
     *  @param minFrac Units must score at least this fraction of the best score.
     */
    def keepQueryRelevant(keep: Array[Boolean], docs: Vector[String], query: String,
                          limit: Int, minFrac: Double = 0.3): Unit =
    {
        if (query.trim.isEmpty || docs.isEmpty || keep.size != docs.size || limit <= 0) return
        val terms  = query.toLowerCase.split("[^a-z0-9_]+").filter(_.nonEmpty).toSet
        if (terms.isEmpty) return
        val scores = docs.map(d => terms.count(d.toLowerCase.contains))
        val max    = scores.max
        if (max <= 0) return
        val cands = scores.indices
            .filter(i => !keep(i) && scores(i) >= max * minFrac)
            .sortBy(i => (-scores(i), i))
        cands.take(limit).foreach(i => keep(i) = true)
    }

    /**
     *  Keeps the rows carrying the minimum and maximum of each predominantly
     *  numeric column. A column qualifies only when at least half its values
     *  parse as finite numbers; NaN and infinities are not numbers.
     *
     *  @param rows      All data rows (uniform column counts).
     *  @param keep      Keep flags over data rows, updated in place.
     */
    def keepNumericExtrema(rows: Vector[Vector[String]], keep: Array[Boolean]): Unit =
    {
        if (rows.isEmpty) return
        val cols = rows.map(_.size).max
        for (c <- 0 until cols)
        {
            val parsed = rows.indices.flatMap { r =>
                if (c < rows(r).size) finiteNumber(rows(r)(c).trim).map(_ -> r) else None
            }
            if (parsed.size * 2 >= rows.size && parsed.nonEmpty)
            {
                keep(parsed.minBy(_._1)._2) = true
                keep(parsed.maxBy(_._1)._2) = true
            }
        }
    }

    /**
     *  Folds the keep set into an emit sequence: kept units in order, dropped
     *  runs of at least [[MinElideUnits]] replaced by a [[Shaped.Marker]]
     *  carrying the run, smaller runs emitted verbatim (a singleton marker
     *  costs what the unit costs).
     *
     *  @param units  Total unit count.
     *  @param keep   Final keep flags.
     *  @return       Ordered [[Shaped]] sequence for the compressor to render.
     */
    def shapedRuns(units: Int, keep: Array[Boolean]): Vector[Shaped] =
    {
        val out = Vector.newBuilder[Shaped]
        var run = Vector.empty[Int]
        def flush(): Unit =
        {
            if (run.nonEmpty)
            {
                if (run.size >= MinElideUnits) out += Shaped.Marker(run)
                else run.foreach(i => out += Shaped.Kept(i))
                run = Vector.empty
            }
        }
        for (i <- 0 until units)
        {
            if (keep(i)) { flush(); out += Shaped.Kept(i) }
            else run :+= i
        }
        flush()
        out.result()
    }

    /**
     *  Builds the marker text for a run of dropped units.
     *
     *  @param run      Indices of the dropped units.
     *  @param noun     Unit noun rendered in the marker: lines, rows, sections, elements.
     *  @param summary  Field facts computed from exactly these units, or `""`.
     *  @return         Marker text, e.g. `… 12 rows elided: status: paid×10 …`.
     */
    def markerText(run: Vector[Int], noun: String, summary: String): String =
    {
        if (summary.isEmpty) s"… ${run.size} $noun elided …"
        else s"… ${run.size} $noun elided: $summary …"
    }

    /**
     *  Computes verified facts about exactly the dropped units of one run.
     *
     *  Facts, in keep-priority order (shed from the tail when over budget):
     *  a varying field enumerable over ≤ [[MaxEnumValues]] short values as
     *  `name: a×4 b×2`; a field constant across every unit as `name=value`;
     *  an all-numeric field as `name=min..max` in original string form; a
     *  high-cardinality field as `name: N distinct, lo..hi` (membership, not
     *  contiguity). Enumerations where every bucket holds one unit are
     *  identifier lists, not classes, and get the coverage entry instead.
     *  Secret-named fields and free-text values are never summarised.
     *
     *  @param units        Per-unit `name -> value` field pairs, in unit order.
     *  @param elidedBytes  Byte size the run occupied, bounding the summary.
     *  @return             `", "`-joined facts, or `""` when nothing is provable.
     */
    def summarizeFields(units: Vector[Vector[(String, String)]], elidedBytes: Int): String =
    {
        if (units.size < 2 || units.exists(_.isEmpty)) return ""
        val budget = math.min(SummaryMaxBytes, math.max(SummaryMinBudget, elidedBytes / SummaryShareDenom))
        val names  = units.flatten.map(_._1).distinct
            .filter(n => n.length <= MaxNameBytes && SecretFieldRE.findFirstIn(n).isEmpty)

        val enums     = mutable.ArrayBuffer.empty[String]
        val constants = mutable.ArrayBuffer.empty[String]
        val ranges    = mutable.ArrayBuffer.empty[String]
        val coverage  = mutable.ArrayBuffer.empty[String]

        for (name <- names)
        {
            val values: Vector[Option[String]] = units.map(u => u.collectFirst {
                case (k, v) if k == name => v
            })
            val present = values.flatten
            if (present.nonEmpty && present.forall(_.length <= MaxValueBytes))
            {
                val distinct = present.distinct
                if (values.forall(_.isDefined) && distinct.size == 1)
                {
                    constants += s"$name=${distinct.head}"
                }
                else if (values.forall(_.isDefined) &&
                         present.forall(v => finiteNumber(v).isDefined))
                {
                    val nums = present.map(v => finiteNumber(v).get)
                    val lo   = present(nums.indexOf(nums.min))
                    val hi   = present(nums.indexOf(nums.max))
                    ranges += s"$name=$lo..$hi"
                }
                else
                {
                    val buckets = values.groupBy(identity).view.mapValues(_.size).toMap
                    if (buckets.size <= MaxEnumValues &&
                        present.forall(_.length <= MaxEnumValueBytes) &&
                        !(buckets.size > 1 && buckets.values.forall(_ == 1)))
                    {
                        enums += s"$name: " + values.distinct.map {
                            case Some(v) => s"$v×${buckets(Some(v))}"
                            case None    => s"absent×${buckets(None)}"
                        }.mkString(" ")
                    }
                    else if (distinct.size >= 2)
                    {
                        coverage += s"$name: ${distinct.size} distinct, ${distinct.min}..${distinct.max}"
                    }
                }
            }
        }

        val entries = enums ++ constants ++ ranges ++ coverage
        var joined  = entries.mkString(", ")
        while (joined.length > budget && entries.nonEmpty)
        {
            entries.remove(entries.size - 1)  // shed whole entries, lowest priority last
            joined = entries.mkString(", ")
        }
        if (joined.length > budget) "" else joined
    }

    /**
     *  Extracts `name=value` and `name: value` pairs from a free-text unit for
     *  [[summarizeFields]].
     *
     *  @param unit  The unit text.
     *  @return      Up to [[MaxFieldsPerUnit]] pairs in order of appearance.
     */
    def fieldsOfLine(unit: String): Vector[(String, String)] =
        FieldRE.findAllMatchIn(unit).take(MaxFieldsPerUnit)
            .map(m => m.group(1) -> m.group(2)).toVector

    /**
     *  Parses a value as a finite number; NaN and infinities are not numbers —
     *  a NaN parses cleanly yet compares false against everything, latching
     *  extrema it does not satisfy.
     *
     *  @param s  Candidate value string.
     *  @return   The value when finite, `None` otherwise.
     */
    def finiteNumber(s: String): Option[Double] =
        s.toDoubleOption.filter(_.isFinite)

    /** Whether most line breaks in `text` are CRLF. */
    def majorityCRLF(text: String): Boolean =
    {
        val lf = text.count(_ == '\n')
        lf > 0 && text.count(_ == '\r') * 2 > lf
    }

    /**
     *  Splits on LF, leaving each line's own CR attached so rejoining
     *  reproduces every ending byte-for-byte.
     *
     *  @param text  Input text.
     *  @return      Lines without their LF, and whether the input ended in a line break.
     */
    def splitLines(text: String): (Vector[String], Boolean) =
    {
        val trailing = text.endsWith("\n")
        val body     = if (trailing) text.dropRight(1) else text
        (body.split("\n", -1).toVector, trailing)
    }

    // ── Vocabulary profiles ─────────────────────────────────────────────────

    private case class Profile(vocab: Set[String], masked: Boolean)

    /**
     *  Reduces a unit to the vocabulary it is judged by. Digit runs mask to
     *  `#` only when the unit carries enough words to stay distinguishable —
     *  a purely numeric unit keeps its digits because the numbers ARE the
     *  content there.
     *  @param unit  The unit text.
     *  @return      The unit's vocabulary profile.
     */
    private def vocabulary(unit: String): Profile =
    {
        val (masked, words) = tokenize(unit, maskDigits = true)
        if (words >= 1) Profile(masked, masked = true)
        else Profile(tokenize(unit, maskDigits = false)._1, masked = false)
    }

    /**
     *  Splits a unit into word/number tokens, optionally collapsing digit
     *  runs to a single `#`.
     *  @param unit        The unit text.
     *  @param maskDigits  Whether to mask digit runs.
     *  @return            The token set and the count of letter-bearing tokens.
     */
    private def tokenize(unit: String, maskDigits: Boolean): (Set[String], Int) =
    {
        val vocab     = mutable.LinkedHashSet.empty[String]
        var words     = 0
        val token     = new StringBuilder
        var hasLetter = false
        var inDigits  = false
        def flush(): Unit =
        {
            if (token.nonEmpty)
            {
                val key = token.result()
                if (!vocab.contains(key))
                {
                    vocab += key
                    if (hasLetter) words += 1
                }
                token.clear()
                hasLetter = false
                inDigits = false
            }
        }
        val chars = unit.toLowerCase
        var i     = 0
        while (i < chars.length)
        {
            val ch = chars(i)
            if (ch.isDigit)
            {
                if (maskDigits)
                {
                    if (!inDigits) { token.append('#'); inDigits = true }
                }
                else token.append(ch)
            }
            else if (ch.isLetter || ch == '_')
            {
                token.append(ch)
                hasLetter = true
                inDigits = false
            }
            else flush()
            if (vocab.size >= MaxUnitTokens) return (vocab.toSet, words)
            i += 1
        }
        flush()
        (vocab.toSet, words)
    }

    /**
     *  Whether a dropped unit's vocabulary is [[ContainmentRatio]]-covered by any
     *  surviving resemblance class. Empty units carry nothing to lose.
     *  @param dropped  The dropped unit's profile.
     *  @param classes  Profiles of units already kept or promoted.
     *  @return         `true` when a kept unit represents this one.
     */
    private def representedBy(dropped: Profile, classes: Iterable[Profile]): Boolean =
    {
        if (dropped.vocab.isEmpty) return true
        val need = math.ceil(ContainmentRatio * dropped.vocab.size).toInt
        classes.exists { c =>
            c.masked == dropped.masked && dropped.vocab.count(c.vocab.contains) >= need
        }
    }
}
