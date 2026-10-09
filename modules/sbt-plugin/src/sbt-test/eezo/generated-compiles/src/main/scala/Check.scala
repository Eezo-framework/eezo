import io.eezo.generated.Routes

/** The assertion `assertTableMounts` runs.
  *
  * It is an application rather than a test suite because the thing under test is a source file the
  * generator wrote into this build, and the cheapest way to have both the compiler and the table
  * itself judge it is to compile it and call it.
  */
object Check {

  private val expected = Seq(
    "GET /",
    "GET /widgets",
    "GET /widgets/new",
    "GET /widgets/:id",
    "GET /widgets/:id/edit",
    "POST /widgets",
    "PUT /widgets/:id",
    "DELETE /widgets/:id"
  )

  def main(arguments: Array[String]): Unit = {
    val _        = arguments
    val mounted  = Routes.table().routes.map(_.describe)
    val missing  = expected.filterNot(mounted.contains)
    val surplus  = mounted.filterNot(expected.contains)

    if (missing.nonEmpty || surplus.nonEmpty)
      sys.error(
        s"the generated table mounts\n  ${mounted.mkString("\n  ")}\n" +
          s"missing:\n  ${missing.mkString("\n  ")}\nunexpected:\n  ${surplus.mkString("\n  ")}"
      )

    println(s"the generated table mounts ${mounted.size} routes, as declared")
  }
}
