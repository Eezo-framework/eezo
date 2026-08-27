package io.eezo.http

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

import io.eezo.core.Id

/** What `derives Resource` mounts, and what each of the seven does when it runs.
  *
  * The routes are driven through a `RouteTable` rather than by calling handlers directly, because
  * the path parameters the handlers read are dispatch's own work, and a test that hand-builds
  * `pathParams` proves the handler and not the route.
  */
class ResourceSuite extends munit.FunSuite {

  case class Widget(id: Id[Widget], name: String, price: Int) derives Form, Resource

  case class BlogPost(id: Id[BlogPost], title: String) derives Form, Resource

  case class Category(id: Id[Category], name: String) derives Form, Resource

  case class Dish(id: Id[Dish], name: String) derives Form, Resource

  /** Nothing but reading, which is what makes the subtracted controls and the 405 observable. */
  case class Note(id: Id[Note], body: String) derives Form, Resource

  object Note {
    given Actions[Note] = Actions.only(Action.Index, Action.Show)
  }

  /** No `Show`, so create and update have nowhere to redirect but the index. */
  case class Draft(id: Id[Draft], body: String) derives Form, Resource

  object Draft {
    given Actions[Draft] = Actions.except(Action.Show)
  }

  /** Forms, and deliberately does not mount: it has no key. */
  case class Login(email: String, password: String) derives Form

  private def table[A](store: Store)(using r: Resource[A]): RouteTable =
    RouteTable(r.routes(store))

  private def request(method: Method, path: String, form: (String, String)*): Request = {
    val body = form
      .map { case (k, v) =>
        s"${URLEncoder.encode(k, StandardCharsets.UTF_8)}=${URLEncoder.encode(v, StandardCharsets.UTF_8)}"
      }
      .mkString("&")
    Request(
      method = method,
      path = path,
      query = Map.empty,
      headers =
        if (form.isEmpty) Map.empty
        else Map("Content-Type" -> Seq("application/x-www-form-urlencoded")),
      body = body.getBytes(StandardCharsets.UTF_8),
      pathParams = Map.empty
    )
  }

  private def markup(response: Response): String = response.body match {
    case Body.Html(node) => node.render
    case other           => fail(s"expected an HTML body, got $other")
  }

  private def location(response: Response): String =
    response.headers.collectFirst { case ("Location", value) => value }.getOrElse("")

  private def widgets(rows: (String, Int)*): (Store, RouteTable, Seq[Widget]) = {
    val store = Store.inMemory()
    val saved = rows.map { case (name, price) =>
      val row = Widget(Id.gen[Widget](), name, price)
      store.insert("widgets", row.id, row)
      row
    }
    (store, table[Widget](store), saved)
  }

  // ---------------------------------------------------------------- the route set

  test("the seven are mounted, with new emitted before show so that /widgets/new is reachable") {
    assertEquals(
      summon[Resource[Widget]].routes(Store.inMemory()).map(_.describe),
      Seq(
        "GET /widgets",
        "GET /widgets/new",
        "GET /widgets/:id",
        "GET /widgets/:id/edit",
        "POST /widgets",
        "PUT /widgets/:id",
        "DELETE /widgets/:id"
      )
    )
  }

  test("the route name is the class name, snake cased and pluralised") {
    def pathOf[A](using r: Resource[A]): String =
      r.routes(Store.inMemory()).head.describe.stripPrefix("GET ")

    assertEquals(pathOf[BlogPost], "/blog_posts")
    assertEquals(pathOf[Category], "/categories")
    assertEquals(pathOf[Dish], "/dishes")
  }

  test("a subtracted action is not mounted at all") {
    assertEquals(
      summon[Resource[Note]].routes(Store.inMemory()).map(_.describe),
      Seq("GET /notes", "GET /notes/:id")
    )
  }

  test("a subtracted write is a 405 with an accurate Allow, not a 404") {
    val store   = Store.inMemory()
    val failure =
      intercept[MethodNotAllowed](table[Note](store).dispatch(request(Method.DELETE, "/notes/x")))
    assertEquals(failure.allowed, Seq(Method.GET))
  }

  test("routesOf mounts nothing for a model with no Resource, and never fails to compile") {
    assertEquals(Resource.routesOf[Login](Store.inMemory()), Seq.empty[Route])
  }

  // ---------------------------------------------------------------- index

  test("index renders a table headed by the form's labels, one row per record") {
    val (_, routes, saved) = widgets("Bolt" -> 3, "Nut" -> 1)
    val page               = markup(routes.dispatch(request(Method.GET, "/widgets")))
    assert(page.contains("<th>Name</th>"), page)
    assert(page.contains("<th>Price</th>"), page)
    assert(page.contains("Bolt"), page)
    assert(page.contains("Nut"), page)
    assert(page.contains(s"""href="/widgets/${saved.head.id.show}""""), page)
  }

  test("index lists rows in insertion order") {
    val (_, routes, _) = widgets("first" -> 1, "second" -> 2)
    val page           = markup(routes.dispatch(request(Method.GET, "/widgets")))
    assert(page.indexOf("first") < page.indexOf("second"), page)
  }

  test("an empty index says so rather than rendering an empty table") {
    val (_, routes, _) = widgets()
    val page           = markup(routes.dispatch(request(Method.GET, "/widgets")))
    assert(page.contains("No widgets yet"), page)
  }

  test("index links to new when New is mounted, and does not when it is not") {
    val (_, routes, _) = widgets()
    assert(markup(routes.dispatch(request(Method.GET, "/widgets"))).contains("""/widgets/new"""))

    val notes = table[Note](Store.inMemory())
    assert(!markup(notes.dispatch(request(Method.GET, "/notes"))).contains("/notes/new"))
  }

  // ---------------------------------------------------------------- new and create

  test("new renders an empty form posting to the collection") {
    val (_, routes, _) = widgets()
    val page           = markup(routes.dispatch(request(Method.GET, "/widgets/new")))
    assert(page.contains("""<form action="/widgets" method="post">"""), page)
    assert(page.contains("""name="name""""), page)
    assert(!page.contains("_method"), page)
  }

  test("create stores the row under a minted key and redirects to its show page") {
    val (store, routes, _) = widgets()
    val response           =
      routes.dispatch(request(Method.POST, "/widgets", "name" -> "Bolt", "price" -> "3"))
    assertEquals(response.status, 303)
    val stored = store.list[Widget]("widgets")
    assertEquals(stored.map(w => (w.name, w.price)), Seq(("Bolt", 3)))
    assertEquals(location(response), s"/widgets/${stored.head.id.show}")
  }

  test("a rejected submission is a 422 that comes back with what was typed") {
    val (store, routes, _) = widgets()
    val response           =
      routes.dispatch(request(Method.POST, "/widgets", "name" -> "Bolt", "price" -> "cheap"))
    assertEquals(response.status, 422)
    assertEquals(store.list[Widget]("widgets"), Seq.empty[Widget])
    val page = markup(response)
    // The same page the form was on, not a bare form: a submission that comes back without its
    // heading reads as a different screen.
    assert(page.contains("<h1>New Widget</h1>"), page)
    assert(page.contains("is not a number"), page)
    assert(page.contains("""value="cheap""""), page)
    assert(page.contains("""value="Bolt""""), page)
  }

  test("create falls back to the index when Show is not mounted") {
    val store    = Store.inMemory()
    val response = table[Draft](store).dispatch(request(Method.POST, "/drafts", "body" -> "hi"))
    assertEquals(response.status, 303)
    assertEquals(location(response), "/drafts")
  }

  // ---------------------------------------------------------------- show

  test("show renders exactly the form's fields with their values, and never the key") {
    val (_, routes, saved) = widgets("Bolt" -> 3)
    val page = markup(routes.dispatch(request(Method.GET, s"/widgets/${saved.head.id.show}")))
    assert(page.contains("<dt>Name</dt><dd>Bolt</dd>"), page)
    assert(page.contains("<dt>Price</dt><dd>3</dd>"), page)
    // The key is in the address bar and on the link that got you here. Rendering it as a field
    // would make show's list disagree with edit's over the one row that is not editable.
    assert(!page.contains("<dt>Id</dt>"), page)
    assert(!page.contains(s"<dd>${saved.head.id.show}</dd>"), page)
  }

  test("show on a key that is not there is a 404") {
    val (_, routes, _) = widgets()
    intercept[NotFound](routes.dispatch(request(Method.GET, s"/widgets/${Id.gen[Widget]().show}")))
  }

  test("a key that will not parse is a 400, not a 404") {
    val (_, routes, _) = widgets()
    intercept[BadRequest](routes.dispatch(request(Method.GET, "/widgets/not-a-key")))
  }

  test("show carries an edit link and a delete button, both of which Actions can remove") {
    val (_, routes, saved) = widgets("Bolt" -> 3)
    val page = markup(routes.dispatch(request(Method.GET, s"/widgets/${saved.head.id.show}")))
    assert(page.contains(s"""href="/widgets/${saved.head.id.show}/edit""""), page)
    assert(page.contains("""<input type="hidden" name="_method" value="DELETE">"""), page)

    val store = Store.inMemory()
    val note  = Note(Id.gen[Note](), "read me")
    store.insert("notes", note.id, note)
    val readOnly =
      markup(table[Note](store).dispatch(request(Method.GET, s"/notes/${note.id.show}")))
    assert(!readOnly.contains("/edit"), readOnly)
    assert(!readOnly.contains("_method"), readOnly)
  }

  // ---------------------------------------------------------------- edit and update

  test("edit renders the record's values in a form whose verb is PUT") {
    val (_, routes, saved) = widgets("Bolt" -> 3)
    val page = markup(routes.dispatch(request(Method.GET, s"/widgets/${saved.head.id.show}/edit")))
    assert(page.contains(s"""<form action="/widgets/${saved.head.id.show}" method="post">"""), page)
    assert(page.contains("""<input type="hidden" name="_method" value="PUT">"""), page)
    assert(page.contains("""value="Bolt""""), page)
    assert(page.contains("""value="3""""), page)
  }

  test("edit on a missing row is a 404") {
    val (_, routes, _) = widgets()
    intercept[NotFound](
      routes.dispatch(request(Method.GET, s"/widgets/${Id.gen[Widget]().show}/edit"))
    )
  }

  test("update replaces the row, keeping its key, and redirects to show") {
    val (store, routes, saved) = widgets("Bolt" -> 3)
    val key                    = saved.head.id
    val response               =
      routes.dispatch(request(Method.PUT, s"/widgets/${key.show}", "name" -> "Nut", "price" -> "4"))
    assertEquals(response.status, 303)
    assertEquals(location(response), s"/widgets/${key.show}")
    assertEquals(store.get[Widget]("widgets", key), Some(Widget(key, "Nut", 4)))
  }

  test("update on a missing row is a 404, decided by the store rather than by a read first") {
    val (_, routes, _) = widgets()
    intercept[NotFound](
      routes.dispatch(
        request(Method.PUT, s"/widgets/${Id.gen[Widget]().show}", "name" -> "Nut", "price" -> "4")
      )
    )
  }

  test("a rejected update is a 422 and changes nothing") {
    val (store, routes, saved) = widgets("Bolt" -> 3)
    val key                    = saved.head.id
    val response               =
      routes.dispatch(
        request(Method.PUT, s"/widgets/${key.show}", "name" -> "Nut", "price" -> "cheap")
      )
    assertEquals(response.status, 422)
    assertEquals(store.get[Widget]("widgets", key).map(_.name), Some("Bolt"))
    assert(markup(response).contains("<h1>Edit Widget</h1>"), markup(response))
    assert(markup(response).contains("""value="cheap""""), markup(response))
  }

  // ---------------------------------------------------------------- destroy

  test("destroy removes the row and redirects to the index") {
    val (store, routes, saved) = widgets("Bolt" -> 3)
    val response = routes.dispatch(request(Method.DELETE, s"/widgets/${saved.head.id.show}"))
    assertEquals(response.status, 303)
    assertEquals(location(response), "/widgets")
    assertEquals(store.list[Widget]("widgets"), Seq.empty[Widget])
  }

  test("destroy on a missing row is a 404") {
    val (_, routes, _) = widgets()
    intercept[NotFound](
      routes.dispatch(request(Method.DELETE, s"/widgets/${Id.gen[Widget]().show}"))
    )
  }

  // ---------------------------------------------------------------- the store

  test("two models mounted over one store keep their rows apart") {
    val store = Store.inMemory()
    val both  = RouteTable(
      Resource.routesOf[Widget](store) ++ Resource.routesOf[BlogPost](store)
    )
    val _ = both.dispatch(request(Method.POST, "/widgets", "name" -> "Bolt", "price" -> "3"))
    val _ = both.dispatch(request(Method.POST, "/blog_posts", "title" -> "Hello"))
    assertEquals(store.list[Widget]("widgets").map(_.name), Seq("Bolt"))
    assertEquals(store.list[BlogPost]("blog_posts").map(_.title), Seq("Hello"))
  }

  // ---------------------------------------------------------------- what does not derive

  test("a model with no id field does not derive, and says why") {
    val errors = compileErrors(
      "case class Ledger(name: String) derives Form, Resource"
    )
    assert(errors.contains("needs a field named `id`"), errors)
  }

  test("a model whose id is not an Id[Model] does not derive, and says why") {
    val errors = compileErrors(
      "case class Ledger(id: Long, name: String) derives Form, Resource"
    )
    assert(errors.contains("typed Id[Model]"), errors)
  }

  test("an id keyed to another model does not derive either, so the phantom is load bearing") {
    val errors = compileErrors(
      "case class Ledger(id: io.eezo.core.Id[String], name: String) derives Form, Resource"
    )
    assert(errors.contains("typed Id[Model]"), errors)
  }

  test("an edit page without an update is orphaned, which is what boot warns about") {
    assertEquals(
      Resource.orphans(Actions.except[Widget](Action.Update)),
      Seq(Action.Edit -> Action.Update)
    )
  }

  test("a new page without a create is orphaned the same way") {
    assertEquals(
      Resource.orphans(Actions.except[Widget](Action.Create)),
      Seq(Action.New -> Action.Create)
    )
  }

  test("a create without a new page is silent, because the direction is one way") {
    assertEquals(Resource.orphans(Actions.except[Widget](Action.New)), Seq.empty)
  }

  test("subtracting a page along with its target leaves nothing orphaned") {
    assertEquals(
      Resource.orphans(Actions.except[Widget](Action.Edit, Action.Update)),
      Seq.empty
    )
  }

  test("all seven, and a read-only subset, are both quiet") {
    assertEquals(Resource.orphans(Actions.except[Widget]()), Seq.empty)
    assertEquals(Resource.orphans(Actions.only[Widget](Action.Index, Action.Show)), Seq.empty)
  }

  test("both pages can be orphaned at once, and each is named") {
    assertEquals(
      Resource.orphans(Actions.only[Widget](Action.New, Action.Edit)),
      Seq(Action.Edit -> Action.Update, Action.New -> Action.Create)
    )
  }
}
