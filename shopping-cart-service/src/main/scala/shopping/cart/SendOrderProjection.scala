package shopping.cart

import akka.actor.typed.ActorSystem
import akka.cluster.sharding.typed.ShardedDaemonProcessSettings
import akka.cluster.sharding.typed.scaladsl.ShardedDaemonProcess
import akka.persistence.jdbc.query.scaladsl.JdbcReadJournal
import akka.persistence.query.Offset
import akka.projection.eventsourced.EventEnvelope
import akka.projection.eventsourced.scaladsl.EventSourcedProvider
import akka.projection.jdbc.scaladsl.JdbcProjection
import akka.projection.scaladsl.SourceProvider
import akka.projection.{ProjectionBehavior, ProjectionId}

import shopping.cart.repository.ScalikeJdbcSession
import shopping.order.api.service.proto

object SendOrderProjection {

  def init(system: ActorSystem[_], service: proto.ShoppingOrderService): Unit = {
    ShardedDaemonProcess(system).init(
      name = "SendOrderProjection",
      ShoppingCart.tags.size,
      index => ProjectionBehavior(createProjectionFor(system, service, index)),
      ShardedDaemonProcessSettings(system),
      Some(ProjectionBehavior.Stop)
    )
  }

  private def createProjectionFor(system: ActorSystem[_], service: proto.ShoppingOrderService, index: Int) = {
    val tag = ShoppingCart.tags(index)

    val sourceProvider: SourceProvider[Offset, EventEnvelope[ShoppingCart.Event]] =
      EventSourcedProvider
        .eventsByTag[ShoppingCart.Event](system = system, readJournalPluginId = JdbcReadJournal.Identifier, tag = tag)

    JdbcProjection.atLeastOnceAsync(
      projectionId = ProjectionId("SendOrderProjection", tag),
      sourceProvider,
      handler = () => new SendOrderProjectHandler(system, service),
      sessionFactory = () => new ScalikeJdbcSession()
    )(system)
  }

}
