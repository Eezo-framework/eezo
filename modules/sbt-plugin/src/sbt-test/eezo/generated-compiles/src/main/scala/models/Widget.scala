package models

import io.eezo.core.Id
import io.eezo.http.Form
import io.eezo.http.Resource

/** One model with the two derivations the generated table's derived half depends on. */
case class Widget(id: Id[Widget], name: String, quantity: Int) derives Form, Resource
