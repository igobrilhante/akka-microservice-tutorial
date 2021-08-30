package shopping.cart

import scala.util.control.NonFatal

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.grpc.GrpcClientSettings
import akka.management.cluster.bootstrap.ClusterBootstrap
import akka.management.scaladsl.AkkaManagement

import org.slf4j.LoggerFactory
import shopping.cart.repository._
import shopping.order.api.service.proto.ShoppingOrderServiceClient

object Main {

  val logger = LoggerFactory.getLogger("shopping.cart.Main")

  def main(args: Array[String]): Unit = {
    val system =
      ActorSystem[Nothing](Behaviors.empty, "ShoppingCartService")
    try {
      init(system)
    } catch {
      case NonFatal(e) =>
        logger.error("Terminating due to initialization failure.", e)
        system.terminate()
    }
  }

  def init(system: ActorSystem[_]): Unit = {
    // akka cluster
    AkkaManagement(system).start()
    ClusterBootstrap(system).start()

    ScalikeJdbcSetup.init(system)
    ShoppingCart.init(system)

    val itemPopularityRepository = new ItemPopularityRepositoryImpl()

    // grpc clients
    val orderServiceClientSettings =
      GrpcClientSettings
        .connectToServiceAt(
          system.settings.config.getString("shopping-order-service.host"),
          system.settings.config.getInt("shopping-order-service.port")
        )(system)
        .withTls(false)
    val orderClient = ShoppingOrderServiceClient(orderServiceClientSettings)(system)

    // projections
    ItemPopularityProjection.init(system, itemPopularityRepository)
    PublishEventsProjection.init(system)
    SendOrderProjection.init(system, orderClient)

    // services
    val grpcService = new ShoppingCartServiceImpl(system, itemPopularityRepository)

    // grpc server
    val grpcInterface = system.settings.config.getString("shopping-cart-service.grpc.interface")
    val grpcPort      = system.settings.config.getInt("shopping-cart-service.grpc.port")
    ShoppingCartServer.start(grpcInterface, grpcPort, system, grpcService)
  }

}
