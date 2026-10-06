package app.checkout._id

import java.time.Instant

import io.eezo.core.Id
import io.eezo.db.*
import io.eezo.db.Scopes.*
import io.eezo.http.{BadRequest, Guarded, NotFound, Request, Response}
import io.eezo.http.client.{Auth, Http, Reply}

import models.{Order, Product}
import stripe.Stripe

object Create {
  given Guarded[Create.type] = Guarded.public

  def create(request: Request): Response = {
    val id      = request.param[Id[Product]]("id")
    val product = read(Table[Product].findById(id)).getOrElse(throw NotFound(request.path))
    val order   = Order(Id.gen(), product.id, product.name, product.price, paid = false, Instant.now())
    transact(Table[Order].insert(order))
    Http.post(
      "https://api.stripe.com/v1/checkout/sessions",
      Http.form(
        "mode"                                          -> "payment",
        "client_reference_id"                           -> order.id.show,
        "success_url"                                   -> s"${Stripe.back}/?paid",
        "cancel_url"                                    -> Stripe.back,
        "line_items[0][quantity]"                       -> "1",
        "line_items[0][price_data][currency]"           -> "eur",
        "line_items[0][price_data][unit_amount]"        -> (product.price * 100).toString,
        "line_items[0][price_data][product_data][name]" -> product.name
      ),
      Auth.bearer(Stripe.key)
    ) match {
      case Reply.Ok(reply) => Response.Redirect(Stripe.sessionUrl(reply.text))
      case other           => throw BadRequest(s"Stripe answered $other")
    }
  }
}
