package models

import io.eezo.core.Id
import io.eezo.http.Form
import io.eezo.http.Resource

/** A candidate model, so the table has a derived line as well as a handwritten row and the model
  * scan is inside what the cache protects.
  */
case class Widget(id: Id[Widget], name: String) derives Form, Resource
