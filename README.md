# eezo
A Scala 3 web framework. Direct-style. The case class is the source of truth. Deploy with one command.

## Prerequisites
Building and running eezo requires JDK 25 or newer. JEP 491, delivered in JDK 24, removes virtual thread pinning on `synchronized` blocks, JDK 25 is the first LTS release carrying it, and eezo's server design depends on it. See `research/http-server.md` section 1.2 for the measurements.
