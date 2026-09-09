package io.eezo.sbt

import munit.FunSuite

/** The Dockerfile is a contract with the platform, so its load-bearing lines are pinned: the
  * entrypoint shape (Fly's `release_command` replaces CMD and keeps ENTRYPOINT, so `migrate
  * --apply` must arrive as arguments to `main`), the workdir (`db/` resolves against it), and the
  * migrations COPY (without it, migrate reports "up to date" against an empty database).
  */
class DeploySuite extends FunSuite {

  private val rendered = Deploy.dockerfile("Main", "25")

  test("the entrypoint is the application's own dispatch, exec-form, wildcard classpath") {
    assert(
      rendered.contains(
        """ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-cp", "lib/*", "Main"]"""
      )
    )
    assert(!rendered.contains("CMD"), "release_command must not fight a default CMD")
  }

  test("workdir and the two copies: jars and migrations") {
    assert(rendered.contains("WORKDIR /app"))
    assert(rendered.contains("COPY lib lib"))
    assert(rendered.contains("COPY db db"))
  }

  test("the base image is a JRE at the configured version, and nothing build-shaped") {
    assert(rendered.contains("FROM eclipse-temurin:25-jre-noble"))
    assert(!rendered.toLowerCase.contains("sbt"))
  }
}
