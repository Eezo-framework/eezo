package io.eezo.sbt.dev

import java.util.concurrent.LinkedBlockingQueue

/** The queue the loop reads, as two children of one session feed it, an old one still draining its
  * pipes while the next one starts.
  */
class HandoverSuite extends munit.FunSuite {

  private val port   = ConfigRow("SHOP_PORT", "port", redacted = false, "required", set = false)
  private val report = ConfigReport(
    Vector(port),
    Vector(
      ConfigOffender(
        ConfigOffender.Missing,
        "port",
        Vector("SHOP_PORT"),
        Vector.empty,
        "port is missing"
      )
    )
  )

  private def queued(queue: LinkedBlockingQueue[Event]): Vector[Event] = {
    val out = Vector.newBuilder[Event]
    queue.forEach(e => out += e)
    out.result()
  }

  test("a line the old child's pipe held back never lands after the next child's start") {
    val queue    = new LinkedBlockingQueue[Event]
    val handover = new Handover(queue)
    val old      = new Object
    val next     = new Object
    handover.swap(old, Event.AppStarted("Main"))
    handover.swap(next, Event.AppStarted("Main"))
    handover.from(old)(Vector(Event.ConfigReported(report)))
    handover.from(next)(Vector(Event.Serving("http://localhost:8080")))
    val events = queued(queue)
    assertEquals(
      events,
      Vector[Event](
        Event.AppStarted("Main"),
        Event.AppStarted("Main"),
        Event.Serving("http://localhost:8080")
      )
    )
    val shown = Update(Model.initial("todo", 80, 24, now = 1000L), Event.ConfigReported(report))._1
    assert(shown.tabs.contains(Tab.Config), shown.tabs.toString)
    val served = events.foldLeft(shown)((m, e) => Update(m, e)._1)
    assertEquals(served.config, None)
    assert(!served.tabs.contains(Tab.Config), served.tabs.toString)
  }

  test("what the old child printed before the next one started stays, ahead of the start") {
    val queue    = new LinkedBlockingQueue[Event]
    val handover = new Handover(queue)
    val old      = new Object
    handover.swap(old, Event.AppStarted("Main"))
    handover.from(old)(Vector(Event.AppLine("last words", err = false)))
    handover.swap(new Object, Event.AppStarted("Main"))
    assertEquals(
      queued(queue),
      Vector[Event](
        Event.AppStarted("Main"),
        Event.AppLine("last words", err = false),
        Event.AppStarted("Main")
      )
    )
  }

  test("a log line the old child's pipe held back still reaches the log, its state does not") {
    val queue    = new LinkedBlockingQueue[Event]
    val handover = new Handover(queue)
    val old      = new Object
    handover.swap(old, Event.AppStarted("Main"))
    handover.swap(new Object, Event.AppStarted("Main"))
    handover.from(old)(
      Vector(
        Event.AppLine("at Main.shutdown(Main.scala:12)", err = true),
        Event.ConfigReported(report)
      )
    )
    assertEquals(
      queued(queue),
      Vector[Event](
        Event.AppStarted("Main"),
        Event.AppStarted("Main"),
        Event.AppLine("at Main.shutdown(Main.scala:12)", err = true)
      )
    )
  }

  private def marker(set: Boolean): String =
    LineProtocol.ConfigMarker +
      s"""{"variables":[{"variable":"SHOP_PORT","path":"port","redacted":false,"need":"required","set":$set}],"offenders":[]}"""

  private def reported(set: Boolean): Event =
    Event.ConfigReported(
      ConfigReport(
        Vector(ConfigRow("SHOP_PORT", "port", redacted = false, "required", set = set)),
        Vector.empty
      )
    )

  test(
    "the old child's pipes drop its config marker once the next child starts, the new one's pass"
  ) {
    val queue    = new LinkedBlockingQueue[Event]
    val handover = new Handover(queue)
    val old      = new Object
    val next     = new Object
    handover.swap(old, Event.AppStarted("Main"))
    val oldPipes = handover.pipes(old)
    handover.swap(next, Event.AppStarted("Main"))
    val nextPipes = handover.pipes(next)
    oldPipes.stdout(marker(set = false))
    oldPipes.stderr("at Main.shutdown(Main.scala:12)")
    oldPipes.flush()
    nextPipes.stdout(marker(set = true))
    assertEquals(
      queued(queue),
      Vector[Event](
        Event.AppStarted("Main"),
        Event.AppStarted("Main"),
        Event.AppLine("at Main.shutdown(Main.scala:12)", err = true),
        reported(set = true)
      )
    )
  }

  test("a child's pipes pass its config marker while it is the current child") {
    val queue    = new LinkedBlockingQueue[Event]
    val handover = new Handover(queue)
    val only     = new Object
    handover.swap(only, Event.AppStarted("Main"))
    handover.pipes(only).stdout(marker(set = false))
    assertEquals(queued(queue), Vector[Event](Event.AppStarted("Main"), reported(set = false)))
  }
}
