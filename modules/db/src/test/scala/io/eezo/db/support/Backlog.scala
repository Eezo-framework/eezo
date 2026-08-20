package io.eezo.db.support

/** Marks a test that asserts behaviour eezo does not have yet.
  *
  * Run only the working suite:   sbt "db/testOnly -- --exclude-tags=backlog"
  * Run only the backlog:         sbt "db/testOnly *BacklogSuite"
  */
object Backlog extends munit.Tag("backlog")
