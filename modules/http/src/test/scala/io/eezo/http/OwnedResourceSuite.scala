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
      _ => Some(who),
      Action.values.toSet -- Set(Action.Index, Action.Show),
      Guarded(Action.values.toSet, identity, Seq.empty)
    )

  private def basketsOwnedBy(who: Id[Person]): Owned[Basket, Person] =
    Owned[Basket, Person](
      basketOwner,
      _ => Some(who),
      Action.values.toSet,
      Guarded(Action.values.toSet, identity, Seq.empty)
    )

  /** Only `Destroy` is covered: everyone signed in may edit a vault, only its author may delete it.
    * `Create` and `Update` are deliberately left uncovered, which is what the reassignment defect
    * needs to happen at all.
    */
  private def vaultsOwnedByOnlyDestroy(who: Option[Id[Person]]): Owned[Vault, Person] =
    Owned[Vault, Person](
      vaultOwner,
      _ => who,
      Set(Action.Destroy),
      Guarded(Action.values.toSet, identity, Seq.empty)
    )

  private def vaultsOwnedBy(who: Id[Person]): Owned[Vault, Person] =
    Owned[Vault, Person](
      vaultOwner,
      _ => Some(who),
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
      case NotFound(_)  => 404
      case Forbidden(_) => 403
    }

  private def note(key: Id[Note], owner: Id[Person], title: String): Note =
    Note(key, owner, title, "body")

  test("an Owned is accepted everywhere a Guarded is, and carries the guard's own routes") {
    val login = Route.derived(Method.GET, "/login", _ => Response.Ok(io.eezo.core.html.Html.empty))
    val declared = Owned[Note, Person](
      noteOwner,
      _ => Some(ada),
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

  test("an Owned is not the bare Guarded it refines, nor another Owned covering more") {
    val bare     = Guarded[Note](Action.values.toSet, identity, Seq.empty)
    val declared = Owned[Note, Person](noteOwner, _ => Some(ada), Set(Action.Create), bare)

    // The three inherited fields are the same three, so a declaration compared by them alone
    // would lose the half that makes it owned: the refinement has to be a different value from
    // what it refines, and from a declaration that covers other actions.
    assertNotEquals(declared: Guarded[Note], bare)
    assertNotEquals(
      declared,
      Owned[Note, Person](noteOwner, _ => Some(ada), Action.values.toSet, bare)
    )
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
    val table = RouteTable(Resource[Vault].routes(store, vaultsOwnedByOnlyDestroy(Some(grace))))

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

    assertEquals(status(table, request(Method.GET, s"/baskets/${his.show}/edit")), 404)
    assertEquals(status(table, request(Method.PUT, s"/baskets/${his.show}", "label" -> "x")), 404)
    assertEquals(status(table, request(Method.DELETE, s"/baskets/${his.show}")), 404)
    assertEquals(counted.finds, 0)
  }

  test("a model with no Show route answers 404 for a foreign row rather than probing it as 403") {
    val store = InMemoryStore.scoped(vaultOwner)
    val his   = Id.gen[Vault]()
    store.insert(his, Vault(his, grace, "His secret"))
    val table = RouteTable(Resource[Vault].routes(store, vaultsOwnedBy(ada)))

    assertEquals(status(table, request(Method.GET, s"/vaults/${his.show}/edit")), 404)
    assertEquals(status(table, request(Method.PUT, s"/vaults/${his.show}", "secret" -> "x")), 404)
    assertEquals(status(table, request(Method.DELETE, s"/vaults/${his.show}")), 404)
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
    // reaches the handler directly, but Edit is both, and `currentUser` here stands in for the
    // guard's own reading of it, which answers nobody when nobody is signed in. Create is guarded
    // too, alongside Edit, Update and Destroy, since Note mounts it and an owned create always
    // needs a signed in user; that is a different question from the one this test is about.
    val declared = Owned[Note, Person](
      noteOwner,
      _ => None,
      Set(Action.Edit, Action.Update, Action.Destroy),
      Guarded(Set(Action.Create, Action.Edit, Action.Update, Action.Destroy), identity, Seq.empty)
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

  test("a covered route reached with nobody signed in fails rather than scoping to nobody") {
    // A declaration that names every action as guarded while its through is identity, which the
    // boot check compares sets against and therefore accepts; nothing about a live guard stops an
    // anonymous request from reaching the handler here, so the throw below is what a real guard's
    // own refusal would otherwise make unreachable. It has to fail loudly: a store narrowed to
    // nobody would read rows that are not the requester's.
    val declared = Owned[Note, Person](
      noteOwner,
      _ => None,
      Action.values.toSet,
      Guarded(Action.values.toSet, identity, Seq.empty)
    )
    val table = RouteTable(Resource.routesOf[Note](notes(), declared))

    val thrown = intercept[IllegalStateException](answer(table, request(Method.GET, "/notes")))
    assert(thrown.getMessage.contains("nobody for an owned route to scope to"), thrown.getMessage)
  }

  test("a create with nobody signed in fails rather than writing a row nobody owns") {
    // Only Destroy is covered here, so the store a create writes through is never narrowed, and
    // filling the owner field is the one and only place this request can ask who is signed in. A
    // create filling that field with a guess would write a row nobody can be refused over.
    val store = InMemoryStore.scoped(vaultOwner)
    val table = RouteTable(Resource[Vault].routes(store, vaultsOwnedByOnlyDestroy(None)))

    val thrown = intercept[IllegalStateException](
      answer(table, request(Method.POST, "/vaults", "secret" -> "Mine"))
    )
    assert(thrown.getMessage.contains("nobody for an owned route to scope to"), thrown.getMessage)
    assertEquals(store.all().size, 0, "a create with nobody signed in wrote a row anyway")
  }

  test("every other covered action with nobody signed in fails the same way the index does") {
    // The same declaration the index fails under, with a row already in the store so the four
    // requests below name a real key. Show and edit read through the narrowed store, update and
    // destroy write through it, and each one asks who is signed in before it touches a row;
    // nobody answers, so none of them may read, change or delete the row that is there.
    val store = notes()
    val key   = Id.gen[Note]()
    store.insert(key, note(key, ada, "Mine"))
    val declared = Owned[Note, Person](
      noteOwner,
      _ => None,
      Action.values.toSet,
      Guarded(Action.values.toSet, identity, Seq.empty)
    )
    val table = RouteTable(Resource[Note].routes(store, declared))

    Seq(
      request(Method.GET, s"/notes/${key.show}"),
      request(Method.GET, s"/notes/${key.show}/edit"),
      request(Method.PUT, s"/notes/${key.show}", "title" -> "Taken", "body" -> "text"),
      request(Method.DELETE, s"/notes/${key.show}")
    ).foreach { attempt =>
      val tried  = s"${attempt.method} ${attempt.path}"
      val thrown = intercept[IllegalStateException](answer(table, attempt))
      assert(
        thrown.getMessage.contains("nobody for an owned route to scope to"),
        s"$tried: ${thrown.getMessage}"
      )
      assertEquals(store.find(key).map(_.title), Some("Mine"), tried)
    }
  }

  test("a declaration covering a route its guard leaves open refuses to mount") {
    // Owned implies guarded: a covered handler reads who is signed in, so a covered route nobody
    // has to be signed in for fails on every request rather than serving anyone.
    val declared = Owned[Note, Person](
      noteOwner,
      _ => Some(ada),
      Action.values.toSet,
      Guarded(Set(Action.Index), identity, Seq.empty)
    )

    val thrown = intercept[IllegalStateException] {
      Resource[Note].routes(notes(), declared)
    }
    assert(thrown.getMessage.contains("Note"), thrown.getMessage)
    assert(thrown.getMessage.contains("Create"), thrown.getMessage)
    assert(thrown.getMessage.contains("Destroy"), thrown.getMessage)
    assert(!thrown.getMessage.contains("Index"), thrown.getMessage)
  }

  test("the blog's own shape, guarded everywhere and covering five of the seven, mounts") {
    assertEquals(Resource[Note].routes(notes(), notesOwnedBy(ada)).size, Action.values.length)
  }

  test("a covered action the model does not mount is not a route to refuse the declaration over") {
    // Vault subtracts Show, so covering it names no route at all and the guard has nothing to
    // cover; only what is actually mounted can be the mismatch this refusal is about. Create is
    // guarded here too, since Vault mounts it and an owned create always needs a signed in user;
    // that is a different question from the one this test is about, so it is settled rather than
    // left to trip the assertion below.
    val declared = Owned[Vault, Person](
      vaultOwner,
      _ => Some(ada),
      Set(Action.Show, Action.Destroy),
      Guarded(Set(Action.Destroy, Action.Create), identity, Seq.empty)
    )

    assertEquals(
      Resource[Vault].routes(InMemoryStore.scoped(vaultOwner), declared).size,
      Action.values.length - 1
    )
  }

  test(
    "a mounted Create the guard leaves open refuses to mount even when covers does not name it"
  ) {
    // Neither covers nor the guard names Create here, and Vault mounts it since only Show is
    // subtracted. An owned create always fills the owner from currentUser, covered or not, so this
    // shape would fail on every anonymous submission rather than being the public create the
    // declaration otherwise looks like, and the check that Owned implies guarded has to catch it
    // the same way it catches an uncovered Destroy or Update.
    val declared = Owned[Vault, Person](
      vaultOwner,
      _ => Some(ada),
      Set(Action.Destroy),
      Guarded(Set(Action.Destroy), identity, Seq.empty)
    )

    val thrown = intercept[IllegalStateException] {
      Resource[Vault].routes(InMemoryStore.scoped(vaultOwner), declared)
    }
    assert(thrown.getMessage.contains("Vault"), thrown.getMessage)
    assert(thrown.getMessage.contains("Create"), thrown.getMessage)
  }

  test("an uncovered update with no such row answers 404 rather than asking who is signed in") {
    val store = InMemoryStore.scoped(vaultOwner)
    // Destroy alone is covered, and Destroy and Create alone are guarded: an anonymous browser
    // reaches `update` directly, so the row it reads to keep the owner of is the only thing
    // standing between it and a `currentUser` that has nobody to answer with. A key naming no row
    // is a 404 everywhere else, and it is one here. Create is guarded as well as Destroy because
    // Vault mounts it and an owned create always needs a signed in user, which this test is not
    // about.
    val declared = Owned[Vault, Person](
      vaultOwner,
      _ => None,
      Set(Action.Destroy),
      Guarded(Set(Action.Destroy, Action.Create), identity, Seq.empty)
    )
    val table   = RouteTable(Resource[Vault].routes(store, declared))
    val nowhere = Id.gen[Vault]().show

    assertEquals(status(table, request(Method.PUT, s"/vaults/$nowhere", "secret" -> "x")), 404)
  }

  test("a mistyped owner name refuses to mount rather than leaving the field editable") {
    val bogus    = OwnerOf[Note, Id[Person]]("auth0r", _.author)
    val declared = Owned[Note, Person](
      bogus,
      _ => Some(ada),
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
