# Authentication and authorization in the frameworks eezo is modeled on

This document surveys how eight web frameworks implement authentication and authorization, so that a later design step for eezo can be made against what the field actually does rather than from memory. For each of Spring Security, Rails, Flask, Phoenix, Play, Django, Laravel, and ASP.NET Core it answers the same seven questions: session versus token, password hashing, the current user abstraction, how auth attaches to routes, the authorization model, CSRF, and what is generated versus hand written. A closing comparison collects the convergent and divergent patterns. Every claim links to a primary source (official docs or framework source on GitHub); where a claim could not be verified it is flagged in place. The survey was done on 2026-09-10 against the versions current on that date. It makes no recommendation for eezo.

## Spring Security (Spring Boot)

Versions verified: the reference docs header reads Spring Security 7.1.1 ([docs](https://docs.spring.io/spring-security/reference/), [GitHub releases](https://github.com/spring-projects/spring-security/releases), with 6.5.11 as the latest 6.x); Spring Boot's GA line is 4.1.1 with 3.5.16 in maintenance ([Boot releases](https://github.com/spring-projects/spring-boot/releases)).

### Session vs token auth

The default is a server side servlet session. A `SecurityContextRepository` associates the user with later requests, and the default `DelegatingSecurityContextRepository` delegates to `HttpSessionSecurityContextRepository` and `RequestAttributeSecurityContextRepository` ([persistence](https://docs.spring.io/spring-security/reference/servlet/authentication/persistence.html)). The context is stored in the `HttpSession` under `SPRING_SECURITY_CONTEXT` ([source](https://github.com/spring-projects/spring-security/blob/main/web/src/main/java/org/springframework/security/web/context/HttpSessionSecurityContextRepository.java)); the `JSESSIONID` cookie belongs to the container. Spring Session swaps the container session for Redis or JDBC (`SPRING_SESSION` tables) ([Spring Session JDBC](https://docs.spring.io/spring-session/reference/configuration/jdbc.html)). Token auth is offered as HTTP Basic ([basic](https://docs.spring.io/spring-security/reference/servlet/authentication/passwords/basic.html)), as an OAuth2 Resource Server accepting JWT or opaque bearer tokens ([JWT](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html)), and `SessionCreationPolicy.STATELESS` installs a `NullSecurityContextRepository` ([session management](https://docs.spring.io/spring-security/reference/servlet/authentication/session-management.html)).

### Password hashing

`DelegatingPasswordEncoder` is the default; hashes are stored as `{id}encodedPassword` so the prefix picks the encoder at match time ([password storage](https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html)). `PasswordEncoderFactories.createDelegatingPasswordEncoder()` sets `encodingId = "bcrypt"` and registers argon2, pbkdf2, scrypt, and legacy encoders ([source](https://github.com/spring-projects/spring-security/blob/main/crypto/src/main/java/org/springframework/security/crypto/factory/PasswordEncoderFactories.java)). The hash lives in `UserDetails.getPassword()`, nullable for passkey users ([UserDetails](https://github.com/spring-projects/spring-security/blob/main/core/src/main/java/org/springframework/security/core/userdetails/UserDetails.java)); the JDBC default schema is `users(username, password, enabled)` plus `authorities` in `users.ddl` ([JDBC](https://docs.spring.io/spring-security/reference/servlet/authentication/passwords/jdbc.html)). Pluggability is a `PasswordEncoder` bean consumed by `DaoAuthenticationProvider` ([DAO provider](https://docs.spring.io/spring-security/reference/servlet/authentication/passwords/dao-authentication-provider.html)).

### Current user abstraction

`SecurityContextHolder` holds the `SecurityContext` in a thread local by default ([source](https://github.com/spring-projects/spring-security/blob/main/core/src/main/java/org/springframework/security/core/context/SecurityContextHolder.java)); the documented access is `SecurityContextHolder.getContext().getAuthentication()` and then `getPrincipal()` ([architecture](https://docs.spring.io/spring-security/reference/servlet/authentication/architecture.html)). The principal is a framework type, `UserDetails`, returned by the app's `UserDetailsService`, so the app can return its own class implementing that interface; for JWT it is a `Jwt`. Controllers inject it with `@AuthenticationPrincipal` or `@CurrentSecurityContext` ([MVC integration](https://docs.spring.io/spring-security/reference/servlet/integrations/mvc.html)).

### How auth attaches to routes/controllers

Spring Security is a servlet `Filter`: `DelegatingFilterProxy` hands off to `FilterChainProxy`, which picks the first matching `SecurityFilterChain`; the filter order is fixed, with `AuthorizationFilter` last ([architecture](https://docs.spring.io/spring-security/reference/servlet/architecture.html)). Apps declare a `SecurityFilterChain` bean with the `HttpSecurity` DSL and `authorizeHttpRequests(...).requestMatchers(...)` ([authorize requests](https://docs.spring.io/spring-security/reference/servlet/authorization/authorize-http-requests.html)). Spring Boot's autoconfigured chain is global: literally `anyRequest().authenticated()` with form login and HTTP Basic ([Boot source](https://github.com/spring-projects/spring-boot/blob/main/module/spring-boot-security/src/main/java/org/springframework/boot/security/autoconfigure/web/servlet/ServletWebSecurityAutoConfiguration.java), [Boot docs](https://docs.spring.io/spring-boot/reference/web/spring-security.html)). Method security (`@PreAuthorize`) requires `@EnableMethodSecurity` ([method security](https://docs.spring.io/spring-security/reference/servlet/authorization/method-security.html)).

```java
@Bean
SecurityFilterChain formLoginFilterChain(HttpSecurity http) throws Exception {
    http
        .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
        .formLogin(Customizer.withDefaults());
    return http.build();
}
```
([Java configuration](https://docs.spring.io/spring-security/reference/servlet/configuration/java.html))

### Authorization model

Authorities are `GrantedAuthority` strings; `hasRole("USER")` looks for `ROLE_USER` while `hasAuthority` uses the raw value, and `RoleHierarchy` lets roles imply others ([authorization architecture](https://docs.spring.io/spring-security/reference/servlet/authorization/architecture.html)). `AuthorizationManager<T>` is the decision interface, with `verify` throwing `AccessDeniedException` ([source](https://github.com/spring-projects/spring-security/blob/main/core/src/main/java/org/springframework/security/authorization/AuthorizationManager.java)); the older `AccessDecisionManager` moved to a legacy module in 7.0 ([migration](https://docs.spring.io/spring-security/reference/migration/servlet/authorization.html)). `@PreAuthorize` evaluates SpEL including `hasPermission` and bean references; the ACL module secures domain instances in `ACL_*` tables ([ACLs](https://docs.spring.io/spring-security/reference/servlet/authorization/acls.html)). On failure `ExceptionTranslationFilter` sends anonymous users to the `AuthenticationEntryPoint` and authenticated ones to `AccessDeniedHandler`, a 403 by default ([architecture](https://docs.spring.io/spring-security/reference/servlet/architecture.html)).

### CSRF

On by default for unsafe methods with the synchronizer token pattern ([CSRF](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html)); the default repository is `HttpSessionCsrfTokenRepository` ([CsrfConfigurer](https://github.com/spring-projects/spring-security/blob/main/config/src/main/java/org/springframework/security/config/annotation/web/configurers/CsrfConfigurer.java)), and `CookieCsrfTokenRepository` writes `XSRF-TOKEN` for SPAs. Since 6.0 `XorCsrfTokenRequestAttributeHandler` masks the token per response against BREACH and loading is deferred ([6.5 migration](https://docs.spring.io/spring-security/reference/6.5/migration/servlet/exploits.html)). Spring form tags and Thymeleaf emit the `_csrf` hidden input automatically; APIs opt out with `csrf.disable()` or `ignoringRequestMatchers`. Spring Security itself does not set SameSite and treats it as defense in depth ([features](https://docs.spring.io/spring-security/reference/features/exploits/csrf.html)).

### Generated vs hand written

Nothing is generated. Spring Boot autoconfigures an in memory user named `user` with a random UUID password logged at startup, backing off when the app defines `UserDetailsService` or related beans ([source](https://github.com/spring-projects/spring-boot/blob/main/module/spring-boot-security/src/main/java/org/springframework/boot/security/autoconfigure/UserDetailsServiceAutoConfiguration.java)), and `DefaultLoginPageGeneratingFilter` renders a login page until `loginPage("/login")` is set ([form login](https://docs.spring.io/spring-security/reference/servlet/authentication/passwords/form.html)). The developer writes the entity, the `UserDetailsService`, the chain, and any password reset flow; Spring Initializr only adds dependencies ([Initializr](https://docs.spring.io/initializr/docs/current/reference/html/)). No primary source states the absence of a scaffolder; it is simply not found in any reference.

## Rails

Versions verified: Rails 8.1.3 was released 2026-03-24 and the guides are at 8.1.3.1 ([rubyonrails.org](https://rubyonrails.org/), [guides](https://guides.rubyonrails.org/)); `main` is 8.2.0.alpha. Source links point at `8-1-stable`.

### Session vs token auth

Rails' default is `ActionDispatch::Session::CookieStore`, encrypted with `secret_key_base`, limited to 4096 bytes ([cookie_store.rb](https://github.com/rails/rails/blob/8-1-stable/actionpack/lib/action_dispatch/middleware/session/cookie_store.rb)). The Rails 8 authentication generator does not use it for auth state: it creates a `Session` model and a `sessions` table (user reference, `ip_address`, `user_agent`) ([generator](https://github.com/rails/rails/blob/8-1-stable/railties/lib/rails/generators/rails/authentication/authentication_generator.rb)). The generated `Authentication` concern resumes a session with `Session.find_by(id: cookies.signed[:session_id])` and `start_new_session_for` writes a signed permanent cookie with `httponly: true, same_site: :lax` ([authentication.rb.tt](https://github.com/rails/rails/blob/8-1-stable/railties/lib/rails/generators/rails/authentication/templates/app/controllers/concerns/authentication.rb.tt)). Devise stores `[to_key, authenticatable_salt]` in the Rails session via Warden ([devise/rails.rb](https://github.com/heartcombo/devise/blob/main/lib/devise/rails.rb), [authenticatable.rb](https://github.com/heartcombo/devise/blob/main/lib/devise/models/authenticatable.rb)). Token auth in core is `authenticate_or_request_with_http_token` with `secure_compare` ([API](https://api.rubyonrails.org/classes/ActionController/HttpAuthentication/Token.html)); Devise's README says API only apps do not support its cookie default and points at custom Warden strategies, with `devise-jwt` as a third party layer ([Devise README](https://github.com/heartcombo/devise/blob/main/README.md), [devise-jwt](https://github.com/waiting-for-dev/devise-jwt)).

### Password hashing

`has_secure_password` uses bcrypt, requires a `password_digest` column, and caps passwords at 72 bytes ([API](https://api.rubyonrails.org/classes/ActiveModel/SecurePassword/ClassMethods.html), [secure_password.rb](https://github.com/rails/rails/blob/8-1-stable/activemodel/lib/active_model/secure_password.rb)). The generator's `User` calls it and the migration is `email_address:string!:uniq password_digest:string!` ([user.rb.tt](https://github.com/rails/rails/blob/8-1-stable/railties/lib/rails/generators/rails/authentication/templates/app/models/user.rb.tt)). Devise stores in `encrypted_password` with bcrypt at `stretches` 12 (1 in test) plus an optional pepper ([database_authenticatable.rb](https://github.com/heartcombo/devise/blob/main/lib/devise/models/database_authenticatable.rb), [initializer template](https://github.com/heartcombo/devise/blob/main/lib/generators/templates/devise.rb)); `devise-argon2` swaps the encryptor ([repo](https://github.com/erdostom/devise-argon2)). On `main` (8.2 alpha) `has_secure_password` gains an algorithm registry with Argon2, not yet in 8.1 ([main](https://github.com/rails/rails/blob/main/activemodel/lib/active_model/secure_password.rb)).

### Current user abstraction

The generated `Current < ActiveSupport::CurrentAttributes` has `attribute :session` and `delegate :user, to: :session, allow_nil: true` ([current.rb.tt](https://github.com/rails/rails/blob/8-1-stable/railties/lib/rails/generators/rails/authentication/templates/app/models/current.rb.tt)); `CurrentAttributes` is a thread isolated singleton reset per request ([API](https://api.rubyonrails.org/classes/ActiveSupport/CurrentAttributes.html)). The type is the app's own `User`. The concern exposes only `helper_method :authenticated?`; there is no generated `current_user` helper. Devise defines `current_user` as `warden.authenticate(scope: :user)` plus `user_signed_in?` ([helpers.rb](https://github.com/heartcombo/devise/blob/main/lib/devise/controllers/helpers.rb)). Pundit's README bridges the two by defining `pundit_user` as `Current.user` ([Pundit README](https://github.com/varvet/pundit/blob/main/README.md)).

### How auth attaches to routes/controllers

The generator injects `include Authentication` into `ApplicationController`, and the concern's `included` block declares `before_action :require_authentication`, so protection is a global default; `allow_unauthenticated_access` is `skip_before_action :require_authentication` for opting out per controller ([authentication.rb.tt](https://github.com/rails/rails/blob/8-1-stable/railties/lib/rails/generators/rails/authentication/templates/app/controllers/concerns/authentication.rb.tt), [sessions_controller.rb.tt](https://github.com/rails/rails/blob/8-1-stable/railties/lib/rails/generators/rails/authentication/templates/app/controllers/sessions_controller.rb.tt)). Devise is opt in: `before_action :authenticate_user!`, with `authenticate(scope)` route constraints ([routes.rb](https://github.com/heartcombo/devise/blob/main/lib/devise/rails/routes.rb)). Pundit adds `after_action :verify_authorized` as a safety net.

### Authorization model

Rails ships none. Pundit uses plain policy classes and `authorize @post` instantiates `PostPolicy.new(current_user, @post)` and calls the `update?` predicate; a nested `Scope` backs `policy_scope`; `Pundit::NotAuthorizedError` is handled with `rescue_from` or by `config.action_dispatch.rescue_responses[...] = :forbidden` ([README](https://github.com/varvet/pundit/blob/main/README.md)).

```ruby
class PostPolicy
  attr_reader :user, :post
  def initialize(user, post)
    @user, @post = user, post
  end
  def update?
    user.admin? || !post.published?
  end
end
```

CanCanCan centralizes rules in an `Ability` class with `can`/`cannot`, checks via `authorize!` or `load_and_authorize_resource`, and raises `CanCan::AccessDenied` ([README](https://github.com/CanCanCommunity/cancancan/blob/develop/README.md), [handling](https://github.com/CanCanCommunity/cancancan/blob/develop/docs/handling_access_denied.md)). Roles such as `user.admin?` are app defined in both.

### CSRF

`ActionController::Base` subclasses are protected by default with the `:exception` strategy, the token is a random string in the session, and API only apps do not include the module ([request_forgery_protection.rb](https://github.com/rails/rails/blob/8-1-stable/actionpack/lib/action_controller/metal/request_forgery_protection.rb)); `default_protect_from_forgery` has been true since 5.2 and `per_form_csrf_tokens` since 5.0 ([configuring](https://guides.rubyonrails.org/configuring.html)). `form_with` emits `authenticity_token` and `csrf_meta_tags` feeds `X-CSRF-Token` ([csrf_helper.rb](https://github.com/rails/rails/blob/8-1-stable/actionview/lib/action_view/helpers/csrf_helper.rb)); `skip_forgery_protection` opts out. The generated `SessionsController` adds `rate_limit to: 10, within: 3.minutes, only: :create` and 8.1 rate limits password resets too ([sessions_controller.rb.tt](https://github.com/rails/rails/blob/8-1-stable/railties/lib/rails/generators/rails/authentication/templates/app/controllers/sessions_controller.rb.tt), [railties CHANGELOG](https://github.com/rails/rails/blob/8-1-stable/railties/CHANGELOG.md)). On `main`, CSRF is moving to `Sec-Fetch-Site` header verification with `:header_only` as the new default ([actionpack CHANGELOG](https://github.com/rails/rails/blob/main/actionpack/CHANGELOG.md)).

### Generated vs hand written

`bin/rails generate authentication` produces `Current`, `User`, `Session`, `SessionsController`, `PasswordsController`, `PasswordsMailer` with HTML and text reset views, views for `sessions/new`, `passwords/new`, `passwords/edit`, the `create_users` and `create_sessions` migrations, a mailer preview, the `Authentication` concern, an Action Cable connection, and two routes; an `--api` flag skips views ([8.0 CHANGELOG](https://github.com/rails/rails/blob/8-0-stable/railties/CHANGELOG.md), [generator](https://github.com/rails/rails/blob/8-1-stable/railties/lib/rails/generators/rails/authentication/authentication_generator.rb)). There is deliberately no registration controller. DHH's PR text: it "is not intended to be an all-singing, all-dancing answer to every possible authentication concern. It's merely intended to illuminate the basic path, and reveal that rolling your own authentication system is not some exotic adventure. So do not expect magic links or passkeys or 2FA" ([PR 52328](https://github.com/rails/rails/pull/52328)); the release post adds "No need to fear rolling your own authentication setup with these basics provided (or, heaven forbid, paying a vendor for it!)" ([Rails 8.0 release](https://rubyonrails.org/2024/11/7/rails-8-no-paas-required)). Devise by contrast is a library: `devise:install` writes an initializer, `devise User` writes a migration and `devise_for :users`, and views are copied only on `devise:views` ([README](https://github.com/heartcombo/devise/blob/main/README.md)). Rails 8.1 added generated controller tests and a `SessionTestHelper` ([CHANGELOG](https://github.com/rails/rails/blob/8-1-stable/railties/CHANGELOG.md)); the 8.1 release notes carry no authentication entry ([8.1 notes](https://guides.rubyonrails.org/8_1_release_notes.html)).

## Flask

Versions verified: Flask 3.1.3 ([PyPI](https://pypi.org/pypi/Flask/json)); Flask-Login 0.6.3, last released 2023-10-30 with 0.7.0 unreleased on main ([PyPI](https://pypi.org/pypi/Flask-Login/json)); Flask-Security-Too 5.8.2, published under both the Flask-Security and Flask-Security-Too names since 2024 ([PyPI](https://pypi.org/project/Flask-Security-Too/)); Werkzeug 3.1.8; Flask-WTF 1.3.0.

### Session vs token auth

Flask's session is a client side cookie that is signed, not encrypted: "the user could look at the contents of your cookie but not modify it" ([quickstart](https://flask.palletsprojects.com/en/stable/quickstart/#sessions)), implemented by `SecureCookieSessionInterface` over itsdangerous ([sessions.py](https://raw.githubusercontent.com/pallets/flask/main/src/flask/sessions.py)). Flask-Session moves data server side and leaves only an id in the cookie ([Flask-Session](https://flask-session.readthedocs.io/en/latest/introduction.html)). Flask-Login writes `_user_id`, `_fresh`, and `_id` into the session and offers a `remember_token` cookie defaulting to 365 days ([utils.py](https://raw.githubusercontent.com/maxcountryman/flask-login/main/src/flask_login/utils.py), [config.py](https://raw.githubusercontent.com/maxcountryman/flask-login/main/src/flask_login/config.py)); token auth goes through `request_loader` ([login_manager.py](https://raw.githubusercontent.com/maxcountryman/flask-login/main/src/flask_login/login_manager.py)). Flask-Security-Too reads an `Authentication-Token` header or `auth_token` parameter and exposes `@auth_required("token", "session")` ([core.py](https://raw.githubusercontent.com/pallets-eco/flask-security/main/flask_security/core.py), [API](https://flask-security-too.readthedocs.io/en/stable/api.html)).

### Password hashing

Werkzeug's `generate_password_hash` defaults to scrypt (`scrypt:32768:8:1`) since 3.0.0; before that it was pbkdf2:sha256 ([utils](https://werkzeug.palletsprojects.com/en/stable/utils/), [changes](https://werkzeug.palletsprojects.com/en/stable/changes/)). Flask-Login does no hashing. Flask-Security-Too builds a passlib `CryptContext` (now via the `libpass` fork) whose `SECURITY_PASSWORD_HASH` default is argon2, changed from bcrypt in 5.5.0, after first HMAC SHA512 keying the password with `SECURITY_PASSWORD_SALT` ([configuration](https://flask-security-too.readthedocs.io/en/stable/configuration.html), [CHANGES](https://raw.githubusercontent.com/pallets-eco/flask-security/main/CHANGES.rst), [utils.py](https://raw.githubusercontent.com/pallets-eco/flask-security/main/flask_security/utils.py)). The column is the app's own `User.password` ([models](https://flask-security-too.readthedocs.io/en/stable/models.html)).

### Current user abstraction

`flask_login.current_user` is a werkzeug `LocalProxy` resolving to whatever the app's `user_loader` returned, or an anonymous user ([utils.py](https://raw.githubusercontent.com/maxcountryman/flask-login/main/src/flask_login/utils.py), [docs](https://flask-login.readthedocs.io/en/latest/#how-it-works)). The user class is the app's; `UserMixin` supplies `is_authenticated`, `is_active`, `is_anonymous`, `get_id` ([mixins.py](https://raw.githubusercontent.com/maxcountryman/flask-login/main/src/flask_login/mixins.py)). Flask-Security-Too reuses the same proxy and adds `has_role` and `has_permission` ([API](https://flask-security-too.readthedocs.io/en/stable/api.html)).

### How auth attaches to routes/controllers

Opt in per view: `@login_required` calls `LoginManager.unauthorized()`, which redirects to `login_view` with `next` or aborts with 401 when none is configured ([login_manager.py](https://raw.githubusercontent.com/maxcountryman/flask-login/0.6.3/src/flask_login/login_manager.py)). Flask-Security-Too adds `@auth_required`, `@roles_required`, `@roles_accepted`, `@permissions_required`, returning 401 for JSON clients ([decorators.py](https://raw.githubusercontent.com/pallets-eco/flask-security/main/flask_security/decorators.py)). The Flask tutorial's global pattern is a `before_app_request` hook loading `g.user` plus a hand written decorator ([tutorial](https://flask.palletsprojects.com/en/stable/tutorial/views/)). Neither library protects globally by default.

### Authorization model

Flask-Login explicitly does not handle permissions beyond logged in or not ([docs](https://flask-login.readthedocs.io/en/latest/)). Flask-Principal offers `Identity`, `Need`, and `Permission.require` ([docs](https://pythonhosted.org/Flask-Principal/)); its last PyPI release is 0.4.0 from 2013 ([PyPI](https://pypi.org/pypi/Flask-Principal/json)), though the repo now lives under pallets-eco with 2026 commits and an unpublished 0.5.0 tag. Flask-Security-Too depends on it, adds a `Role.permissions` column, and its default unauthorized handler returns 403 JSON or redirects to `SECURITY_UNAUTHORIZED_VIEW` ([features](https://flask-security-too.readthedocs.io/en/stable/features.html), [decorators.py](https://raw.githubusercontent.com/pallets-eco/flask-security/main/flask_security/decorators.py)).

### CSRF

Flask core has none: "The ideal place for this to happen is the form validation framework, which does not exist in Flask" ([web security](https://flask.palletsprojects.com/en/stable/web-security/#cross-site-request-forgery-csrf)). Flask-WTF's `CSRFProtect` is global once enabled; the token is stored in the session, signed with salt `wtf-csrf-token`, and compared with `hmac.compare_digest` ([csrf.py](https://raw.githubusercontent.com/pallets-eco/flask-wtf/main/src/flask_wtf/csrf.py)). Templates use `{{ csrf_token() }}` or `form.hidden_tag()`, JS sends `X-CSRFToken`, `@csrf.exempt` opts out, and failures are 400 ([CSRF docs](https://flask-wtf.readthedocs.io/en/1.2.x/csrf/)). Flask-Security-Too recommends `WTF_CSRF_CHECK_DEFAULT = False` so token auth is exempt and provides an `XSRF-TOKEN` cookie for SPAs ([patterns](https://flask-security-too.readthedocs.io/en/stable/patterns.html)). `SESSION_COOKIE_SAMESITE` defaults to None ([config](https://flask.palletsprojects.com/en/stable/config/#SESSION_COOKIE_SAMESITE)).

### Generated vs hand written

No generator. The tutorial's `flaskr/auth.py` blueprint (register, login, `load_logged_in_user`, logout, a ten line `login_required`) is the hand written baseline ([tutorial](https://flask.palletsprojects.com/en/stable/tutorial/views/)). Flask-Login leaves the model, loader, login view, and form to the developer and does not handle registration or recovery ([docs](https://flask-login.readthedocs.io/en/latest/)). Flask-Security-Too is a library that registers a blueprint with login, registration, confirmation, password reset, change password, two factor, and WebAuthn views, overridable templates under `security/`, features toggled off by default (`SECURITY_REGISTERABLE` etc.), and datastores for SQLAlchemy, MongoEngine, Peewee, and Pony ([customizing](https://flask-security-too.readthedocs.io/en/stable/customizing.html), [configuration](https://flask-security-too.readthedocs.io/en/stable/configuration.html)).

## Phoenix

Versions verified: Phoenix 1.8.13, released 2026-08-25 ([hex.pm](https://hex.pm/packages/phoenix), [CHANGELOG](https://github.com/phoenixframework/phoenix/blob/v1.8/CHANGELOG.md)). The 1.8 changelog reads "Introduce scopes to Phoenix generators, designed to make secure data access the default, not something you remember (or forget) to do later" and "Introduce magic links (passwordless auth) and 'sudo mode' to `mix phx.gen.auth`".

### Session vs token auth

The generated endpoint uses `store: :cookie` with a `signing_salt` and `same_site: "Lax"`; the comment says the session "will be stored in the cookie and signed, this means its contents can be read but not tampered with. Set :encryption_salt if you would also like to encrypt it" ([endpoint.ex.eex](https://github.com/phoenixframework/phoenix/blob/v1.8/installer/templates/phx_web/endpoint.ex.eex), [Plug.Session.COOKIE](https://hexdocs.pm/plug/Plug.Session.COOKIE.html)). `phx.gen.auth` adds a `users_tokens` table: a 32 byte random session token valid for 14 days, because cookies alone "are valid indefinitely, unless you change the signing/encryption salt" ([schema_token.ex.eex](https://github.com/phoenixframework/phoenix/blob/v1.8/priv/templates/phx.gen.auth/schema_token.ex.eex)). `UserAuth` stores the token in the session and optionally in a signed remember me cookie (14 days, reissued after 7), and `log_out_user` deletes it and broadcasts `"disconnect"` to the user's LiveViews ([auth.ex.eex](https://github.com/phoenixframework/phoenix/blob/v1.8/priv/templates/phx.gen.auth/auth.ex.eex)). API tokens are another `UserToken` context read from a Bearer header ([API guide](https://github.com/phoenixframework/phoenix/blob/v1.8/guides/authn_authz/api_authentication.md)). Guardian is JWT based with `VerifyHeader`, `VerifySession`, `EnsureAuthenticated`, `LoadResource` plugs ([README](https://github.com/ueberauth/guardian/blob/master/README.md)); Pow keeps credentials in a server cache with a 30 minute TTL ([Pow.Plug.Session](https://hexdocs.pm/pow/Pow.Plug.Session.html)).

### Password hashing

The generator "defaults to `bcrypt` for Unix systems and `pbkdf2` for Windows systems", with `--hashing-lib argon2` available and recommended ([Mix.Tasks.Phx.Gen.Auth](https://hexdocs.pm/phoenix/Mix.Tasks.Phx.Gen.Auth.html)). The migration adds a nullable `hashed_password` string ([migration.ex.eex](https://github.com/phoenixframework/phoenix/blob/v1.8/priv/templates/phx.gen.auth/migration.ex.eex)); the schema keeps `password` virtual and redacted, validates 12 to 72 characters, and its `valid_password?` fallback calls `no_user_verify()` against timing attacks ([schema.ex.eex](https://github.com/phoenixframework/phoenix/blob/v1.8/priv/templates/phx.gen.auth/schema.ex.eex), [Bcrypt](https://hexdocs.pm/bcrypt_elixir/Bcrypt.html)). Pow uses PBKDF2 SHA512 at 100,000 iterations ([Pow.Ecto.Schema.Password](https://hexdocs.pm/pow/Pow.Ecto.Schema.Password.html)). Guardian's README describes only token verification; that it does no hashing is inferred, not stated.

### Current user abstraction

Phoenix 1.8 assigns `conn.assigns.current_scope`, a `%MyApp.Accounts.Scope{user: user}` struct generated into the app, instead of a bare `current_user` ([scope.ex.eex](https://github.com/phoenixframework/phoenix/blob/v1.8/priv/templates/phx.gen.auth/scope.ex.eex)). The scopes guide calls it "a container with information required by the majority of pages in your application" and motivates it with OWASP's "Broken access control" ([scopes](https://hexdocs.pm/phoenix/scopes.html)). The generator writes `config :my_app, :scopes, user: [default: true, module: ..., assign_key: :current_scope, access_path: [:user, :id], schema_key: :user_id]` so later generators can filter by it. In LiveView it is `socket.assigns.current_scope` via `assign_new`. Guardian gives `Guardian.Plug.current_resource(conn)`, Pow gives `Pow.Plug.current_user(conn)`; in all cases the value is the app's Ecto schema.

### How auth attaches to routes/controllers

The `:browser` pipeline carries `fetch_session`, `protect_from_forgery`, and after generation `fetch_current_scope_for_user`; the `:api` pipeline is only `accepts ["json"]` ([router.ex.eex](https://github.com/phoenixframework/phoenix/blob/v1.8/installer/templates/phx_web/router.ex.eex)). Fetching is global, requiring is opt in per scope block, and LiveViews need their own `on_mount` hooks because navigation inside a `live_session` skips plugs ([LiveView security](https://hexdocs.pm/phoenix_live_view/security-model.html)).

```elixir
scope "/", MyAppWeb do
  pipe_through [:browser, :require_authenticated_user]

  live_session :require_authenticated_user,
    on_mount: [{MyAppWeb.UserAuth, :require_authenticated}] do
    live "/users/settings", UserLive.Settings, :edit
  end
end
```
([routes.ex.eex](https://github.com/phoenixframework/phoenix/blob/v1.8/priv/templates/phx.gen.auth/routes.ex.eex))

Guardian groups plugs in a `Guardian.Plug.Pipeline` module ([docs](https://hexdocs.pm/guardian/Guardian.Plug.Pipeline.html)); Pow uses `Pow.Plug.RequireAuthenticated` ([docs](https://hexdocs.pm/pow/Pow.Plug.RequireAuthenticated.html)).

### Authorization model

Phoenix ships no roles or policies. Scopes are the 1.8 answer to ownership: generated contexts take the scope and filter `where: post.user_id == ^scope.user.id` ([scopes](https://hexdocs.pm/phoenix/scopes.html)); the guide says the struct "can store important metadata such as the teams, companies, or organizations the user belongs to, permissions" but leaves that to the developer ([guide](https://hexdocs.pm/phoenix/mix_phx_gen_auth.html)). Failure is procedural: `require_authenticated_user` flashes "You must log in to access this page.", redirects, and halts; the API example returns 401. No default 403 mapping was found. Guardian encodes permissions in the token and enforces them with `plug Guardian.Permissions` ([docs](https://hexdocs.pm/guardian/Guardian.Permissions.html)); Bodyguard ([repo](https://github.com/schrockwell/bodyguard)) and Canada ([repo](https://github.com/jarednorman/canada)) are the ecosystem policy libraries.

### CSRF

`protect_from_forgery` wraps `Plug.CSRFProtection`, which keeps the unmasked token in the session and accepts a masked token via `_csrf_token` param or `x-csrf-token` header; GET, HEAD, OPTIONS are unprotected ([csrf_protection.ex](https://github.com/elixir-plug/plug/blob/main/lib/plug/csrf_protection.ex)). The root layout emits a `csrf-token` meta, `Phoenix.Component.form/1` inserts the hidden field, and `app.js` passes it on the LiveSocket ([root.html.heex.eex](https://github.com/phoenixframework/phoenix/blob/v1.8/installer/templates/phx_web/components/layouts/root.html.heex.eex), [form/1](https://hexdocs.pm/phoenix_live_view/Phoenix.Component.html#form/1)). APIs are exempt by construction. 1.8 adds sudo mode: `require_sudo_mode` demands authentication within the last 10 minutes ([auth.ex.eex](https://github.com/phoenixframework/phoenix/blob/v1.8/priv/templates/phx.gen.auth/auth.ex.eex)).

### Generated vs hand written

`mix phx.gen.auth Accounts User users` writes `user.ex`, `user_token.ex`, `user_notifier.ex`, `scope.ex`, the migration (with `citext` on Postgres), `user_auth.ex`, `user_session_controller.ex`, and with `--live` the Registration, Login, Settings, and Confirmation LiveViews, all with tests; it injects context functions, fixtures, `conn_case` helpers, routes, and deps ([phx.gen.auth.ex](https://github.com/phoenixframework/phoenix/blob/v1.8/lib/mix/tasks/phx.gen.auth.ex)). Registration collects only an email and sends a magic link; passwords are opt in, and the guide states "the new authentication system fully removes registering an account with password" ([guide](https://hexdocs.pm/phoenix/mix_phx_gen_auth.html)). The stated rationale: "Since Phoenix generates this code into your application instead of building these modules into Phoenix itself, you now have complete freedom to modify the authentication system", with the caveat that "it will not be updated after it's been generated". Valim's 2020 announcement: "The best authentication framework is no authentication framework at all", "I don't want to hide my model/domain code under a framework/library", and "A complex codebase is harder to be audited" ([Dashbit](https://dashbit.co/blog/a-new-authentication-solution-for-phoenix)). Pow is a library (`mix pow.install`, templates only on `pow.phoenix.gen.templates`) ([README](https://github.com/pow-auth/pow/blob/main/README.md)); Guardian is a token library with no UI.

## Play Framework

Versions verified: Play 3.0.11 (Pekko based) and 2.9.11 ([playframework.com](https://www.playframework.com), [3.0.11 release](https://github.com/playframework/playframework/releases/tag/3.0.11)).

### Session vs token auth

Play's session is a client side `PLAY_SESSION` cookie; "Session and Flash data are not stored in the server" ([ScalaSessionFlash](https://www.playframework.com/documentation/3.0.x/ScalaSessionFlash)). Since 2.6 it is a JWT that "is signed using the secret, but is not encrypted by Play" ([SettingsSession](https://www.playframework.com/documentation/3.0.x/SettingsSession)), with `sameSite = "lax"`, `httpOnly = true`, no `maxAge` by default ([reference.conf](https://github.com/playframework/playframework/blob/3.0.x/core/play/src/main/resources/reference.conf)). Silhouette ships Cookie, Session, BearerToken, JWT, and Dummy authenticators with an `AuthenticatorRepository` for server side state ([authenticators](https://github.com/playframework/play-silhouette/tree/main/silhouette/app/play/silhouette/impl/authenticators), [repository](https://github.com/playframework/play-silhouette/blob/main/silhouette/app/play/silhouette/api/repositories/AuthenticatorRepository.scala)). pac4j distinguishes indirect clients (form, OIDC) from direct ones (bearer, header) ([clients](https://www.pac4j.org/docs/clients.html)), and play-pac4j offers `PlayCacheSessionStore` and `PlayCookieSessionStore` ([store](https://github.com/pac4j/play-pac4j/tree/master/shared/src/main/java/org/pac4j/play/store)).

### Password hashing

Play core has none; `play.api.mvc.Security` only wraps a `userinfo` lookup ([Security.scala](https://github.com/playframework/playframework/blob/3.0.x/core/play/src/main/scala/play/api/mvc/Security.scala)). Silhouette has `PasswordInfo(hasher, password, salt)` and a `PasswordHasherRegistry` with deprecated hashers for rotation ([PasswordHasher.scala](https://github.com/playframework/play-silhouette/blob/main/silhouette/app/play/silhouette/api/util/PasswordHasher.scala)), `BCryptPasswordHasher` and `BCryptSha256PasswordHasher` over jBCrypt, and an Argon2 module ([bcrypt module](https://github.com/playframework/play-silhouette/blob/main/silhouette-password-bcrypt/src/main/scala/play/silhouette/password/BCryptSha256PasswordHasher.scala), [Dependencies.scala](https://github.com/playframework/play-silhouette/blob/main/project/Dependencies.scala)); storage goes through the app's `DelegableAuthInfoDAO` ([source](https://github.com/playframework/play-silhouette/blob/main/silhouette-persistence/src/main/scala/play/silhouette/persistence/daos/DelegableAuthInfoDAO.scala)). pac4j authenticators need a configured `PasswordEncoder` (Spring, Shiro, or jBCrypt adapters) over a `users` table ([authenticators](https://www.pac4j.org/docs/authenticators.html), [SQL](https://www.pac4j.org/docs/authenticators/sql.html)).

### Current user abstraction

Baseline: `request.session.get("connected")` returns `Option[String]` ([ScalaSessionFlash](https://www.playframework.com/documentation/3.0.x/ScalaSessionFlash)). `Security.AuthenticatedBuilder[U]` yields `AuthenticatedRequest(user: U, request)`, a `WrappedRequest` ([Security.scala](https://github.com/playframework/playframework/blob/3.0.x/core/play/src/main/scala/play/api/mvc/Security.scala)); the docs recommend writing your own `UserRequest` through an `ActionTransformer` ([ScalaActionsComposition](https://www.playframework.com/documentation/3.0.x/ScalaActionsComposition)). Silhouette's `SecuredRequest[E, B]` exposes `identity`, typed by the app's `Env` ([SecuredAction.scala](https://github.com/playframework/play-silhouette/blob/main/silhouette/app-3/play/silhouette/api/actions/SecuredAction.scala)); play-pac4j's `Security[P]` trait gives `AuthenticatedRequest(profiles, request)` with `CommonProfile` ([Security.scala](https://github.com/pac4j/play-pac4j/blob/master/shared/src/main/scala/org/pac4j/play/scala/Security.scala)).

### How auth attaches to routes/controllers

Opt in per action through `ActionBuilder` composition with `andThen`, or Java's `@Security.Authenticated(Secured.class)` ([Security.java](https://github.com/playframework/playframework/blob/3.0.x/core/play/src/main/java/play/mvc/Security.java)). Global concerns use filters via `play.filters.enabled` ([Filters](https://www.playframework.com/documentation/3.0.x/Filters)); pac4j's `SecurityFilter` applies `pac4j.security.rules` path regexes globally ([SecurityFilter.scala](https://github.com/pac4j/play-pac4j/blob/master/shared/src/main/scala/org/pac4j/play/filters/SecurityFilter.scala)) and `Secure(...)` per action ([wiki](https://github.com/pac4j/play-pac4j/wiki/Apply-security)). Silhouette exposes `SecuredAction`, `UnsecuredAction`, `UserAwareAction`.

```scala
class UserRequest[A](val username: Option[String], request: Request[A])
  extends WrappedRequest[A](request)

class UserAction @Inject() (val parser: BodyParsers.Default)(implicit val executionContext: ExecutionContext)
  extends ActionBuilder[UserRequest, AnyContent] with ActionTransformer[Request, UserRequest] {
  def transform[A](request: Request[A]) = Future.successful {
    new UserRequest(request.session.get("username"), request)
  }
}
```
([docs sample](https://github.com/playframework/playframework/blob/3.0.x/documentation/manual/working/scalaGuide/main/http/code/ScalaActionsComposition.scala))

### Authorization model

Play core has none; the docs show a hand written `ActionFilter` returning `Some(Forbidden)` ([ScalaActionsComposition](https://www.playframework.com/documentation/3.0.x/ScalaActionsComposition)). Silhouette's `Authorization[I, A]` trait has `isAuthorized(identity, authenticator)` with `!`, `&&`, `||` combinators ([Authorization.scala](https://github.com/playframework/play-silhouette/blob/main/silhouette/app/play/silhouette/api/Authorization.scala)); `onNotAuthenticated` should answer 401 and `onNotAuthorized` 403 ([ErrorHandler.scala](https://github.com/playframework/play-silhouette/blob/main/silhouette/app/play/silhouette/api/ErrorHandler.scala)). A `WithRole` class is app written, not shipped. pac4j's `Authorizer` interface, with `RequireAnyRoleAuthorizer` and `RequireAllRolesAuthorizer`, yields a 403 page on failure, 401 for direct clients, or a redirect for indirect ones ([authorizers](https://www.pac4j.org/docs/authorizers/profile-authorizers.html), [security filter](https://www.pac4j.org/docs/security-filter.html)).

### CSRF

"As of Play 2.6.x, the CSRF filter is included in Play's list of default filters" ([ScalaCsrf](https://www.playframework.com/documentation/3.0.x/ScalaCsrf)). The token lives in the session (or a cookie via `play.filters.csrf.cookie.name`), is signed per request against BREACH, is emitted with `@CSRF.formField`, and is read from a `Csrf-Token` header ([reference.conf](https://github.com/playframework/playframework/blob/3.0.x/web/play-filters-helpers/src/main/resources/reference.conf)). Checks run only when the request carries a `Cookie` or `Authorization` header, so a bare API call is exempt; other opt outs are `bypassHeaders`, the `+ nocsrf` route modifier, or per action `CSRFCheck`.

### Generated vs hand written

No generator: `play-scala-seed.g8` produces a `HomeController`, two views, and routes ([seed](https://github.com/playframework/play-scala-seed.g8/tree/3.0.x/src/main/g8)). Silhouette's only seed is the archived `mohiva/play-silhouette-seed` (last push 2020, sign up, sign in, social auth, password reset) ([seed](https://github.com/mohiva/play-silhouette-seed)). Maintenance status: `mohiva/play-silhouette` was archived 2021-09-12 and says "This repository is not longer maintained" ([mohiva](https://github.com/mohiva/play-silhouette)); the fork under `playframework/play-silhouette` publishes as `org.playframework.silhouette` since 9.0.0, 10.0.0 added Play 3 and Pekko, and the latest is 10.0.4 from 2025-12-30 with the repo active ([README](https://github.com/playframework/play-silhouette#readme), [10.0.0](https://github.com/playframework/play-silhouette/releases/tag/10.0.0), [10.0.4](https://github.com/playframework/play-silhouette/releases/tag/10.0.4)). play-pac4j's newest tag is 13.0.3 for Play 3.0 ([tags](https://github.com/pac4j/play-pac4j/tags)).

## Django

Versions verified: Django 6.1.1 is the latest release, 5.2 is LTS until April 2028 ([download](https://www.djangoproject.com/download/)); stable docs identify as 6.1.

### Session vs token auth

Sessions are server side in the database by default (`django_session`, engine `django.contrib.sessions.backends.db`, cookie `sessionid`), with cached_db, cache, file, and signed_cookies alternatives; only signed_cookies puts data in the cookie, signed but not encrypted ([sessions](https://docs.djangoproject.com/en/stable/topics/http/sessions/), [global_settings.py](https://github.com/django/django/blob/stable/6.1.x/django/conf/global_settings.py)). Login writes `_auth_user_id`, `_auth_user_backend`, and `_auth_user_hash`, and `get_user` flushes the session on hash mismatch ([auth/__init__.py](https://github.com/django/django/blob/main/django/contrib/auth/__init__.py)). Token auth is not in core; DRF's `TokenAuthentication` stores tokens in the `authtoken` app, and JWT is delegated to third parties ([DRF authentication](https://www.django-rest-framework.org/api-guide/authentication/)).

### Password hashing

`PASSWORD_HASHERS` defaults to PBKDF2 with SHA256 first, then PBKDF2 SHA1, Argon2, bcrypt SHA256, scrypt ([global_settings.py](https://github.com/django/django/blob/stable/6.1.x/django/conf/global_settings.py)); the iteration count is 1,500,000 on 6.1.x and 1,000,000 on 5.2.x ([hashers.py 6.1](https://github.com/django/django/blob/stable/6.1.x/django/contrib/auth/hashers.py), [hashers.py 5.2](https://github.com/django/django/blob/stable/5.2.x/django/contrib/auth/hashers.py)). Hashes use `algorithm$iterations$salt$hash` in `AbstractBaseUser.password`, and passwords are rehashed on login when the algorithm or iteration count is outdated ([passwords](https://docs.djangoproject.com/en/stable/topics/auth/passwords/)).

### Current user abstraction

`AuthenticationMiddleware` sets `request.user` to a `SimpleLazyObject` ([middleware.py](https://github.com/django/django/blob/main/django/contrib/auth/middleware.py)), an `AnonymousUser` or an instance of `AUTH_USER_MODEL` (default `auth.User`, a framework model) ([default](https://docs.djangoproject.com/en/stable/topics/auth/default/)). The docs insist on setting `AUTH_USER_MODEL` "before creating any migrations or running manage.py migrate for the first time" because changing it later needs manual schema work ([customizing](https://docs.djangoproject.com/en/stable/topics/auth/customizing/)).

### How auth attaches to routes/controllers

Opt in: `@login_required`, `@permission_required(raise_exception=...)`, `@user_passes_test`, and the `LoginRequiredMixin` family for class based views ([decorators.py](https://github.com/django/django/blob/stable/6.1.x/django/contrib/auth/decorators.py), [default](https://docs.djangoproject.com/en/stable/topics/auth/default/)). Django 5.1 added `LoginRequiredMiddleware`, which "redirects all unauthenticated requests to a login page" except views marked `@login_not_required`, turning the default global ([5.1 notes](https://docs.djangoproject.com/en/stable/releases/5.1/), [middleware ref](https://docs.djangoproject.com/en/stable/ref/middleware/)).

```python
MIDDLEWARE = [
    "django.contrib.auth.middleware.AuthenticationMiddleware",
    "django.contrib.auth.middleware.LoginRequiredMiddleware",
]

@login_not_required
def my_view(request): ...
```

### Authorization model

`Permission` rows are created per model (`add`, `change`, `delete`, `view`) by a post migrate handler, grouped by `Group`, and checked with `user.has_perm("app.codename")`; superusers pass everything ([management/__init__.py](https://github.com/django/django/blob/stable/6.1.x/django/contrib/auth/management/__init__.py), [ref](https://docs.djangoproject.com/en/stable/ref/contrib/auth/)). Backends implement `has_perm(user, perm, obj=None)` but `ModelBackend` returns nothing when `obj` is given ([backends.py](https://github.com/django/django/blob/main/django/contrib/auth/backends.py)). django-guardian fills that with `ObjectPermissionBackend`, `assign_perm`, `get_objects_for_user`, and `UserObjectPermission` tables ([configuration](https://django-guardian.readthedocs.io/en/stable/configuration/), [shortcuts.py](https://github.com/django-guardian/django-guardian/blob/main/guardian/shortcuts.py)). `PermissionDenied` renders `403.html` via `handler403` ([views ref](https://docs.djangoproject.com/en/stable/ref/views/)).

### CSRF

`CsrfViewMiddleware` is in the default project template. The mechanism is a secret in the `csrftoken` cookie plus a `csrfmiddlewaretoken` form field that is freshly masked per `get_token()` call against BREACH; the secret rotates on login; Origin and Referer are checked with `CSRF_TRUSTED_ORIGINS` ([CSRF ref](https://docs.djangoproject.com/en/stable/ref/csrf/)). `{% csrf_token %}` in templates, `X-CSRFToken` for JS, `CSRF_USE_SESSIONS` to move the secret into the session, and `@csrf_exempt` per view ([howto](https://docs.djangoproject.com/en/stable/howto/csrf/)). Session and CSRF cookies default to `SameSite=Lax` ([global_settings.py](https://github.com/django/django/blob/stable/6.1.x/django/conf/global_settings.py)).

### Generated vs hand written

`startproject` installs `django.contrib.auth` and the middleware ([settings template](https://github.com/django/django/blob/main/django/conf/project_template/project_name/settings.py-tpl)); the `User`, `Group`, and `Permission` tables come from Django's own migration with `swappable = "AUTH_USER_MODEL"` ([0001_initial.py](https://github.com/django/django/blob/stable/6.1.x/django/contrib/auth/migrations/0001_initial.py)). `django.contrib.auth.urls` wires login, logout, password change, and the four password reset routes with class based views and forms ([urls.py](https://github.com/django/django/blob/stable/6.1.x/django/contrib/auth/urls.py)), but "Django provides no default template" so the developer writes `registration/login.html` and friends, and there is no signup view ([default](https://docs.djangoproject.com/en/stable/topics/auth/default/)). The admin is the only shipped UI. django-allauth is the ecosystem answer for signup, social, and MFA ([allauth](https://docs.allauth.org/en/latest/introduction/index.html)).

## Laravel

Versions verified: Laravel 13 has been current since 2026-03-17; Laravel 12 (released 2025-02-24) receives security fixes until 2027-02-24 ([support policy](https://laravel.com/docs/13.x/releases#support-policy)). The 12.x docs are cited below as requested; the 12.x pages now carry an "old version" banner.

### Session vs token auth

Auth is guards plus providers; the skeleton's single `web` guard uses the `session` driver over the Eloquent `users` provider ([config/auth.php](https://github.com/laravel/laravel/blob/12.x/config/auth.php), [authentication](https://laravel.com/docs/12.x/authentication#introduction)). The session driver defaults to `database` with a `sessions` table in the first migration ([config/session.php](https://github.com/laravel/laravel/blob/12.x/config/session.php), [session](https://laravel.com/docs/12.x/session#configuration)); the cookie is encrypted by `EncryptCookies` in the `web` group ([middleware groups](https://laravel.com/docs/12.x/middleware#laravels-default-middleware-groups)). Remember me uses a `remember_token` column ([remembering users](https://laravel.com/docs/12.x/authentication#remembering-users)). Sanctum issues tokens in `personal_access_tokens` via `HasApiTokens::createToken`, sent as Bearer, with abilities checked by `tokenCan` ([Sanctum](https://laravel.com/docs/12.x/sanctum#api-token-authentication)); SPA auth uses cookies through `statefulApi()` and `EnsureFrontendRequestsAreStateful` ([SPA](https://laravel.com/docs/12.x/sanctum#spa-authentication)). Passport is reserved for real OAuth2 needs ([Passport](https://laravel.com/docs/12.x/passport#passport-or-sanctum)).

### Password hashing

"By default, Laravel uses the `bcrypt` hashing driver", with argon and argon2id selectable ([hashing](https://laravel.com/docs/12.x/hashing#configuration)); the framework config sets `BCRYPT_ROUNDS` 12, `HASH_VERIFY` true, and `rehash_on_login` true ([config/hashing.php](https://github.com/laravel/framework/blob/12.x/config/hashing.php)). `SessionGuard` calls `rehashPasswordIfRequired` on login ([SessionGuard.php](https://github.com/laravel/framework/blob/12.x/src/Illuminate/Auth/SessionGuard.php), [automatic rehashing](https://laravel.com/docs/12.x/authentication#automatic-password-rehashing)). The skeleton `User` casts `password` to `hashed` and the `users` migration has `password` and `remember_token` ([User.php](https://github.com/laravel/laravel/blob/12.x/app/Models/User.php), [migration](https://github.com/laravel/laravel/blob/12.x/database/migrations/0001_01_01_000000_create_users_table.php)).

### Current user abstraction

`Auth::user()`, `Auth::id()`, or `$request->user()` ([retrieving](https://laravel.com/docs/12.x/authentication#retrieving-the-authenticated-user)). The type is the app's `App\Models\User`, which extends `Illuminate\Foundation\Auth\User` implementing `Authenticatable`, `Authorizable`, and `CanResetPassword` ([framework User.php](https://github.com/laravel/framework/blob/12.x/src/Illuminate/Foundation/Auth/User.php)). Multiple guards allow multiple user types via `Auth::guard('admin')` ([guards](https://laravel.com/docs/12.x/authentication#accessing-specific-guard-instances)).

### How auth attaches to routes/controllers

Opt in: `->middleware('auth')` or `auth:sanctum` on routes and groups, or `HasMiddleware` on controllers ([protecting routes](https://laravel.com/docs/12.x/authentication#protecting-routes), [controllers](https://laravel.com/docs/12.x/controllers#controller-middleware)). Neither the `web` nor the `api` group includes `auth` ([middleware groups](https://laravel.com/docs/12.x/middleware#laravels-default-middleware-groups)). Unauthenticated requests raise `AuthenticationException`, answered with JSON 401 or a redirect to `login`, configurable with `redirectGuestsTo` ([Authenticate.php](https://github.com/laravel/framework/blob/12.x/src/Illuminate/Auth/Middleware/Authenticate.php), [redirecting](https://laravel.com/docs/12.x/authentication#redirecting-unauthenticated-users)). Laravel 13 adds a `#[Middleware('auth')]` attribute ([13 release notes](https://laravel.com/docs/13.x/releases#php-attributes)).

### Authorization model

Gates are closures in `AppServiceProvider::boot`; policies are classes from `make:policy --model`, auto discovered by name or registered with `Gate::policy` ([authorization](https://laravel.com/docs/12.x/authorization#registering-policies)). Checks are `$user->can()`, `Gate::authorize`, the `can:` middleware, `@can` in Blade, and Form Request `authorize()`. `AuthorizationException` is "automatically converted to a 403 HTTP response" unless `denyWithStatus` or `denyAsNotFound` overrides it ([Handler.php](https://github.com/laravel/framework/blob/12.x/src/Illuminate/Foundation/Exceptions/Handler.php)). The docs warn "Guards and providers should not be confused with 'roles' and 'permissions'"; roles come from packages such as Spatie's laravel-permission ([spatie](https://spatie.be/docs/laravel-permission/v6/introduction)).

```php
class PostPolicy
{
    public function update(User $user, Post $post): bool
    {
        return $user->id === $post->user_id;
    }
}

// controller
Gate::authorize('update', $post);
```
([policy methods](https://laravel.com/docs/12.x/authorization#policy-methods))

### CSRF

`ValidateCsrfToken` sits in the `web` group and compares the request token with the session token; `@csrf` emits a hidden `_token`, `X-CSRF-TOKEN` is read from a meta tag, and an encrypted `XSRF-TOKEN` cookie serves Axios ([CSRF](https://laravel.com/docs/12.x/csrf#preventing-csrf-requests)). Exclusions are `validateCsrfTokens(except: [...])` in `bootstrap/app.php`; the `api` group has no CSRF middleware; a mismatch is HTTP 419. Sanctum SPAs first hit `/sanctum/csrf-cookie` ([Sanctum CSRF](https://laravel.com/docs/12.x/sanctum#csrf-protection)). Cookies default to `same_site` lax ([config/session.php](https://github.com/laravel/laravel/blob/12.x/config/session.php)). Laravel 13 reworks this as `PreventRequestForgery` with origin checks ([13 release notes](https://laravel.com/docs/13.x/releases#request-forgery-protection)).

### Generated vs hand written

The skeleton ships the `User` model, the `users`, `password_reset_tokens`, and `sessions` tables, and the password broker config, but no auth routes, views, or controllers. Starter kits (React, Svelte, Vue with Inertia 2, Livewire) chosen at `laravel new` "include the routes, controllers, and views you need to register and authenticate", and "all of the backend and frontend code exists within your application" ([starter kits](https://laravel.com/docs/12.x/starter-kits#introduction)). They delegate the flows to Fortify, "a frontend agnostic authentication backend" whose routes and controllers live in the package while the app supplies views ([Fortify](https://laravel.com/docs/12.x/fortify#introduction)); two factor is enabled by default in the kits. Breeze and Jetstream "will no longer receive additional updates" ([12 release notes](https://laravel.com/docs/12.x/releases#new-application-starter-kits)). Password reset and email verification are framework services (`Password::sendResetLink`, `MustVerifyEmail`) ([passwords](https://laravel.com/docs/12.x/passwords#routing), [verification](https://laravel.com/docs/12.x/verification)). The stance is hybrid: services in the framework, UI scaffolded into the app.

## ASP.NET Core

Versions verified: .NET 10 is the current LTS (10.0.12, released 2025-11-11, supported to 2028-11-14); .NET 9 is STS until 2026-11-10 ([download](https://dotnet.microsoft.com/en-us/download/dotnet), [support policy](https://dotnet.microsoft.com/en-us/platform/support/policy/dotnet-core)).

### Session vs token auth

Identity defaults to the `Identity.Application` cookie scheme ([IdentityServiceCollectionExtensions.cs](https://github.com/dotnet/aspnetcore/blob/main/src/Identity/Core/src/IdentityServiceCollectionExtensions.cs)). The cookie carries the serialized `ClaimsPrincipal`, encrypted by Data Protection, with no server state unless an `ITicketStore` is configured ([cookie auth](https://learn.microsoft.com/en-us/aspnet/core/security/authentication/cookie?view=aspnetcore-10.0), [ITicketStore](https://learn.microsoft.com/en-us/dotnet/api/microsoft.aspnetcore.authentication.cookies.iticketstore?view=aspnetcore-10.0)); defaults are 14 days sliding, `SameSite=Lax`, `HttpOnly` ([CookieAuthenticationOptions.cs](https://github.com/dotnet/aspnetcore/blob/main/src/Security/Authentication/Cookies/src/CookieAuthenticationOptions.cs)), with the security stamp revalidated every 30 minutes ([SecurityStampValidatorOptions.cs](https://github.com/dotnet/aspnetcore/blob/main/src/Identity/Core/src/SecurityStampValidatorOptions.cs)). `AddJwtBearer` handles JWTs ([JWT bearer](https://learn.microsoft.com/en-us/aspnet/core/security/authentication/configure-jwt-bearer-authentication?view=aspnetcore-10.0)). `MapIdentityApi` tokens "aren't standard JSON Web Tokens" but Data Protection payloads, and the feature "isn't intended to be a full-featured identity service provider or token server" ([identity API](https://learn.microsoft.com/en-us/aspnet/core/security/authentication/identity-api-authorization?view=aspnetcore-10.0)). .NET 10 makes recognized API endpoints return 401/403 instead of redirecting under cookie auth ([API endpoint auth](https://learn.microsoft.com/en-us/aspnet/core/security/authentication/api-endpoint-auth?view=aspnetcore-10.0)).

### Password hashing

`PasswordHasher<TUser>` V3 is "PBKDF2 with HMAC-SHA512, 128-bit salt, 256-bit subkey, 100000 iterations", and verifying an older hash returns `SuccessRehashNeeded` ([PasswordHasher.cs](https://github.com/dotnet/aspnetcore/blob/main/src/Identity/Extensions.Core/src/PasswordHasher.cs), [PasswordHasherOptions.cs](https://github.com/dotnet/aspnetcore/blob/main/src/Identity/Extensions.Core/src/PasswordHasherOptions.cs)). It is replaceable through `IPasswordHasher<TUser>` in DI ([identity configuration](https://learn.microsoft.com/en-us/aspnet/core/security/authentication/identity-configuration?view=aspnetcore-10.0)). The hash lives in `IdentityUser.PasswordHash`, mapped to `AspNetUsers` ([IdentityUser.cs](https://github.com/dotnet/aspnetcore/blob/main/src/Identity/Extensions.Stores/src/IdentityUser.cs)).

### Current user abstraction

`HttpContext.User` is a `ClaimsPrincipal`, a framework type ([HttpContext.User](https://learn.microsoft.com/en-us/dotnet/api/microsoft.aspnetcore.http.httpcontext.user?view=aspnetcore-10.0)), set by the authentication middleware. Identity fills it with `NameIdentifier`, `Name`, `Role`, and security stamp claims; the app's user entity is fetched separately with `UserManager.GetUserAsync(User)` ([GetUserAsync](https://learn.microsoft.com/en-us/dotnet/api/microsoft.aspnetcore.identity.usermanager-1.getuserasync?view=aspnetcore-10.0)). Blazor exposes it through `AuthenticationStateProvider` ([Blazor security](https://learn.microsoft.com/en-us/aspnet/core/blazor/security/?view=aspnetcore-10.0)).

### How auth attaches to routes/controllers

`UseAuthentication` then `UseAuthorization` in the pipeline, added automatically by `WebApplication` when the services are registered ([middleware](https://learn.microsoft.com/en-us/aspnet/core/fundamentals/middleware/?view=aspnetcore-10.0)). Opt in via `[Authorize]` on controllers, actions, pages, or components, `[AllowAnonymous]` to open, and `RequireAuthorization()` on minimal API endpoints and groups ([simple authorization](https://learn.microsoft.com/en-us/aspnet/core/security/authorization/simple?view=aspnetcore-10.0)). A global default is `FallbackPolicy = RequireAuthenticatedUser()`, applied wherever no authorization metadata exists ([AuthorizationOptions](https://learn.microsoft.com/en-us/dotnet/api/microsoft.aspnetcore.authorization.authorizationoptions?view=aspnetcore-10.0), [secure data](https://learn.microsoft.com/en-us/aspnet/core/security/authorization/secure-data?view=aspnetcore-10.0)).

### Authorization model

Roles via `[Authorize(Roles = "Admin")]` and `AspNetRoles` tables ([roles](https://learn.microsoft.com/en-us/aspnet/core/security/authorization/roles?view=aspnetcore-10.0)); claims via `RequireClaim`; policies as `IAuthorizationRequirement` markers plus `AuthorizationHandler<T>` registered as `IAuthorizationHandler`, requirements combined with AND and handlers with OR, and "Authorization handlers are called even if authentication fails" ([policies](https://learn.microsoft.com/en-us/aspnet/core/security/authorization/policies?view=aspnetcore-10.0)). Resource based checks use `IAuthorizationService.AuthorizeAsync(user, resource, policy)` and `OperationAuthorizationRequirement` ([resource based](https://learn.microsoft.com/en-us/aspnet/core/security/authorization/resource-based?view=aspnetcore-10.0)). Failure is delegated to the scheme: cookies redirect to login or access denied, JWT returns 401 or 403 ([authentication overview](https://learn.microsoft.com/en-us/aspnet/core/security/authentication/?view=aspnetcore-10.0)).

```csharp
public class MinimumAgeRequirement(int minimumAge) : IAuthorizationRequirement
{
    public int MinimumAge { get; } = minimumAge;
}

builder.Services.AddAuthorizationBuilder()
    .AddPolicy("AtLeast21", policy =>
        policy.Requirements.Add(new MinimumAgeRequirement(21)));
builder.Services.AddSingleton<IAuthorizationHandler, MinimumAgeHandler>();
```

### CSRF

Antiforgery is a synchronizer token tied to the user identity: a `.AspNetCore.Antiforgery.*` cookie (`HttpOnly`, `SameSite=Strict`) plus a `__RequestVerificationToken` form field or `RequestVerificationToken` header, validated with a fixed time claim uid comparison ([anti request forgery](https://learn.microsoft.com/en-us/aspnet/core/security/anti-request-forgery?view=aspnetcore-10.0), [AntiforgeryOptions.cs](https://github.com/dotnet/aspnetcore/blob/main/src/Antiforgery/src/AntiforgeryOptions.cs), [DefaultAntiforgeryTokenGenerator.cs](https://github.com/dotnet/aspnetcore/blob/main/src/Antiforgery/src/Internal/DefaultAntiforgeryTokenGenerator.cs)). The form tag helper injects the field; Razor Pages validate automatically; MVC uses `[AutoValidateAntiforgeryToken]` or `[ValidateAntiForgeryToken]`; minimal APIs need `UseAntiforgery` for form binding and `DisableAntiforgery()` for bearer only endpoints; Blazor `EditForm` adds an `AntiforgeryToken` ([Blazor security](https://learn.microsoft.com/en-us/aspnet/core/blazor/security/?view=aspnetcore-10.0)).

### Generated vs hand written

Identity is a library (`UserManager`, `SignInManager`, EF stores) plus a Razor Class Library UI: `dotnet new webapp -au Individual` produces `ApplicationDbContext`, `AddDefaultIdentity<IdentityUser>()`, and migrations, with the UI served from the RCL ([Identity intro](https://learn.microsoft.com/en-us/aspnet/core/security/authentication/identity?view=aspnetcore-10.0)). For Blazor Web Apps in .NET 8 and later, "Identity Razor components are included in the generated project" under `Components/Account`, with `ApplicationUser` and an `IdentityNoOpEmailSender` ([Blazor security](https://learn.microsoft.com/en-us/aspnet/core/blazor/security/?view=aspnetcore-10.0)). `dotnet aspnet-codegenerator identity --files ...` copies selected pages into the app, and "Generated code takes precedence over the same code in the Identity RCL" ([scaffold identity](https://learn.microsoft.com/en-us/aspnet/core/security/authentication/scaffold-identity?view=aspnetcore-10.0)). The developer writes `IEmailSender` and custom user properties ([account confirmation](https://learn.microsoft.com/en-us/aspnet/core/security/authentication/accconfirm?view=aspnetcore-10.0)). `MapIdentityApi` maps register, login, refresh, confirmEmail, forgotPassword, resetPassword, manage/2fa, and manage/info, with no logout endpoint ([IdentityApiEndpointRouteBuilderExtensions.cs](https://github.com/dotnet/aspnetcore/blob/main/src/Identity/Core/src/IdentityApiEndpointRouteBuilderExtensions.cs)).

## Comparison

| Facet | Spring Security | Rails 8 | Flask | Phoenix 1.8 | Play 3 | Django 6 | Laravel 12 | ASP.NET Core 10 |
|---|---|---|---|---|---|---|---|---|
| Session default | Server side servlet session, `JSESSIONID` | Encrypted cookie store; generator adds server side `sessions` table with signed cookie id | Signed cookie (itsdangerous) | Signed cookie plus `users_tokens` table | Signed JWT cookie, no server state | Database session, cookie holds id | Database session, encrypted cookie | Encrypted ticket in cookie, no server state |
| Token / API | Basic, OAuth2 resource server JWT, stateless policy | `authenticate_or_request_with_http_token`; devise-jwt | `request_loader`; FST `Authentication-Token` | `UserToken` api context, Phoenix.Token, Guardian JWT | Silhouette bearer/JWT; pac4j direct clients | DRF token; JWT third party | Sanctum tokens, Passport OAuth2 | JWT bearer; `MapIdentityApi` opaque tokens |
| Hash default | bcrypt via `DelegatingPasswordEncoder` | bcrypt (`password_digest`) | scrypt (Werkzeug); argon2 (FST) | bcrypt (pbkdf2 on Windows), argon2 optional | none in core; bcrypt (Silhouette) | PBKDF2 SHA256, 1.5M iterations | bcrypt, 12 rounds | PBKDF2 SHA512, 100k iterations |
| Current user | `SecurityContextHolder`, `UserDetails` (framework type) | `Current.user` (app model) | `current_user` proxy (app model) | `conn.assigns.current_scope` (generated struct wrapping app schema) | `WrappedRequest` field (app type) | `request.user` (`AUTH_USER_MODEL`) | `Auth::user()` (app model) | `HttpContext.User` `ClaimsPrincipal` (framework type) |
| Attachment | Filter chain; global by Boot default | `before_action` in `ApplicationController`; global default, opt out | Decorators; opt in | Router pipelines, `live_session on_mount`; fetch global, require opt in | ActionBuilder composition; opt in | Decorators and mixins; opt in, or global middleware since 5.1 | Route middleware; opt in | `[Authorize]`; opt in, or `FallbackPolicy` global |
| Authorization | Authorities, `AuthorizationManager`, SpEL, ACL | None; Pundit policies, CanCanCan abilities | None; Flask-Principal, FST roles | None; scopes for ownership | None; Silhouette `Authorization`, pac4j authorizers | Model permissions, groups; guardian for objects | Gates and policies | Roles, claims, policies with requirements and handlers |
| Failure mapping | `AccessDeniedException` to 403 or entry point | `rescue_from` to 403 or redirect | 403 or redirect | Flash, redirect, halt | 401/403 via error handlers | `PermissionDenied` to 403 | `AuthorizationException` to 403 | Scheme decides challenge or forbid |
| CSRF | On by default, session token, Xor masking | On by default, session token, per form | Off in core; Flask-WTF session token | On in browser pipeline, masked session token | On by default, signed session token, skipped without cookie | On by default, cookie secret plus masked field | On in web group, session token, `XSRF-TOKEN` cookie | On for forms, identity bound cookie plus field |
| Generated | Nothing; Boot autoconfig | Full auth stack generated into app | Nothing; libraries | Full auth stack generated into app | Nothing; archived seed | Framework app, no templates | Skeleton model and tables; starter kits copy UI in | Library plus RCL; Blazor template generates pages |

Observations on convergent patterns:

- Password hashing has settled on a small set of memory hard or iterated algorithms with a self describing hash string. bcrypt is the default in Spring, Rails, Phoenix, Laravel, and Silhouette; PBKDF2 remains the default in Django and ASP.NET Core; Werkzeug and Flask-Security-Too moved to scrypt and argon2 respectively. Every framework stores the hash in a single string column on the user record, and Spring, Django, Laravel, and ASP.NET Core all rehash transparently on login when the stored parameters are outdated.
- Authentication and authorization are separate layers everywhere. Rails, Flask, Phoenix, and Play ship no authorization at all and delegate to policy libraries; Django, Laravel, and ASP.NET Core ship a policy or permission layer that is still invoked separately from the login check; Spring merges both into one filter chain but still distinguishes `AuthenticationManager` from `AuthorizationManager`.
- The current user lives on the request or connection scoped object, not in a global: `request.user`, `conn.assigns.current_scope`, `HttpContext.User`, `$request->user()`, `WrappedRequest`. Spring's thread local `SecurityContextHolder` and Rails' `Current` attributes are the two exceptions, and both are request scoped singletons reset per request.
- Six of eight hand the app its own user type. Spring (`UserDetails`) and ASP.NET Core (`ClaimsPrincipal`) expose a framework principal and require a second lookup (`UserDetailsService`, `UserManager.GetUserAsync`) to reach the app entity.
- CSRF protection is on by default for browser routes in every framework except Flask, and is always some form of synchronizer token: the secret sits in the session (Spring, Rails, Phoenix, Play, Laravel), in a cookie (Django, ASP.NET Core), or either. Django, Spring, Phoenix, and Play mask or re sign the token per request against BREACH. APIs are exempt either by construction (a pipeline or route group without the middleware in Phoenix, Laravel, Rails API mode) or by explicit opt out.
- Sessions are split between two camps: server side records (Django, Laravel, Spring, and the Rails 8 and Phoenix generators, which both add a sessions or tokens table precisely so that individual sessions can be revoked) versus stateless signed or encrypted cookies (Flask, Play, ASP.NET Core cookie auth, Rails' own default store).
- Global versus opt in protection is a real divergence. Rails 8, Spring Boot, and Phoenix's fetch step make authentication the default and require opting out; Flask, Play, Laravel, Django (before 5.1), and ASP.NET Core (without a `FallbackPolicy`) require opting in per route. Django 5.1's `LoginRequiredMiddleware` and ASP.NET's `FallbackPolicy` show the older frameworks adding a global switch after the fact.
- Generated code is the recent trend in the productivity frameworks: Rails 8 (2024) and Phoenix 1.6 onward (with 1.8's rewrite) generate the whole auth stack into the app and justify it in near identical terms, that developers should own and understand the code and not hide the domain under a library. Laravel and ASP.NET Core are hybrids, keeping the services in the framework and copying only the UI layer in. Spring, Flask, Play, and Django remain library or framework centric with nothing generated.
- Phoenix 1.8 is the outlier on two axes: it replaced `current_user` with a generated `Scope` struct so that generated queries are ownership filtered by default, and it made passwordless magic links the registration default with passwords opt in. Rails 8 explicitly declined magic links, passkeys, and 2FA in its generator.
- Maintenance signals differ across ecosystems. Flask-Login has had no release since 2023 and Flask-Principal none since 2013; Silhouette was archived by its original author in 2021 and continues under the playframework org; Breeze and Jetstream are frozen in favour of the Laravel 12 starter kits. The framework owned generators in Rails and Phoenix sidestep this by leaving no library to maintain, at the cost that generated code "will not be updated after it's been generated".

## Facets not verified from primary sources

- Spring: the absence of any scaffolder is inferred from the Initializr and Boot references, which describe none; the CSRF migration facts come from the 6.5 doc line because the 6.0 migration page returns 404.
- Rails: the paraphrases sometimes attributed to DHH's Rails World 2024 keynote could not be checked against a transcript; the quotes above come from the PR and release post.
- Flask: the Flask-Principal README under pallets-eco and the 0.5.0 tag date were unreachable.
- Phoenix: the v1.8 API guide still shows a 60 day session validity while the templates say 14; that Guardian does no hashing is inferred; no primary source discusses a default 403 mapping.
- Play: no `playframework/play-silhouette-seed` repository exists, only the archived mohiva one; a `WithRole` authorization is not shipped; the date of the org transfer is not stated in the README.
- Django: guardian's table names were not confirmed from docs; the 6.1.1 patch date is not on the download page.
- Laravel: `auth()->user()` and the `AuthorizesRequests` trait are not on the 12.x pages read; only `Gate::authorize` is cited.
- ASP.NET Core: Identity's `AccessDeniedPath` default and the exact release that raised the PBKDF2 iteration count from 10,000 to 100,000 were not found on a primary page.
