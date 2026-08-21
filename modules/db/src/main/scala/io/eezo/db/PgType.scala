package io.eezo.db

enum PgType {
  case Text
  case Varchar(n: Int)
  case Int4
  case Int8
  case Bool
  case Numeric(precision: Int, scale: Int)
  case Uuid
  case Date
  case Timestamptz
  case Bytea
  case Jsonb

  def render: String = this match {
    case Text          => "text"
    case Varchar(n)    => s"varchar($n)"
    case Int4          => "integer"
    case Int8          => "bigint"
    case Bool          => "boolean"
    case Numeric(p, s) => s"numeric($p,$s)"
    case Uuid          => "uuid"
    case Date          => "date"
    case Timestamptz   => "timestamptz"
    case Bytea         => "bytea"
    case Jsonb         => "jsonb"
  }
}
