package io.iohk.scalanet.monix_subjects

import monix.execution.CancelableFuture
import monix.reactive.Observable
import monix.reactive.subjects.PublishSubject
import org.scalatest.FlatSpec
import org.scalatest.Matchers._
import org.scalatest.concurrent.ScalaFutures._

class DemoSpec extends FlatSpec {
  import monix.execution.Scheduler.Implicits.global

  behavior of "MyOldPeerGroup"

  it should "lose messages" in {
    val pg = new MyOldPeerGroup()

    allProcessedMessages(pg).futureValue shouldBe List()
  }

  it should "allow multiple subscriptions to the server Observable" in {
    val pg = new MyOldPeerGroup()

    allProcessedMessages(pg).futureValue
    allProcessedMessages(pg).futureValue
  }

  behavior of "MyNewPeerGroup"

  it should "not lose messages" in {
    val pg = new MyNewPeerGroup()

    allProcessedMessages(pg).futureValue shouldBe List("a-1", "a-2", "a-3", "b-1", "b-2", "b-3")
  }

  it should "throw when multiple subscribers are added" in {
    val pg = new MyNewPeerGroup()

    pg.server.foreach(println)
    an[IllegalArgumentException] should be thrownBy pg.server.foreach(println)
  }

  private def allProcessedMessages(pg: MyNewPeerGroup): CancelableFuture[List[String]] = {
    val messagesObservable: Observable[String] = pg.server.mergeMap(_.in)
    messagesObservable.toListL.runToFuture
  }

  private def allProcessedMessages(pg: MyOldPeerGroup): CancelableFuture[List[String]] = {
    val messagesObservable = pg.server.mergeMap(_.in)
    messagesObservable.toListL.runToFuture
  }

  class MyNewPeerGroup() {

    val server = CacheUntilConnectStrictlyOneSubject[MyNewChannel]()

    server.onNext(new MyNewChannel("a"))
    server.onNext(new MyNewChannel("b"))
    server.onComplete()
  }

  class MyNewChannel(val id: String) {
    val in = CacheUntilConnectStrictlyOneSubject[String]()

    in.onNext(s"$id-1")
    in.onNext(s"$id-2")
    in.onNext(s"$id-3")
    in.onComplete()
  }

  class MyOldPeerGroup() {

    val server = PublishSubject[MyOldChannel]()

    server.onNext(new MyOldChannel("a"))
    server.onNext(new MyOldChannel("b"))
    server.onComplete()
  }

  class MyOldChannel(val id: String) {
    val in = PublishSubject[String]()

    in.onNext(s"$id-1")
    in.onNext(s"$id-2")
    in.onNext(s"$id-3")
    in.onComplete()
  }
}
