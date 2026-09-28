package io.eezo.db.internal

import io.eezo.core.internal.Json
import io.eezo.db.SchemaError
import io.eezo.db.schema.{ColumnSnap, IndexSnap, SchemaSnap, TableSnap}

// Hand rolled, and one more thing to be correct about for no gain over a library. BACKLOG §30.
private[db] object SnapshotJson {
  def parse(s: String): SchemaSnap = {
    val p = new P(s)
    p.ws()
    val root                            = p.value()
    def obj(j: Json): Map[String, Json] = j match {
      case Json.Obj(fs) => fs.toMap
      case _            => throw new SchemaError(s"expected object in schema.json")
    }
    def arr(j: Json): List[Json] = j match {
      case Json.Arr(xs) => xs
      case _            => throw new SchemaError("expected array in schema.json")
    }
    def str(j: Json): String = j match {
      case Json.Str(v) => v
      case _           => throw new SchemaError("expected string in schema.json")
    }
    def bool(j: Json): Boolean = j match {
      case Json.Bool(v) => v
      case _            => throw new SchemaError("expected boolean in schema.json")
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
            co("references") match { case Json.Null => None; case x => Some(str(x)) }
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
    var i             = 0
    def ws(): Unit    = while (i < s.length && s(i).isWhitespace) i += 1
    def value(): Json = {
      ws()
      s(i) match {
        case '{' => obj()
        case '[' => arr()
        case '"' => Json.Str(string())
        case 't' => i += 4; Json.Bool(true)
        case 'f' => i += 5; Json.Bool(false)
        case 'n' => i += 4; Json.Null
        case _   => num()
      }
    }
    def obj(): Json = {
      i += 1; ws()
      val fs = List.newBuilder[(String, Json)]
      if (s(i) == '}') { i += 1; return Json.Obj(Nil) }
      while (true) {
        ws()
        val k = string(); ws(); i += 1 // colon
        fs += (k -> value()); ws()
        if (s(i) == ',') i += 1 else { i += 1; return Json.Obj(fs.result()) }
      }
      Json.Null
    }
    def arr(): Json = {
      i += 1; ws()
      val xs = List.newBuilder[Json]
      if (s(i) == ']') { i += 1; return Json.Arr(Nil) }
      while (true) {
        xs += value(); ws()
        if (s(i) == ',') i += 1 else { i += 1; return Json.Arr(xs.result()) }
      }
      Json.Null
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
    def num(): Json = {
      val st = i
      while (i < s.length && (s(i).isDigit || s(i) == '-')) i += 1
      Json.Num(s.substring(st, i).toLong)
    }
  }
}
