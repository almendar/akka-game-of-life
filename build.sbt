name := "game-of-life"

version := "2.0.0"

scalaVersion := "3.3.9"

val pekkoVersion = "1.7.0"

scalacOptions ++= Seq(
  "-deprecation",
  "-feature",
  "-unchecked",
  "-Wunused:all",
  "-Wvalue-discard",
  "-Werror"
)

val pekkoDeps: Seq[ModuleID] = Seq(
  "pekko-actor-typed",
  "pekko-cluster-typed",
  "pekko-cluster-sharding-typed",
  "pekko-slf4j",
  "pekko-serialization-jackson"
).map(artifact => "org.apache.pekko" %% artifact % pekkoVersion)

libraryDependencies ++= pekkoDeps ++ Seq(
  "ch.qos.logback"    % "logback-classic"          % "1.5.37",
  "org.apache.pekko" %% "pekko-actor-testkit-typed" % pekkoVersion % Test,
  "org.scalatest"    %% "scalatest"                % "3.2.19"     % Test
)

run / fork  := true
Test / fork := true