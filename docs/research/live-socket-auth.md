# How other frameworks authenticate a live socket and check its origin

This document answers wayfinder ticket [#194](https://github.com/Eezo-framework/eezo/issues/194). It surveys how Phoenix LiveView, Rails Action Cable with Hotwire Turbo Streams, Laravel (Livewire, Echo and Reverb), and ASP.NET Core (SignalR and Blazor Server) authenticate the WebSocket behind a server rendered live page, and what each does with the `Origin` header. For each it answers the same four questions: how the identity of the HTTP request that rendered the page reaches the socket, what happens when the session expires or is revoked while the socket is open, whether a missing `Origin` is accepted, and how allowed origins are configured. A comparison table and a short reading of eezo's current code against the field close the document. Every claim links to a primary source (official docs or framework source on GitHub); where a claim is inferred from code and not stated by the project it is flagged in place and again in the last section. The survey was done on 2026-09-21 against the default branch of each repository on that date (Rails against `8-1-stable`, Laravel framework and docs against `13.x`). It makes no recommendation for eezo.

## Phoenix LiveView

### How identity reaches the socket

Both mechanisms, with distinct jobs. The session cookie travels on the upgrade request, and a signed token rendered into the page travels in the join message.

The cookie half: the LiveView socket is declared with `connect_info: [session: @session_options]`, the same options given to `Plug.Session` ([Phoenix.LiveView.Socket](https://github.com/phoenixframework/phoenix_live_view/blob/main/lib/phoenix_live_view/socket.ex)). At upgrade the transport reads the session cookie with the configured store and hands the decoded session to the socket, but only when a CSRF token rides along as the `_csrf_token` connect parameter and validates against the session's CSRF state. If the cookie is absent or the CSRF token fails, the session is `nil`, not an error ([`connect_session` and `csrf_token_valid?` in transport.ex](https://github.com/phoenixframework/phoenix/blob/main/lib/phoenix/socket/transport.ex#L515-L575), [endpoint docs, "Connect info"](https://github.com/phoenixframework/phoenix/blob/main/lib/phoenix/endpoint.ex#L965-L975)). The client reads the token from a `<meta name="csrf-token">` tag and passes it as `params: {_csrf_token: csrfToken}`; LiveView's own diagnostic spells out all six setup steps ([channel.ex](https://github.com/phoenixframework/phoenix_live_view/blob/main/lib/phoenix_live_view/channel.ex#L1180-L1215)). A LiveView join whose `connect_info` session is `nil` is refused with reason `"stale"` (same source).

The signed token half: the static render signs a `phx_session` token with `Phoenix.Token` and embeds it in the page as `data-phx-session`. The token carries the view module, the socket id, the router, the `live_session` name and the extra `session:` map given to `live_render`, not the cookie session ([`sign_root_session` in static.ex](https://github.com/phoenixframework/phoenix_live_view/blob/main/lib/phoenix_live_view/static.ex#L357-L375)). At join the channel verifies it and then merges the cookie session from `connect_info` beneath it ([channel.ex `mount`](https://github.com/phoenixframework/phoenix_live_view/blob/main/lib/phoenix_live_view/channel.ex#L1173-L1220)). So the signed token proves "this server rendered this view for this page", and the cookie proves "this browser holds this session". The token's maximum age is fixed at two weeks (`@max_session_age 1_209_600`, [static.ex](https://github.com/phoenixframework/phoenix_live_view/blob/main/lib/phoenix_live_view/static.ex#L17-L37)).

The user is then resolved a second time, on the socket, from the session: the security guide states that "any session validation must happen both in the HTTP request (plug pipeline) and the stateful connection (LiveView mount)", and the idiom is an `on_mount` hook under `live_session` that reads `user_token` from the session and loads the user ([security model](https://github.com/phoenixframework/phoenix_live_view/blob/main/guides/server/security-model.md)).

Plain Phoenix channels also offer an `auth_token` option that carries a token in the `Sec-WebSocket-Protocol` header, for clients without cookies ([endpoint docs](https://github.com/phoenixframework/phoenix/blob/main/lib/phoenix/endpoint.ex#L858-L866)).

### Session expiry while the socket is open

Nothing happens by itself. The guide says: "because LiveView is a permanent connection between client and server, even when a user is logged out or removed from the system, this change won't reflect on the LiveView part unless the user reloads the page" ([security model, "Disconnecting all instances of a live user"](https://github.com/phoenixframework/phoenix_live_view/blob/main/guides/server/security-model.md#disconnecting-all-instances-of-a-live-user)). The remedy is explicit: the login code puts a `live_socket_id` in the session, `Phoenix.LiveView.Socket.id/1` returns it as the socket's id ([socket.ex](https://github.com/phoenixframework/phoenix_live_view/blob/main/lib/phoenix_live_view/socket.ex#L103)), and logout broadcasts `"disconnect"` on that topic. The client then reconnects, `mount/3` runs again against the now empty session, fails, and redirects. The guide notes that `mix phx.gen.auth` generates these lines. There is no timer that reads the session again during a connection.

### Missing Origin

Accepted. `check_origin/5` opens with `is_nil(origin) or check_origin == false -> conn`, and its doc says the connection passes "if the origin header matches the allowed origins, no origin header was sent or no origin was configured" ([transport.ex](https://github.com/phoenixframework/phoenix/blob/main/lib/phoenix/socket/transport.ex#L332-L387)). A present and disallowed origin gets a 403 and an error log that tells the developer how to fix the config.

### How allowed origins are configured

`check_origin` on the endpoint (default `true`) or per transport. `true` compares the origin's host with the configured `url: [host: ...]`, ignoring scheme and port. A list allows explicit origins, each with optional scheme and port and `*.` wildcards (`"https://example.com"`, `"//another.com:888"`, `"//*.other.com"`). `:conn` accepts any origin whose scheme, host and port equal the request's own. An MFA tuple is a custom predicate. `false` disables the check ([endpoint docs](https://github.com/phoenixframework/phoenix/blob/main/lib/phoenix/endpoint.ex#L901-L938), [`origin_allowed?`](https://github.com/phoenixframework/phoenix/blob/main/lib/phoenix/socket/transport.ex#L621-L661)).

Phoenix treats origin and CSRF as two defences of which one must stand: "To avoid Cross-Site WebSocket Hijacking, you must have at least one of `check_origin` and `check_csrf` enabled. If you set both to `false`, Phoenix will raise" (same endpoint docs). This is why accepting a missing `Origin` is safe there: a client that omits the header still cannot obtain the session without a CSRF token that matches it.

## Rails: Action Cable and Hotwire Turbo Streams

### How identity reaches the socket

The cookie on the upgrade, and nothing else, for the connection. The overview guide: "The WebSocket server doesn't have access to the session, but it has access to the cookies" ([guide, Notes](https://github.com/rails/rails/blob/8-1-stable/guides/source/action_cable_overview.md#notes)). The app writes `ApplicationCable::Connection#connect`, reads a signed or encrypted cookie, sets an `identified_by` attribute, or calls `reject_unauthorized_connection` ([guide, Connection Setup](https://github.com/rails/rails/blob/8-1-stable/guides/source/action_cable_overview.md#connection-setup)). The Rails 8 authentication generator emits exactly this, resolving the same server side `Session` row the HTTP side uses:

```ruby
def connect
  set_current_user || reject_unauthorized_connection
end

def set_current_user
  if session = Session.find_by(id: cookies.signed[:session_id])
    self.current_user = session.user
  end
end
```
([connection.rb.tt](https://github.com/rails/rails/blob/8-1-stable/railties/lib/rails/generators/rails/authentication/templates/app/channels/application_cable/connection.rb.tt))

No CSRF token is involved in the upgrade. The defence against cross site use of the cookie is the origin check alone, which is why Rails is strict about it (below).

Hotwire adds a signed value in the page, but for authorization of a stream and not for identity. `turbo_stream_from` renders a `signed_stream_name`; `Turbo::StreamsChannel#subscribed` verifies it and rejects the subscription otherwise. The source comment: "Since stream names are exposed directly to the user via the HTML stream subscription tags, we need to ensure that the name isn't tampered with, so the names are signed upon generation and verified upon receipt" ([stream_name.rb](https://github.com/hotwired/turbo-rails/blob/main/app/channels/turbo/streams/stream_name.rb), [streams_channel.rb](https://github.com/hotwired/turbo-rails/blob/main/app/channels/turbo/streams_channel.rb)). The key derives from the app's key generator ([engine.rb](https://github.com/hotwired/turbo-rails/blob/main/lib/turbo/engine.rb#L87-L90)). The signed name is generated with no expiry argument and is not bound to a user, so anyone holding it may subscribe; turbo-rails points apps that need more at a custom channel with a `subscription_allowed?` check (same channel source). Whether the socket has a user at all is still Action Cable's `connect`.

### Session expiry while the socket is open

Nothing automatic. Identity is fixed at `connect`. Revocation is an explicit server call: `ActionCable.server.remote_connections.where(current_user: user).disconnect`, which reaches "all the connections established for" that user "across all servers running on all machines"; by default the client is told to reconnect, and `connect` then runs again against the current cookie ([remote_connections.rb](https://github.com/rails/rails/blob/8-1-stable/actioncable/lib/action_cable/remote_connections.rb)). The guide motivates `identified_by` with exactly this: to "potentially disconnect them all if the user is deleted or unauthorized" ([guide](https://github.com/rails/rails/blob/8-1-stable/guides/source/action_cable_overview.md#connection-setup)).

### Missing Origin

Refused, by reading the code. `allow_request_origin?` passes only if `HTTP_ORIGIN` equals `"#{proto}://#{HTTP_HOST}"` or matches an entry of `allowed_request_origins` with `===`; a `nil` origin satisfies neither, so the method logs "Request origin not allowed" and returns false ([connection/base.rb](https://github.com/rails/rails/blob/8-1-stable/actioncable/lib/action_cable/connection/base.rb#L228-L240)). The only way to admit clients without the header is `disable_request_forgery_protection = true`, which turns the check off for everyone. The guide does not discuss the missing header case.

### How allowed origins are configured

Two settings. `allow_same_origin_as_host` defaults to `true` and compares scheme and host together ([configuration.rb](https://github.com/rails/rails/blob/8-1-stable/actioncable/lib/action_cable/server/configuration.rb#L29)). `config.action_cable.allowed_request_origins` takes strings or regular expressions: `["https://rubyonrails.com", %r{http://ruby.*}]` ([guide, Allowed Request Origins](https://github.com/rails/rails/blob/8-1-stable/guides/source/action_cable_overview.md#allowed-request-origins)). In development it defaults to `/https?:\/\/localhost:\d+/` ([engine.rb](https://github.com/rails/rails/blob/8-1-stable/actioncable/lib/action_cable/engine.rb#L45)).

## Laravel: Livewire, Echo and Reverb

### How identity reaches the socket

It does not, because Laravel's live page has no socket of its own. Livewire talks to the server with ordinary `fetch` requests to an update route that is forced into the `web` middleware group; the source comment reads "Without it, CSRF protection is lost entirely on the update endpoint" ([HandleRequests.php](https://github.com/livewire/livewire/blob/main/src/Mechanisms/HandleRequests/HandleRequests.php#L97-L109)). Each update is therefore authenticated like any POST: session cookie plus CSRF token. Two Livewire additions matter. The component snapshot in the page is protected by an HMAC SHA256 checksum keyed with the app key, and a mismatch throws `CorruptComponentPayloadException` ([Checksum.php](https://github.com/livewire/livewire/blob/main/src/Mechanisms/HandleComponents/Checksum.php), [security docs](https://livewire.laravel.com/docs/security)). And "persistent middleware", which by default includes `Authenticate` and `Authorize`: "If any of the above middlewares are applied to the initial page-load, they will be persisted (re-applied) to any future network requests" ([security docs](https://livewire.laravel.com/docs/security)).

Server push is a separate system, Laravel Echo over the Pusher protocol, served by Reverb. There the WebSocket connection itself is anonymous, and identity arrives per channel through a side HTTP request. For a private channel the client calls `/broadcasting/auth`, which Laravel registers under the `web` middleware group, so the session cookie identifies the user; the channel callback in `routes/channels.php` receives "the currently authenticated user" and returns a boolean ([broadcasting docs, Authorizing Channels](https://github.com/laravel/docs/blob/13.x/broadcasting.md#authorizing-channels), [BroadcastManager.php](https://github.com/laravel/framework/blob/13.x/src/Illuminate/Broadcasting/BroadcastManager.php#L86-L90)). On success the app returns an HMAC SHA256 signature over `socket_id:channel_name` made with the Reverb app secret, and Reverb verifies it with `hash_equals` before subscribing the connection ([InteractsWithPrivateChannels.php](https://github.com/laravel/reverb/blob/main/src/Protocols/Pusher/Channels/Concerns/InteractsWithPrivateChannels.php)). This is a signed token scoped to one socket and one channel, minted by an authenticated HTTP request. The socket server never sees the cookie.

### Session expiry while the socket is open

For Livewire the question dissolves: every update is a fresh HTTP request, the `Authenticate` middleware is applied again, and an expired session fails the next interaction. For Reverb, a subscription once signed stays subscribed; the signature carries no expiry (see the source above, which signs only the socket id, the channel name and optional channel data). Neither the Reverb docs nor the broadcasting docs describe a revocation mechanism tied to the session. Not verified beyond that absence.

### Missing Origin

With the shipped default, accepted, because everything is: `config/reverb.php` ships `'allowed_origins' => ['*']` ([config](https://github.com/laravel/reverb/blob/main/config/reverb.php#L85)). With a restricted list, a missing header appears to be refused: `verifyOrigin` takes `parse_url($connection->origin(), PHP_URL_HOST)`, tests it against each pattern with `Str::is`, and throws `InvalidOrigin` when nothing matches, with no branch for an absent header ([Server.php](https://github.com/laravel/reverb/blob/main/src/Protocols/Pusher/Server.php#L190-L207)). This is my reading of the code; the docs do not state it.

### How allowed origins are configured

`allowed_origins` per app in `config/reverb.php`, matched on host only with `Str::is` wildcards, for example `['laravel.com']`; "Any requests from an origin not listed in your allowed origins will be rejected. You may allow all origins using `*`" ([Reverb docs, Allowed Origins](https://github.com/laravel/docs/blob/13.x/reverb.md#allowed-origins)). Scheme and port are not compared, since only `PHP_URL_HOST` is extracted.

## ASP.NET Core: SignalR and Blazor Server

### How identity reaches the socket

The cookie on the upgrade, for browser apps. "In a browser-based app, cookie authentication allows existing user credentials to automatically flow to SignalR connections. When the browser client is used, no extra configuration is needed" ([SignalR authn and authz, Cookie authentication](https://github.com/dotnet/AspNetCore.Docs/blob/main/aspnetcore/signalr/authn-and-authz.md#cookie-authentication)). The alternative is a bearer token, which for browser WebSockets has to ride in the query string as `access_token`, because "browsers can't set custom headers, such as `Authorization`, on the WebSocket and Server-Sent Events APIs"; the docs warn that servers log query strings (same page, "Bearer token authentication"). There is no page embedded signed token in the framework. Blazor Server, the closest analogue to a live page, uses the same path: "The authentication context is only established when the app starts, which is when the app first connects to the WebSocket over a SignalR connection", and it "is maintained for the lifetime of the connection and is re-evaluated on reconnection" ([Blazor security](https://github.com/dotnet/AspNetCore.Docs/blob/main/aspnetcore/blazor/security/index.md)).

### Session expiry while the socket is open

The most thoroughly documented of the four. The default is to do nothing: "If a token expires during the lifetime of a connection, by default the connection continues to work", and "SignalR doesn't automatically revalidate the user during the life of the connection, regardless of the authentication scheme. This behavior applies to all schemes, including cookie authentication" ([authn and authz](https://github.com/dotnet/AspNetCore.Docs/blob/main/aspnetcore/signalr/authn-and-authz.md#user-and-role-changes-during-the-connection-lifetime)). Two opt in options exist, both `false` by default: `CloseOnAuthenticationExpiration` closes the connection when the authentication ticket's expiry passes, and `EnableAuthenticationRefresh` lets a client renew without reconnecting ([configuration, advanced HTTP options](https://github.com/dotnet/AspNetCore.Docs/blob/main/aspnetcore/signalr/configuration.md#configure-advanced-http-options)). The docs note that automatic refresh is "typically" a bearer token feature, since it needs a scheme that reports an expiry.

Blazor Server adds the only periodic recheck found in this survey: `RevalidatingServerAuthenticationStateProvider` validates again the circuit's user on a timer, "30 minutes by default", and the project template's implementation checks the Identity security stamp. Returning `false` flips the circuit to unauthenticated and the UI redirects to sign in ([Blazor security, additional security abstractions](https://github.com/dotnet/AspNetCore.Docs/blob/main/aspnetcore/blazor/security/index.md)).

### Missing Origin

Accepted. The WebSocket middleware rejects with 403 only when the header is present and not listed: `if (!StringValues.IsNullOrEmpty(originHeader) && webSocketFeature.IsWebSocketRequest)` guards the comparison ([WebSocketMiddleware.cs](https://github.com/dotnet/aspnetcore/blob/main/src/Middleware/WebSockets/src/WebSocketMiddleware.cs#L65-L83)).

### How allowed origins are configured

By default not at all: "A list of allowed Origin header values for WebSocket requests. By default, all origins are allowed" ([WebSockets, configure the middleware](https://github.com/dotnet/AspNetCore.Docs/blob/main/aspnetcore/fundamentals/websockets.md)). An empty `WebSocketOptions.AllowedOrigins` or a `*` entry means any origin (`_anyOriginAllowed` in the same middleware source). Otherwise entries are full origins such as `https://client.com`, compared as exact strings. The docs are explicit that CORS does not cover this: "The protections provided by CORS don't apply to WebSockets", browsers neither preflight nor honour `Access-Control` headers for them, and "Applications should be configured to validate these headers" ([WebSocket origin restriction](https://github.com/dotnet/AspNetCore.Docs/blob/main/aspnetcore/fundamentals/websockets.md#websocket-origin-restriction), [SignalR security](https://github.com/dotnet/AspNetCore.Docs/blob/main/aspnetcore/signalr/security.md#websocket-origin-restriction)). Blazor's threat guidance repeats that apps "can be accessed cross-origin unless additional measures are taken" ([interactive server side rendering threat mitigation](https://github.com/dotnet/AspNetCore.Docs/blob/main/aspnetcore/blazor/security/interactive-server-side-rendering.md#cross-origin-protection)). ASP.NET Core is the one framework here whose cookie authenticated socket is open to cross site hijacking until the developer configures something.

## Comparison

| Facet | Phoenix LiveView | Rails Action Cable + Turbo | Laravel Livewire / Echo + Reverb | ASP.NET Core SignalR / Blazor Server |
|---|---|---|---|---|
| Transport of the live page | WebSocket (long polling fallback) | WebSocket | Livewire: plain HTTP `fetch`. Push: Pusher protocol WebSocket | WebSocket (SSE and long polling fallback) |
| Identity to the socket | Both: session cookie on the upgrade, released only with a matching CSRF connect param; plus a signed `phx_session` token from the page in the join | Session or signed cookie on the upgrade, read in `Connection#connect` | Socket is anonymous; each private channel is authorized by an HTTP call under `web` middleware (cookie), which returns an HMAC over `socket_id:channel` | Auth cookie on the upgrade; or bearer token in the query string |
| What the signed page value proves | Which view, router, `live_session` and extra session this server rendered; two week max age | Turbo only: the stream name was issued by this app; no expiry, not user bound | Livewire: snapshot integrity (HMAC checksum). Reverb: this socket may join this channel | None exists |
| User resolved again on the socket | Yes, by convention: `on_mount` loads the user from the session | Yes: `connect` looks the user up | Yes, in the channel auth HTTP request | Principal captured once at connect and cached |
| Session expires or is revoked while open | Nothing automatic. App broadcasts `"disconnect"` on the `live_socket_id` topic; client reconnects and `mount` fails. `phx.gen.auth` generates it | Nothing automatic. `remote_connections.where(...).disconnect`; client reconnects and `connect` rejects | Livewire: next request fails, auth middleware is applied again per update. Reverb: subscription persists, no documented mechanism | Nothing by default. Opt in `CloseOnAuthenticationExpiration`; Blazor revalidates on a 30 minute timer |
| Missing `Origin` | Accepted (documented and in code) | Refused (code reading) | Default `*` accepts all; with a list, refused (code reading) | Accepted (code) |
| Disallowed `Origin` | 403, loud error log with fix instructions | Refused, error log | `InvalidOrigin` | 403, debug log |
| Default origin policy | Host must equal configured `url.host` | Origin must equal `scheme://Host` of the request; localhost regex in development | Allow all | Allow all |
| Origin configuration | `check_origin`: `true`, `false`, list with wildcards, `:conn`, MFA; endpoint wide or per socket | `allowed_request_origins` (strings or regexes), `allow_same_origin_as_host`, `disable_request_forgery_protection` | `allowed_origins` per app, host patterns only | `WebSocketOptions.AllowedOrigins`, exact origin strings |
| Second defence besides origin | CSRF token required to read the session; framework raises if both are disabled | None on the upgrade | CSRF on the auth HTTP request | None on the upgrade |

Observations on convergent patterns:

- The session cookie on the upgrade request is the universal carrier of identity. Three of four use it directly, and Laravel uses it one step removed, on the HTTP request that signs the channel subscription. No framework puts the user's identity in a page token. Where a signed value is rendered into the page (LiveView, Turbo, Livewire, Reverb's auth response) it proves what the server rendered or what the socket may subscribe to, never who the user is.
- LiveView is the only one that binds the socket to the specific HTTP render, by requiring both the cookie and a signed token from that render. It is also the only one that demands a CSRF token on the upgrade, which lets it accept a missing `Origin` without opening a hole.
- The identity is resolved afresh on the socket side by running the app's own lookup against the session (`on_mount`, `Connection#connect`, the channel auth request). The user object is never serialized from the HTTP request to the socket.
- Nobody reads the session again on a timer during a connection, with the single exception of Blazor's 30 minute revalidation. The shared pattern for logout and revocation is push, not poll: the connection is labelled with a user scoped id at connect (`live_socket_id`, `identified_by`), the app closes every socket with that label when it signs a user out, the client reconnects, and the ordinary connect check refuses it. Expiry by the clock alone (a session lifetime running out with no server action) is handled only by SignalR's opt in `CloseOnAuthenticationExpiration`.
- Missing `Origin` splits by whether a second defence exists. Phoenix (CSRF token) and ASP.NET Core (nothing, by default) accept it; Rails, whose origin check is its only defence, refuses it. The frameworks that accept it document the reason as clients that are not browsers, "such as mobile apps" in Phoenix's words.
- Defaults for the allowed origin split the same way as in the HTTP auth survey, secure by default against opt in. Phoenix and Rails ship a same host default that works with no configuration; Reverb and ASP.NET Core ship allow all and ask the developer to narrow it. Rails compares scheme and host against the request's own `Host` header; Phoenix compares host only against configured `url.host`, and offers `:conn` for the Rails behaviour with port included.
- Every framework that checks has an explicit list setting for the deployment where the page and the socket live on different hosts, and Phoenix and Rails both support patterns (wildcards, regular expressions).

## eezo's current code, read against the survey

Facts only, from the checkout at `4156da4`:

- The live endpoint checks `Origin` and then the page id, and never reads the session or the `Guard` ([`Live.scala` `endpoint` and `originAllowed`](../../modules/live/src/main/scala/io/eezo/live/Live.scala)). The page id, 128 bits from `SecureRandom` minted during the HTTP render, is the sole capability, and the registry's own comment says so: "The id is the capability to the page until an auth layer exists" ([`PageRegistry.scala`](../../modules/live/src/main/scala/io/eezo/live/PageRegistry.scala)). In the survey's terms this is the page token half of LiveView's pair (unsigned but unguessable, single use per connection, 30 second time to live before first connect) without the cookie half.
- `originAllowed` accepts a missing `Origin`, like Phoenix and ASP.NET Core, and compares the origin's authority (host and port) with the request's `Host` header, ignoring the scheme. That sits between Rails' `allow_same_origin_as_host` (scheme and host) and Phoenix's `:conn` (scheme, host and port). There is no configurable list, which all four surveyed frameworks have.
- `Guard` already refuses a guarded WebSocket route with 403 when the session names nobody ([`Guard.scala` `through`](../../modules/auth/src/main/scala/io/eezo/auth/Guard.scala)), which is the cookie on the upgrade pattern, and reads the session through the same `key` method that enforces the sign in lifetime. It checks once, at upgrade, like every framework here; nothing closes a live page's socket on sign out or on expiry, and no surveyed framework does that without app or generator code either.

## Facets not verified from primary sources

- Rails: that a missing `Origin` is refused is my reading of `allow_request_origin?`; neither the guide nor the API docs state it.
- Reverb: that a missing `Origin` is refused under a restricted `allowed_origins` list is my reading of `verifyOrigin`; the behaviour of `parse_url` on a null origin was not executed. The absence of any session tied revocation for subscriptions is an absence in the docs, not a statement by them.
- Turbo: that signed stream names never expire is inferred from `signed_stream_name` calling `generate` with no expiry option; the verifier's construction in `lib/turbo-rails.rb` was not read.
- Laravel: that Echo sends the CSRF token to `/broadcasting/auth` was not checked in the Echo source; the claim made above is only that the route sits in the `web` group.
- Phoenix: `phx.gen.auth` emitting the `live_socket_id` and disconnect broadcast is taken from the LiveView security guide's note, not from the generator templates.
- Versions: sources were read from default branches on 2026-09-21, not from tagged releases, so line anchors may drift.
