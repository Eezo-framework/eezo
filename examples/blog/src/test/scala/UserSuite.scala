import io.eezo.core.html.Url
import models.User

/** Pins where a sign in that was not preceded by a refusal lands.
  *
  * A round trip through `/admin/login` would prove this the way `GuardSuite` proves the general
  * mechanism, but authenticating for real calls `User.byEmail`, which opens a Postgres connection
  * through `Table[User]`; this suite stays free of Docker, the network and any credential, so it
  * reads the guard's own `home` field instead of dispatching a request through it.
  */
class UserSuite extends munit.FunSuite {

  test("the guard's home is the page the blog mounts, not the mount root nothing answers") {
    val field = User.guard.getClass.getDeclaredField("home")
    field.setAccessible(true)
    assertEquals(field.get(User.guard), Url.Mounted("/posts"))
  }
}
