package models

import io.eezo.core.Id
import io.eezo.db.Table
import io.eezo.http.{Form, Guarded, Resource}

case class Product(id: Id[Product], name: String, price: Int) derives Table, Form, Resource

object Product {
  given Guarded[Product] = User.guard.required
}
