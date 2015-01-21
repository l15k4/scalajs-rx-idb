import org.scalajs.sbtplugin.ScalaJSPlugin
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport._
import sbt.Keys._
import sbt._

object Build extends sbt.Build {

  lazy val `scalajs-rx-idb` =
    project.in(file("."))
      .enablePlugins(ScalaJSPlugin)
      .settings(
        organization := "com.viagraphs",
        name := "scalajs-rx-idb",
        version := "0.0.6-SNAPSHOT",
        scalaVersion := "2.11.5",
        scalacOptions ++= Seq(
          "-unchecked", "-deprecation", "-feature", "-Xfatal-warnings",
          "-Xlint", "-Xfuture",
          "-Yinline-warnings", "-Ywarn-adapted-args", "-Ywarn-inaccessible",
          "-Ywarn-nullary-override", "-Ywarn-nullary-unit", "-Yno-adapted-args"
        ),
        libraryDependencies ++= Seq(
          "org.scala-js" %%% "scalajs-dom" % "0.7.1-SNAPSHOT",
          "org.monifu" %%% "monifu" % "0.1-SNAPSHOT",
          "com.lihaoyi" %%% "upickle" % "0.2.6-RC1",
          "com.lihaoyi" %%% "utest" % "0.2.5-RC1" % "test"
        ),
        scalaJSStage := FastOptStage,
        testFrameworks += new TestFramework("utest.runner.Framework"),
        autoAPIMappings := true,
        requiresDOM := true,
        persistLauncher in Test := false
      )
}