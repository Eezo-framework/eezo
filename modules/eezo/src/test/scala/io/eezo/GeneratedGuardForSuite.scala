package io.eezo

/** The guard lookup the sbt plugin writes into every application's route table, compiled for real.
  *
  * `RouteGeneratorSuite` in `modules/sbt-plugin` can only string match the generated source. A
  * change that stops the strict message from folding to a constant, such as computing `name` from
  * something the compiler cannot see through at the call site, would pass every one of those
  * assertions while turning the application side failure into "A literal string is expected as an
  * argument to compiletime.error", a message that points at generated code the user cannot edit and
  * never names the model. Nothing but a compiler can see that, and `compileErrors` takes a literal
  * source, so the helpers below are copied from `RouteGenerator` rather than produced by calling
  * it.
  *
  * The copy is not left to trust. `RouteGeneratorSuite` reads this very file and fails unless it
  * contains, character for character, the three helper bodies the generator emits, so a helper that
  * changes without this file changing is a red build in the plugin's own suite. That is the whole
  * mechanism: the plugin is cross built for sbt 1 on Scala 2.12 (ADR 0002), so `modules/eezo` can
  * never depend on it and call `render` directly, and one file read is smaller than publishing a
  * shared artifact for three helper strings.
  *
  * This lives in the umbrella rather than in `modules/sbt-plugin` itself because the helpers only
  * name `io.eezo.http`, and `sbt-plugin` never depends on `http`. `PasswordStorageSuite` sits
  * beside this test for the same reason: the umbrella is the one place a macro assertion about
  * generated application code can compile.
  *
  * `// format: off` around each copy is what keeps the pin honest. scalafmt formats this file and
  * does not format the file the generator writes, so an alignment scalafmt prefers here would be a
  * drift the plugin's suite then reports against a generator that never changed.
  */
class GeneratedGuardForSuite extends munit.FunSuite {

  test("the strict helper names the offending type when nothing guards it") {
    val failure =
      compileErrors("""io.eezo.StrictTable.forRoute[io.eezo.Unguarded]("app.Unguarded")""")
    assert(clue(failure).contains("no Guarded given for"), failure)
    assert(clue(failure).contains("app.Unguarded"), failure)
  }

  test("the strict helper hands back the declaration a page wrote") {
    val guarded = StrictTable.forRoute[DeclaredPage.type]("app.DeclaredPage")
    assertEquals(guarded.actions, Set[io.eezo.http.Action](io.eezo.http.Action.Index))
    assertEquals(guarded.carries, Seq(DeclaredPage.login))
  }

  test("a model that mounts nothing is not asked to declare anything") {
    // `examples/blog`'s `User` derives `Table` alone, so it has no `Resource` and mounts no route.
    val guarded = StrictTable.forModel[Unguarded]("models.Unguarded")
    assertEquals(guarded.actions, Set.empty[io.eezo.http.Action])
    assertEquals(guarded.carries, Seq.empty[io.eezo.http.Route])
  }

  test("the lenient helper is public when nothing guards it, so an app with no users compiles") {
    val guarded = PublicTable.forRoute[Unguarded]
    assertEquals(guarded.actions, Set.empty[io.eezo.http.Action])
    assertEquals(guarded.carries, Seq.empty[io.eezo.http.Route])
  }

  /** An `Owned` is a `Guarded`, so the guard lookup finds it and a model declares one line rather
    * than two that have to agree. The arm it matches is the same one every other declaration
    * matches, which is the property worth pinning: an ownership declaration that stopped being seen
    * there would mount unguarded routes and say nothing.
    */
  test("a model that declares only an Owned is what the guard lookup hands back") {
    Seq(StrictTable.forRoute[OwnedRow]("models.OwnedRow"), PublicTable.forRoute[OwnedRow]).foreach {
      guarded =>
        assertEquals(guarded.actions, io.eezo.http.Action.values.toSet)
        assertEquals(guarded.carries, Seq(DeclaredPage.login))
        assert(guarded.isInstanceOf[io.eezo.http.Owned[?, ?]], guarded)
    }
  }

  test("a model that declares nothing about ownership gets a store that cannot narrow") {
    assert(!PublicStores.forModel[Unguarded].isInstanceOf[io.eezo.http.Scoped[?]])
    assert(!DbStores.forModel[Unguarded].isInstanceOf[io.eezo.http.Scoped[?]])
  }

  /** The one thing only a compiler can answer about the owned arm: whether it resolves at all.
    *
    * The arm reaches `o.ownerOf` through an `Owned[A, ?]`, and hands it to a factory that wants a
    * `Column` for the owner's type, which is an `Id[U]` whose `U` is named nowhere in the emitted
    * file. Every string assertion in `RouteGeneratorSuite` passes whether or not that resolves.
    */
  test("a model that declares an Owned gets a store that narrows, with or without a database") {
    Seq(PublicStores.forModel[OwnedRow], DbStores.forModel[OwnedRow]).foreach { store =>
      val scoped = store match {
        case s: io.eezo.http.Scoped[?] => s.asInstanceOf[io.eezo.http.Scoped[OwnedRow]]
        case other => fail(s"an owned model landed on an unscoped store: $other")
      }
      val key = io.eezo.core.Id.gen[OwnedRow]()
      scoped.insert(key, OwnedRow(key, OwnedRow.ada, "hers"))
      assertEquals(scoped.all().size, 1)
      assertEquals(scoped.by(OwnedRow.ada).all().size, 1)
      assertEquals(scoped.by(io.eezo.core.Id.gen[Owner]()).all().size, 0)
    }
  }

  /** `OwnedRow` derives no `Table`, so the assertion above never runs the db arm: both
    * `PublicStores.forModel` and `DbStores.forModel` fall through to `InMemoryStore.scoped` for it,
    * and `io.eezo.db.JdbcStore.owned`, the `Column[Id[Owner]]` resolution and the column lookup by
    * name are compiled but never executed anywhere in the suite. A model that derives a `Table` as
    * well as declaring an `Owned` is what makes the db arm run: a wrong `OwnerOf` name would throw
    * `IllegalStateException` right here, at store construction, rather than only when the blog
    * boots in a browser.
    */
  test("a model with a Table and an Owned lands on JdbcStore.owned, not the in-memory fallback") {
    DbStores.forModel[OwnedTableRow] match {
      case _: io.eezo.http.Scoped[?] => ()
      case other => fail(s"an owned model with a Table landed on an unscoped store: $other")
    }
  }

  test("the stamp one guard hands every declaration it makes is composed once, not once a row") {
    // What `distinct` is for, and the reason it is worth having: the function is a `val` on the
    // guard, so twenty guarded things hand the table the same instance and an upgrade reads the
    // session once rather than twenty times.
    var reads                                               = 0
    val stamp: io.eezo.http.Request => io.eezo.http.Request = request => {
      reads += 1
      request.copy(currentUser = Some("ann"))
    }
    val named = NamingTable.of(Seq.fill(20)(stamp))
    assertEquals(named(GeneratedGuardForSuite.anyRequest).currentUser, Some("ann"))
    assertEquals(reads, 1)
  }

  test("a table whose declarations name nobody hands the request back exactly as it came") {
    val request = GeneratedGuardForSuite.anyRequest
    val named   = NamingTable.of(
      Seq(
        io.eezo.http.Guarded.public[Unguarded].identify,
        io.eezo.http.Guarded.public[DeclaredPage.type].identify
      )
    )
    assertEquals(named(request), request)
  }

  test("the lenient helper still hands back a declaration that was written") {
    // The defect this pins: an application on the umbrella, or one reaching auth through a
    // `dependsOn` module, declares no `eezo-auth` of its own. Its table used to mount every route
    // bare, so `given Guarded[Post] = User.guard.required` beside the model was read by nobody and
    // the admin pages were public with nothing anywhere to say so.
    val guarded = PublicTable.forRoute[DeclaredPage.type]
    assertEquals(guarded.actions, Set[io.eezo.http.Action](io.eezo.http.Action.Index))
    assertEquals(guarded.carries, Seq(DeclaredPage.login))
  }
}

object GeneratedGuardForSuite {

  /** Any request at all, since what is under test is what the composed function does to one. */
  def anyRequest: io.eezo.http.Request =
    io.eezo.http.Request(
      io.eezo.http.Method.GET,
      "/",
      Map.empty,
      Map.empty,
      Array.emptyByteArray,
      Map.empty
    )
}

/** A type with no `Guarded` and no `Resource` beside it: the silence both readings answer. */
class Unguarded

/** A page that declares a guard in its own companion, the way `examples/blog`'s `Index` does.
  *
  * The declaration is not `Guarded.public`, so a table that read it and a table that ignored it
  * cannot be mistaken for one another.
  */
object DeclaredPage {

  val login: io.eezo.http.Route =
    io.eezo.http.Route.Http(
      io.eezo.http.Method.GET,
      io.eezo.http.PathPattern.parse("/login"),
      _ => throw new UnsupportedOperationException("never dispatched")
    )

  given io.eezo.http.Guarded[DeclaredPage.type] =
    io.eezo.http.Guarded(Set[io.eezo.http.Action](io.eezo.http.Action.Index), identity, Seq(login))
}

/** The helpers `RouteGenerator` emits for a build that declares `eezo-auth`, verbatim.
  *
  * Everything between the `format: off` markers is the generator's text and is edited only by
  * copying it again. The two forwarders after it are this suite's own: the generator writes these
  * helpers `private`, so a test outside the generated object reaches them through a call of its
  * own.
  */
object StrictTable {

  // format: off
  /** Who may reach this route. Every mounted route has to say, because this application's
    * build declares eezo-auth and so the application has a way of signing in.
    */
  private inline def guardFor[A](inline name: String): io.eezo.http.Guarded[A] =
    scala.compiletime.summonFrom {
      case g: io.eezo.http.Guarded[A] => g
      case _                          =>
        scala.compiletime.error(
          "no Guarded given for " + name + ", and this application's build declares " +
            "eezo-auth, so every mounted route has to say who may reach it. In its " +
            "companion, one of:\n" +
            "  given io.eezo.http.Guarded[T] = <yourGuard>.required\n" +
            "  given io.eezo.http.Guarded[T] = io.eezo.http.Guarded.public"
        )
    }

  /** Who may reach a derived model's routes. A model with no `Resource` mounts nothing, so it
    * is not asked to declare anything.
    */
  private inline def guardForModel[A](inline name: String): io.eezo.http.Guarded[A] =
    scala.compiletime.summonFrom {
      case _: io.eezo.http.Resource[A] => guardFor[A](name)
      case _                           => io.eezo.http.Guarded.public[A]
    }
  // format: on

  inline def forRoute[A](inline name: String): io.eezo.http.Guarded[A] = guardFor[A](name)

  inline def forModel[A](inline name: String): io.eezo.http.Guarded[A] = guardForModel[A](name)
}

/** The one naming function the generator writes beside the rows, verbatim.
  *
  * Compiled here for the reason the guard lookup is: the plugin's suite can string match the text
  * and nothing more, and what has to hold is that the fold types, that `distinct` collapses the
  * function one guard hands every declaration it makes, and that a table of declarations naming
  * nobody hands a request straight back.
  */
object NamingTable {

  // format: off
  /** Who the table says is behind a request it serves, composed out of what every mounted
    * declaration names with. It is run on a socket upgrade, which is the one place a route
    * nobody guarded has no wrapper of its own to carry the answer.
    */
  private def identifying(
      named: Seq[io.eezo.http.Request => io.eezo.http.Request]
  ): io.eezo.http.Request => io.eezo.http.Request =
    named.distinct
      .foldLeft(identity[io.eezo.http.Request])((first, next) => first.andThen(next))
  // format: on

  def of(
      named: Seq[io.eezo.http.Request => io.eezo.http.Request]
  ): io.eezo.http.Request => io.eezo.http.Request = identifying(named)
}

/** The helper `RouteGenerator` emits for a build that declares no `eezo-auth`, verbatim.
  *
  * There is no model helper here, because sparing a model from an error there is no error to raise
  * would be a second spelling of this one.
  */
object PublicTable {

  // format: off
  /** Who may reach this route. A `given Guarded` beside the model or the page is what the
    * table mounts it behind; this application's build declares no eezo-auth, so saying
    * nothing means anyone may reach it.
    */
  private inline def guardFor[A]: io.eezo.http.Guarded[A] =
    scala.compiletime.summonFrom {
      case g: io.eezo.http.Guarded[A] => g
      case _                          => io.eezo.http.Guarded.public[A]
    }
  // format: on

  inline def forRoute[A]: io.eezo.http.Guarded[A] = guardFor[A]
}

/** A user model, present only as the thing an owner key points at. */
class Owner

/** A model that declares ownership, so the emitted store lookup's first arm has something to find.
  */
case class OwnedRow(id: io.eezo.core.Id[OwnedRow], author: io.eezo.core.Id[Owner], text: String)

object OwnedRow {

  val ada: io.eezo.core.Id[Owner] = io.eezo.core.Id.gen[Owner]()

  given io.eezo.http.Owned[OwnedRow, Owner] =
    io.eezo.http.Owned(
      io.eezo.core.OwnerOf("author", _.author),
      _ => Some(ada),
      io.eezo.http.Action.values.toSet,
      io.eezo.http.Guarded(io.eezo.http.Action.values.toSet, identity, Seq(DeclaredPage.login))
    )
}

/** A model that both derives a `Table` and declares ownership, so the db arm of the store lookup
  * resolves a real owner column instead of falling through to the in-memory arm every other model
  * in this file reaches. `OwnedRow`, above, deliberately derives no `Table`, so it alone cannot pin
  * that the db arm even runs.
  */
case class OwnedTableRow(
    id: io.eezo.core.Id[OwnedTableRow],
    author: io.eezo.core.Id[Owner],
    text: String
) derives io.eezo.db.Table

object OwnedTableRow {

  given io.eezo.http.Owned[OwnedTableRow, Owner] =
    io.eezo.http.Owned(
      io.eezo.core.OwnerOf("author", _.author),
      _ => Some(OwnedRow.ada),
      io.eezo.http.Action.values.toSet,
      io.eezo.http.Guarded(io.eezo.http.Action.values.toSet, identity, Seq(DeclaredPage.login))
    )
}

/** The store lookup `RouteGenerator` emits for a build with no database, verbatim.
  *
  * Compiled here for the reason the guard helpers are: `RouteGeneratorSuite` can only match the
  * emitted text, and the one question that matters about the owned arm, whether it resolves without
  * ever naming the owner's type, is a question only a compiler answers.
  */
object PublicStores {

  // format: off
  /** The store each derived model gets. Nothing on this application's classpath persists a
    * model, so a model that declares an `Owned` gets one that can narrow to the user who
    * owns a row, and every model lives in memory for as long as the process does.
    */
  private inline def storeFor[A]: io.eezo.core.Store[A] =
    scala.compiletime.summonFrom {
      case o: io.eezo.http.Owned[A, ?] => io.eezo.http.InMemoryStore.scoped(o.ownerOf)
      case _                           => io.eezo.http.InMemoryStore[A]()
    }
  // format: on

  inline def forModel[A]: io.eezo.core.Store[A] = storeFor[A]
}

/** The store lookup `RouteGenerator` emits for a build that has `eezo-db`, verbatim.
  *
  * The umbrella carries a database, so both arms of both inner lookups resolve here. Neither of the
  * models this suite asks about derives a `Table`, which is the per-model decision the generator
  * could not make and the compiler does: an application with a database still mounts its
  * `Table`-less models in memory, owned or not.
  */
object DbStores {

  // format: off
  /** The store each derived model gets, picked by the compiler: a model that declares an
    * `Owned` gets one that can narrow to the user who owns a row, a model whose companion
    * carries a `Table` is persisted through it, and every other one lives in memory for as
    * long as the process does.
    */
  private inline def storeFor[A]: io.eezo.core.Store[A] =
    scala.compiletime.summonFrom {
      case o: io.eezo.http.Owned[A, ?] =>
        scala.compiletime.summonFrom {
          case t: io.eezo.db.Table[A] =>
            io.eezo.http.Scoped(
              io.eezo.db.JdbcStore[A]()(using t),
              io.eezo.db.JdbcStore.owned(o.ownerOf)(using t, summon)
            )
          case _ => io.eezo.http.InMemoryStore.scoped(o.ownerOf)
        }
      case t: io.eezo.db.Table[A] => io.eezo.db.JdbcStore[A]()(using t)
      case _                      => io.eezo.http.InMemoryStore[A]()
    }
  // format: on

  inline def forModel[A]: io.eezo.core.Store[A] = storeFor[A]
}
