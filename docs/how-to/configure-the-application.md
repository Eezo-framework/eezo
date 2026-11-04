# Configure the application

Give the application values of its own, such as an API key or a public address, from one
committed file and the environment. Each step below is the shop's code, from `examples/shop`.
[Configuration from one file](../explanation/configuration.md) tells why it works this way.

The framework's own variables, `EEZO_SECRET` and the database ones, stay where
[the configuration reference](../reference/configuration.md#environment) puts them. Do not put
them in the file.

## Declare a value

Write a case class in a named package, with one field for each value. A default is what the field
takes when the file gives no value:

```scala
final case class ShopConfig(
    stripe: Stripe,
    shopUrl: Url = Url.Absolute("http://localhost:8080"),
    createUser: CreateUserConfig
) derives Config
```

Name the class in the build, fully qualified:

```scala
eezoConfigClass := Some("shop.ShopConfig")
```

Then write `src/main/resources/application.conf`. From now on, boot reads the file before the
application starts, and it refuses to start while the file is missing.

## Feed it from a variable

Feed each value from a variable with `${?VAR}`. The file and not the field name says which
variable it is:

```hocon
# Unset on a laptop, where the class's default, http://localhost:8080, applies.
shopUrl = ${?SHOP_URL}
```

With `SHOP_URL` unset, `shopUrl` takes its default. A field with no default that is not an
`Option` is required. When the file neither states it nor feeds it, the build fails and the error
points at the field. When the file feeds it and the variable is unset, boot refuses to start and
names the variable. To make a value optional, give it an `Option` type. The shop's operator job reads two optional values:

```scala
final case class CreateUserConfig(email: Option[String], password: Option[Password]) derives Config
```

```hocon
createUser {
  email = ${?SHOP_EMAIL}
  password = ${?SHOP_PASSWORD}
}
```

Do not name a variable that starts with `EEZO_`. That prefix is the framework's, and boot refuses
it.

## Add a section

A section is a field whose type has a `Config` of its own. Its values go in a block under the
field's name. The shop keeps its two Stripe values in one:

```scala
final case class Stripe(secretKey: Redacted, webhookSecret: Redacted) derives Config
```

```hocon
stripe {
  secretKey = ${?STRIPE_SECRET_KEY}
  webhookSecret = ${?STRIPE_WEBHOOK_SECRET}
}
```

Give a section no default and do not make it an `Option`: give defaults to its fields. Use each
section type at one path only. Make a secret a `Redacted`, and always feed it from a variable,
because the build refuses a `Redacted` that the file states as a literal.

## Read it in a handler

Read a section with `Config[A].current`, where `A` is the section's type:

```scala
def authorization: (String, String) = Auth.bearer(Config[Stripe].current.secretKey.reveal)
```

Read a value that is not in a section from the whole class:

```scala
def returns: (String, String) = {
  val back = Config[ShopConfig].current.shopUrl
  ((back / "?paid").path, back.path)
}
```

Read inside a function or a handler, not in a `val` of an object. A suite builds the route table
with no value installed, and a `val` that reads `current` fails there.

## Install it in a suite

Build the whole class in the suite and make it current for one block with `installed`. Use test
values, never a real key:

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

`Stripe.authorization` reads `Config[Stripe].current`, and it gets the section of the class that
the suite installed. Change one value with `copy`, as the shop's suite does for `shopUrl`.

## Check it with `eezo config`

List the variables the file names. The command reads the file and no environment, so you can run
it before you set a single variable:

```
$ eezo config
STRIPE_SECRET_KEY	stripe.secretKey	redacted	required
STRIPE_WEBHOOK_SECRET	stripe.webhookSecret	redacted	required
SHOP_URL	shopUrl	plain	defaulted
SHOP_EMAIL	createUser.email	plain	optional
SHOP_PASSWORD	createUser.password	plain	optional
```

[The CLI reference](../reference/cli.md#commands) gives the format of each line. `redacted` and
`required` come from the class alone. `SHOP_PASSWORD` is `plain`, because only a `Redacted` field
is listed as `redacted`, and `createUser.password` is a `Password`.

When the file names a variable under `EEZO_` or states a `Redacted` as a literal, the command
lists nothing, prints one line for each offender and exits with code 1.

## Deploy it

Set each `redacted` variable the list shows, here `STRIPE_SECRET_KEY` and
`STRIPE_WEBHOOK_SECRET`, with `fly secrets set`, as [Deploy](deploy.md#secrets) does for
`EEZO_SECRET`. A `plain` variable can be a Fly secret too, or go in the `[env]` table of
`fly.toml`. Then run `eezo deploy`. Before it builds, the deploy runs `eezo config` and stops when
a required variable is in neither place, or when `[env]` names a redacted one.
[Deployment files](../reference/deploy-files.md#what-is-never-written) tells where each variable
goes on Fly, and [the CLI reference](../reference/cli.md#commands) gives the full rule of the
deploy.
