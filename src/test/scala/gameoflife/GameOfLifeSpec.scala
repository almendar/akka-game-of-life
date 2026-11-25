package gameoflife

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.serialization.{SerializationExtension, Serializers}
import org.scalatest.wordspec.AnyWordSpecLike
import scala.concurrent.duration.*

class GameOfLifeSpec
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

  "GameOfLife.nextState" should {
    "kill a live cell with fewer than 2 live neighbours (underpopulation)" in {
      GameOfLife.nextState(CellState.Alive, 0) shouldBe CellState.Dead
      GameOfLife.nextState(CellState.Alive, 1) shouldBe CellState.Dead
    }

    "keep a live cell alive with 2 or 3 live neighbours (survival)" in {
      GameOfLife.nextState(CellState.Alive, 2) shouldBe CellState.Alive
      GameOfLife.nextState(CellState.Alive, 3) shouldBe CellState.Alive
    }

    "kill a live cell with more than 3 live neighbours (overpopulation)" in {
      for count <- 4 to 8 do GameOfLife.nextState(CellState.Alive, count) shouldBe CellState.Dead
    }

    "revive a dead cell with exactly 3 live neighbours (reproduction)" in {
      GameOfLife.nextState(CellState.Dead, 3) shouldBe CellState.Alive
      for count <- Seq(0, 1, 2, 4, 5, 6, 7, 8) do
        GameOfLife.nextState(CellState.Dead, count) shouldBe CellState.Dead
    }
  }

  "BoardSize and Position" should {
    "enumerate exactly width * height positions without off-by-one errors" in {
      val size = BoardSize(6, 4)
      size.allPositions.size shouldBe 24
      size.cellCount shouldBe 24
      size.allPositions.forall(size.contains) shouldBe true
      size.contains(Position(6, 0)) shouldBe false
      size.contains(Position(0, 4)) shouldBe false
    }

    "return 3 neighbours for a corner, 5 for an edge, and 8 for an interior cell" in {
      val size = BoardSize(4, 4)
      size.neighbours(Position(0, 0)).toSet shouldBe Set(
        Position(0, 1),
        Position(1, 0),
        Position(1, 1)
      )
      size.neighbours(Position(0, 1)).size shouldBe 5
      val interior = size.neighbours(Position(1, 1))
      interior.size shouldBe 8
      interior should not contain Position(1, 1)
    }

    "round-trip Position through entityId" in {
      val pos = Position(3, 5)
      Position.fromEntityId(pos.entityId) shouldBe pos
    }

    "round-trip CellEntityId for both legacy and extended formats" in {
      val legacy = CellEntityId(Position(2, 4))
      legacy.encode shouldBe "2,4"
      CellEntityId.decode("2,4") shouldBe legacy

      val spec     = BoardSpec(BoardSize(12, 10), seed = 99L, pattern = BoardPattern.Glider)
      val extended = CellEntityId(Position(3, 7), simulationId = 2, boardSpec = Some(spec))
      CellEntityId.decode(extended.encode) shouldBe extended
    }

    "place BoardPattern presets deterministically on the board" in {
      val gliderSpec = BoardSpec(BoardSize(10, 10), seed = 1L, pattern = BoardPattern.Glider)
      val aliveCells =
        gliderSpec.size.allPositions.count(p => GameOfLife.initialCellState(p, gliderSpec).isAlive)
      aliveCells shouldBe 5

      val gunSpec =
        BoardSpec(BoardSize(38, 18), seed = 1L, pattern = BoardPattern.GosperGliderGun)
      val gunAlive =
        gunSpec.size.allPositions.count(p => GameOfLife.initialCellState(p, gunSpec).isAlive)
      gunAlive shouldBe 36
    }

    "reject non-positive dimensions" in {
      an[IllegalArgumentException] should be thrownBy BoardSize(0, 5)
      an[IllegalArgumentException] should be thrownBy BoardSize(5, -1)
    }
  }

  "LoggerActor" should {
    "render rows in coordinate order regardless of arrival order and ignore duplicate epochs" in {
      val boardSize    = BoardSize(2, 2)
      val renderedProb = createTestProbe[(Int, String)]()
      val logger       = spawn(
        LoggerActor(boardSize, (epoch, board) => renderedProb.ref ! (epoch, board))
      )

      // Send cells in scrambled order for epoch 1:
      // Row 0: [1,0] -> (0,0)=Alive, (1,0)=Dead
      // Row 1: [0,1] -> (0,1)=Dead,  (1,1)=Alive
      logger ! LoggerActor.CellStateReported(Position(1, 1), alive = true, epoch = 1)
      logger ! LoggerActor.CellStateReported(Position(0, 1), alive = false, epoch = 1)
      logger ! LoggerActor.CellStateReported(Position(1, 0), alive = false, epoch = 1)
      logger ! LoggerActor.CellStateReported(Position(0, 0), alive = true, epoch = 1)

      val (epoch, rendered) = renderedProb.expectMessageType[(Int, String)](3.seconds)
      epoch shouldBe 1
      rendered shouldBe
        """At epoch:1
          |-----
          |[1,0]
          |[0,1]
          |-----
          |""".stripMargin

      // Replayed reports for already-logged epoch 1 should be ignored
      logger ! LoggerActor.CellStateReported(Position(0, 0), alive = true, epoch = 1)
      logger ! LoggerActor.CellStateReported(Position(1, 0), alive = false, epoch = 1)
      logger ! LoggerActor.CellStateReported(Position(0, 1), alive = false, epoch = 1)
      logger ! LoggerActor.CellStateReported(Position(1, 1), alive = true, epoch = 1)
      renderedProb.expectNoMessage(200.millis)
    }
  }

  "CellActor" should {
    "simulate a 3x3 Blinker oscillator accurately across epochs" in {
      val boardSize   = BoardSize(3, 3)
      val loggerProbe = createTestProbe[LoggerActor.Command]()

      // Epoch 0: vertical bar at x = 1
      val initialAlive = Set(Position(1, 0), Position(1, 1), Position(1, 2))
      var cells        = Map.empty[Position, ActorRef[CellActor.Command]]

      cells = boardSize.allPositions.map { pos =>
        val state = CellState.fromBoolean(initialAlive.contains(pos))
        val ref   = spawn(
          CellActor(
            position = pos,
            boardSize = boardSize,
            initialState = state,
            neighbourLookup = p => cells(p),
            gatherRetryInterval = 200.millis
          )
        )
        pos -> ref
      }.toMap

      def collectEpoch(epoch: Int): Set[Position] =
        cells.values.foreach(_ ! CellActor.AdvanceToEpoch(epoch, loggerProbe.ref))
        val reports = (1 to boardSize.cellCount).map { _ =>
          loggerProbe.expectMessageType[LoggerActor.CellStateReported](5.seconds)
        }
        reports.foreach(_.epoch shouldBe epoch)
        reports.collect { case LoggerActor.CellStateReported(pos, true, _, _, _) => pos }.toSet

      // Epoch 1: horizontal bar at y = 1
      collectEpoch(1) shouldBe Set(Position(0, 1), Position(1, 1), Position(2, 1))
      // Epoch 2: back to vertical bar at x = 1
      collectEpoch(2) shouldBe initialAlive
    }

    "report epoch 0 with nodeAddress and emit CellCrashed on PreRestart" in {
      val boardSize   = BoardSize(2, 2)
      val loggerProbe = createTestProbe[LoggerActor.Command]()
      val dummyNbr    = createTestProbe[CellActor.Command]()
      val cell        = spawn(
        CellActor(
          position = Position(0, 0),
          boardSize = boardSize,
          initialState = CellState.Alive,
          neighbourLookup = _ => dummyNbr.ref,
          nodeAddress = "pekko://TheGameOfLife@127.0.0.1:2552",
          simulationId = 3
        )
      )

      cell ! CellActor.AdvanceToEpoch(0, loggerProbe.ref)
      val report = loggerProbe.expectMessageType[LoggerActor.CellStateReported](3.seconds)
      report shouldBe LoggerActor.CellStateReported(
        Position(0, 0),
        alive = true,
        epoch = 0,
        nodeAddress = "pekko://TheGameOfLife@127.0.0.1:2552",
        simulationId = 3
      )

      cell ! CellActor.DoCrash
      val crash = loggerProbe.expectMessageType[LoggerActor.CellCrashed](3.seconds)
      crash shouldBe LoggerActor.CellCrashed(
        Position(0, 0),
        epoch = 0,
        nodeAddress = "pekko://TheGameOfLife@127.0.0.1:2552",
        simulationId = 3
      )
    }

    "recover after a simulated crash and catch up using neighbour histories" in {
      val boardSize   = BoardSize(2, 2)
      val loggerProbe = createTestProbe[LoggerActor.Command]()

      // 2x2 Block (still life: all 4 cells stay alive forever)
      var cells = Map.empty[Position, ActorRef[CellActor.Command]]
      cells = boardSize.allPositions.map { pos =>
        val ref = spawn(
          CellActor(
            position = pos,
            boardSize = boardSize,
            initialState = CellState.Alive,
            neighbourLookup = p => cells(p),
            gatherRetryInterval = 200.millis
          )
        )
        pos -> ref
      }.toMap

      // Advance to epoch 1
      cells.values.foreach(_ ! CellActor.AdvanceToEpoch(1, loggerProbe.ref))
      val epoch1 =
        (1 to 4).map(_ => loggerProbe.expectMessageType[LoggerActor.CellStateReported](5.seconds))
      epoch1.forall(r => r.epoch == 1 && r.alive) shouldBe true

      // Crash cell (0,0) and advance everyone to epoch 2
      val crashedPos = Position(0, 0)
      cells(crashedPos) ! CellActor.DoCrash
      cells.values.foreach(_ ! CellActor.AdvanceToEpoch(2, loggerProbe.ref))

      // The restarted cell replays epoch 1 and then computes epoch 2; collect until all 4 report epoch 2
      var epoch2Reports = Map.empty[Position, Boolean]
      val deadline      = 5.seconds.fromNow
      while epoch2Reports.size < 4 && deadline.hasTimeLeft() do
        loggerProbe.expectMessageType[LoggerActor.Command](deadline.timeLeft) match
          case LoggerActor.CellStateReported(pos, alive, 2, _, _) =>
            epoch2Reports += (pos -> alive)
          case _ => ()

      epoch2Reports shouldBe boardSize.allPositions.map(_ -> true).toMap
    }
  }

  "Jackson CBOR serialization" should {
    "round-trip all cluster wire messages without Java serialization" in {
      val serialization = SerializationExtension(system)
      val loggerProbe   = createTestProbe[LoggerActor.Command]()
      val gathererProbe = createTestProbe[NextStateCellGathererActor.Command]()

      val messages: Seq[CborSerializable] = Seq(
        CellActor.AdvanceToEpoch(3, loggerProbe.ref),
        CellActor.GetStateForEpoch(2, gathererProbe.ref),
        CellActor.DoCrash,
        CellActor.Passivate,
        CellActor.Stop,
        NextStateCellGathererActor.StateForEpoch(2, alive = true, Position(1, 2)),
        LoggerActor.CellStateReported(
          Position(0, 1),
          alive = false,
          epoch = 4,
          nodeAddress = "pekko://TheGameOfLife@127.0.0.1:2552",
          simulationId = 2
        ),
        LoggerActor.CellCrashed(
          Position(1, 1),
          epoch = 3,
          nodeAddress = "pekko://TheGameOfLife@127.0.0.1:2553",
          simulationId = 2
        )
      )

      messages.foreach { msg =>
        val serializer = serialization.findSerializerFor(msg)
        serializer.getClass.getName should include("JacksonCborSerializer")
        val bytes    = serialization.serialize(msg).get
        val manifest = Serializers.manifestFor(serializer, msg)
        val restored = serialization.deserialize(bytes, serializer.identifier, manifest).get
        restored shouldBe msg
      }
    }
  }
