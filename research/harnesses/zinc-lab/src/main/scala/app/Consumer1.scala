package app
import model.*
object Consumer1:
  def run(u: User): String =
    summon[Codec[User]].encode(u) + summon[Table[User]].columns.mkString + summon[Form[User]].html + summon[Resource[User]].routes.mkString
