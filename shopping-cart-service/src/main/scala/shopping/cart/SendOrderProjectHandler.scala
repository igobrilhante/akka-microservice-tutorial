package shopping.cart

import scala.concurrent.Future

import akka.Done
import akka.actor.typed.{ActorSystem, DispatcherSelector}
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.projection.eventsourced.EventEnvelope
import akka.projection.scaladsl.Handler
import akka.util.Timeout

import shopping.cart.ShoppingCart.{CheckedOut, Get}
import shopping.order.api.service.proto
import shopping.order.api.service.proto.{Item, OrderResponse}

class SendOrderProjectHandler(system: ActorSystem[_], service: proto.ShoppingOrderService)
    extends Handler[EventEnvelope[ShoppingCart.Event]]() {

  private implicit val ec      = system.dispatchers.lookup(DispatcherSelector.blocking())
  private val sharding         = ClusterSharding(system)
  private implicit val timeout = Timeout.create(system.settings.config.getDuration("shopping-cart-service.ask-timeout"))

  override def process(envelope: EventEnvelope[ShoppingCart.Event]): Future[Done] = {
    envelope.event match {
      case CheckedOut(cartId, _) =>
        val entityRef = sharding.entityRefFor(ShoppingCart.EntityKey, cartId)
        for {
          cart <- entityRef.ask(Get)
          items = cart.items.map { case (itemId, qtd) => Item.of(itemId, qtd) }.toSeq
          response <- service.order(proto.OrderRequest(cartId, items)).flatMap(handleResponse)
        } yield response

      case _ => Future.successful(Done)
    }

  }

  private def handleResponse(response: OrderResponse) = {
    if (response.ok) Future.successful(Done)
    else Future.failed(new Exception("Failed to send order"))
  }

}
