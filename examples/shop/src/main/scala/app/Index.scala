package app

import io.eezo.core.html.*
import io.eezo.db.*
import io.eezo.db.Scopes.read
import io.eezo.http.{Csrf, Guarded, Request, Response}

import models.Product
import views.Show

object Index {
  given Guarded[Index.type] = Guarded.public

  def index(request: Request): Response = {
    val products = read(Table[Product].all())
    Response.Ok(
      title("the shop") ++ h1("the shop") ++
        ul(products.map { p =>
          li(
            p.name,
            " ",
            Show.money(p.price),
            " ",
            form(
              Attrs.method := "post",
              Attrs.action := s"/checkout/${p.id.show}",
              Csrf.hidden(request.csrf),
              button("Buy")
            )
          )
        })
    )
  }
}
