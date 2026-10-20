package models

import io.eezo.core.Id
import io.eezo.db.Table
import io.eezo.http.{Action, Api, Form, Guarded, Resource}
import io.eezo.json.upickle.given

case class Product(id: Id[Product], name: String, price: Int) derives Table, Form, Resource, Api

object Product {

  /** One declaration for the seven pages and the five JSON routes, so a shopper and a program read
    * the catalogue on the same terms and nobody changes it without a user behind them. The form
    * pages stay guarded with the writes they submit to, because a page whose submit is refused is
    * no use to anybody who is not signed in.
    */
  given Guarded[Product] = User.guard.except(Action.Index, Action.Show)
}
