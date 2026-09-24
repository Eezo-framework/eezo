package io.eezo.http

import io.eezo.core.{Id, OwnerOf}

/** A model whose `Destroy` is covered and subtracted at once, so the delete control is asked about
  * twice, once for ownership and once for mounting, and either answer alone must be enough to hide
  * it.
  */
case class Ledger(id: Id[Ledger], author: Id[Person], entry: String) derives Form, Resource

object Ledger {
  given Actions[Ledger] = Actions.except(Action.Destroy)
}

/** The owner's controls on a show page, for every pairing of covered and mounted.
  *
  * A control rendered on the wrong row is the ownership bypass itself, so the pairings the blog
  * suite does not reach are stated here: a control ownership covers but the model subtracted, and a
  * control neither covered nor owned.
  */
class OwnerControlsSuite extends munit.FunSuite with ResourceFixtures {

  private val ada   = Id.gen[Person]()
  private val grace = Id.gen[Person]()

  private val ledgerOwner = OwnerOf[Ledger, Id[Person]]("author", _.author)

  /** Every action but the two reading routes is covered, `Destroy` included even though `Ledger`
    * does not mount it, so the subtracted control is also one that ownership asks about.
    */
  private def ledgersOwnedBy(who: Option[Id[Person]]): Owned[Ledger, Person] =
    Owned[Ledger, Person](
      ledgerOwner,
      _ => who,
      Action.values.toSet -- Set(Action.Index, Action.Show),
      Guarded(Action.values.toSet -- Set(Action.Index, Action.Show), identity, Seq.empty)
    )

  /** The show page for a row `author` owns, as `viewer` sees it. */
  private def shown(author: Id[Person], viewer: Option[Id[Person]]): String = {
    val store = InMemoryStore.scoped(ledgerOwner)
    val key   = Id.gen[Ledger]()
    store.insert(key, Ledger(key, author, "Rent"))
    val table = RouteTable(Resource[Ledger].routes(store, ledgersOwnedBy(viewer)))
    markup(table.dispatch(request(Method.GET, s"/ledgers/${key.show}")))
  }

  test("a covered control the model subtracted never renders, even for the row's owner") {
    val page = shown(ada, Some(ada))
    assert(page.contains("/edit"), page)
    assert(!page.contains("Delete"), page)
  }

  test("a covered control renders for nobody but the owner, and never for an anonymous viewer") {
    Seq(Some(grace), None).foreach { viewer =>
      val page = shown(ada, viewer)
      assert(page.contains("Rent"), page)
      assert(!page.contains("/edit"), s"$viewer: $page")
      assert(!page.contains("Delete"), s"$viewer: $page")
    }
  }

  test("an uncovered control renders for anyone, owner or not") {
    val store = InMemoryStore.scoped(ledgerOwner)
    val key   = Id.gen[Ledger]()
    store.insert(key, Ledger(key, ada, "Rent"))
    Seq(Some(ada), Some(grace), None).foreach { viewer =>
      // Only Update is covered, so the edit page link is everyone's, while the write behind it is
      // still the owner's alone.
      val declared = Owned[Ledger, Person](
        ledgerOwner,
        _ => viewer,
        Set(Action.Update),
        Guarded(Set(Action.Create, Action.Update), identity, Seq.empty)
      )
      val table = RouteTable(Resource[Ledger].routes(store, declared))

      val page = markup(table.dispatch(request(Method.GET, s"/ledgers/${key.show}")))
      assert(page.contains(s"/ledgers/${key.show}/edit"), s"$viewer: $page")
    }
  }
}
