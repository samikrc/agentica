package agentica.shell.compression

import org.scalatest.funsuite.AnyFunSuite

/**
 *  Tests for [[JsonCompressor]]: array elision preserving structure and
 *  error subtrees, marker objects keeping output valid JSON, invariant
 *  summaries, number fidelity, and fail-closed pass-through.
 */
class JsonCompressorTest extends AnyFunSuite
{
    /** An orders array where every element is `{"id","status","total"}`. */
    private def orders(n: Int, statusAt: Map[Int, String] = Map.empty): String =
    {
        val items = (1 to n).map { i =>
            s"""{"id":${1000 + i},"status":"${statusAt.getOrElse(i, "paid")}","total":${100 + i}}"""
        }
        items.mkString("[", ",", "]")
    }

    test("long array collapses with a valid-JSON marker object") {
        val out = JsonCompressor.compress(orders(30)).get
        val v   = ujson.read(out) // must remain valid JSON
        assert(v.arr.size < 30, out)
        assert(out.contains("\"__agentica_elided__\""), out)
        assert(out.length < orders(30).length)
    }

    test("integral numbers keep their original form") {
        val out = JsonCompressor.compress(orders(30)).get
        assert(out.contains("\"id\":1001"), s"no .0 drift allowed: $out")
        assert(!out.contains("1001.0"), out)
    }

    test("error-state elements and error subtrees survive") {
        val input = orders(30, Map(15 -> "error"))
        val out   = JsonCompressor.compress(input).get
        assert(out.contains("\"status\":\"error\""), out)

        val nested = s"""{"ok":true,"error":{"trace":"a very long stack trace","code":42},"items":${orders(30)}}"""
        val nout = JsonCompressor.compress(nested).get
        assert(nout.contains("a very long stack trace"), "error subtree must stay verbatim")
    }

    test("invariant summary states facts about elided elements") {
        val out = JsonCompressor.compress(orders(30)).get
        assert(out.contains("\"__agentica_invariants__\""), out)
        assert(out.contains("status"), out)
    }

    test("malformed JSON fails closed") {
        assert(JsonCompressor.compress("{not json").isEmpty)
        assert(JsonCompressor.compress("{\"a\":1} trailing").isEmpty)
    }

    test("small arrays pass through untouched") {
        assert(JsonCompressor.compress(orders(5)).isEmpty)
    }

    test("arrays where nothing safe drops fail closed") {
        // Every element carries unique letter vocabulary → keepNonRedundant
        // promotes them all → no element elides → honest zero.
        val pool  = Vector("alpha","bravo","charlie","delta","echo","foxtrot","golf","hotel",
                           "india","juliet","kilo","lima","mike","november","oscar")
        val items = (0 until 15).map { i =>
            s"""{"key_${pool(i)}":"${pool((i + 3) % 15)}","other_${pool((i + 9) % 15)}":"${pool((i + 12) % 15)}"}"""
        }
        assert(JsonCompressor.compress(items.mkString("[", ",", "]")).isEmpty)
    }

    test("numeric extrema elements are kept even mid-array") {
        // Element 15 carries the maximum total and would otherwise elide.
        val items = (1 to 30).map { i =>
            s"""{"id":${1000 + i},"status":"paid","total":${if (i == 15) 9999 else 100 + i}}"""
        }
        val out = JsonCompressor.compress(items.mkString("[", ",", "]")).get
        assert(out.contains("9999"), s"extremum element must survive: $out")
    }

    test("already-compressed output is not re-compressed") {
        val once = JsonCompressor.compress(orders(30)).get
        assert(JsonCompressor.compress(once).isEmpty)
    }
}
