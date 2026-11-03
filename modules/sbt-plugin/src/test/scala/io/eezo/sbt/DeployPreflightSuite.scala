package io.eezo.sbt

import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.util.concurrent.TimeUnit

import munit.FunSuite

/** `eezo deploy` run as a process, with a fake `fly` and a fake `sbt` first on a PATH that holds
  * nothing else but `/usr/bin` and `/bin`, so no real Fly account and no real build can be reached
  * from a suite. On macOS that PATH also makes the launcher run under `/bin/bash` 3.2, the shell
  * many users get from `#!/usr/bin/env bash`, which is how a bash 4 construct is caught before a
  * user meets it.
  *
  * It lives here because the launcher has no module of its own, and this is the project `+test`
  * reaches that already holds the deploy pins.
  */
class DeployPreflightSuite extends FunSuite {

  import DeployPreflightSuite._

  test("a required plain variable in neither fly secrets nor [env] stops the deploy") {
    val run = deploy(config = "SHOP_PORT\tport\tplain\trequired\n")
    assertEquals(run.code, 1)
    assertEquals(
      run.err,
      header +
        "    SHOP_PORT is required: set it with fly secrets set SHOP_PORT=..., or as a key of the [env] table of fly.toml\n"
    )
    assertNoStage(run)
    assertReadOnly(run)
  }

  test("a required redacted variable missing from fly secrets is sent to fly secrets alone") {
    val run = deploy(config = "STRIPE_KEY\tstripe.key\tredacted\trequired\n")
    assertEquals(run.code, 1)
    assertEquals(
      run.err,
      header + "    STRIPE_KEY is required and redacted: set it with fly secrets set STRIPE_KEY=...\n"
    )
    assertNoStage(run)
    assertReadOnly(run)
  }

  test("a redacted variable in [env] stops the deploy, even when fly secrets has it too") {
    val run = deploy(
      config = "STRIPE_KEY\tstripe.key\tredacted\toptional\n",
      secrets = secretsHeader + "STRIPE_KEY          9f2c41aa07be51d3    Deployed\n",
      flyToml = generatedToml + "\n[env]\n  STRIPE_KEY = 'sk_live_leaked'\n"
    )
    assertEquals(run.code, 1)
    assertEquals(
      run.err,
      header +
        "    STRIPE_KEY is redacted and belongs in fly secrets, not in fly.toml, which is committed: remove it from the [env] table and set it with fly secrets set STRIPE_KEY=...\n"
    )
    assertEquals(run.out, "")
    assertNoStage(run)
    assertReadOnly(run)
  }

  test("a required redacted variable in [env] stops the deploy, even when fly secrets has it too") {
    val run = deploy(
      config = "STRIPE_KEY\tstripe.key\tredacted\trequired\n" +
        "SHOP_PORT\tport\tplain\trequired\n",
      secrets = secretsHeader +
        "STRIPE_KEY          9f2c41aa07be51d3    Deployed\n" +
        "SHOP_PORT           0c1d2e3f4a5b6c7d    Deployed\n",
      flyToml = generatedToml + "\n[env]\n  STRIPE_KEY = 'sk_live_leaked'\n"
    )
    assertEquals(run.code, 1)
    assertEquals(
      run.err,
      header +
        "    STRIPE_KEY is redacted and belongs in fly secrets, not in fly.toml, which is committed: remove it from the [env] table and set it with fly secrets set STRIPE_KEY=...\n"
    )
    assertEquals(run.flyCalls, List("auth whoami", "secrets list"))
    assertNoStage(run)
    assertReadOnly(run)
  }

  test("a required plain variable only in fly secrets passes") {
    assertStaged(
      deploy(
        config = "SHOP_PORT\tport\tplain\trequired\n",
        secrets = secretsHeader + "SHOP_PORT           0c1d2e3f4a5b6c7d    Deployed\n"
      )
    )
  }

  test("a required plain variable only in the [env] table of fly.toml passes") {
    assertStaged(
      deploy(
        config = "SHOP_PORT\tport\tplain\trequired\n",
        flyToml = generatedToml + "\n[env]\n  SHOP_PORT = '8080'\n"
      )
    )
  }

  test("a required redacted variable in fly secrets and not in [env] passes") {
    assertStaged(
      deploy(
        config = "STRIPE_KEY\tstripe.key\tredacted\trequired\n",
        secrets = secretsHeader + "STRIPE_KEY          9f2c41aa07be51d3    Deployed\n",
        flyToml = generatedToml + "\n[env]\n  SHOP_PORT = '8080'\n"
      )
    )
  }

  test("defaulted and optional variables, plain or redacted, may be absent from both") {
    assertStaged(
      deploy(config =
        "SHOP_CURRENCY\tcurrency\tplain\tdefaulted\n" +
          "SHOP_BANNER\tbanner\tplain\toptional\n" +
          "STRIPE_WEBHOOK\tstripe.webhook\tredacted\tdefaulted\n" +
          "STRIPE_KEY\tstripe.key\tredacted\toptional\n"
      )
    )
  }

  test("an application with no Config class deploys as before, and fly secrets is not listed") {
    val run = deploy(config = "")
    assertStaged(run)
    assertEquals(run.flyCalls, List("auth whoami"))
  }

  test("every offender is listed in one message from one run, in the order eezo config gives") {
    val run = deploy(
      config = "SHOP_PORT\tport\tplain\trequired\n" +
        "STRIPE_KEY\tstripe.key\tredacted\trequired\n" +
        "SHOP_CURRENCY\tcurrency\tplain\tdefaulted\n" +
        "STRIPE_WEBHOOK\tstripe.webhook\tredacted\tdefaulted\n" +
        "MAIL_HOST\tmail.host\tplain\trequired\n",
      flyToml = generatedToml + "\n[env]\n  STRIPE_WEBHOOK = 'whsec_leaked'\n"
    )
    assertEquals(run.code, 1)
    assertEquals(
      run.err,
      header +
        "    SHOP_PORT is required: set it with fly secrets set SHOP_PORT=..., or as a key of the [env] table of fly.toml\n" +
        "    STRIPE_KEY is required and redacted: set it with fly secrets set STRIPE_KEY=...\n" +
        "    STRIPE_WEBHOOK is redacted and belongs in fly secrets, not in fly.toml, which is committed: remove it from the [env] table and set it with fly secrets set STRIPE_WEBHOOK=...\n" +
        "    MAIL_HOST is required: set it with fly secrets set MAIL_HOST=..., or as a key of the [env] table of fly.toml\n"
    )
    assertNoStage(run)
    assertReadOnly(run)
  }

  test("a staged or partially deployed secret counts as present, its indicator glued or apart") {
    assertStaged(
      deploy(
        config = "SHOP_PORT\tport\tplain\trequired\n" +
          "STRIPE_KEY\tstripe.key\tredacted\trequired\n" +
          "MAIL_HOST\tmail.host\tplain\trequired\n" +
          "MAIL_PASSWORD\tmail.password\tredacted\trequired\n",
        secrets = secretsHeader +
          "* SHOP_PORT         0c1d2e3f4a5b6c7d    Staged\n" +
          "*STRIPE_KEY         9f2c41aa07be51d3    Staged\n" +
          "! MAIL_HOST         5e6f7a8b9c0d1e2f    Partial\n" +
          "!MAIL_PASSWORD      a1b2c3d4e5f60718    Partial\n"
      )
    )
  }

  test("a name is matched whole, so a longer secret or [env] key does not satisfy it") {
    val run = deploy(
      config = "STRIPE_KEY\tstripe.key\tredacted\trequired\n" +
        "SHOP_PORT\tport\tplain\trequired\n",
      secrets = secretsHeader +
        "STRIPE_KEY_OLD      9f2c41aa07be51d3    Deployed\n" +
        "OLD_STRIPE_KEY      1a2b3c4d5e6f7081    Deployed\n",
      flyToml = generatedToml + "\n[env]\n  SHOP_PORT_FALLBACK = '8080'\n"
    )
    assertEquals(run.code, 1)
    assertEquals(
      run.err,
      header +
        "    STRIPE_KEY is required and redacted: set it with fly secrets set STRIPE_KEY=...\n" +
        "    SHOP_PORT is required: set it with fly secrets set SHOP_PORT=..., or as a key of the [env] table of fly.toml\n"
    )
    assertNoStage(run)
  }

  test("the header row of fly secrets list is not a secret") {
    val run = deploy(config = "NAME\tname\tplain\trequired\n")
    assertEquals(run.code, 1)
    assertEquals(
      run.err,
      header +
        "    NAME is required: set it with fly secrets set NAME=..., or as a key of the [env] table of fly.toml\n"
    )
    assertNoStage(run)
  }

  test("eezo config refusing stops the deploy with its own lines and nothing added") {
    val refusal =
      "[eezo] stripe.key is Redacted and application.conf states it as a literal\n" +
        "[eezo] port is fed from EEZO_PORT, a variable under EEZO_, which is eezo's own\n"
    val run = deploy(
      config = "[error] Nonzero exit code returned from runner: 1\n" +
        "[error] (Compile / run) Nonzero exit code returned from runner: 1\n" +
        "[error] Total time: 3 s, completed 3 Nov 2026, 10:00:00\n",
      configErr = refusal,
      configCode = 1
    )
    assertEquals(run.code, 1)
    assertEquals(run.err, refusal)
    assertEquals(run.out, "")
    assertEquals(run.sbtCalls, List(Config))
    assertEquals(run.flyCalls, List("auth whoami"))
  }

  test("a line of eezo config that is no variable row stops the deploy without echoing it") {
    val run = deploy(config =
      "SHOP_PORT\tport\tplain\trequired\n" +
        "Picked up JAVA_TOOL_OPTIONS: -Dtoken=abc123\n"
    )
    assertEquals(run.code, 1)
    assertEquals(
      run.err,
      "✗ deployment cannot continue: line 2 of what eezo config printed is not a variable row, so the\n" +
        "  variables application.conf names cannot be checked. Run eezo config to see it.\n"
    )
    assertNoStage(run)
    assertEquals(run.flyCalls, List("auth whoami"))
  }

  // One case per part of the row grammar, each on line 2 after a well formed row: four tab
  // separated fields, a path, the two redaction words, the three need words, no blank line. A tab
  // is a separator and never padding, so an empty field, a leading tab or a trailing tab breaks
  // the row even where the words around it would still read as one.
  List(
    "a fifth field"             -> "SHOP_PORT\tport\tplain\trequired\tdefaulted\n",
    "an empty path"             -> "SHOP_PORT\t\tplain\trequired\n",
    "an empty field among five" -> "SHOP_PORT\tport\t\tplain\trequired\n",
    "a leading tab"             -> "\tSHOP_PORT\tport\tplain\trequired\n",
    "a trailing tab"            -> "SHOP_PORT\tport\tplain\trequired\t\n",
    "an unknown redaction"      -> "SHOP_PORT\tport\tsecret\trequired\n",
    "an unknown need"           -> "SHOP_PORT\tport\tplain\tneeded\n",
    "a blank line"              -> "\nSHOP_PORT\tport\tplain\trequired\n"
  ).foreach { case (construct, line2) =>
    test(s"a row with $construct stops the deploy") {
      val run = deploy(config = "STRIPE_KEY\tstripe.key\tredacted\toptional\n" + line2)
      assertEquals(run.code, 1)
      assertEquals(
        run.err,
        "✗ deployment cannot continue: line 2 of what eezo config printed is not a variable row, so the\n" +
          "  variables application.conf names cannot be checked. Run eezo config to see it.\n"
      )
      assertNoStage(run)
      assertEquals(run.flyCalls, List("auth whoami"))
    }
  }

  test("fly secrets list failing stops the deploy, and no variable is called missing") {
    val run = deploy(
      config = "SHOP_PORT\tport\tplain\trequired\n" +
        "STRIPE_KEY\tstripe.key\tredacted\trequired\n",
      secrets = "",
      secretsErr = "Error: failed to list secrets: unauthorized\n",
      secretsCode = 1
    )
    assertEquals(run.code, 1)
    assertEquals(
      run.err,
      "Error: failed to list secrets: unauthorized\n" +
        "✗ deployment cannot continue: fly secrets list failed, so the variables application.conf names\n" +
        "  cannot be checked. See the output above.\n"
    )
    assertNoStage(run)
    assertReadOnly(run)
  }

  test("fly secrets is not listed when no required variable is left for it to hold") {
    val run = deploy(
      config = "SHOP_PORT\tport\tplain\trequired\n" +
        "STRIPE_KEY\tstripe.key\tredacted\toptional\n",
      secretsCode = 1,
      flyToml = generatedToml + "\n[env]\n  SHOP_PORT = '8080'\n"
    )
    assertStaged(run)
    assertEquals(run.flyCalls, List("auth whoami"))
  }

  test("an [env] key counts indented or not, bare, double quoted or single quoted") {
    val run = deploy(
      config = "SHOP_PORT\tport\tplain\trequired\n" +
        "SHOP_HOST\thost\tplain\trequired\n" +
        "MAIL_HOST\tmail.host\tplain\trequired\n" +
        "MAIL_FROM\tmail.from\tplain\trequired\n",
      flyToml = generatedToml +
        "\n[env] # read at boot\n" +
        "SHOP_PORT='8080'\n" +
        "\n" +
        "  # the host the shop answers on\n" +
        "  \"SHOP_HOST\" = \"shop.example.com\"\n" +
        "\t'MAIL_HOST' = 'smtp.example.com'\n" +
        "  MAIL_FROM = 'shop=orders@example.com'\n"
    )
    assertStaged(run)
  }

  test("a key commented out, in another table, or under a table below [env] is not in [env]") {
    val run = deploy(
      config = "SHOP_PORT\tport\tplain\trequired\n" +
        "MAIL_HOST\tmail.host\tplain\trequired\n" +
        "MAIL_FROM\tmail.from\tplain\trequired\n" +
        "SHOP_HOST\thost\tplain\trequired\n",
      flyToml = "SHOP_HOST = 'shop.example.com'\n" + generatedToml +
        "  MAIL_FROM = 'orders@example.com'\n" +
        "\n[env]\n" +
        "  # SHOP_PORT = '8080'\n" +
        "  STRIPE_CURRENCY = 'eur'\n" +
        "\n[env.extra]\n" +
        "  MAIL_HOST = 'smtp.example.com'\n"
    )
    assertEquals(run.code, 1)
    assertEquals(
      run.err,
      header +
        "    SHOP_PORT is required: set it with fly secrets set SHOP_PORT=..., or as a key of the [env] table of fly.toml\n" +
        "    MAIL_HOST is required: set it with fly secrets set MAIL_HOST=..., or as a key of the [env] table of fly.toml\n" +
        "    MAIL_FROM is required: set it with fly secrets set MAIL_FROM=..., or as a key of the [env] table of fly.toml\n" +
        "    SHOP_HOST is required: set it with fly secrets set SHOP_HOST=..., or as a key of the [env] table of fly.toml\n"
    )
    assertNoStage(run)
  }

  test("the database secret check still comes first, with its own message, before any sbt start") {
    val run = deploy(
      config = "SHOP_PORT\tport\tplain\trequired\n",
      migrations = true
    )
    assertEquals(run.code, 1)
    assertEquals(
      run.err,
      """✗ deployment cannot continue: no database secret is set.
        |
        |This app has migrations, and `db migrate --apply` runs during deploy. Set the
        |database secret first:
        |
        |    fly secrets set DATABASE_URL=postgres://user:password@host/dbname
        |
        |(eezo also understands EEZO_DB_URL / EEZO_DB_USER / EEZO_DB_PASS.)
        |""".stripMargin
    )
    assertEquals(run.sbtCalls, Nil)
  }

  test("with the database secret set, the preflight runs next and passes on its own terms") {
    assertStaged(
      deploy(
        config = "SHOP_PORT\tport\tplain\trequired\n",
        secrets = secretsHeader +
          "DATABASE_URL        77aa88bb99cc00dd    Deployed\n" +
          "SHOP_PORT           0c1d2e3f4a5b6c7d    Deployed\n",
        migrations = true
      )
    )
  }

  test(
    "a variable that feeds several paths is reported once, redacted and required if any row is"
  ) {
    val run = deploy(config =
      "SHOP_KEY\tshop.a\tplain\toptional\n" +
        "SHOP_KEY\tshop.b\tredacted\tdefaulted\n" +
        "SHOP_KEY\tshop.c\tplain\trequired\n"
    )
    assertEquals(run.code, 1)
    assertEquals(
      run.err,
      header + "    SHOP_KEY is required and redacted: set it with fly secrets set SHOP_KEY=...\n"
    )
    assertNoStage(run)
  }

  test("a line inside a multi line string of [env] is no key") {
    val run = deploy(
      config = "SHOP_PORT\tport\tplain\trequired\n",
      flyToml = generatedToml + "\n[env]\n  BANNER = \"\"\"\nSHOP_PORT = 8080\n\"\"\"\n"
    )
    assertEquals(run.code, 1)
    assertEquals(
      run.err,
      header +
        "    SHOP_PORT is required: set it with fly secrets set SHOP_PORT=..., or as a key of the [env] table of fly.toml\n"
    )
    assertNoStage(run)
  }

  test("a line inside a multi line literal string of [env] is no key") {
    val run = deploy(
      config = "SHOP_PORT\tport\tplain\trequired\n",
      flyToml = generatedToml + "\n[env]\n  BANNER = '''\nSHOP_PORT = 8080\n'''\n"
    )
    assertEquals(run.code, 1)
    assertEquals(
      run.err,
      header +
        "    SHOP_PORT is required: set it with fly secrets set SHOP_PORT=..., or as a key of the [env] table of fly.toml\n"
    )
    assertNoStage(run)
  }

  test("an [env] line inside a multi line string of another table opens no [env] table") {
    val run = deploy(
      config = "SHOP_PORT\tport\tplain\trequired\n",
      flyToml = generatedToml.replace(
        "[deploy]\n",
        "[deploy]\n  notes = \"\"\"\n[env]\nSHOP_PORT = 8080\n\"\"\"\n"
      )
    )
    assertEquals(run.code, 1)
    assertEquals(
      run.err,
      header +
        "    SHOP_PORT is required: set it with fly secrets set SHOP_PORT=..., or as a key of the [env] table of fly.toml\n"
    )
    assertNoStage(run)
  }

  test("a redacted variable in the [env] table of a CRLF fly.toml stops the deploy") {
    val run = deploy(
      config = "STRIPE_KEY\tstripe.key\tredacted\toptional\n",
      flyToml = (generatedToml + "\n[env]\n  STRIPE_KEY = 'sk_live_leaked'\n").replace("\n", "\r\n")
    )
    assertEquals(run.code, 1)
    assertEquals(
      run.err,
      header +
        "    STRIPE_KEY is redacted and belongs in fly secrets, not in fly.toml, which is committed: remove it from the [env] table and set it with fly secrets set STRIPE_KEY=...\n"
    )
    assertNoStage(run)
  }

  test("a project pinned to an eezo older than the config command deploys as before") {
    val run = deploy(
      configErr = "[eezo] unknown command: config. Run `help` for the list.\n",
      configCode = 2,
      pluginsSbt = "addSbtPlugin(\"io.eezo\" % \"sbt-eezo\" % \"0.3.0\")\n"
    )
    assertEquals(run.err, "✗ build failed; deployment was not attempted\n")
    assertEquals(run.code, 1)
    assertEquals(run.sbtCalls, List(Stage))
    assertEquals(run.flyCalls, List("auth whoami"))
  }

  test("a project pinned to an eezo with the config command is checked") {
    val run = deploy(
      config = "SHOP_PORT\tport\tplain\trequired\n",
      pluginsSbt = "addSbtPlugin(\"io.eezo\" % \"sbt-eezo\" % \"0.4.0\")\n"
    )
    assertEquals(run.code, 1)
    assertEquals(
      run.err,
      header +
        "    SHOP_PORT is required: set it with fly secrets set SHOP_PORT=..., or as a key of the [env] table of fly.toml\n"
    )
    assertNoStage(run)
  }

  test("the launcher uses nothing macOS's /bin/bash 3.2 lacks") {
    val source = new String(Files.readAllBytes(launcher.toPath), UTF_8)
    val bash4  = List("declare -A", "local -A", "typeset -A", "mapfile", "readarray", ",,}", "^^}")
    assertEquals(bash4.filter(source.contains), Nil)
  }

  private def assertStaged(run: Run): Unit = {
    assertEquals(run.out, "")
    assertEquals(run.err, "✗ build failed; deployment was not attempted\n")
    assertEquals(run.code, 1)
    assertEquals(run.sbtCalls, List(Config, Stage))
    assertReadOnly(run)
  }

  private def assertNoStage(run: Run): Unit = {
    assert(!run.sbtCalls.contains(Stage), s"eezoStage ran: ${run.sbtCalls}")
    assertEquals(run.out, "")
  }

  private def assertReadOnly(run: Run): Unit = {
    val reads = Set("auth whoami", "secrets list")
    assert(run.flyCalls.forall(reads), s"fly was asked for more than names: ${run.flyCalls}")
  }
}

object DeployPreflightSuite {

  /** What the fake sbt logs for the preflight's `eezo config`. */
  val Config = "-error -batch run config"

  /** What the fake sbt logs for the staging step. */
  val Stage = "-error -batch eezoStage"

  /** The header of every preflight refusal, which the per variable lines follow. */
  val header: String =
    "✗ deployment cannot continue: the variables application.conf names are not all in place on Fly.\n\n"

  /** The fly.toml `eezo deploy` writes, which has no [env] table. */
  val generatedToml: String =
    """app = 'shop'
      |primary_region = 'fra'
      |
      |[deploy]
      |  release_command = 'db migrate --apply'
      |
      |[http_service]
      |  internal_port = 8080
      |""".stripMargin

  /** The header row `fly secrets list` prints above the rows. */
  val secretsHeader = "NAME                DIGEST              STATUS\n"

  final case class Run(
      code: Int,
      out: String,
      err: String,
      sbtCalls: List[String],
      flyCalls: List[String]
  )

  /** Runs `eezo deploy` in a fresh project directory. `config`, `configErr` and `configCode` are
    * what the fake sbt answers to `run config`; `secrets`, `secretsErr` and `secretsCode` what the
    * fake fly answers to `secrets list`; `pluginsSbt`, when given, is the project's own
    * project/plugins.sbt, whose `sbt-eezo` pin says which eezo the project is on. The fake sbt
    * fails `eezoStage` after logging it, so a deploy that passes the preflight stops there and
    * never reaches `fly deploy` or the health poll.
    */
  def deploy(
      config: String = "",
      configErr: String = "",
      configCode: Int = 0,
      secrets: String = secretsHeader,
      secretsErr: String = "",
      secretsCode: Int = 0,
      flyToml: String = generatedToml,
      migrations: Boolean = false,
      pluginsSbt: String = ""
  ): Run = {
    val root    = Files.createTempDirectory("eezo-deploy")
    val bin     = Files.createDirectory(root.resolve("bin"))
    val project = Files.createDirectory(root.resolve("project"))
    val fixture = Files.createDirectory(root.resolve("fixture"))
    write(fixture.resolve("config.out"), config)
    write(fixture.resolve("config.err"), configErr)
    write(fixture.resolve("config.code"), configCode.toString)
    write(fixture.resolve("secrets.out"), secrets)
    write(fixture.resolve("secrets.err"), secretsErr)
    write(fixture.resolve("secrets.code"), secretsCode.toString)
    write(project.resolve("fly.toml"), flyToml)
    if (pluginsSbt.nonEmpty) {
      val dir = Files.createDirectory(project.resolve("project"))
      write(dir.resolve("plugins.sbt"), pluginsSbt)
    }
    if (migrations) {
      val dir = Files.createDirectories(project.resolve("db").resolve("migrations"))
      write(dir.resolve("V1__init.sql"), "create table t (id int);\n")
    }
    val f = fixture.toString
    executable(
      bin.resolve("fly"),
      s"""#!/bin/sh
         |printf '%s\\n' "$$*" >> '$f/fly.log'
         |case "$$*" in
         |  "auth whoami") echo someone@example.com; exit 0 ;;
         |  "secrets list")
         |    cat '$f/secrets.out'
         |    cat '$f/secrets.err' >&2
         |    exit "$$(cat '$f/secrets.code')" ;;
         |esac
         |exit 1
         |""".stripMargin
    )
    executable(
      bin.resolve("sbt"),
      s"""#!/bin/sh
         |printf '%s\\n' "$$*" >> '$f/sbt.log'
         |case "$$3" in
         |  "run config")
         |    cat '$f/config.out'
         |    cat '$f/config.err' >&2
         |    exit "$$(cat '$f/config.code')" ;;
         |esac
         |exit 1
         |""".stripMargin
    )

    val out     = root.resolve("stdout").toFile
    val err     = root.resolve("stderr").toFile
    val builder = new ProcessBuilder(launcher.getPath, "deploy")
      .directory(project.toFile)
      .redirectInput(new File("/dev/null"))
      .redirectOutput(out)
      .redirectError(err)
    builder.environment().put("PATH", s"$bin:/usr/bin:/bin")
    val process = builder.start()
    if (!process.waitFor(60, TimeUnit.SECONDS)) {
      process.destroyForcibly().waitFor()
      throw new AssertionError(s"eezo deploy did not finish in 60 seconds:\n${read(err.toPath)}")
    }
    Run(
      process.exitValue(),
      read(out.toPath),
      read(err.toPath),
      lines(fixture.resolve("sbt.log")),
      lines(fixture.resolve("fly.log"))
    )
  }

  /** bin/eezo, found from the working directory sbt runs a suite in and from its parents, so that
    * this passes whether the suite was started at the build root or inside the module.
    */
  lazy val launcher: File = {
    def upwards(from: File, left: Int): Option[File] = {
      val candidate = new File(from, "bin/eezo")
      if (candidate.isFile) Some(candidate)
      else if (left == 0 || from.getParentFile == null) None
      else upwards(from.getParentFile, left - 1)
    }
    val here = new File(".").getCanonicalFile
    upwards(here, 4).getOrElse(throw new AssertionError(s"bin/eezo not found from $here"))
  }

  private def write(path: Path, text: String): Unit = {
    Files.write(path, text.getBytes(UTF_8))
    ()
  }

  private def executable(path: Path, text: String): Unit = {
    write(path, text)
    if (!path.toFile.setExecutable(true)) throw new AssertionError(s"cannot make $path executable")
  }

  private def read(path: Path): String = new String(Files.readAllBytes(path), UTF_8)

  private def lines(path: Path): List[String] =
    if (Files.exists(path)) read(path).split("\n").toList.filter(_.nonEmpty) else Nil
}
