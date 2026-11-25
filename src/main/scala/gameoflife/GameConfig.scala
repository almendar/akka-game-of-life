package gameoflife

import com.typesafe.config.Config
import scala.concurrent.duration.FiniteDuration
import scala.jdk.DurationConverters.*

final case class SimulationParams(
    waitForBackends: FiniteDuration,
    startDelay: FiniteDuration,
    tick: FiniteDuration,
    firstErrorAfter: FiniteDuration,
    errorEvery: FiniteDuration,
    maxNumberOfCrashes: Int = Int.MaxValue
)

final case class GameConfig(
    boardSize: BoardSize,
    boardSeed: Long,
    simulationParams: SimulationParams
)

object GameConfig:
  def fromConfig(config: Config): GameConfig =
    val gol       = config.getConfig("game-of-life")
    val boardSize = BoardSize(
      width = gol.getInt("board.size.x"),
      height = gol.getInt("board.size.y")
    )
    val boardSeed = gol.getLong("board.seed")
    val simParams = SimulationParams(
      waitForBackends = gol.getDuration("simulation.wait-for-backends").toScala,
      startDelay = gol.getDuration("simulation.start-delay").toScala,
      tick = gol.getDuration("simulation.tick").toScala,
      firstErrorAfter = gol.getDuration("errors.delay").toScala,
      errorEvery = gol.getDuration("errors.every").toScala,
      maxNumberOfCrashes = gol.getInt("simulation.max-crashes")
    )
    GameConfig(boardSize, boardSeed, simParams)
