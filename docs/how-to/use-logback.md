<!-- draft -->
# Log through Logback instead of java.util.logging

Swap the logging backend eezo, Jetty and HikariCP share by default for Logback, without two providers on the classpath.

> **TODO** This page is a placeholder. The outline below is what it should cover; write it by
> hand and remove the `draft` marker at the top when it is done.

## What to cover

- how the three log by default: `System.Logger`, SLF4J, `slf4j-jdk14`
- the two changes that go together: exclude `slf4j-jdk14` for the whole project, add `slf4j-jdk-platform-logging` and `logback-classic`
- why the exclusion is project wide and not on one dependency line
- the warning that means a step was missed
- setting levels for `io.eezo`, `org.eclipse.jetty`, `com.zaxxer.hikari`

## Where the material is

- `README.md`, Logging
- `project/Dependencies.scala` (`slf4jJdk14`)
