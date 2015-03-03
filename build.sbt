name := "game-of-life"

version := "1.0"

scalaVersion := "2.11.5"

resolvers += "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/"

val akkaDeps : List[ModuleID] = for {
  artefact <- List("akka-actor","akka-slf4j")
} yield ("com.typesafe.akka" %% artefact % "2.3.9")


libraryDependencies ++= akkaDeps

libraryDependencies += "ch.qos.logback" % "logback-classic" % "1.0.13"

Revolver.settings
    

fork in run := true