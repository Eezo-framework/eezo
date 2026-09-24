package io.eezo.http

import io.eezo.core.{Id, OwnerOf, Store}

/** What an owned declaration answers the handlers, asked directly rather than through a route.
  *
  * The handlers read these answers and never branch on ownership themselves, so every rule about
  * owned rows is stated here once, against the three shapes the route suites mount: a blog that
  * lets everyone read, a basket nobody else can see, and a vault with no page to read a row on.
  */
class OwnershipSuite extends munit.FunSuite with ResourceFixtures {

  private val ada   = Id.gen[Person]()
  private val grace = Id.gen[Person]()

  private val noteOwner = OwnerOf[Note, Id[Person]]("author", _.author)

  /** Declared the way `OwnedResourceSuite.notesOwnedBy` is: every route guarded, everything but the
    * two reading routes covered.
    */
  private def notesOwnedBy(who: Id[Person]): Owned[Note, Person] =
    Owned[Note, Person](
      noteOwner,
      _ => Some(who),
      Action.values.toSet -- Set(Action.Index, Action.Show),
      Guarded(Action.values.toSet, identity, Seq.empty)
    )

  private val vaultOwner  = OwnerOf[Vault, Id[Person]]("author", _.author)
  private val basketOwner = OwnerOf[Basket, Id[Person]]("author", _.author)

  /** Everything guarded and everything covered, `Show` included, like a cart. */
  private def basketsOwnedBy(who: Id[Person]): Owned[Basket, Person] =
    Owned[Basket, Person](
      basketOwner,
      _ => Some(who),
      Action.values.toSet,
      Guarded(Action.values.toSet, identity, Seq.empty)
    )

  /** Only `Destroy` covered, `Create` guarded beside it: anyone signed in may create or edit a
    * vault, and only its author may delete it.
    */
  private def vaultsOwnedByOnlyDestroy(who: Option[Id[Person]]): Owned[Vault, Person] =
    Owned[Vault, Person](
      vaultOwner,
      _ => who,
      Set(Action.Destroy),
      Guarded(Action.values.toSet, identity, Seq.empty)
    )

  /** Declared the way the blog is, over a model whose `Actions` has subtracted `Show`. */
  private def vaultsOwnedBy(who: Id[Person]): Owned[Vault, Person] =
    Owned[Vault, Person](
      vaultOwner,
      _ => Some(who),
      Action.values.toSet -- Set(Action.Index, Action.Show),
      Guarded(Action.values.toSet, identity, Seq.empty)
    )

  private def scopedNotes(): Scoped[Note]   = InMemoryStore.scoped(noteOwner)
  private def scopedVaults(): Scoped[Vault] = InMemoryStore.scoped(vaultOwner)

  /** A notes store holding one row each for ada and grace, keyed so a test can name either. */
  private def twoNotes(): (Scoped[Note], Id[Note], Id[Note]) = {
    val store = scopedNotes()
    val hers  = Id.gen[Note]()
    val his   = Id.gen[Note]()
    store.insert(hers, Note(hers, ada, "Hers", "body"))
    store.insert(his, Note(his, grace, "His", "body"))
    (store, hers, his)
  }

  private def notes(store: Store[Note], guarded: Guarded[Note]): Ownership[Note] =
    new Ownership[Note]("Note", summon[Form[Note]], summon[Actions[Note]], store, guarded)

  private def vaults(store: Store[Vault], guarded: Guarded[Vault]): Ownership[Vault] =
    new Ownership[Vault]("Vault", summon[Form[Vault]], summon[Actions[Vault]], store, guarded)

  private def baskets(store: Store[Basket], guarded: Guarded[Basket]): Ownership[Basket] =
    new Ownership[Basket]("Basket", summon[Form[Basket]], summon[Actions[Basket]], store, guarded)

  /** What `missing` threw, as the status the boundary would render it with. */
  private def answered(run: => Nothing): Int =
    try run
    catch {
      case NotFound(_)  => 404
      case Forbidden(_) => 403
    }

  test("an Owned on a store that cannot narrow is refused at construction") {
    val thrown = intercept[IllegalStateException] {
      notes(InMemoryStore[Note](), notesOwnedBy(ada))
    }
    assertEquals(
      thrown.getMessage,
      "Note declares an Owned, so its routes need a store that can narrow to one owner. The " +
        "generated route table builds one; a hand-written mount has to pass " +
        "InMemoryStore.scoped, or JdbcStore's owner aware pair."
    )
  }

  test("an owner name naming no field is refused at construction") {
    val declared = Owned[Note, Person](
      OwnerOf[Note, Id[Person]]("auth0r", _.author),
      _ => Some(ada),
      Set(Action.Create),
      Guarded(Action.values.toSet, identity, Seq.empty)
    )

    val thrown = intercept[IllegalStateException](notes(scopedNotes(), declared))
    assertEquals(
      thrown.getMessage,
      "Note declares Owned naming \"auth0r\" as its owner, but Note has no field of that name " +
        "for the form to hide. Name the field the declaration actually owns."
    )
  }

  test("a covered or created action the guard leaves open is refused at construction") {
    // Only Destroy is covered and only Destroy is guarded, and Vault still mounts Create: an owned
    // create fills the owner from who is signed in, so it needs the guard as much as Destroy does.
    val declared = Owned[Vault, Person](
      vaultOwner,
      _ => Some(ada),
      Set(Action.Destroy),
      Guarded(Set(Action.Destroy), identity, Seq.empty)
    )

    val thrown = intercept[IllegalStateException](vaults(scopedVaults(), declared))
    assertEquals(
      thrown.getMessage,
      "Vault declares Owned that needs a signed in user for Create, which its guard does not " +
        "require one for. A covered route reads who is signed in, and a create fills the owner " +
        "field from who is signed in whether or not it is covered, so those routes would refuse " +
        "every request. Guard those actions as well, or leave a covered one out of what " +
        "ownership covers; a create ownership does not cover can only be guarded or left " +
        "unmounted."
    )
  }

  test("the refusals fire in order: the store, then the owner name, then the guard") {
    // Every declaration below is wrong in more than one way, so only the order decides which
    // mistake is reported.
    val misnamedAndOpen = Owned[Note, Person](
      OwnerOf[Note, Id[Person]]("auth0r", _.author),
      _ => Some(ada),
      Action.values.toSet,
      Guarded(Set(Action.Index), identity, Seq.empty)
    )
    val open = Owned[Note, Person](
      noteOwner,
      _ => Some(ada),
      Action.values.toSet,
      Guarded(Set(Action.Index), identity, Seq.empty)
    )

    def refusal(run: => Any): String = intercept[IllegalStateException](run).getMessage

    assert(refusal(notes(InMemoryStore[Note](), misnamedAndOpen)).contains("narrow to one owner"))
    assert(refusal(notes(scopedNotes(), misnamedAndOpen)).contains("\"auth0r\""))
    assert(refusal(notes(InMemoryStore[Note](), open)).contains("narrow to one owner"))
    assert(
      refusal(notes(scopedNotes(), open))
        .startsWith("Note declares Owned that needs a signed in user for New, Show, Edit, Create")
    )
  }

  test("shape hides the owner field, and an unowned model keeps every field it declared") {
    val owned   = notes(scopedNotes(), notesOwnedBy(ada)).shape.fields.map(_.name)
    val unowned = notes(InMemoryStore[Note](), Guarded.public).shape.fields.map(_.name)

    assert(!owned.contains("author"), owned)
    assert(owned.contains("title"), owned)
    assertEquals(unowned, summon[Form[Note]].fields.map(_.name))
    assert(unowned.contains("author"), unowned)
  }

  test("storeFor narrows a covered action to the current user's rows and leaves the rest whole") {
    val (store, hers, his) = twoNotes()
    val ownership          = notes(store, notesOwnedBy(ada))
    val asked              = request(Method.GET, "/notes")

    assertEquals(
      ownership.storeFor(asked, Action.Index).all().map(_.title).sorted,
      Seq("Hers", "His")
    )
    assertEquals(ownership.storeFor(asked, Action.Update).all().map(_.title), Seq("Hers"))
    assertEquals(ownership.storeFor(asked, Action.Destroy).find(his), None)
    assertEquals(ownership.storeFor(asked, Action.Show).find(his).map(_.title), Some("His"))
    assertEquals(ownership.storeFor(asked, Action.Edit).find(hers).map(_.title), Some("Hers"))
  }

  test("missing is 403 for a foreign row only while Show is uncovered and mounted, else 404") {
    val (store, _, his) = twoNotes()
    val blog            = notes(store, notesOwnedBy(ada))
    val nowhere         = Id.gen[Note]()
    val asked           = request(Method.GET, s"/notes/${his.show}/edit")

    assertEquals(answered(blog.missing(asked, his, Action.Edit)), 403)
    assertEquals(answered(blog.missing(asked, his, Action.Destroy)), 403)
    assertEquals(answered(blog.missing(asked, nowhere, Action.Edit)), 404)
    // Show is not covered on the blog, so a row missing from it is simply not there.
    assertEquals(answered(blog.missing(asked, his, Action.Show)), 404)

    val basketRows = InMemoryStore.scoped(basketOwner)
    val basket     = Id.gen[Basket]()
    basketRows.insert(basket, Basket(basket, grace, "His"))
    val cart = baskets(basketRows, basketsOwnedBy(ada))
    assertEquals(answered(cart.missing(asked, basket, Action.Edit)), 404)

    val vaultRows = scopedVaults()
    val vault     = Id.gen[Vault]()
    vaultRows.insert(vault, Vault(vault, grace, "His secret"))
    val safe = vaults(vaultRows, vaultsOwnedBy(ada))
    assertEquals(answered(safe.missing(asked, vault, Action.Edit)), 404)
  }

  test(
    "body overwrites the owner on a covered write and keeps the row's owner on an uncovered one"
  ) {
    val blog    = notes(scopedNotes(), notesOwnedBy(ada))
    val hostile = request(Method.POST, "/notes", "title" -> "Hostile", "author" -> grace.show)
    assertEquals(blog.body(hostile, None).get("author"), Some(Seq(ada.show)))
    assertEquals(blog.body(hostile, None).get("title"), Some(Seq("Hostile")))

    // Update is not covered here, and grace is the one signed in: the owner the row already had
    // is what the write keeps, whatever the body or the session says.
    val safe     = vaults(scopedVaults(), vaultsOwnedByOnlyDestroy(Some(grace)))
    val existing = Vault(Id.gen[Vault](), ada, "Mine")
    val renamed  = request(Method.PUT, "/vaults/x", "secret" -> "Taken", "author" -> grace.show)
    assertEquals(safe.body(renamed, Some(existing)).get("author"), Some(Seq(ada.show)))
    // An uncovered create has no earlier row, so its first owner is whoever sent it.
    assertEquals(safe.body(renamed, None).get("author"), Some(Seq(grace.show)))

    val unowned = notes(InMemoryStore[Note](), Guarded.public)
    assertEquals(unowned.body(hostile, None), hostile.form)
  }

  test("keeping reads nothing when Update is covered and the stored row when it is not") {
    val (store, hers, _) = twoNotes()
    val asked            = request(Method.PUT, s"/notes/${hers.show}", "title" -> "x")
    assertEquals(notes(store, notesOwnedBy(ada)).keeping(asked, hers, store), None)

    val vaultRows = scopedVaults()
    val vault     = Id.gen[Vault]()
    val stored    = Vault(vault, ada, "Mine")
    vaultRows.insert(vault, stored)
    val safe = vaults(vaultRows, vaultsOwnedByOnlyDestroy(Some(grace)))
    assertEquals(safe.keeping(asked, vault, vaultRows), Some(stored))
    // A key naming no row is the 404 it is everywhere else, before anyone asks who is signed in.
    intercept[NotFound](safe.keeping(asked, Id.gen[Vault](), vaultRows))

    val unowned = notes(store, Guarded.public)
    assertEquals(unowned.keeping(asked, hers, store), None)
  }

  test("currentUserOf answers what the declared currentUser reads, and None when unowned") {
    val asked = anonymous(Method.GET, "/vaults")

    assertEquals(vaults(scopedVaults(), vaultsOwnedByOnlyDestroy(None)).currentUserOf(asked), None)
    assertEquals(
      vaults(scopedVaults(), vaultsOwnedByOnlyDestroy(Some(ada))).currentUserOf(asked),
      Some(ada)
    )
    assertEquals(notes(InMemoryStore[Note](), Guarded.public).currentUserOf(asked), None)
  }

  test("owns is true for an uncovered action, true for the owner and false for anyone else") {
    val blog = notes(scopedNotes(), notesOwnedBy(ada))
    val hers = Note(Id.gen[Note](), ada, "Hers", "body")

    assert(blog.owns(Action.Show, hers, Some(grace)))
    assert(blog.owns(Action.Edit, hers, Some(ada)))
    assert(!blog.owns(Action.Edit, hers, Some(grace)))
    assert(!blog.owns(Action.Destroy, hers, Some(grace)))
    assert(!blog.owns(Action.Edit, hers, None))
  }

  test("owns never asks who is signed in when ownership does not cover the action") {
    var asked                      = 0
    def currentUser(): Option[Any] = { asked += 1; Some(ada) }
    val hers                       = Note(Id.gen[Note](), ada, "Hers", "body")
    val blog                       = notes(scopedNotes(), notesOwnedBy(ada))
    val unowned                    = notes(InMemoryStore[Note](), Guarded.public)

    assert(blog.owns(Action.Show, hers, currentUser()))
    assert(blog.owns(Action.Index, hers, currentUser()))
    Action.values.foreach(action => assert(unowned.owns(action, hers, currentUser())))
    assertEquals(asked, 0)

    assert(blog.owns(Action.Edit, hers, currentUser()))
    assertEquals(asked, 1)
  }
}
