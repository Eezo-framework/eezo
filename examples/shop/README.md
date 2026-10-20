# shop: a small shop with Stripe

| | |
|---|---|
| artifact | `"io.eezo" %% "eezo"`, `"io.eezo" %% "eezo-auth"` and `"io.eezo" %% "eezo-upickle"` |
| entry trait | `io.eezo.EezoApp` |
| layers | database and http |

The shop from the Scala Days talk. Anybody browses the products on `/` and pays for one through
Stripe Checkout; the board at `/board` shows every sale as it is paid; a signed in admin keeps the
catalogue at `/products`. A program reads the same catalogue as JSON at `/api/products`, and writes
to it with an API token the admin issues at `/settings/token`.

`models/Product.scala` says most of that in one line, `derives Table, Form, Resource, Api`: the
table, the form, the seven pages and the five JSON routes. Its one guard declaration,
`User.guard.except(Action.Index, Action.Show)`, covers the pages and the JSON routes alike, so
reading is open to anybody and every write needs a user: a browser by its session, a program by its
API token.

The shop keeps its tables in a Postgres schema named `shop`, so it shares the dev database with the
other examples.

```bash
docker run -d --name eezo-pg -p 5442:5432 \
  -e POSTGRES_PASSWORD=postgres -e POSTGRES_DB=eezo postgres:17   # skip if it already runs
sbt publishLocalForExample      # once, at the repository root
cd examples
sbt "shop/run db migrate --apply"
```

The first user comes next, since there is no sign up page. `CreateUser.scala` reads the email and
the password from the environment:

```bash
SHOP_EMAIL=you@example.com SHOP_PASSWORD='a long password' sbt "shop/runMain CreateUser"
```

The server needs Stripe's two secrets, a test mode key and the webhook's signing secret, and reads
them when it boots:

```bash
export STRIPE_SECRET_KEY=sk_test_... STRIPE_WEBHOOK_SECRET=whsec_...
sbt shop/run                    # http://localhost:8080
```

`sbt shop/test` runs the shop's own suite against a Postgres of its own, started through Docker.

## Calling the shop from a program

A program gets an API token from the admin, once. Sign in at `/login`, follow "API token" in the
nav to `/settings/token`, and press "Issue a new API token". The reply page shows the token, and it
is the only time anybody sees it: the shop keeps the hash of it on the user's row and nothing else,
so a lost token cannot be shown again, only replaced. Issuing a new one replaces the last, and a
program still sending the old one is refused from then on; that is how a token is revoked.

The button posts a form carrying the CSRF token, like every form the shop serves, so another site
cannot make a signed in admin's browser issue one. Reloading the reply page posts again, which issues
a fresh token and revokes the one just shown.

Reading needs no token. The list is every product, each as its `id`, `name` and `price`, the
fields of the model:

```bash
curl -i localhost:8080/api/products
```

```text
HTTP/1.1 200 OK
Content-Type: application/json; charset=utf-8

[{"id":"2142e6f5-9771-4959-843e-23e66e78eafd","name":"Mug","price":9}]
```

Writing without a token is refused. The 401 carries `WWW-Authenticate: Bearer`, which tells the
program what to send, and a problem detail saying what was missing:

```bash
curl -i -X POST localhost:8080/api/products -d '{"name":"Cap","price":15}'
```

```text
HTTP/1.1 401 Unauthorized
Content-Type: application/problem+json
WWW-Authenticate: Bearer

{
  "type": "about:blank",
  "title": "Unauthorized",
  "status": 401,
  "detail": "this API route needs an API token issued to a user",
  "instance": "/api/products"
}
```

The same call with the token in the `Authorization` header creates the product. The shop chooses its
key, so the body names none, and the answer is the row as stored with its address in `Location`:

```bash
curl -i -X POST localhost:8080/api/products \
  -H "Authorization: Bearer $TOKEN" -d '{"name":"Cap","price":15}'
```

```text
HTTP/1.1 201 Created
Location: /api/products/9466b6d1-145f-42d0-9504-51ef2b66aef0
Content-Type: application/json; charset=utf-8

{"id":"9466b6d1-145f-42d0-9504-51ef2b66aef0","name":"Cap","price":15}
```

The admin pages read the same rows, so the cap is now in the admin list, which anybody may read.
The reply is the whole page in the shop's layout, title and nav included; the block after the call
is only its main content, spaced out to read:

```bash
curl localhost:8080/products
```

```html
<h1>All products</h1>
<table><thead><tr><th>Name</th><th>Price</th></tr></thead><tbody>
<tr><td><a href="/products/2142e6f5-9771-4959-843e-23e66e78eafd">Mug</a></td><td>9</td></tr>
<tr><td><a href="/products/9466b6d1-145f-42d0-9504-51ef2b66aef0">Cap</a></td><td>15</td></tr>
</tbody></table>
<p><a href="/products/new">New Product</a></p>
```

The other three routes work the same way: `GET /api/products/<id>` reads one product with no token,
and `PUT` and `DELETE` at the same address replace and remove it with one. Orders are not exposed:
there is no `/api/orders`.
