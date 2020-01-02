import org.scalajs.sbtplugin.ScalaJSPlugin
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport._

lazy val `scalajs-rx-idb` =
  project.in(file("."))
    .enablePlugins(ScalaJSPlugin)
    .settings(
      organization := "com.pragmaxim",
      name := "scalajs-rx-idb",
      version := "0.0.9",
      scalaVersion := "2.11.7",
      scalacOptions ++= Seq(
        "-unchecked", "-deprecation", "-feature", "-Xfatal-warnings",
        "-Xlint", "-Xfuture",
        "-Ywarn-adapted-args", "-Ywarn-inaccessible",
        "-Ywarn-nullary-override", "-Ywarn-nullary-unit", "-Yno-adapted-args"
      ),
      libraryDependencies ++= Seq(
        "org.scala-js" %%% "scalajs-dom" % "0.8.1",
        "org.monifu" %%% "monifu" % "1.0",
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
      homepage := Some(url(s"https://github.com/l15k4/scalajs-rx-idb/blob/master/README.md")),
      licenses in ThisBuild += ("MIT", url("http://opensource.org/licenses/MIT")),
      developers += Developer("l15k4", "Jakub Liska", "liska.jakub@gmail.com", url("https://github.com/l15k4")),
      scmInfo := Some(ScmInfo(url(s"https://github.com/l15k4/scalajs-rx-idb"), s"git@github.com:l15k4/scalajs-rx-idb.git")),
      bintrayVcsUrl := Some(s"git@github.com:l15k4/scalajs-rx-idb.git"),
      bintrayOrganization := Some("pragmaxim"),
      bintrayRepository := "maven",
      pomExtra :=
        <url>https://github.com/l15k4/scalajs-rx-idb</url>
          <licenses>
            <license>
              <name>The MIT License (MIT)</name>
              <url>http://opensource.org/licenses/MIT</url>
              <distribution>repo</distribution>
            </license>
          </licenses>
          <scm>
            <url>git@github.com:l15k4/scalajs-rx-idb.git</url>
            <connection>scm:git:git@github.com:l15k4/scalajs-rx-idb.git</connection>
          </scm>
          <developers>
            <developer>
              <id>l15k4</id>
              <name>Jakub Liska</name>
              <email>liska.jakub@gmail.com</email>
            </developer>
          </developers>
    )
