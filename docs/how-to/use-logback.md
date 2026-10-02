# Log through Logback instead of java.util.logging

Swap the logging backend eezo, Jetty and HikariCP share by default for Logback, without ending up
with two providers on the classpath.

## How it logs by default

eezo writes its own lines through the JDK's `System.Logger`. Jetty and HikariCP write theirs
through SLF4J. `eezo-http` and `eezo-db` each ship `slf4j-jdk14` at runtime, which sends the
SLF4J lines to `java.util.logging` too, so all three share one format on stderr with nothing
configured.

## Two changes, together

```scala
libraryDependencies += "io.eezo" %% "eezo" % eezoVersion
excludeDependencies += ExclusionRule("org.slf4j", "slf4j-jdk14")
libraryDependencies += "org.slf4j" % "slf4j-jdk-platform-logging" % "2.0.17"
libraryDependencies += "ch.qos.logback" % "logback-classic" % logbackVersion
```

The exclusion takes `slf4j-jdk14` off the classpath. `slf4j-jdk-platform-logging` hands eezo's
`System.Logger` lines to SLF4J, so they end up in Logback with everything else.

The exclusion is one rule for the whole project, not an exclusion on the `eezo` line, because
`eezo-auth`, `eezo-live` and `eezo-testkit` all reach `eezo-http` and would bring the binding
back.

## The warning that means a step was missed

Adding Logback without the exclusion leaves two SLF4J providers on the classpath,
`slf4j-jdk14` and `logback-classic`, and SLF4J says so at every boot. Add the exclusion.

## Levels

The loggers to tune in `logback.xml`:

```xml
<logger name="io.eezo" level="INFO"/>
<logger name="org.eclipse.jetty" level="WARN"/>
<logger name="com.zaxxer.hikari" level="WARN"/>
```
