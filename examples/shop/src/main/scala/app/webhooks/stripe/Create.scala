package app.webhooks.stripe

import java.time.Instant

import io.eezo.core.Id
import io.eezo.db.*
import io.eezo.db.Scopes.transact
import io.eezo.http.{ApiRequest, Guarded, Response}

import components.Sales
import models.Order
import stripe.Stripe

object Create {
  given Guarded[Create.type] = Stripe.signed

  def create(request: ApiRequest): Response = {
    for (ref <- Stripe.completed(request.body)) transact {
      val orders = Table[Order]
      for (order <- orders.findById(Id[Order](ref))) {
        val sale = order.copy(paid = true, at = Instant.now())
        orders.update(sale)
        Sales.paid.publish(sale)
      }
    }
    Response.status(200)
  }
}
