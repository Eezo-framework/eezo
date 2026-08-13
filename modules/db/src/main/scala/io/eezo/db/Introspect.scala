package io.eezo.db

import java.sql.Connection

object Introspect {

  private def pgTypeOf(
      dataType: String,
      udt: String,
      maxLen: Option[Int],
      precision: Option[Int],
      scale: Option[Int]
  ): String =
    dataType match {
      case "text"                     => "text"
      case "character varying"        => maxLen.map(n => s"varchar($n)").getOrElse("text")
      case "integer"                  => "integer"
      case "bigint"                   => "bigint"
      case "boolean"                  => "boolean"
      case "uuid"                     => "uuid"
      case "date"                     => "date"
      case "timestamp with time zone" => "timestamptz"
      case "bytea"                    => "bytea"
      case "jsonb"                    => "jsonb"
      case "numeric"                  =>
        (precision, scale) match {
          case (Some(p), Some(s)) => s"numeric($p,$s)"
          case _                  => "numeric"
        }
      case other => other
    }

  def snapshot(c: Connection, schema: String = "public"): SchemaSnap = {
    val tableNames = query(
      c,
      """select table_name from information_schema.tables
         where table_schema = ? and table_type = 'BASE TABLE'
         order by table_name""",
      schema
    )(rs => rs.getString(1))

    val tables = tableNames.filterNot(_ == "eezo_migrations").map { t =>
      TableSnap(t, columns(c, schema, t), indexes(c, schema, t))
    }
    SchemaSnap(tables)
  }

  private def columns(c: Connection, schema: String, table: String): List[ColumnSnap] = {
    val pks = query(
      c,
      """select a.attname
         from pg_index i
         join pg_attribute a on a.attrelid = i.indrelid and a.attnum = any(i.indkey)
         where i.indrelid = (quote_ident(?) || '.' || quote_ident(?))::regclass
           and i.indisprimary""",
      schema,
      table
    )(_.getString(1)).toSet

    val fks = query(
      c,
      """select kcu.column_name, ccu.table_name
         from information_schema.table_constraints tc
         join information_schema.key_column_usage kcu
           on kcu.constraint_name = tc.constraint_name and kcu.table_schema = tc.table_schema
         join information_schema.constraint_column_usage ccu
           on ccu.constraint_name = tc.constraint_name and ccu.table_schema = tc.table_schema
         where tc.constraint_type = 'FOREIGN KEY'
           and tc.table_schema = ? and tc.table_name = ?""",
      schema,
      table
    )(rs => rs.getString(1) -> rs.getString(2)).toMap

    // Column-level CHECKs, normalised to the shape our Check renderer emits.
    val checks = query(
      c,
      """select a.attname, pg_get_constraintdef(con.oid)
         from pg_constraint con
         join pg_attribute a on a.attrelid = con.conrelid and a.attnum = any(con.conkey)
         where con.contype = 'c'
           and con.conrelid = (quote_ident(?) || '.' || quote_ident(?))::regclass
           and array_length(con.conkey, 1) = 1""",
      schema,
      table
    )(rs => rs.getString(1) -> rs.getString(2))
      .groupMap(_._1)(p => normaliseCheck(p._2))

    query(
      c,
      """select column_name, data_type, udt_name, is_nullable,
                character_maximum_length, numeric_precision, numeric_scale
         from information_schema.columns
         where table_schema = ? and table_name = ?
         order by column_name""",
      schema,
      table
    ) { rs =>
      val name                        = rs.getString(1)
      def optInt(i: Int): Option[Int] = {
        val v = rs.getInt(i); if (rs.wasNull()) None else Some(v)
      }
      ColumnSnap(
        name = name,
        pgType = pgTypeOf(rs.getString(2), rs.getString(3), optInt(5), optInt(6), optInt(7)),
        nullable = rs.getString(4) == "YES",
        primaryKey = pks.contains(name),
        checks = checks.getOrElse(name, Nil).sorted,
        references = fks.get(name)
      )
    }
  }

  private def indexes(c: Connection, schema: String, table: String): List[IndexSnap] = {
    val raw = query(
      c,
      """select i.relname, ix.indisunique, ix.indisprimary,
                array_to_string(array(
                  select pg_get_indexdef(ix.indexrelid, k + 1, true)
                  from generate_subscripts(ix.indkey, 1) as k
                  order by k
                ), ',') as cols
         from pg_index ix
         join pg_class i on i.oid = ix.indexrelid
         where ix.indrelid = (quote_ident(?) || '.' || quote_ident(?))::regclass""",
      schema,
      table
    )(rs => (rs.getString(1), rs.getBoolean(2), rs.getBoolean(3), rs.getString(4)))

    raw
      .collect { case (name, unique, false, cols) =>
        IndexSnap(
          name,
          cols.split(",").toList.map(_.trim.stripPrefix("\"").stripSuffix("\"")),
          unique
        )
      }
      .sortBy(_.name)
  }

  /** `CHECK ((length(title) <= 200))` → `length("title") <= 200` */
  private def normaliseCheck(def_ : String): String = {
    var s = def_.trim
    if (s.startsWith("CHECK")) s = s.drop(5).trim
    while (s.startsWith("(") && s.endsWith(")") && balanced(s.drop(1).dropRight(1)))
      s = s.drop(1).dropRight(1).trim
    s
  }

  private def balanced(s: String): Boolean =
    s.foldLeft(0) { (d, ch) =>
      if (d < 0) d
      else if (ch == '(') d + 1
      else if (ch == ')') d - 1
      else d
    } == 0

  private def query[A](c: Connection, sql: String, args: String*)(
      f: java.sql.ResultSet => A
  ): List[A] = {
    val ps = c.prepareStatement(sql)
    try {
      args.zipWithIndex.foreach { case (a, i) => ps.setString(i + 1, a) }
      val rs = ps.executeQuery()
      try Iterator.continually(rs).takeWhile(_.next()).map(f).toList
      finally rs.close()
    } finally ps.close()
  }
}
