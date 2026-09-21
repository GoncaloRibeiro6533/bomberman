import org.typelevel.scalacoptions.ScalacOptions

lazy val thisBuildSettings: Seq[Setting[_]] = inThisBuild(
  Seq(
    version      := "0.2",
    scalaVersion := "2.13.18",

    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-Ymacro-annotations",
      "-Xsource:3-cross"
    ),

    tpolecatExcludeOptions += ScalacOptions.warnNonUnitStatement,

    run / fork           := true,
    run / connectInput   := true,
    run / outputStrategy := Some(StdoutOutput),
  )
)

val http4sVersion      = "0.23.36"
val circeVersion       = "0.14.1"
val catsVersion        = "2.9.0"
val catsTaglessVersion = "0.14.0"
val catsEffect3Version = "3.3.0"
val log4CatsVersion    = "2.5.0"
val scalaTestVersion = "3.2.7.0"


lazy val root = project
  .in(file("."))
  .settings(
    name := "bomberman",

    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-core"   % catsVersion,
      "org.typelevel" %% "cats-effect" % catsEffect3Version,
      "org.http4s" %% "http4s-dsl"              % http4sVersion,
      "org.http4s" %% "http4s-ember-server"    % http4sVersion,
      "org.http4s" %% "http4s-ember-client"    % http4sVersion,
      "org.http4s" %% "http4s-circe"           % http4sVersion,
      "org.http4s" %% "http4s-jdk-http-client" % "0.9.0",
      "io.circe" %% "circe-core"    % circeVersion,
      "io.circe" %% "circe-generic" % circeVersion,
      "io.circe" %% "circe-parser"  % circeVersion,
      "org.typelevel" %% "log4cats-slf4j" % log4CatsVersion,
      "ch.qos.logback" % "logback-classic" % "1.5.13",
      "org.typelevel" %% "cats-tagless-macros" % catsTaglessVersion,
      "org.typelevel" %% "cats-effect-testing-scalatest" % "1.5.0" % Test,
      "org.scalameta"  %% "munit"                       % "0.7.29" % Test,
      "org.typelevel" %% "munit-cats-effect"            % "2.0.0-M3" % Test,
      "org.mockito"   %% "mockito-scala"                % "1.16.32" % Test,
      "org.scalatestplus"        %% "scalacheck-1-15"               % scalaTestVersion % Test,
      "org.scalatestplus"        %% "selenium-3-141"                % scalaTestVersion % Test,
      "org.typelevel" %% "cats-effect-testkit" % "3.7.1" % Test,
    )
  )

thisBuildSettings