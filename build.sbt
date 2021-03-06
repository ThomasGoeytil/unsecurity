ThisBuild / organization := "io.unsecurity"
ThisBuild / crossScalaVersions := Seq("2.12.8", "2.11.12")
ThisBuild / scalaVersion := crossScalaVersions.value.head
ThisBuild / useGpg := true

lazy val root = (project in file(".")).settings(name := "unsecurity")
  .aggregate(core, auth0)

lazy val core = project
lazy val auth0 = project.dependsOn(core)

publishArtifact := false
publishTo := Some(Resolver.file("Unused transient repository", file("target/unusedrepo")))
