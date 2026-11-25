package gameoflife

import com.typesafe.config.ConfigFactory
import gameoflife.ui.*
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.wordspec.AnyWordSpecLike
import scala.concurrent.duration.*

class UiComponentsSpec
    extends ScalaTestWithActorTestKit(
      ConfigFactory
        .parseString(
          """pekko.actor.provider = local
            |pekko.actor.allow-java-serialization = off
            |pekko.actor.serialization-bindings {
            |  "gameoflife.CborSerializable" = jackson-cbor
            |}
            |""".stripMargin
        )
        .withFallback(ConfigFactory.load())
    )
    with AnyWordSpecLike:

  private def defaultStatus: SimulationStatus =
    SimulationStatus(
      simulationId = 0,
      boardSpec = BoardSpec(BoardSize(4, 4), seed = 42L, pattern = BoardPattern.Random),
      started = false,
      paused = false,
      step = 0,
      tickMs = 1000L,
      autoCrashEnabled = false,
      autoCrashEveryMs = 5000L,
      crashCount = 0
    )

  private def defaultGameConfig: GameConfig =
    GameConfig(
      boardSize = BoardSize(3, 3),
      boardSeed = 42L,
      simulationParams = SimulationParams(
        waitForBackends = 1.second,
        startDelay = 100.millis,
        tick = 500.millis,
        firstErrorAfter = 5.seconds,
        errorEvery = 5.seconds,
        maxNumberOfCrashes = 100
      )
    )

  "UiEventHub" should {
    "buffer epoch history, broadcast SSE frames, and clear history on reset" in {
      val hub    = new UiEventHub(defaultStatus, maxHistory = 3, maxLogs = 10)
      val out    = new ByteArrayOutputStream()
      val client = hub.addClient(out)

      try
        for e <- 0 to 3 do
          hub.publish(
            UiEvent.EpochCompleted(
              EpochSnapshot(
                simulationId = 0,
                epoch = e,
                width = 2,
                height = 2,
                timestampMs = 1000L + e,
                cells = Vector(CellSnapshot(0, 0, alive = true, "pekko://S@127.0.0.1:2552"))
              )
            )
          )

        hub.historySnapshot().map(_.epoch) shouldBe Vector(1, 2, 3)

        val streamed = new String(out.toByteArray, StandardCharsets.UTF_8)
        streamed should include("event: epoch")
        streamed should include("127.0.0.1:2552")

        val newSpec = BoardSpec(BoardSize(6, 6), 7L, BoardPattern.Glider)
        hub.publish(UiEvent.SimulationReset(1, newSpec))
        hub.historySnapshot() shouldBe empty
        hub.currentStatus().simulationId shouldBe 1

        val hydrated = UiJson.parseObject(hub.fullStateJson())
        hydrated.path("status").path("simulationId").asInt() shouldBe 1
        hydrated.path("epochHistory").size() shouldBe 0
      finally
        hub.removeClient(client)
        hub.close()
    }
  }

  "BoardCoordinator interactive commands" should {
    "handle StepOnce, UpdateTickInterval, SetAutoCrash, CrashCell, and ResetSimulation" in {
      val cellProbe   = createTestProbe[CellActor.Command]()
      val statusProbe = createTestProbe[SimulationStatus]()
      val coord       = spawn(
        BoardCoordinator.withEntityLookup(
          config = defaultGameConfig,
          entityRefFor = _ => cellProbe.ref,
          subscribeToCluster = false,
          autoStartOnBackendJoin = false,
          autoCrashInitiallyEnabled = false
        )
      )

      coord ! BoardCoordinator.StepOnce
      val stepMsgs =
        (1 to 9).map(_ => cellProbe.expectMessageType[CellActor.AdvanceToEpoch](3.seconds))
      stepMsgs.forall(_.epoch == 1) shouldBe true

      coord ! BoardCoordinator.UpdateTickInterval(250.millis)
      coord ! BoardCoordinator.SetAutoCrash(enabled = true, interval = Some(2.seconds))
      coord ! BoardCoordinator.CrashCell(Position(1, 1))
      cellProbe.expectMessage(3.seconds, CellActor.DoCrash)

      coord ! BoardCoordinator.GetStatus(statusProbe.ref)
      val status1 = statusProbe.expectMessageType[SimulationStatus](3.seconds)
      status1.step shouldBe 1
      status1.tickMs shouldBe 250L
      status1.autoCrashEnabled shouldBe true
      status1.crashCount shouldBe 1

      val newSpec = BoardSpec(BoardSize(2, 2), seed = 99L, pattern = BoardPattern.Blinker)
      coord ! BoardCoordinator.ResetSimulation(newSpec, autoStart = false)

      // 9 Passivate messages for the 3x3 board + 4 AdvanceToEpoch(0) for the new 2x2 board
      val resetMsgs = (1 to 13).map(_ => cellProbe.expectMessageType[CellActor.Command](3.seconds))
      resetMsgs.count(_ == CellActor.Passivate) shouldBe 9
      resetMsgs.collect { case CellActor.AdvanceToEpoch(0, _) => () }.size shouldBe 4

      coord ! BoardCoordinator.GetStatus(statusProbe.ref)
      val status2 = statusProbe.expectMessageType[SimulationStatus](3.seconds)
      status2.simulationId shouldBe 1
      status2.step shouldBe 0
      status2.boardSpec shouldBe newSpec
    }
  }

  "UiHttpServer" should {
    "serve static assets and route simulation and crash REST commands" in {
      val coordProbe = createTestProbe[BoardCoordinator.Command]()
      val hub        = new UiEventHub(defaultStatus)
      val procMgr    = new BackendProcessManager(seedPort = 2551, onEvent = hub.publish)
      val server     = new UiHttpServer(
        port = 0,
        coordinator = coordProbe.ref,
        eventHub = hub,
        backendManager = procMgr,
        host = "127.0.0.1"
      )
      val port   = server.start()
      val client = HttpClient.newHttpClient()
      val base   = s"http://127.0.0.1:$port"

      try
        val indexResp = client.send(
          HttpRequest.newBuilder(URI.create(s"$base/")).GET().build(),
          HttpResponse.BodyHandlers.ofString()
        )
        indexResp.statusCode() shouldBe 200
        indexResp.body() should include("Pekko Game of Life")

        val stateResp = client.send(
          HttpRequest.newBuilder(URI.create(s"$base/api/state")).GET().build(),
          HttpResponse.BodyHandlers.ofString()
        )
        stateResp.statusCode() shouldBe 200
        val stateJson = UiJson.parseObject(stateResp.body())
        stateJson.path("patterns").size() shouldBe BoardPattern.values.length

        val stepResp = client.send(
          HttpRequest
            .newBuilder(URI.create(s"$base/api/simulation/step"))
            .POST(HttpRequest.BodyPublishers.ofString("{}"))
            .build(),
          HttpResponse.BodyHandlers.ofString()
        )
        stepResp.statusCode() shouldBe 200
        coordProbe.expectMessage(3.seconds, BoardCoordinator.StepOnce)

        val crashResp = client.send(
          HttpRequest
            .newBuilder(URI.create(s"$base/api/cells/crash"))
            .POST(HttpRequest.BodyPublishers.ofString("""{"x":2,"y":1}"""))
            .build(),
          HttpResponse.BodyHandlers.ofString()
        )
        crashResp.statusCode() shouldBe 200
        coordProbe.expectMessage(3.seconds, BoardCoordinator.CrashCell(Position(2, 1)))

        val resetResp = client.send(
          HttpRequest
            .newBuilder(URI.create(s"$base/api/simulation/reset"))
            .POST(
              HttpRequest.BodyPublishers.ofString(
                """{"width":8,"height":8,"seed":7,"pattern":"glider","autoStart":true}"""
              )
            )
            .build(),
          HttpResponse.BodyHandlers.ofString()
        )
        resetResp.statusCode() shouldBe 200
        coordProbe.expectMessage(
          3.seconds,
          BoardCoordinator.ResetSimulation(
            BoardSpec(BoardSize(8, 8), 7L, BoardPattern.Glider),
            autoStart = true
          )
        )
      finally
        server.stop()
        procMgr.stopAll()
    }
  }

  "BackendProcessManager" should {
    "allocate distinct free ports" in {
      val mgr   = new BackendProcessManager(seedPort = 35500, onEvent = _ => ())
      val port1 = mgr.allocateFreePort()
      val port2 = mgr.allocateFreePort()
      port1 should be >= 35501
      port2 should be > port1
    }
  }
