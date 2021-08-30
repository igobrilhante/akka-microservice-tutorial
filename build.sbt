//import sbt._

ThisBuild / organization         := "com.lightbend.akka.samples"
ThisBuild / organizationHomepage := Some(url("https://akka.io"))
ThisBuild / licenses := Seq(
  ("CC0", url("https://creativecommons.org/publicdomain/zero/1.0"))
)
ThisBuild / scalaVersion    := "2.13.6"
ThisBuild / version         := "0.1.0"
ThisBuild / dynverSeparator := "-"

ThisBuild / scalaVersion := "2.13.5"

ThisBuild / Compile / javacOptions ++= Seq("-Xlint:unchecked", "-Xlint:deprecation")
ThisBuild / Compile / scalacOptions ++= Seq(
  "-target:11",
  "-deprecation",
  "-feature",
  "-unchecked",
  "-Xlog-reflective-calls",
  "-Xlint",
  "-Xfatal-warnings"
)

ThisBuild / Test / parallelExecution := false
ThisBuild / Test / testOptions += Tests.Argument("-oDF")
ThisBuild / Test / logBuffered := false

ThisBuild / run / fork := false
Global / cancelable    := false // ctrl-c

lazy val root = (project in file("."))
  .aggregate(
    `core`,
    `shopping-cart-service`,
    `shopping-order-service`,
    `shopping-analytics-service`
  )

lazy val core = (project in file("core"))
  .enablePlugins(AkkaGrpcPlugin)

lazy val `shopping-order-service` = (project in file("shopping-order-service"))
  .enablePlugins(AkkaGrpcPlugin)
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .dependsOn(core)

lazy val `shopping-analytics-service` = (project in file("shopping-analytics-service"))
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .dependsOn(core, `shopping-cart-api`)

lazy val `shopping-cart-api` = (project in file("shopping-cart-api"))
  .enablePlugins(AkkaGrpcPlugin)
  .dependsOn(core)

lazy val `shopping-cart-service` = (project in file("shopping-cart-service"))
  .enablePlugins(AkkaGrpcPlugin)
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .dependsOn(core, `shopping-cart-api`)
