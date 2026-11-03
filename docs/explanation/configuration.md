# Configuration from one file

An application reads its own values from one committed file into one Scala type, and it starts
only when every value it needs is there. This page tells why it works that way. The rules are in
[the configuration reference](../reference/configuration.md#application-conf), and the steps are in
[configure the application](../how-to/configure-the-application.md).

The framework's own variables, `EEZO_SECRET` and the database ones, are not part of this. They
come from the environment as the [reference](../reference/configuration.md#environment) lists
them, and the file does not hold them.

## One file, committed with the code

The values are in `src/main/resources/application.conf`. The file is HOCON, and it goes into the
repository with the code. Boot reads the first `application.conf` on the classpath, which is the
application's own, as the one root file. It does not merge `reference.conf`, the system properties,
`-Dconfig.file`, or a file that a dependency ships. A file that an `include` in `application.conf`
names is also read. So a reviewer reads `application.conf` and every file it includes, and a
library adds a value only when the committed file includes it.

The build names the type that the file is read into:

```scala
eezoConfigClass := Some("shop.ShopConfig")
```

When the build names no type, boot reads nothing and a file in `src/main/resources` has no
effect.

## One type, with sections

The type is a case class. Its fields are the values, and a field whose type has a `Config` of its
own is a section: a block of the file under the field's name. This is the shop's:

```scala
final case class ShopConfig(
    stripe: Stripe,
    shopUrl: Url = Url.Absolute("http://localhost:8080"),
    createUser: CreateUserConfig
) derives Config
```

`stripe` and `createUser` are sections, and `shopUrl` is a value. The type decides what may be
absent. A field with a default takes the default, an `Option` field is `None`, and any other
absent field is missing. Only the type decides this. A `Boolean` that nobody set is missing, not
false, because a value that nobody stated is a deployment that forgot something.

Each section type occurs once in the tree, and a section has no default and is never an `Option`.
The reason is that code reads a section by its type, as the next sections show, so one type must
name one place in the file.

## The file names the variables

A value can be a literal in the file, or the file can feed it from the environment through a
substitution. The shop feeds each of its values from a variable:

```hocon
stripe {
  secretKey = ${?STRIPE_SECRET_KEY}
  webhookSecret = ${?STRIPE_WEBHOOK_SECRET}
}
```

A substitution is the only way that a variable gets to a value. eezo does not make a variable
name from a path, and it reads no variable because its name looks like the name of a field. The
file says which variable sets which path, so a reader finds the answer in the file and does
not have to know a naming rule. For the same reason, `eezo config` can list every variable an
application reads from the file alone, before one of them is set.

The question mark matters. With `${?VAR}`, an unset variable leaves the path to a literal that the
file writes before the substitution. When there is no such literal, the path is absent, and then
the type decides: the default, `None`, or missing.

A variable under `EEZO_` belongs to the framework, so the file cannot name one. A file that feeds
a path from `EEZO_` is refused, and the line tells the operator to name another variable.

## Boot refuses before anything starts

Boot reads the file when the application starts, with `sbt run` and no arguments or with
`eezo dev`. The read comes before the database layer builds a `Database`, before the drift check,
and before the server binds its port. When a value is missing or does not read, boot writes one
line for each offender to stderr and exits with code 1:

```text
[eezo] stripe.secretKey is missing: set STRIPE_SECRET_KEY, which application.conf names for it
[eezo] stripe.webhookSecret is missing: set STRIPE_WEBHOOK_SECRET, which application.conf names for it
```

Boot names every offender at once. A deployment that is fixed one value for each restart is
fixed slowly. Each line names a path and the variables that the file names for it, and never the
text that boot found, because that text can be a secret. There is no stack trace, no pool opens
and no port is bound, so an application that cannot start does not look half started.

The other commands do not read the values. `routes`, `config`, `help` and the `db` commands work
while a variable is unset.

## Reading a value

When the read succeeds, boot installs the value, and code reads it with `current`. It installs
each section under its own type too, so code that needs one section reads only that section. The
shop builds the header of its Stripe calls from the `stripe` section:

```scala
def authorization: (String, String) = Auth.bearer(Config[Stripe].current.secretKey.reveal)
```

`shopUrl` is not in a section, so the shop reads it from the whole type:

```scala
def returns: (String, String) = {
  val back = Config[ShopConfig].current.shopUrl
  ((back / "?paid").path, back.path)
}
```

The read is inside a function, not in a `val` of an object. A suite builds the route table with
nothing installed, and a `val` that reads `current` when the object loads fails there. `current`
before any install throws an exception that names `installed`.

## A test installs its own value

A suite does not read the file or the environment. It builds the value and makes it current for
one block with `installed`. The shop's Stripe suite installs a whole `ShopConfig`:

```scala
private val installed = ShopConfig(
  stripe = Stripe(secretKey = Redacted("sk_test_suite"), webhookSecret = Redacted("whsec_suite")),
  shopUrl = Url.Absolute("http://localhost:8080"),
  createUser = CreateUserConfig(email = None, password = None)
)

test("the Checkout call carries the installed key as its Bearer token") {
  Config[ShopConfig].installed(installed) {
    assertEquals(Stripe.authorization, "Authorization" -> "Bearer sk_test_suite")
  }
}
```

`Stripe.authorization` reads `Config[Stripe].current`, and the read finds the section of the
`ShopConfig` that the suite installed. After the block, the value that was current before is
current again, also when the block throws. So a developer who exported `STRIPE_SECRET_KEY` cannot
change what the suite proves.

## Redacted values

A `Redacted` holds text that the application must not leak, such as an API key. When something
prints it, the output is `Redacted(redacted)`. Code gets the text only through `reveal`, so each
place that needs the text shows it. Two `Redacted` values compare in constant time. The shop's
Stripe section holds two:

```scala
final case class Stripe(secretKey: Redacted, webhookSecret: Redacted) derives Config
```

A field of type `Redacted`, or an `Option` of one, has three rules. Each one is enforced where the
value could leak:

1. The file must not state it as a literal. A committed file is not a place for a secret. The
   compiler refuses the literal, `eezo config` refuses it, and boot refuses it, also when the file
   copies the text in from another path.
2. `eezo config` lists its variable as `redacted`, so a person or a script that sets up a
   deployment knows the value is a secret before they set it.
3. `eezo deploy` accepts the variable only from `fly secrets`. It refuses a deploy while the
   `[env]` table of `fly.toml` names the variable, also when `fly secrets` has it too, because
   `fly.toml` is committed.

The mark comes from the type of the field and from nothing else. The shop's `createUser.password`
is a `Password`, not a `Redacted`, so `eezo config` lists `SHOP_PASSWORD` as `plain`.

## The compiler, boot and `eezo config`

Three readers check the file, and each one checks what it can see.

The compiler sees the type and the file as the author wrote it, before any substitution is
resolved. It does not see the environment where the application will run. When the build names
the type, the plugin writes a source file that checks `application.conf` against it, and the
build fails when:

- a field has no `Parser` and is not a section, a section has a default or is an `Option`, or a
  section type occurs at two paths;
- a required field is neither stated nor fed by a substitution in the file. A `${?VAR}` counts as
  fed, whether or not the variable is set where the compiler runs;
- a `Redacted` field is stated as a literal;
- a section is stated as one value;
- the file does not parse.

An error about a field points at that field in the type. A file that does not parse is
reported at the generated source. An edit to `application.conf`, or to any `.conf`,
`.json` or `.properties` file under `src/main/resources`, compiles that check again. So does a
change to the type or to one of its sections.

Boot sees the environment, and it checks what the compiler leaves to it: an unset
variable, a value that its `Parser` does not read, a variable under `EEZO_`, a substitution that
does not resolve, and a `Redacted` value that does not come from a variable. It also refuses
when the file is missing. The compiler reads a missing file as an empty one, so a type whose
fields all have defaults compiles without it.

`eezo config` sees the file and never the environment. It refuses only what is wrong whatever the
environment holds: a missing file, a file that does not parse, a variable under `EEZO_`, and a
`Redacted` stated as a literal. It never reports a value as missing, because a deployment that has
no variables set yet is the case it is for. [The CLI reference](../reference/cli.md#commands)
gives its output.

## Where to go next

[Configure the application](../how-to/configure-the-application.md) does each step on the shop.
[The configuration reference](../reference/configuration.md#application-conf) has the rules of the
file and every line that boot prints. [Deploy](../how-to/deploy.md) tells what a deploy checks
before it builds.
