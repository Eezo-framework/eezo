package components

import io.eezo.core.html.*
import io.eezo.core.html.Tags.*
import io.eezo.live.{Component, Event, Init, Live}

/** The live-form demo: every M5 behavior on one page.
  *
  * The name and email validate per keystroke (debounced on the client), and the server echoes
  * each field's value back as its `value` attribute - which is exactly the echo that would eat
  * your text mid-word if the applier did not protect the focused element's value property. Type
  * fast and watch the caret stay put. The checkbox and select send committed changes; submit
  * sends the whole form as one payload; and the roster below is a keyed list whose shuffle
  * button emits a handful of `moveChild` patches, so the rows keep their scroll position and
  * identity instead of re-rendering.
  */
final case class SignupState(
    name: String,
    email: String,
    subscribed: Boolean,
    flavor: String,
    saved: Boolean,
    roster: Vector[String]
)

final class Signup extends Component[SignupState] {

  def init(ctx: Init[SignupState]): SignupState =
    SignupState(
      name = "",
      email = "",
      subscribed = false,
      flavor = "vanilla",
      saved = false,
      roster = Vector("Ada", "Grace", "Edsger", "Barbara", "Tony", "Robin", "Niklaus", "John")
    )

  def handle(event: Event, s: SignupState): SignupState = {
    def value = event.payload.getOrElse("value", "")
    event.name match {
      case "name-changed"  => s.copy(name = value, saved = false)
      case "email-changed" => s.copy(email = value, saved = false)
      case "subscribed"    => s.copy(subscribed = value == "on", saved = false)
      case "flavor-picked" => s.copy(flavor = value, saved = false)
      case "shuffle"       => s.copy(roster = scala.util.Random.shuffle(s.roster))
      case "save" =>
        s.copy(
          name = event.payload.getOrElse("name", ""),
          email = event.payload.getOrElse("email", ""),
          subscribed = event.payload.contains("subscribed"),
          flavor = event.payload.getOrElse("flavor", s.flavor),
          saved = true
        )
      case _ => s
    }
  }

  private def nameProblem(s: SignupState): Option[String] =
    Option.when(s.name.trim.isEmpty)("a name is required")

  private def emailProblem(s: SignupState): Option[String] =
    if (s.email.isEmpty) Some("an email is required")
    else Option.when(!s.email.contains("@"))("that is not an email address")

  def render(s: SignupState): Html =
    div(
      Attrs.style := "font-family: system-ui; max-width: 30rem; margin: 3rem auto",
      h1("live signup"),
      form(
        Live.onSubmit("save"),
        p(
          label("name ", input(
            Attrs.tpe   := "text",
            Attrs.name  := "name",
            Attrs.value := s.name,
            Live.onInput("name-changed")
          )),
          nameProblem(s).map(problem => em(" ", problem))
        ),
        p(
          label("email ", input(
            Attrs.tpe   := "text",
            Attrs.name  := "email",
            Attrs.value := s.email,
            Live.onInput("email-changed", debounceMillis = 150)
          )),
          emailProblem(s).map(problem => em(" ", problem))
        ),
        p(label(
          input(
            Attrs.tpe     := "checkbox",
            Attrs.name    := "subscribed",
            Attrs.checked := s.subscribed,
            Live.onChange("subscribed")
          ),
          " subscribe to the newsletter"
        )),
        p(label("flavor ", select(
          Attrs.name := "flavor",
          Live.onChange("flavor-picked"),
          Seq("vanilla", "chocolate", "pistachio").map { f =>
            option(Attrs.value := f, Attrs.selected := (f == s.flavor), f)
          }
        ))),
        p(button(Attrs.tpe := "submit", "save")),
        p(
          if (s.saved) strong("saved ✓")
          else if (nameProblem(s).isEmpty && emailProblem(s).isEmpty) em("ready to save")
          else em("fix the fields above")
        )
      ),
      h2("roster"),
      p(button(Live.onClick("shuffle"), "shuffle"), " — keyed rows move, they do not re-render"),
      ul(s.roster.map(person => li(Key(person), person)))
    )
}
