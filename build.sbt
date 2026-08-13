lazy val thisBuildSettings: Seq[Setting[_]] = inThisBuild(
  Seq(
    version := "0.2",
    scalaVersion := "2.13.15",
    // From https://tpolecat.github.io/2017/04/25/scalac-flags.html
    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-Ymacro-annotations",
    ),
    run / fork := true,
    run / connectInput := true,
    run / outputStrategy := Some(StdoutOutput),
    addCompilerPlugin("org.typelevel" %% "kind-projector"     % "0.13.3" cross CrossVersion.full),
    addCompilerPlugin("com.olegpy"    %% "better-monadic-for" % "0.3.1"),
  )
)

val http4sVersion      = "0.23.18"
val circeVersion       = "0.14.1"
val playVersion        = "2.8.2"
val doobieVersion      = "1.0.0-RC1"
val catsVersion        = "2.9.0"
val catsTaglessVersion = "0.14.0"
val catsEffect3Version = "3.3.0"
val catsEffect2Version = "2.5.4"
val epimetheusVersion  = "0.6.0-M2"

val log4CatsVersion = "2.5.0"

val scalaTestVersion = "3.2.7.0"
val h2Version        = "2.0.202"
val slickVersion     = "3.3.3"
val munitVersion     = "0.7.29"

thisBuildSettings

lazy val domain = project.in(file("domain/"))
  .settings(
    name := "domain",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-core"                      % catsVersion,
      "eu.timepit"    %% "refined"                        % "0.9.17",
      "org.typelevel" %% "cats-core"                      % catsVersion,
      "org.typelevel" %% "cats-effect"                    % catsEffect3Version,
      "co.fs2"        %% "fs2-core"                       % "3.6.1",
      "org.typelevel" %% "cats-effect-testing-scalatest"  % "1.5.0"          % Test,
    )

  )

lazy val persistence = project.in(file("persistence/"))
  .settings(
    name := "persistence",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-core"   % catsVersion,
      "org.typelevel" %% "cats-effect" % catsEffect3Version,
      "org.tpolecat"  %% "doobie-core" % doobieVersion,
      "org.tpolecat"  %% "doobie-h2"   % doobieVersion,
      "com.h2database" % "h2"          % h2Version,
      "eu.timepit"               %% "refined"                       % "0.9.17",
    )
  )
  .dependsOn(domain)

lazy val service = project.in(file("service/"))
  .settings(
    name := "service",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect"         % catsEffect3Version,
      "org.http4s"    %% "http4s-dsl"          % http4sVersion,
      "org.http4s"    %% "http4s-ember-server" % http4sVersion,
      "org.http4s"    %% "http4s-circe"        % http4sVersion,
      "io.circe"      %% "circe-generic"       % circeVersion,
      "org.typelevel" %% "log4cats-slf4j"      % log4CatsVersion,
      "ch.qos.logback" % "logback-classic"     % "1.2.3",
      "eu.timepit"               %% "refined"                       % "0.9.17",
)
  )
  .dependsOn(domain, persistence)

lazy val root = project
  .in(file("."))
  .aggregate(domain, persistence, service)
  .settings(name := "bomberman")
