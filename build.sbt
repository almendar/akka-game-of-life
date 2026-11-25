name := "game-of-life"

version := "1.0"

scalaVersion := "3.3.4"

val pekkoVersion = "1.1.3"

val pekkoDeps: List[ModuleID] = for {
  artefact <- List("pekko-actor", "pekko-cluster", "pekko-slf4j", "pekko-serialization-jackson")
} yield "org.apache.pekko" %% artefact % pekkoVersion

libraryDependencies ++= pekkoDeps

libraryDependencies ++= Seq(
  "ch.qos.logback" % "logback-classic" % "1.5.12",
  "org.apache.pekko" %% "pekko-testkit" % pekkoVersion % Test,
  "org.scalatest" %% "scalatest" % "3.2.19" % Test
)

run / fork := true