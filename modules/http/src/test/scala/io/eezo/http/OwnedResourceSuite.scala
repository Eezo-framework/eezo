package io.eezo.http

import io.eezo.core.{Id, OwnerOf, Store}

/** The blog's model: guarded everywhere, scoped everywhere but the two reading routes, so that two
  * users read each other's notes and edit only their own.
  */
case class Note(id: Id[Note], author: Id[Person], title: String, body: String)
    derives Form,
      Resource

/** A user model, present only as the thing a key points at. */
case class Person(id: Id[Person], name: String)

/** The other half of the split: scoped everywhere, `Show` included, so a row somebody else owns is
  * not there at all rather than refused.
  */
case class Basket(id: Id[Basket], author: Id[Person], label: String) derives Form, Resource

/** A third shape: `Show` itself is unmounted, so there is no route at all a plain `GET` could read
  * a row from, and a 403 that reveals a foreign row exists would be an oracle nothing else offers.
  */
case class Vault(id: Id[Vault], author: Id[Person], secret: String) derives Form, Resource

object Vault {
  given Actions[Vault] = Actions.except(Action.Show)
}

/** The derived seven over an owned model.
  *
  * The suite drives the routes the way a server does, through a request whose session already
  * carries a token, and reads the refusals back as statuses rather than as pages: the whole of the
  * mechanism is which store a handler reached for and what it answered when the row was not there.
  */
class OwnedResourceSuite extends munit.FunSuite with ResourceFixtures {

  private val ada   = Id.gen[Person]()
  private val grace = Id.gen[Person]()

  private val noteOwner   = OwnerOf[Note, Id[Person]]("author", _.author)
  private val basketOwner = OwnerOf[Basket, Id[Person]]("author", _.author)
  private val vaultOwner  = OwnerOf[Vault, Id[Person]]("author", _.author)

  /** A declaration as `auth` builds one: every route guarded, and the covered set the terminal call
    * of the chain produced. `through` is `identity` here, because who is signed in is the one thing
    * this suite states outright rather than reading out of a session.
    */
  private def notesOwnedBy(who: Id[Person]): Owned[Note, Person] =
    Owned[Note, Person](
      noteOwner,
      _ => who,
      Action.values.toSet -- Set(Action.Index, Action.Show),
      Guarded(Action.values.toSet, identity, Seq.empty)
    )

  private def basketsOwnedBy(who: Id[Person]): Owned[Basket, Person] =
    Owned[Basket, Person](
      basketOwner,
      _ => who,
      Action.values.toSet,
      Guarded(Action.values.toSet, identity, Seq.empty)
    )

  /** Only `Destroy` is covered: everyone signed in may edit a vault, only its author may delete
    * it. `Create` and `Update` are deliberately left uncovered, which is what the reassignment
    * defect needs to happen at all.
    */
  private def vaultsOwnedByOnlyDestroy(who: Id[Person]): Owned[Vault, Person] =
    Owned[Vault, Person](
      vaultOwner,
      _ => who,
      Set(Action.Destroy),
      Guarded(Action.values.toSet, identity, Seq.empty)
    )

  private def vaultsOwnedBy(who: Id[Person]): Owned[Vault, Person] =
    Owned[Vault, Person](
      vaultOwner,
      _ => who,
      Action.values.toSet -- Set(Action.Index, Action.Show),
      Guarded(Action.values.toSet, identity, Seq.empty)
    )

  private def notes(): Scoped[Note] = InMemoryStore.scoped(noteOwner)

  private def routes(store: Store[Note], who: Id[Person]): RouteTable =
    RouteTable(Resource[Note].routes(store, notesOwnedBy(who)))

  private def answer(table: RouteTable, request: Request): Response =
    table.dispatch(request)

  private def status(table: RouteTable, request: Request): Int =
    try answer(table, request).status
    catch {
      case NotFound(_)     => 404
      case Forbidden(_)    => 403
      case Unauthorized(_) => 401
    }

  private def note(key: Id[Note], owner: Id[Person], title: String): Note =
    Note(key, owner, title, "body")

  test("an Owned is accepted everywhere a Guarded is, and carries the guard's own routes") {
    val login = Route.derived(Method.GET, "/login", _ => Response.Ok(io.eezo.core.html.Html.empty))
    val declared = Owned[Note, Person](
      noteOwner,
      _ => ada,
      Set(Action.Create),
      Guarded(Action.values.toSet, identity, Seq(login))
    )

    val asGuarded: Guarded[Note] = declared
    assertEquals(asGuarded.carries, Seq(login))
    assertEquals(declared.covers, Set(Action.Create))
    assertEquals(declared.ownerOf.name, "author")
    assertEquals(declared.ownerOf.get(note(Id.gen(), grace, "t")), grace)
    assertEquals(Resource.routesOf[Note](notes(), declared).count(_ == login), 1)
  }

  test("the new and edit pages emit no input named for the owner field") {
    val store = notes()
    val key   = Id.gen[Note]()
    store.insert(key, note(key, ada, "Mine"))
    val table = routes(store, ada)

    val fresh   = markup(answer(table, request(Method.GET, "/notes/new")))
    val editing = markup(answer(table, request(Method.GET, s"/notes/${key.show}/edit")))

    Seq(fresh, editing).foreach { page =>
      assert(page.contains("""name="title""""), page)
      assert(page.contains("""name="body""""), page)
      assert(!page.contains("""name="author""""), page)
    }
  }

  test("an owner field never reaches an index heading or a show page either") {
    val store = notes()
    val key   = Id.gen[Note]()
    store.insert(key, note(key, ada, "Mine"))
    val table = routes(store, ada)

    val index = markup(answer(table, request(Method.GET, "/notes")))
    val one   = markup(answer(table, request(Method.GET, s"/notes/${key.show}")))

    assert(!index.contains("Author"), index)
    assert(!index.contains(ada.show), index)
    assert(!one.contains(ada.show), one)
    assert(one.contains("Mine"), one)
  }

  test("create fills the owner from the request, whatever the body claims") {
    val store = notes()
    val table = routes(store, ada)

    val answered = answer(
      table,
      request(
        Method.POST,
        "/notes",
        "title"  -> "Hostile",
        "body"   -> "text",
        "author" -> grace.show
      )
    )

    assertEquals(answered.status, 303)
    assertEquals(store.all().map(_.author), Seq(ada))
  }

  test("update preserves the owner, and a body naming another user does not move the row") {
    val store = notes()
    val key   = Id.gen[Note]()
    store.insert(key, note(key, ada, "Mine"))
    val table = routes(store, ada)

    val answered = answer(
      table,
      request(
        Method.PUT,
        s"/notes/${key.show}",
        "title"  -> "Renamed",
        "body"   -> "text",
        "author" -> grace.show
      )
    )

    assertEquals(answered.status, 303)
    assertEquals(store.find(key).map(row => (row.title, row.author)), Some(("Renamed", ada)))
  }

  test("an uncovered update never reassigns the owner, whoever the request signs in as") {
    val store = InMemoryStore.scoped(vaultOwner)
    val key   = Id.gen[Vault]()
    store.insert(key, Vault(key, ada, "Mine"))
    // Update is not in this declaration's covers, so it is grace, not ada, standing in for who is
    // signed in on the request below: exactly the "anyone may edit" shape `only(Action.Destroy)`
    // describes, and the shape the reassignment defect needed to fire at all.
    val table = RouteTable(Resource[Vault].routes(store, vaultsOwnedByOnlyDestroy(grace)))

    val answered = answer(
      table,
      request(
        Method.PUT,
        s"/vaults/${key.show}",
        "secret" -> "Taken",
        "author" -> grace.show
      )
    )

    assertEquals(answered.status, 303)
    assertEquals(store.find(key).map(row => (row.secret, row.author)), Some(("Taken", ada)))
  }

  test("index and show read the plain store, so either user sees the other's rows") {
    val store = notes()
    val hers  = Id.gen[Note]()
    val his   = Id.gen[Note]()
    store.insert(hers, note(hers, ada, "Hers"))
    store.insert(his, note(his, grace, "His"))

    Seq(ada, grace).foreach { who =>
      val table = routes(store, who)
      val index = markup(answer(table, request(Method.GET, "/notes")))
      assert(index.contains("Hers"), index)
      assert(index.contains("His"), index)
      assert(markup(answer(table, request(Method.GET, s"/notes/${hers.show}"))).contains("Hers"))
      assert(markup(answer(table, request(Method.GET, s"/notes/${his.show}"))).contains("His"))
    }
  }

  test("a model that does not cover Show answers 403 for a foreign row and 404 for no row") {
    val store = notes()
    val his   = Id.gen[Note]()
    store.insert(his, note(his, grace, "His"))
    val table   = routes(store, ada)
    val nowhere = Id.gen[Note]().show

    assertEquals(status(table, request(Method.GET, s"/notes/${his.show}/edit")), 403)
    assertEquals(
      status(table, request(Method.PUT, s"/notes/${his.show}", "title" -> "x", "body" -> "y")),
      403
    )
    assertEquals(status(table, request(Method.DELETE, s"/notes/${his.show}")), 403)

    assertEquals(status(table, request(Method.GET, s"/notes/$nowhere/edit")), 404)
    assertEquals(
      status(table, request(Method.PUT, s"/notes/$nowhere", "title" -> "x", "body" -> "y")),
      404
    )
    assertEquals(status(table, request(Method.DELETE, s"/notes/$nowhere")), 404)
    assertEquals(store.find(his).map(_.title), Some("His"))
  }

  test("a model that covers Show answers 404 for a foreign row and never reads the whole table") {
    val rows    = InMemoryStore[Basket]()
    val counted = new Counting(rows)
    val scoped  = Scoped(counted, InMemoryStore.narrowing(rows, basketOwner))
    val his     = Id.gen[Basket]()
    rows.insert(his, Basket(his, grace, "His"))
    val table = RouteTable(Resource[Basket].routes(scoped, basketsOwnedBy(ada)))
    counted.reset()

    def code(request: Request): Int =
      try table.dispatch(request).status
      catch { case NotFound(_) => 404; case Forbidden(_) => 403 }

    assertEquals(code(request(Method.GET, s"/baskets/${his.show}/edit")), 404)
    assertEquals(code(request(Method.PUT, s"/baskets/${his.show}", "label" -> "x")), 404)
    assertEquals(code(request(Method.DELETE, s"/baskets/${his.show}")), 404)
    assertEquals(counted.finds, 0)
  }

  test("a model with no Show route answers 404 for a foreign row rather than probing it as 403") {
    val store = InMemoryStore.scoped(vaultOwner)
    val his   = Id.gen[Vault]()
    store.insert(his, Vault(his, grace, "His secret"))
    val table = RouteTable(Resource[Vault].routes(store, vaultsOwnedBy(ada)))

    def code(request: Request): Int =
      try table.dispatch(request).status
      catch { case NotFound(_) => 404; case Forbidden(_) => 403 }

    assertEquals(code(request(Method.GET, s"/vaults/${his.show}/edit")), 404)
    assertEquals(code(request(Method.PUT, s"/vaults/${his.show}", "secret" -> "x")), 404)
    assertEquals(code(request(Method.DELETE, s"/vaults/${his.show}")), 404)
    assertEquals(store.find(his).map(_.secret), Some("His secret"))
  }

  test("a successful covered write never reads the whole table") {
    val rows    = InMemoryStore[Note]()
    val counted = new Counting(rows)
    val scoped  = Scoped(counted, InMemoryStore.narrowing(rows, noteOwner))
    val key     = Id.gen[Note]()
    rows.insert(key, note(key, ada, "Mine"))
    val table = routes(scoped, ada)
    counted.reset()

    assertEquals(
      status(table, request(Method.PUT, s"/notes/${key.show}", "title" -> "x", "body" -> "y")),
      303
    )
    assertEquals(counted.finds, 0)
  }

  test("show offers edit and delete on the owner's own row and neither on a foreign one") {
    val store = notes()
    val hers  = Id.gen[Note]()
    val his   = Id.gen[Note]()
    store.insert(hers, note(hers, ada, "Hers"))
    store.insert(his, note(his, grace, "His"))
    val table = routes(store, ada)

    val own = markup(answer(table, request(Method.GET, s"/notes/${hers.show}")))
    assert(own.contains(s"/notes/${hers.show}/edit"), own)
    assert(own.contains("Delete"), own)

    val foreign = markup(answer(table, request(Method.GET, s"/notes/${his.show}")))
    assert(foreign.contains("His"), foreign)
    assert(!foreign.contains(s"/notes/${his.show}/edit"), foreign)
    assert(!foreign.contains("Delete"), foreign)
  }

  test("a public show page renders for nobody signed in, even though editing is covered") {
    val store = notes()
    val key   = Id.gen[Note]()
    store.insert(key, note(key, grace, "Hers"))

    // The shape `only` allows: Show is neither guarded nor covered, so an anonymous browser
    // reaches the handler directly, but Edit is both, and `owner` here stands in for
    // `Guard.owning`, which throws Unauthorized rather than answering when nobody is signed in.
    val declared = Owned[Note, Person](
      noteOwner,
      _ => throw Unauthorized("nobody is signed in for this request"),
      Set(Action.Edit, Action.Update, Action.Destroy),
      Guarded(Set(Action.Edit, Action.Update, Action.Destroy), identity, Seq.empty)
    )
    val table = RouteTable(Resource[Note].routes(store, declared))

    val page = markup(answer(table, request(Method.GET, s"/notes/${key.show}")))
    assert(page.contains("Hers"), page)
    assert(!page.contains(s"/notes/${key.show}/edit"), page)
    assert(!page.contains("Delete"), page)
  }

  test("mounting an owned model on a store that cannot narrow refuses rather than serving all") {
    val thrown = intercept[IllegalStateException] {
      Resource[Note].routes(InMemoryStore[Note](), notesOwnedBy(ada))
    }
    assert(thrown.getMessage.contains("Note"), thrown.getMessage)
  }

  test("a mistyped owner name refuses to mount rather than leaving the field editable") {
    val bogus = OwnerOf[Note, Id[Person]]("auth0r", _.author)
    val declared = Owned[Note, Person](
      bogus,
      _ => ada,
      Set(Action.Create),
      Guarded(Action.values.toSet, identity, Seq.empty)
    )

    val thrown = intercept[IllegalStateException] {
      Resource[Note].routes(notes(), declared)
    }
    assert(thrown.getMessage.contains("Note"), thrown.getMessage)
    assert(thrown.getMessage.contains("auth0r"), thrown.getMessage)
  }

  /** The whole table, with its `find` counted.
    *
    * It is the unnarrowed half of a [[Scoped]] built by hand, while the narrowing reads the same
    * rows directly, so what this counts is exactly the existence probe: a test can then state that
    * the whole table was consulted on the failure path of one model and never on the other's, nor
    * on any success.
    */
  private final class Counting[A](rows: Store[A]) extends Store[A] {
    private var seen: Int = 0

    def finds: Int    = seen
    def reset(): Unit = seen = 0

    def all(): Seq[A]                       = rows.all()
    def find(key: Id[A]): Option[A]         = { seen += 1; rows.find(key) }
    def insert(key: Id[A], row: A): Unit    = rows.insert(key, row)
    def update(key: Id[A], row: A): Boolean = rows.update(key, row)
    def delete(key: Id[A]): Boolean         = rows.delete(key)
  }
}
