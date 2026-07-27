package model

case class User(id: Int, name: String, email: String, active: Boolean, age: Int)
  derives Codec, Table, Form, Resource

case class Order(id: Int, sku: String, qty: Int, discount: Int, note: String)
