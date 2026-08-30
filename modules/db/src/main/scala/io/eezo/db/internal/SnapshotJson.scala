package io.eezo.db.internal

import io.eezo.db.SchemaError
import io.eezo.db.schema.{ColumnSnap, IndexSnap, SchemaSnap, TableSnap}

// Hand-rolled, and one more thing to be correct about for no gain over a library. BACKLOG §30.
object SnapshotJson {
  def parse(s: String): SchemaSnap = {
    val p = new P(s)
    p.ws()
    val root                      = p.value()
    def obj(j: J): Map[String, J] = j match {
      case J.O(fs) => fs.toMap
      case _       => throw new SchemaError(s"expected object in schema.json")
    }
    def arr(j: J): List[J] = j match {
      case J.A(xs) => xs
      case _       => throw new SchemaError("expected array in schema.json")
    }
    def str(j: J): String = j match {
      case J.S(v) => v
      case _      => throw new SchemaError("expected string in schema.json")
    }
    def bool(j: J): Boolean = j match {
      case J.B(v) => v
      case _      => throw new SchemaError("expected boolean in schema.json")
    }

    SchemaSnap(arr(obj(root)("tables")).map { t =>
      val to = obj(t)
      TableSnap(
        str(to("name")),
        arr(to("columns")).map { c =>
          val co = obj(c)
          ColumnSnap(
            str(co("name")),
            str(co("type")),
            bool(co("nullable")),
            bool(co("primaryKey")),
            arr(co("checks")).map(str),
            co("references") match { case J.Nul => None; case x => Some(str(x)) }
          )
        },
        arr(to("indexes")).map { i =>
          val io_ = obj(i)
          IndexSnap(str(io_("name")), arr(io_("columns")).map(str), bool(io_("unique")))
        }
      )
    })
  }

  private class P(s: String) {
    var i          = 0
    def ws(): Unit = while (i < s.length && s(i).isWhitespace) i += 1
    def value(): J = {
      ws()
      s(i) match {
        case '{' => obj()
        case '[' => arr()
        case '"' => J.S(string())
        case 't' => i += 4; J.B(true)
        case 'f' => i += 5; J.B(false)
        case 'n' => i += 4; J.Nul
        case _   => num()
      }
    }
    def obj(): J = {
      i += 1; ws()
      val fs = List.newBuilder[(String, J)]
      if (s(i) == '}') { i += 1; return J.O(Nil) }
      while (true) {
        ws()
        val k = string(); ws(); i += 1 // colon
        fs += (k -> value()); ws()
        if (s(i) == ',') i += 1 else { i += 1; return J.O(fs.result()) }
      }
      J.Nul
    }
    def arr(): J = {
      i += 1; ws()
      val xs = List.newBuilder[J]
      if (s(i) == ']') { i += 1; return J.A(Nil) }
      while (true) {
        xs += value(); ws()
        if (s(i) == ',') i += 1 else { i += 1; return J.A(xs.result()) }
      }
      J.Nul
    }
    def string(): String = {
      i += 1
      val b = new StringBuilder
      while (s(i) != '"') {
        if (s(i) == '\\') {
          i += 1
          s(i) match {
            case 'n' => b += '\n'; case 't' => b += '\t'; case 'r' => b += '\r'
            case 'u' => b += Integer.parseInt(s.substring(i + 1, i + 5), 16).toChar; i += 4
            case c   => b += c
          }
        } else b += s(i)
        i += 1
      }
      i += 1
      b.toString
    }
    def num(): J = {
      val st = i
      while (i < s.length && (s(i).isDigit || s(i) == '-')) i += 1
      J.N(s.substring(st, i).toLong)
    }
  }
}
