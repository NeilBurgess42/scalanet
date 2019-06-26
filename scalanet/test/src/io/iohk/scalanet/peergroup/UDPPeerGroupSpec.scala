package io.iohk.scalanet.peergroup

import org.scalatest.FlatSpec
import org.scalatest.Matchers._
import io.iohk.scalanet.NetUtils._

import scala.concurrent.Future
import scala.concurrent.duration._
import io.iohk.decco.auto._
import io.iohk.decco.BufferInstantiator.global.HeapByteBuffer
import io.iohk.decco.Codec
import io.iohk.scalanet.NetUtils
import monix.execution.Scheduler.Implicits.global
import org.scalatest.concurrent.ScalaFutures._
import io.iohk.scalanet.TaskValues._
import io.iohk.scalanet.peergroup.PeerGroup.MessageMTUException
import org.scalatest.RecoverMethods._

class UDPPeerGroupSpec extends FlatSpec {

  implicit val patienceConfig = PatienceConfig(5 seconds)

  behavior of "UDPPeerGroup"

  it should "report an error for sending a message greater than the MTU" in
    withARandomUDPPeerGroup[Array[Byte]] { alice =>
      val address = InetMultiAddress(NetUtils.aRandomAddress())
      val invalidMessage = NetUtils.randomBytes(65535 + 1)
      val messageSize = Codec[Array[Byte]].encode(invalidMessage).capacity()

      val error = recoverToExceptionIf[MessageMTUException[InetMultiAddress]] {
        alice.client(address).flatMap(channel => channel.sendMessage(invalidMessage)).runAsync
      }.futureValue

      error.size shouldBe messageSize
      error.mtu shouldBe 65535
    }

  it should "send and receive a message" in withTwoRandomUDPPeerGroups[String] { (alice, bob) =>
    val alicesMessage = NetUtils.randomBytes(1024).mkString
    val bobsMessage = NetUtils.randomBytes(1024).mkString

    val bobReceived: Future[String] = bob.server().mergeMap(channel => channel.in).headL.runAsync
    bob.server().foreach(channel => channel.sendMessage(bobsMessage).runAsync)

    val aliceClient = alice.client(bob.processAddress).evaluated
    val aliceReceived = aliceClient.in.headL.runAsync
    aliceClient.sendMessage(alicesMessage).runAsync

    bobReceived.futureValue shouldBe alicesMessage
    aliceReceived.futureValue shouldBe bobsMessage
  }

  it should "shutdown cleanly" in {
    val pg1 = randomUDPPeerGroup[String]
    isListeningUDP(pg1.config.bindAddress) shouldBe true

    pg1.shutdown().runAsync.futureValue

    isListeningUDP(pg1.config.bindAddress) shouldBe false
  }
}
