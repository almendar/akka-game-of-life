package gameoflife

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.{ActorSystem, Props}
import org.apache.pekko.testkit.{ImplicitSender, TestKit, TestProbe}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration._

class GameOfLifeSpec
    extends TestKit(
      ActorSystem(
        "GameOfLifeSpec",
        ConfigFactory.parseString("pekko.actor.provider = local")
      )
    )
    with ImplicitSender
    with AnyWordSpecLike
    with Matchers
    with BeforeAndAfterAll {

  override def afterAll(): Unit =
    TestKit.shutdownActorSystem(system)

  "generateNeighbourAddresses" should {
    "return 3 neighbours for a corner cell" in {
      val neighbours = generateNeighbourAddresses((4, 4), (0, 0))
      neighbours.toSet shouldBe Set((0, 1), (1, 0), (1, 1))
    }

    "return 8 neighbours for an interior cell" in {
      val neighbours = generateNeighbourAddresses((4, 4), (1, 1))
      neighbours.size shouldBe 8
      neighbours should not contain (1, 1)
    }
  }

  "CellActor" should {
    "advance to the next epoch after gathering neighbour states and notify logger" in {
      val loggerProbe = TestProbe()
      val cellA = system.actorOf(Props(classOf[CellActor], (0, 0), true, loggerProbe.ref), "cell-a")
      val cellB = system.actorOf(Props(classOf[CellActor], (0, 1), false, loggerProbe.ref), "cell-b")

      cellA ! CellActor.NeighboursRefs(List(cellB))
      cellB ! CellActor.NeighboursRefs(List(cellA))

      cellA ! CellActor.CurrentEpochMsg(1)
      cellB ! CellActor.CurrentEpochMsg(1)

      val msg1 = loggerProbe.expectMsgType[BoardCreator.CellStateMsg](5.seconds)
      val msg2 = loggerProbe.expectMsgType[BoardCreator.CellStateMsg](5.seconds)

      Set(msg1.position, msg2.position) shouldBe Set((0, 0), (0, 1))
      msg1.epoch shouldBe 1
      msg2.epoch shouldBe 1
    }
  }
}
