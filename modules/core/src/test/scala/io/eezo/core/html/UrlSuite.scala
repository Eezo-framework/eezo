package io.eezo.core.html

/** What a [[Url]] is as a value, before any renderer or any mount has looked at it. */
class UrlSuite extends munit.FunSuite {

  test("a mounted url normalises its payload, so one intention written three ways is one value") {
    assertEquals(Url.Mounted("posts"), Url.Mounted("/posts"))
    assertEquals(Url.Mounted("/posts/"), Url.Mounted("/posts"))
    assertEquals(Url.Mounted("//posts//"), Url.Mounted("/posts"))
    assertEquals(Url.Mounted("posts").path, "/posts")
  }

  test("appending a segment doubles no slash and drops none") {
    assertEquals((Url.Mounted("/posts") / "new").path, "/posts/new")
    assertEquals((Url.Mounted("/posts/") / "/new").path, "/posts/new")
    assertEquals((Url.Mounted("/") / "posts").path, "/posts")
    assertEquals((Url.Absolute("https://eezo.io") / "docs").path, "https://eezo.io/docs")
  }

  test("an absolute url keeps its payload verbatim, since it need not be a path at all") {
    assertEquals(Url.Absolute("https://eezo.io/docs").path, "https://eezo.io/docs")
    assertEquals(Url.Absolute("mailto:hi@eezo.io").path, "mailto:hi@eezo.io")
  }

  test("mounting prepends the prefix to a mounted url and leaves an absolute one alone") {
    assertEquals(Url.Mounted("/posts").under("/admin"), Url.Mounted("/admin/posts"))
    assertEquals(Url.Mounted("/posts").under("admin/"), Url.Mounted("/admin/posts"))
    assertEquals(Url.Mounted("/posts").under("/v1").under("/admin"), Url.Mounted("/admin/v1/posts"))
    assertEquals(Url.Absolute("/posts").under("/admin"), Url.Absolute("/posts"))
  }

  test("mounting normalises the result, so a root url under a prefix leaves no trailing slash") {
    assertEquals(Url.Mounted("/").under("/admin"), Url.Mounted("/admin"))
    assertEquals(Url.Mounted("/").under("/admin").path, "/admin")
    assertEquals(Url.Mounted("/").under("/"), Url.Mounted("/"))
    assertEquals(Url.Mounted("/posts").under("//admin//"), Url.Mounted("/admin/posts"))
  }

  test(
    "normalising a mounted url collapses doubled slashes only in the path, not in the query or fragment"
  ) {
    assertEquals(
      Url.Mounted("/oauth?redirect_uri=https://app.example.com/cb").path,
      "/oauth?redirect_uri=https://app.example.com/cb"
    )
    assertEquals(Url.Mounted("/login?next=//host/x").path, "/login?next=//host/x")
    assertEquals(Url.Mounted("/posts#a//b").path, "/posts#a//b")
    assertEquals(
      Url.Mounted("/posts?q=a//b").under("/admin").path,
      "/admin/posts?q=a//b"
    )
  }
}
