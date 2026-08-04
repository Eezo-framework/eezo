package eezo

import java.io.DataInputStream

import munit.FunSuite

/** The build's toolchain promises, asserted rather than assumed.
  *
  * This suite runs in CI, so a wrong JDK, a drifted Scala version or a build that quietly targets
  * the JVM it happened to run on fails the pipeline instead of being discovered at a user's
  * runtime.
  */
class ToolchainSuite extends FunSuite:

  test("the build compiles against Scala 3.8.x"):
    assert(
      BuildInfo.scalaVersion.startsWith("3.8."),
      s"expected Scala 3.8.x, the build used ${BuildInfo.scalaVersion}"
    )

  test("the JVM running the tests meets eezo's JDK floor"):
    val running = Runtime.version().feature()
    assert(
      running >= BuildInfo.jdkTarget,
      s"eezo needs JDK ${BuildInfo.jdkTarget} or newer, these tests run on JDK $running"
    )

  test("compiled classes target the JDK floor, not whichever JVM built them"):
    // Java 1.0 stamps class file major version 45, and it counts up by one per release.
    val expected = BuildInfo.jdkTarget + 44
    assertEquals(
      classFileMajorOfBuildInfo,
      expected,
      s"expected class files for JDK ${BuildInfo.jdkTarget}; `-release` is not being applied"
    )

  /** The major version in the header of `BuildInfo`'s own class file: four magic bytes, then the
    * minor and major version as unsigned shorts.
    */
  private def classFileMajorOfBuildInfo: Int =
    val cls      = BuildInfo.getClass
    val resource = "/" + cls.getName.replace('.', '/') + ".class"
    val stream   = cls.getResourceAsStream(resource)
    assert(stream != null, s"cannot read $resource off the test classpath")
    val in = DataInputStream(stream)
    try
      assertEquals(in.readInt(), 0xcafebabe, s"$resource does not start with the class file magic")
      in.readUnsignedShort() // minor
      in.readUnsignedShort() // major
    finally in.close()
