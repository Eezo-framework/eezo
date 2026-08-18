package io.eezo.db

// TODO A DropCheck must precede an AlterType on the same column in real Postgres — currently it doesn't
object Differ {

  /** Changes to turn `from` into `to`. */
  def diff(from: SchemaSnap, to: SchemaSnap): List[Change] = {
    val fromT = from.tables.map(t => t.name -> t).toMap
    val toT   = to.tables.map(t => t.name -> t).toMap

    val created = to.tables.filterNot(t => fromT.contains(t.name))
    val dropped = from.tables.filterNot(t => toT.contains(t.name))
    val common  = to.tables.filter(t => fromT.contains(t.name))

    val createChanges = created.map(Change.CreateTable(_))

    // FKs on new tables are emitted separately so table order never matters.
    val newTableFks = created.flatMap { t =>
      t.columns.flatMap(c => c.references.map(r => Change.AddForeignKey(t.name, c.name, r)))
    }
    val newTableIdx = created.flatMap(t => t.indexes.map(Change.CreateIndex(t.name, _)))

    val alterChanges = common.flatMap(t => diffTable(fromT(t.name), t))

    val dropChanges = dropped.map(t => Change.DropTable(t.name))

    createChanges ++ newTableFks ++ alterChanges ++ newTableIdx ++ dropChanges
  }

  private def diffTable(from: TableSnap, to: TableSnap): List[Change] = {
    val fromC = from.columns.map(c => c.name -> c).toMap
    val toC   = to.columns.map(c => c.name -> c).toMap

    val added = to.columns
      .filterNot(c => fromC.contains(c.name))
      .map(Change.AddColumn(to.name, _))

    val addedFks = to.columns
      .filterNot(c => fromC.contains(c.name))
      .flatMap(c => c.references.map(r => Change.AddForeignKey(to.name, c.name, r)))

    val removed = from.columns
      .filterNot(c => toC.contains(c.name))
      .map(c => Change.DropColumn(to.name, c.name))

    val altered = to.columns
      .filter(c => fromC.contains(c.name))
      .flatMap(c => diffColumn(to.name, fromC(c.name), c))

    val fromI = from.indexes.map(i => i.name -> i).toMap
    val toI   = to.indexes.map(i => i.name -> i).toMap

    val idxDropped = from.indexes
      .filterNot(i => toI.get(i.name).contains(i))
      .map(i => Change.DropIndex(to.name, i.name))
    val idxCreated = to.indexes
      .filterNot(i => fromI.get(i.name).contains(i))
      .map(Change.CreateIndex(to.name, _))

    added ++ addedFks ++ altered ++ idxDropped ++ idxCreated ++ removed
  }

  private def diffColumn(table: String, from: ColumnSnap, to: ColumnSnap): List[Change] = {
    val typeChange =
      if (from.pgType != to.pgType) List(Change.AlterType(table, to.name, from.pgType, to.pgType))
      else Nil

    val nullChange =
      if (from.nullable != to.nullable) List(Change.SetNullable(table, to.name, to.nullable))
      else Nil

    val checksDropped = from.checks
      .filterNot(to.checks.contains)
      .map(Change.DropCheck(table, to.name, _))
    val checksAdded = to.checks
      .filterNot(from.checks.contains)
      .map(Change.AddCheck(table, to.name, _))

    val fkChange = (from.references, to.references) match {
      case (Some(_), None)              => List(Change.DropForeignKey(table, to.name))
      case (None, Some(t))              => List(Change.AddForeignKey(table, to.name, t))
      case (Some(a), Some(b)) if a != b =>
        List(Change.DropForeignKey(table, to.name), Change.AddForeignKey(table, to.name, b))
      case _ => Nil
    }

    typeChange ++ nullChange ++ checksDropped ++ checksAdded ++ fkChange
  }
}
