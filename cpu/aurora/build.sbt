ThisBuild / scalaVersion     := "2.13.12"
ThisBuild / version          := "5.2.0"
ThisBuild / organization     := "aurora"

val chiselVersion = "3.6.1"

lazy val root = (project in file("."))
  .settings(
    name := "aurora",
    Compile / unmanagedSourceDirectories := Seq(
      baseDirectory.value / "src" / "Aurora"
    ),
    libraryDependencies ++= Seq(
      "edu.berkeley.cs" %% "chisel3" % chiselVersion,
      "edu.berkeley.cs" %% "chiseltest" % "0.6.2" % Test,
      "org.scalatest" %% "scalatest" % "3.2.17" % Test
    ),
    scalacOptions ++= Seq(
      "-language:reflectiveCalls",
      "-deprecation",
      "-feature",
      "-Xcheckinit",
      "-Ymacro-annotations"
    ),
    addCompilerPlugin("edu.berkeley.cs" % "chisel3-plugin" % chiselVersion cross CrossVersion.full)
  )
