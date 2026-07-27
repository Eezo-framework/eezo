package app
import model.*
import model.given
object OrderUse:
  def run(o: Order): String = summon[Codec[Order]].encode(o) + summon[Table[Order]].columns.mkString(",")
