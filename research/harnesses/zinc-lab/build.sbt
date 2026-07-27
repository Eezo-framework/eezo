ThisBuild / scalaVersion := "3.7.3"
ThisBuild / logLevel := Level.Debug
ThisBuild / incOptions := (ThisBuild / incOptions).value.withRecompileAllFraction(1.0)
lazy val root = (project in file("."))
