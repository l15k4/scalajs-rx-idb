resolvers += Resolver.sonatypeRepo("releases")

resolvers += Resolver.sonatypeRepo("snapshots")

addSbtPlugin("org.scala-lang.modules.scalajs" % "scalajs-sbt-plugin" % "0.6.0-SNAPSHOT")

addSbtPlugin("com.lihaoyi" % "utest-js-plugin" % "0.2.6-SNAPSHOT")
