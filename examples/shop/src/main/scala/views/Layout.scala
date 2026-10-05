package views

import io.eezo.core.html.*
import io.eezo.http.Request

/** The shop's frame: the nav and the stylesheet around every page, the derived admin pages, the
  * login page and the error page included. Named in `Main`. A link appears in the nav in the step
  * that mounts its route: admin and sign out at step 1, board at step 5.
  */
object Layout extends io.eezo.http.Layout {

  /** Element selectors only, so the pages typed on stage carry no class names: the nav and its
    * sign out form, the product list and its Buy buttons, the tables, forms and definition lists of
    * the derived admin pages, the login page, the error page, and the board's figure, which is the
    * one heading that sits inside a div.
    */
  private val stylesheet =
    """* { box-sizing: border-box }
      |body { margin: 0; padding: 0 1.5rem 4rem; background: #f6f5f1; color: #1c1c1c;
      |       font: 1.125rem/1.5 system-ui, sans-serif }
      |a { color: #0f766e }
      |nav { max-width: 44rem; margin: 0 auto; padding: 1.25rem 0; display: flex; align-items: center;
      |      gap: 1.5rem; border-bottom: 1px solid #e3e0d8 }
      |nav a { color: #1c1c1c; text-decoration: none; font-weight: 500 }
      |nav a:first-child { font-size: 1.5rem; font-weight: 700; margin-right: auto }
      |nav form { margin: 0 }
      |nav button { background: none; border: 0; padding: 0; color: #1c1c1c; font: inherit; font-weight: 500 }
      |nav a:hover, nav button:hover { color: #0f766e }
      |main { max-width: 44rem; margin: 2.5rem auto 0 }
      |form { max-width: 26rem; margin: 0 0 1.25rem }
      |h1 { margin: 0 0 1.5rem; font-size: 2.25rem; letter-spacing: -.02em }
      |main div h1 { font-size: 3.5rem; color: #0f766e }
      |p { margin: 0 0 1.25rem }
      |ul { margin: 0; padding: 0; list-style: none; background: #fff; border: 1px solid #e3e0d8;
      |     border-radius: .75rem }
      |li { display: flex; gap: 1rem; align-items: center; padding: .9rem 1.25rem;
      |     border-top: 1px solid #e3e0d8 }
      |li:first-child { border-top: 0 }
      |li form { margin: 0 0 0 auto }
      |button { padding: .45rem 1.1rem; border: 0; border-radius: .5rem; background: #0f766e;
      |         color: #fff; font: inherit; font-weight: 500; cursor: pointer }
      |button:hover { background: #115e59 }
      |table { width: 100%; margin-bottom: 1.5rem; border-collapse: separate; border-spacing: 0;
      |        background: #fff; border: 1px solid #e3e0d8; border-radius: .75rem; overflow: hidden }
      |th, td { padding: .75rem 1.25rem; text-align: left }
      |th { font-size: .875rem; font-weight: 600; letter-spacing: .05em; text-transform: uppercase;
      |     color: #6b6860; background: #faf9f6 }
      |td { border-top: 1px solid #e3e0d8 }
      |tbody tr:nth-child(even) td { background: #faf9f6 }
      |dl { display: grid; grid-template-columns: max-content 1fr; gap: .5rem 1.5rem; margin: 0 0 1.5rem;
      |     padding: 1.25rem; background: #fff; border: 1px solid #e3e0d8; border-radius: .75rem }
      |dt { font-weight: 600; color: #6b6860 }
      |dd { margin: 0 }
      |form div { margin-bottom: 1.25rem }
      |label { display: block; margin-bottom: .35rem; font-weight: 600 }
      |input:not([type=checkbox]) { width: 100%; padding: .55rem .8rem; border: 1px solid #c9c5bb;
      |                             border-radius: .5rem; background: #fff; font: inherit }
      |input:focus { outline: 2px solid #0f766e; outline-offset: 1px; border-color: #0f766e }
      |.error { color: #b42318; font-weight: 500 }""".stripMargin

  def apply(request: Request, title: Option[String], content: Html): Html =
    html(
      head(
        meta(Attrs.charset := "utf-8"),
        Tags.title(title.getOrElse("the shop")),
        style(stylesheet)
      ),
      body(
        nav(a(Attrs.href := "/", "shop")),
        main(content)
      )
    )
}
