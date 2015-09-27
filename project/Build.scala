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
        version := "0.0.8-SNAPSHOT",
        scalaVersion := "2.11.7",
        scalacOptions ++= Seq(
          "-unchecked", "-deprecation", "-feature", "-Xfatal-warnings",
          "-Xlint", "-Xfuture",
          "-Yinline-warnings", "-Ywarn-adapted-args", "-Ywarn-inaccessible",
          "-Ywarn-nullary-override", "-Ywarn-nullary-unit", "-Yno-adapted-args"
        ),
        libraryDependencies ++= Seq(
          "org.scala-js" %%% "scalajs-dom" % "0.8.1",
          "org.monifu" %%% "monifu" % "1.0-RC3",
          "com.lihaoyi" %%% "upickle" % "0.3.6",
          "com.lihaoyi" %%% "utest" % "0.3.1" % "test"
        ),
        scalaJSStage := FastOptStage,
        testFrameworks += new TestFramework("utest.runner.Framework"),
        autoAPIMappings := true,
        requiresDOM := true,
        persistLauncher in Test := false,
        publishMavenStyle := true,
        publishArtifact in Test := false,
        pomIncludeRepository := { _ => false },
        publishTo := {
          val nexus = "https://oss.sonatype.org/"
          if (isSnapshot.value)
            Some("snapshots" at nexus + "content/repositories/snapshots")
          else
            Some("releases"  at nexus + "service/local/staging/deploy/maven2")
        },
        pomExtra :=
          <url>https://github.com/viagraphs/scalajs-rx-idb</url>
            <licenses>
              <license>
                <name>The MIT License (MIT)</name>
                <url>http://opensource.org/licenses/MIT</url>
                <distribution>repo</distribution>
              </license>
            </licenses>
            <scm>
              <url>git@github.com:viagraphs/scalajs-rx-idb.git</url>
              <connection>scm:git:git@github.com:viagraphs/scalajs-rx-idb.git</connection>
            </scm>
            <developers>
              <developer>
                <id>l15k4</id>
                <name>Jakub Liska</name>
                <email>liska.jakub@gmail.com</email>
              </developer>
            </developers>
      )
}