import io.eezo.http.Assets

/** The stylesheet is built by the build, not committed: this suite runs after `Compile / compile`,
  * so a stylesheet on the classpath here is one the plugin's resource generator wrote, by running
  * the Tailwind CLI over `tailwind.css` and the sources beside it.
  */
class TailwindSuite extends munit.FunSuite {

  test("the plugin built the stylesheet into the jar's assets/") {
    val bytes = Assets
      .read("tailwind.css")
      .getOrElse(fail("no assets/tailwind.css on the classpath: the build did not run Tailwind"))
    val css = new String(bytes, "UTF-8")
    // A class only the components use, so its presence means the sources were scanned and not
    // just the stylesheet compiled.
    assert(css.contains(".text-6xl"), "the counter's size class is missing from the stylesheet")
    // A rule from the base layer in tailwind.css, so the file itself was read too.
    assert(css.contains("button"), "the base layer is missing from the stylesheet")
  }

  test("the layout links the stylesheet under its versioned address") {
    assert(Assets.url("tailwind.css").path.startsWith("/assets/tailwind.css?v="))
  }
}
