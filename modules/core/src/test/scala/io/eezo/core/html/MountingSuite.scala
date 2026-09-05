package io.eezo.core.html

import io.eezo.core.html.Tags.*

/** What moving a rendered tree under a prefix does, and what it deliberately leaves alone. */
class MountingSuite extends munit.FunSuite {

  test("every mounted url in the tree takes the prefix, however deep it sits") {
    val page = div(
      p(a(Attrs.href := Url.Mounted("/posts"), "all")),
      form(Attrs.action := Url.Mounted("/posts"), input(Attrs.value := "x"))
    )

    assertEquals(
      page.under("/admin").render,
      """<div><p><a href="/admin/posts">all</a></p>""" +
        """<form action="/admin/posts"><input value="x"></form></div>"""
    )
  }

  test("an absolute url and a plain String are left where their author put them") {
    val page = p(
      a(Attrs.href := Url.Absolute("https://eezo.io"), "home"),
      a(Attrs.href := "/posts", "handwritten")
    )

    assertEquals(
      page.under("/admin").render,
      """<p><a href="https://eezo.io">home</a><a href="/posts">handwritten</a></p>"""
    )
  }

  test("mounting does not reach inside raw content") {
    val page = div(Html.raw("""<a href="/posts">raw</a>"""))
    assertEquals(page.under("/admin").render, """<div><a href="/posts">raw</a></div>""")
  }

  test("a fragment carries its children under the prefix too") {
    val page = a(Attrs.href := Url.Mounted("/a"), "a") ++ a(Attrs.href := Url.Mounted("/b"), "b")
    assertEquals(
      page.under("/admin").render,
      """<a href="/admin/a">a</a><a href="/admin/b">b</a>"""
    )
  }
}
