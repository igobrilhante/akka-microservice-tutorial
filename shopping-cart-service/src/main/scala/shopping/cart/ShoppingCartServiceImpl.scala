package shopping.cart

import java.util.concurrent.TimeoutException

import scala.concurrent.{ExecutionContext, Future}

import akka.actor.typed.{ActorSystem, DispatcherSelector}
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.grpc.GrpcServiceException
import akka.util.Timeout

import io.grpc.Status
import org.slf4j.LoggerFactory
import shopping.cart.api.service.proto
import shopping.cart.api.service.proto.{AdjustItemQuantityRequest, Cart, RemoveItemRequest}
import shopping.cart.repository._

class ShoppingCartServiceImpl(system: ActorSystem[_], itemPopularityRepository: ItemPopularityRepository)
    extends proto.ShoppingCartService {
  import system.executionContext

  private val logger = LoggerFactory.getLogger(getClass)

  implicit private val timeout: Timeout =
    Timeout.create(system.settings.config.getDuration("shopping-cart-service.ask-timeout"))

  private val sharding = ClusterSharding(system)
  private val blockingJdbcExecutor: ExecutionContext =
    system.dispatchers.lookup(DispatcherSelector.fromConfig("akka.projection.jdbc.blocking-jdbc-dispatcher"))

  override def addItem(in: proto.AddItemRequest): Future[proto.Cart] = {
    logger.info(s"addItem ${in.itemId} to cart ${in.cartId}")
    val entityRef = getEntityRef(in.cartId)
    val reply =
      entityRef.askWithStatus(ShoppingCart.AddItem(in.itemId, in.quantity, _))
    val response = reply.map(cart => toProtoCart(cart))
    convertError(response)
  }

  override def checkout(in: proto.CheckoutCartRequest): Future[proto.Cart] = {
    val entityRef = getEntityRef(in.cartId)
    val reply     = entityRef.askWithStatus(ShoppingCart.Checkout)
    val response  = reply.map(toProtoCart)
    convertError(response)
  }

  override def get(in: proto.GetCartRequest): Future[proto.Cart] = {
    val entityRef = getEntityRef(in.cartId)
    val reply     = entityRef.ask(ShoppingCart.Get)
    val response  = reply.map(toProtoCart)
    convertError(response)
  }

  override def getItemPopularity(in: proto.GetItemPopularityRequest): Future[proto.GetItemPopularityResponse] = {

    Future {
      ScalikeJdbcSession.withSession { session =>
        itemPopularityRepository.getItem(session, in.itemId)
      }
    }(blockingJdbcExecutor).map {
      case Some(count) =>
        proto.GetItemPopularityResponse(in.itemId, count)
      case None =>
        proto.GetItemPopularityResponse(in.itemId)
    }
  }

  override def removeItem(in: RemoveItemRequest): Future[Cart] = {
    val entityRef = getEntityRef(in.cartId)
    val reply     = entityRef.askWithStatus(ShoppingCart.RemoveItem(in.itemId, _))
    val response  = reply.map(toProtoCart)
    convertError(response)
  }

  override def adjustItemQuantity(in: AdjustItemQuantityRequest): Future[Cart] = {
    val entityRef = getEntityRef(in.cartId)
    val reply     = entityRef.askWithStatus(ShoppingCart.AdjustItemQuantity(in.itemId, in.quantity, _))
    val response  = reply.map(toProtoCart)
    convertError(response)
  }

  private def getEntityRef(id: String) = sharding.entityRefFor(ShoppingCart.EntityKey, id)

  private def toProtoCart(cart: ShoppingCart.Summary): proto.Cart = {
    proto.Cart(
      cart.items.iterator.map { case (itemId, quantity) =>
        proto.Item(itemId, quantity)
      }.toSeq,
      checkedOut = cart.checkedOut
    )
  }

  private def convertError[T](response: Future[T]): Future[T] = {
    response.recoverWith {
      case _: TimeoutException =>
        Future.failed(new GrpcServiceException(Status.UNAVAILABLE.withDescription("Operation timed out")))
      case exc =>
        Future.failed(new GrpcServiceException(Status.INVALID_ARGUMENT.withDescription(exc.getMessage)))
    }
  }

}
