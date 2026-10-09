// The version comes from `scriptedLaunchOpts` in the root build, because sbt-dynver derives it
// from the git state and nothing can be written here by hand.
addSbtPlugin("io.eezo" % "sbt-eezo" % sys.props("plugin.version"))
