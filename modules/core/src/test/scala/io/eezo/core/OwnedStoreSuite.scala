package io.eezo.core

class OwnedStoreSuite extends munit.FunSuite {

  case class Widget(id: Id[Widget], owner: String, name: String)

  test("an OwnerOf carries the column name and the getter, and nothing else") {
    val ownerOf = OwnerOf[Widget, String]("owner", _.owner)
    assertEquals(ownerOf.name, "owner")
    assertEquals(ownerOf.get(Widget(Id.gen(), "ada", "Bolt")), "ada")
  }

  test("by answers a plain Store, so a narrowed store speaks the same five operations") {
    val key   = Id.gen[Widget]()
    val rows  = Seq(Widget(key, "ada", "Bolt"))
    val owned = new OwnedStore[Widget, String] {
      def by(owner: String): Store[Widget] = new Store[Widget] {
        def all(): Seq[Widget]                          = rows.filter(_.owner == owner)
        def find(k: Id[Widget]): Option[Widget]         = all().find(_.id == k)
        def insert(k: Id[Widget], row: Widget): Unit    = ()
        def update(k: Id[Widget], row: Widget): Boolean = false
        def delete(k: Id[Widget]): Boolean              = false
      }
    }

    val narrowed: Store[Widget] = owned.by("ada")
    assertEquals(narrowed.all().map(_.name), Seq("Bolt"))
    assertEquals(owned.by("grace").all(), Seq.empty[Widget])
  }
}
