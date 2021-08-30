package shopping.analytics

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

import akka.Done
import akka.actor.typed.ActorSystem
import akka.kafka.scaladsl.{Committer, Consumer}
import akka.kafka.{CommitterSettings, ConsumerSettings, Subscriptions}
import akka.stream.RestartSettings
import akka.stream.scaladsl.RestartSource

import com.google.protobuf.any.{Any => ScalaPBAny}
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.serialization.{ByteArrayDeserializer, StringDeserializer}
import org.slf4j.LoggerFactory
import shopping.cart.api.proto

object ShoppingCartEventConsumer {

  private val log =
    LoggerFactory.getLogger("shopping.analytics.ShoppingCartEventConsumer")

  def init(system: ActorSystem[_]): Unit = {
    implicit val sys: ActorSystem[_] = system
    implicit val ec: ExecutionContext =
      system.executionContext

    val topic = system.settings.config.getString("shopping-analytics-service.shopping-cart-kafka-topic")
    val consumerSettings =
      ConsumerSettings(system, new StringDeserializer, new ByteArrayDeserializer).withGroupId("shopping-cart-analytics")
    val committerSettings = CommitterSettings(system)

    RestartSource
      .onFailuresWithBackoff(RestartSettings(minBackoff = 1.second, maxBackoff = 30.seconds, randomFactor = 0.1)) {
        () =>
          Consumer
            .committableSource(consumerSettings, Subscriptions.topics(topic))
            .mapAsync(1) { msg =>
              handleRecord(msg.record).map(_ => msg.committableOffset)
            }
            .via(Committer.flow(committerSettings))
      }
      .run()
  }

  private def handleRecord(record: ConsumerRecord[String, Array[Byte]]): Future[Done] = {
    val bytes   = record.value()
    val x       = ScalaPBAny.parseFrom(bytes)
    val typeUrl = x.typeUrl
    try {
      val inputBytes = x.value.newCodedInput()
      val event = typeUrl match {
        case "shopping-cart-service/shoppingcart.ItemAdded" =>
          proto.ItemAdded.parseFrom(inputBytes)
        case "shopping-cart-service/shoppingcart.CheckedOut" =>
          proto.CheckedOut.parseFrom(inputBytes)
        case "shopping-cart-service/shoppingcart.ItemRemoved" =>
          proto.ItemRemoved.parseFrom(inputBytes)
        case "shopping-cart-service/shoppingcart.ItemQuantityAdjusted" =>
          proto.ItemQuantityAdjusted.parseFrom(inputBytes)
        case _ =>
          throw new IllegalArgumentException(s"unknown record type [$typeUrl]")
      }

      event match {
        case proto.ItemAdded(cartId, itemId, quantity, _) =>
          log.info("ItemAdded: {} {} to cart {}", quantity.toString, itemId, cartId)
        case proto.CheckedOut(cartId, _) =>
          log.info("CheckedOut: cart {} checked out", cartId)
        case proto.ItemQuantityAdjusted(cartId, itemId, quantity, _) =>
          log.info("ItemQuantityAdjusted: cart {} item {} adjusted to {}", cartId, itemId, quantity.toString)
        case proto.ItemRemoved(cartId, itemId, quantity) =>
          log.info("ItemRemoved: {} {} of cart {}", quantity.toString, itemId, cartId)

        case e => throw new IllegalArgumentException(s"unknown event [$e]")
      }

      Future.successful(Done)
    } catch {
      case NonFatal(e) =>
        log.error(s"Could not process event of type [$typeUrl]", e)
        // continue with next
        Future.successful(Done)
    }
  }

}
