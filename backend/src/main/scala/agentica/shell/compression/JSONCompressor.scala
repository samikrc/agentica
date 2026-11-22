package agentica.shell.compression

import scala.collection.mutable
import scala.util.matching.Regex

/**
 *  Compresses JSON payloads by eliding elements of long arrays while keeping
 *  every object key, the overall structure, and error/message subtrees
 *  verbatim.
 *
 *  Array selection keeps the first and last few elements, elements carrying
 *  an error or failure state, elements holding the extrema of
 *  fully-covered numeric leaf paths, query-relevant elements, and a
 *  representative of every distinct element shape
 *  ([[Elision.keepNonRedundant]]). Dropped runs collapse into marker objects
 *  `{"__agentica_elided__": N, "__agentica_invariants__": "…"}` so the output
 *  stays valid JSON, with [[Elision.summarizeFields]] facts computed from the
 *  dropped elements' top-level scalar members.
 *
 *  Fail-closed: malformed JSON, no array actually collapsing, or output not
 *  smaller than the input passes the original through. Re-serialisation
 *  alone (whitespace, key order) never counts as a reduction.
 */
object JSONCompressor extends Compressor
{
    /** Arrays longer than this are eligible to collapse. */
    private val ArrayThreshold = 8

    /** Elements kept from the front and back of an array. */
    private val KeepFirst = 3
    private val KeepLast  = 2

    /** Elements a query-aware pass may additionally keep. */
    private val QueryLimit = 24

    /** Fully-covered numeric leaf paths considered for extrema keeps. */
    private val MaxExtremaPaths = 8

    /** Marker keys carrying the elided count and the run's invariant summary. */
    private val ElidedKey      = "__agentica_elided__"
    private val InvariantsKey  = "__agentica_invariants__"

    /** Object keys whose subtrees are preserved verbatim. */
    private val ErrorKeyRe: Regex =
        "(?i)^(error|errors|message|msg|stack|stacktrace|stack_trace|trace|traceback|exception|reason|detail|details|warning|warnings)$".r

    /** Critical keywords inside an element force-keep that element. */
    private val ErrorValueRe: Regex =
        "(?i)\\b(error|errors|exception|failed|failure|critical|fatal|crash|panic|abort|timeout|denied|rejected)\\b".r

    /** Content type reported in result metadata. */
    def contentType: String = "json"

    /**
     *  Compresses a JSON document by array elision.
     *  @inheritdoc Compressor.compress
     */
    def compress(text: String, query: String = ""): Option[String] =
    {
        val parsed =
            try Some(ujson.read(text))
            catch { case _: Exception => None }
        if (parsed.isEmpty) return None

        var collapsed = false
        def transform(v: ujson.Value, preserve: Boolean): ujson.Value =
        {
            if (preserve) v
            else v match
            {
                case o: ujson.Obj =>
                    val out = ujson.Obj()
                    for ((k, vv) <- o.obj) out.obj(k) = transform(vv, ErrorKeyRe.matches(k))
                    out
                case a: ujson.Arr =>
                    if (containsMarker(a)) a
                    else if (a.arr.size > ArrayThreshold) selectArray(a.arr.toVector, query, transform, () => collapsed = true)
                    else
                    {
                        val items = mutable.ArrayBuffer.empty[ujson.Value]
                        a.arr.foreach(e => items += transform(e, preserve = false))
                        ujson.Arr(items)
                    }
                case _ => v
            }
        }

        val shaped = transform(parsed.get, preserve = false)
        if (!collapsed) return None
        val out = renderJSON(shaped)
        if (out.length >= text.length) None else Some(out)
    }

    /**
     *  Collapses a long array to the elements carrying signal, emitting one
     *  marker object per dropped run so the output stays valid JSON.
     *
     *  @param arr          Elements of the array.
     *  @param query        Optional relevance query.
     *  @param transform    Recursive transform applied to kept elements.
     *  @param markDropped  Called when at least one element is actually elided.
     */
    private def selectArray(arr: Vector[ujson.Value], query: String,
                            transform: (ujson.Value, Boolean) => ujson.Value,
                            markDropped: () => Unit): ujson.Value =
    {
        val n    = arr.size
        val docs = arr.map(renderJSON)
        val keep = Array.fill(n)(false)
        for (i <- 0 until KeepFirst.min(n)) keep(i) = true
        for (i <- (n - KeepLast) until n) keep(i) = true
        for (i <- docs.indices if ErrorValueRe.findFirstIn(docs(i)).isDefined) keep(i) = true
        keepExtrema(arr, keep)
        Elision.keepQueryRelevant(keep, docs, query, QueryLimit)
        Elision.keepNonRedundant(docs, keep)

        val items = mutable.ArrayBuffer.empty[ujson.Value]
        Elision.shapedRuns(n, keep).foreach {
            case Elision.Shaped.Kept(i) =>
                items += transform(arr(i), false)
            case Elision.Shaped.Marker(run) =>
                val fields   = run.map(i => scalarFields(arr(i)))
                val summary  = Elision.summarizeFields(fields, run.map(i => docs(i).length + 1).sum)
                val marker   = ujson.Obj(ElidedKey -> ujson.Num(run.size.toDouble))
                if (summary.nonEmpty) marker.obj(InvariantsKey) = ujson.Str(summary)
                items += marker
                markDropped()
        }
        ujson.Arr(items)
    }

    /** True when any element already carries an elision marker key — the array is our own output. */
    private def containsMarker(a: ujson.Arr): Boolean =
        a.arr.exists {
            case o: ujson.Obj => o.obj.contains(ElidedKey)
            case _            => false
        }

    /** Top-level scalar members of an element as summary field pairs; nested values don't contribute. */
    private def scalarFields(v: ujson.Value): Vector[(String, String)] =
        v match
        {
            case o: ujson.Obj =>
                o.obj.iterator.collect {
                    case (k, s: ujson.Str)  => k -> s.value
                    case (k, ujson.Num(d))  => k -> (if (d.isWhole) d.toLong.toString else d.toString)
                    case (k, b: ujson.Bool) => k -> b.value.toString
                }.toVector
            case _ => Vector.empty
        }

    /**
     *  Keeps the elements holding the min and max of each numeric leaf path
     *  present in every element (capped at [[MaxExtremaPaths]] paths).
     */
    private def keepExtrema(arr: Vector[ujson.Value], keep: Array[Boolean]): Unit =
    {
        val leaves  = arr.map(e => { val m = mutable.Map.empty[String, Double]; numericLeaves(e, "", m); m.toMap })
        val covered = leaves.headOption.map(_.keySet).getOrElse(Set.empty)
            .filter(p => leaves.forall(_.contains(p)))
        for (p <- covered.toVector.sorted.take(MaxExtremaPaths))
        {
            val series = leaves.map(_(p))
            keep(series.indices.minBy(series)) = true
            keep(series.indices.maxBy(series)) = true
        }
    }

    /** Records numeric leaf values under dotted member paths; arrays inside elements are not descended. */
    private def numericLeaves(v: ujson.Value, prefix: String, out: mutable.Map[String, Double]): Unit =
        v match
        {
            case ujson.Num(d) if d.isFinite => out(prefix) = d
            case o: ujson.Obj =>
                for ((k, vv) <- o.obj) numericLeaves(vv, if (prefix.isEmpty) k else s"$prefix.$k", out)
            case _ => ()
        }

    /**
     *  Serialises a value compactly, rendering integral numbers without a
     *  decimal point so kept content stays as close to its input form as the
     *  parse allows.
     */
    private def renderJSON(v: ujson.Value): String =
    {
        val sb = new StringBuilder
        def go(x: ujson.Value): Unit = x match
        {
            case s: ujson.Str  => sb.append(s.render())
            case ujson.Num(d)  => if (d.isWhole && d.abs < 1e15) sb.append(d.toLong.toString) else sb.append(d.toString)
            case b: ujson.Bool => sb.append(b.value.toString)
            case ujson.Null    => sb.append("null")
            case a: ujson.Arr  =>
                sb.append('[')
                var first = true
                a.arr.foreach { e => if (!first) sb.append(','); go(e); first = false }
                sb.append(']')
            case o: ujson.Obj  =>
                sb.append('{')
                var first = true
                o.obj.foreach { case (k, vv) =>
                    if (!first) sb.append(',')
                    sb.append(ujson.Str(k).render()); sb.append(':'); go(vv); first = false
                }
                sb.append('}')
        }
        go(v)
        sb.result()
    }
}
