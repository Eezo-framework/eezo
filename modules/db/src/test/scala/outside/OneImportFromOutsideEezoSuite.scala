package outside

import io.eezo.db.*
import io.eezo.db.Scopes.*
import io.eezo.db.support.DbSuite

class OneImportFromOutsideEezoSuite extends DbSuite {

  private def needsTx()(using Tx): String = "wrote"
  private def needsDb()(using DB): String = "read"

  test("a method that takes `using Tx` runs inside `transact`") {
    assertEquals(transact { needsTx() }, "wrote")
  }

  test("a method that takes `using DB` runs inside `read`") {
    assertEquals(read { needsDb() }, "read")
  }
}
