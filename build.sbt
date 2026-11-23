ThisBuild / scalaVersion := "2.13.12"
ThisBuild / organization := "morganite"
ThisBuild / version := "0.1.0"

lazy val root = (project in file(".")).settings(
  name := "morganite",
  Compile / unmanagedSourceDirectories += baseDirectory.value / "third_party" / "hardfloat" / "src",
  libraryDependencies += "org.chipsalliance" %% "chisel" % "6.6.0",
  addCompilerPlugin("org.chipsalliance" % "chisel-plugin" % "6.6.0" cross CrossVersion.full),
  scalacOptions ++= Seq("-deprecation", "-feature", "-language:reflectiveCalls")
)
