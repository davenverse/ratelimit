ThisBuild / tlBaseVersion := "0.1" // current series x.y

ThisBuild / organization := "io.chrisdavenport"
ThisBuild / organizationName := "Christopher Davenport"
ThisBuild / startYear := Some(2021)
ThisBuild / licenses := Seq(License.MIT)
ThisBuild / developers := List(
  tlGitHubDev("christopherdavenport", "Christopher Davenport")
)

// sbt-davenverse published a snapshot from main on every push; preserve that.
ThisBuild / tlCiReleaseBranches := Seq()

val Scala213tl = "2.13.18"
ThisBuild / crossScalaVersions := Seq("2.12.20",  Scala213tl)
ThisBuild / scalaVersion := Scala213tl

// Compiler settings DavenversePlugin injected globally. sbt-typelevel-ci-release
// does not supply these (only sbt-typelevel-settings would). Scoped to ThisBuild
// so every project picks them up without editing each one.
ThisBuild / libraryDependencies ++= (CrossVersion.partialVersion(scalaVersion.value) match {
  case Some((2, _)) =>
    Seq(
      compilerPlugin("org.typelevel" % "kind-projector" % "0.13.4" cross CrossVersion.full),
      compilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1")
    )
  case _ => Nil
})
ThisBuild / scalacOptions ++= (CrossVersion.partialVersion(scalaVersion.value) match {
  case Some((3, _)) => Seq("-Ykind-projector")
  case Some((2, 12)) => Seq("-Ypartial-unification")
  case _ => Nil
})


val Scala213 = "2.13.6"


ThisBuild / testFrameworks += new TestFramework("munit.Framework")

val catsV = "2.6.1"
val catsEffectV = "3.2.9"
val fs2V = "3.1.5"
val http4sV = "0.23.6"
val circeV = "0.14.1"
val munitCatsEffectV = "1.0.5"


// Projects
lazy val `ratelimit` = project.in(file("."))
    .enablePlugins(NoPublishPlugin)
  .aggregate(core.jvm, core.js, rediculous.jvm, rediculous.js, examples)

lazy val core = crossProject(JVMPlatform, JSPlatform)
  .crossType(CrossType.Pure)
  .in(file("core"))
  .settings(
    name := "ratelimit",

    libraryDependencies ++= Seq(
      "org.typelevel"               %%% "cats-core"                  % catsV,
      "org.typelevel"               %%% "cats-effect"                % catsEffectV,

      "io.chrisdavenport"           %%% "mapref"                     % "0.2.1",

      "co.fs2"                      %%% "fs2-core"                   % fs2V,
      "co.fs2"                      %%% "fs2-io"                     % fs2V,

      "org.http4s"                  %%% "http4s-core"                 % http4sV,

      "org.http4s"                  %%% "http4s-dsl"                  % http4sV % Test,
      "org.typelevel"               %%% "munit-cats-effect-3"        % munitCatsEffectV         % Test,

    )
  ).jsSettings(
    scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.CommonJSModule)},
  )

lazy val rediculous = crossProject(JVMPlatform, JSPlatform)
  .crossType(CrossType.Pure)
  .dependsOn(core)
  .in(file("rediculous"))
  .settings(
    name := "ratelimit-rediculous",
    libraryDependencies ++= Seq(
      "io.chrisdavenport"           %%% "rediculous"                  % "0.1.1",
      "org.http4s"                  %%% "http4s-dsl"                  % http4sV % Test,
      "org.typelevel"               %%% "munit-cats-effect-3"        % munitCatsEffectV         % Test,
    )
  ).jsSettings(
    scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.CommonJSModule)},
  )


lazy val examples = project.in(file("examples"))
    .enablePlugins(NoPublishPlugin)
  .dependsOn(core.jvm, rediculous.jvm)


lazy val site = project.in(file("site"))
    .enablePlugins(TypelevelSitePlugin)
  .settings(
    laikaTheme := tlSiteHelium.value.site
      .topNavigationBar(
        homeLink = laika.helium.config.IconLink.internal(laika.ast.Path.Root / "index.md", laika.helium.config.HeliumIcon.home)
      )
      .build
  )
  .dependsOn(core.jvm)
  .settings{
    Seq(
    )
  }
