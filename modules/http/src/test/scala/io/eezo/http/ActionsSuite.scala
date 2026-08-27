package io.eezo.http

import io.eezo.core.Id

/** Which of the seven a model mounts.
  *
  * The default is all seven and it arrives by saying nothing, so the cases that matter are the two
  * combinators and the priority that lets a model's own `given` beat the default.
  */
class ActionsSuite extends munit.FunSuite {

  case class Widget(id: Id[Widget], name: String)

  case class ReadOnly(id: Id[ReadOnly], name: String)

  object ReadOnly {
    given Actions[ReadOnly] = Actions.only(Action.Index, Action.Show)
  }

  test("saying nothing mounts all seven") {
    assertEquals(summon[Actions[Widget]].allowed, Action.values.toSet)
  }

  test("a given in the model's companion beats the default") {
    assertEquals(summon[Actions[ReadOnly]].allowed, Set(Action.Index, Action.Show))
  }

  test("except subtracts, and everything else stays") {
    assertEquals(
      Actions.except[Widget](Action.Destroy).allowed,
      Action.values.toSet - Action.Destroy
    )
  }

  test("only keeps exactly what it names") {
    assertEquals(Actions.only[Widget](Action.Index).allowed, Set(Action.Index))
  }

  test("the seven are the seven, named by role") {
    assertEquals(
      Action.values.toSeq,
      Seq(
        Action.Index,
        Action.New,
        Action.Show,
        Action.Edit,
        Action.Create,
        Action.Update,
        Action.Destroy
      )
    )
  }
}
