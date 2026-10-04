package components

import io.eezo.core.html.*
import io.eezo.db.*
import io.eezo.db.Scopes.read
import io.eezo.live.{Component, Event, Init, Topic}

import models.Order
import views.Show

object Sales {
  val paid: Topic[Order] = new Topic[Order]
}

final class Board extends Component[List[Order]] {

  def init(ctx: Init[List[Order]]): List[Order] = {
    ctx.subscribe(Sales.paid)((sale, sales) => sale :: sales)
    read(Table[Order].where(_.paid === true).orderBy(_.at.desc).list())
  }

  def handle(event: Event, sales: List[Order]): List[Order] = sales

  def render(sales: List[Order]): Html =
    div(
      h1(sales.size, " sales, ", Show.money(sales.map(_.price).sum)),
      ul(sales.take(10).map { s =>
        li(Key(s.id.show), s.name, " ", Show.money(s.price), " ", Show.time(s.at))
      })
    )
}
