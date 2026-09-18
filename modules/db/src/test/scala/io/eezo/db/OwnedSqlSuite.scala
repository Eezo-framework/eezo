package io.eezo.db

import io.eezo.core.OwnerOf
import io.eezo.db.support.Memo

/** The four owner aware statements, rendered by `TableDef` and by nothing else.
  *
  * Pinned as text because the whole reason they live on `TableDef` is that no SQL for them is
  * composed inside `JdbcStore`: a `where` assembled at the call site is the one that forgets the
  * owner on the fourth statement, and nothing but reading the emitted string catches that.
  */
class OwnedSqlSuite extends munit.FunSuite {

  private val memos = Table[Memo].tableDef
  private val owner = memos.columns.find(_.name == "owner").get

  test("the ordered select puts the owner condition in front of the order by") {
    assertEquals(
      memos.selectAllOrderedByIdOwnedBy(owner),
      """select "id", "owner", "text" from "memo" where "owner" = ? order by "id""""
    )
  }

  test("select by id adds the owner to the key match") {
    assertEquals(
      memos.selectByIdOwnedBy(owner),
      """select "id", "owner", "text" from "memo" where "id" = ? and "owner" = ?"""
    )
  }

  test("update by id adds the owner to the key match, after every column it sets") {
    assertEquals(
      memos.updateByIdOwnedBy(owner),
      """update "memo" set "id" = ?, "owner" = ?, "text" = ? where "id" = ? and "owner" = ?"""
    )
  }

  test("delete by id adds the owner to the key match") {
    assertEquals(
      memos.deleteByIdOwnedBy(owner),
      """delete from "memo" where "id" = ? and "owner" = ?"""
    )
  }

  test("an owner field that is not a column of the table is refused when the store is built") {
    val thrown = intercept[IllegalStateException] {
      JdbcStore.owned(OwnerOf[Memo, String]("nobody", _.owner))
    }
    assert(thrown.getMessage.contains("memo"), thrown.getMessage)
    assert(thrown.getMessage.contains("nobody"), thrown.getMessage)
  }
}
