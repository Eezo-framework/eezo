// The first eezo application: one handwritten route, no database, no derivation.
//
// `EezoPlugin` is what puts `eezo.generated.Routes` on the source path. It is `noTrigger`, so the
// `enablePlugins` below is not optional.
lazy val hello = (project in file("."))
  .enablePlugins(EezoPlugin)
  .settings(
    name         := "hello",
    scalaVersion := EezoVersion.scalaVersion,
    scalacOptions ++= Seq(
      "-release",
      EezoVersion.jdkFloor,
      "-deprecation",
      "-feature",
      "-unchecked",
      "-no-indent"
    ),
    libraryDependencies += "io.eezo" %% "eezo-http" % EezoVersion.value,
    // eezo needs JDK 25 to run: JEP 491, which removed virtual-thread pinning on `synchronized`,
    // landed in JDK 24, and the server design depends on it.
    run / fork := true
  )
