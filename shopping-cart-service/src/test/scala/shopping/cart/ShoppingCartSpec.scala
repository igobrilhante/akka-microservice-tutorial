package shopping.cart

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.pattern.StatusReply
import akka.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit

import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfterEach
import org.scalatest.wordspec.AnyWordSpecLike

object ShoppingCartSpec {
  val config = ConfigFactory
    .parseString("""
      akka.actor.serialization-bindings {
        "shopping.core.adapters.ser.CborSerializable" = jackson-cbor
      }
      """)
    .withFallback(EventSourcedBehaviorTestKit.config)
}

class ShoppingCartSpec
    extends ScalaTestWithActorTestKit(ShoppingCartSpec.config)
    with AnyWordSpecLike
    with BeforeAndAfterEach {

  private val cartId = "testCart"
  private val eventSourcedTestKit =
    EventSourcedBehaviorTestKit[ShoppingCart.Command, ShoppingCart.Event, ShoppingCart.State](
      system,
      ShoppingCart(cartId, "tag-0")
    )

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    eventSourcedTestKit.clear()
  }

  "The Shopping Cart" should {

    "add item" in {
      val result1 =
        eventSourcedTestKit.runCommand[StatusReply[ShoppingCart.Summary]](replyTo =>
          ShoppingCart.AddItem("foo", 42, replyTo)
        )
      result1.reply should ===(StatusReply.Success(ShoppingCart.Summary(Map("foo" -> 42), checkedOut = false)))
      result1.event should ===(ShoppingCart.ItemAdded(cartId, "foo", 42))
    }

    "reject already added item" in {
      val result1 =
        eventSourcedTestKit.runCommand[StatusReply[ShoppingCart.Summary]](ShoppingCart.AddItem("foo", 42, _))
      result1.reply.isSuccess should ===(true)
      val result2 =
        eventSourcedTestKit.runCommand[StatusReply[ShoppingCart.Summary]](ShoppingCart.AddItem("foo", 13, _))
      result2.reply.isError should ===(true)
    }

    "checkout" in {
      val result1 =
        eventSourcedTestKit.runCommand[StatusReply[ShoppingCart.Summary]](ShoppingCart.AddItem("foo", 42, _))
      result1.reply.isSuccess should ===(true)
      val result2 = eventSourcedTestKit.runCommand[StatusReply[ShoppingCart.Summary]](ShoppingCart.Checkout)
      result2.reply should ===(StatusReply.Success(ShoppingCart.Summary(Map("foo" -> 42), checkedOut = true)))
      result2.event.asInstanceOf[ShoppingCart.CheckedOut].cartId should ===(cartId)

      val result3 =
        eventSourcedTestKit.runCommand[StatusReply[ShoppingCart.Summary]](ShoppingCart.AddItem("bar", 13, _))
      result3.reply.isError should ===(true)
    }

    "get" in {
      val result1 =
        eventSourcedTestKit.runCommand[StatusReply[ShoppingCart.Summary]](ShoppingCart.AddItem("foo", 42, _))
      result1.reply.isSuccess should ===(true)

      val result2 = eventSourcedTestKit.runCommand[ShoppingCart.Summary](ShoppingCart.Get)
      result2.reply should ===(ShoppingCart.Summary(Map("foo" -> 42), checkedOut = false))
    }

    "remove item" in {
      val result1 =
        eventSourcedTestKit.runCommand[StatusReply[ShoppingCart.Summary]](ShoppingCart.AddItem("foo", 42, _))
      result1.reply.isSuccess should ===(true)

      val result2 = eventSourcedTestKit.runCommand[StatusReply[ShoppingCart.Summary]](ShoppingCart.RemoveItem("foo", _))
      result2.reply should ===(StatusReply.Success(ShoppingCart.Summary(Map(), checkedOut = false)))
      result2.event should ===(ShoppingCart.ItemRemoved(cartId, "foo", 42))
    }

    "reject remove not existing item" in {
      val result1 =
        eventSourcedTestKit
          .runCommand[StatusReply[ShoppingCart.Summary]](ShoppingCart.AddItem("foo", 42, _))
      result1.reply.isSuccess should ===(true)

      val result2 =
        eventSourcedTestKit
          .runCommand[StatusReply[ShoppingCart.Summary]](ShoppingCart.RemoveItem("invalidItem", _))
      result2.reply.isError should ===(true)
    }

    "adjust item quantity" in {
      val expectedSummary = ShoppingCart.Summary(Map("foo" -> 50), checkedOut = false)

      val result1 =
        eventSourcedTestKit.runCommand[StatusReply[ShoppingCart.Summary]](ShoppingCart.AddItem("foo", 42, _))
      result1.reply.isSuccess should ===(true)

      val result2 =
        eventSourcedTestKit.runCommand[StatusReply[ShoppingCart.Summary]](ShoppingCart.AdjustItemQuantity("foo", 50, _))
      result2.reply should ===(StatusReply.Success(expectedSummary))
      result2.event should ===(ShoppingCart.ItemQuantityAdjusted(cartId, "foo", 42, 50))

      val result3 =
        eventSourcedTestKit.runCommand[ShoppingCart.Summary](ShoppingCart.Get)

      result3.reply === (expectedSummary)
    }

    "reject adjust quantity of a non existing item" in {
      val expectedSummary = ShoppingCart.Summary(Map("foo" -> 42), checkedOut = false)

      val result1 =
        eventSourcedTestKit.runCommand[StatusReply[ShoppingCart.Summary]](ShoppingCart.AddItem("foo", 42, _))
      result1.reply.isSuccess should ===(true)

      val result2 =
        eventSourcedTestKit.runCommand[StatusReply[ShoppingCart.Summary]](
          ShoppingCart.AdjustItemQuantity("anotherItem", 50, _)
        )
      result2.reply.isError should ===(true)

      val result3 =
        eventSourcedTestKit.runCommand[ShoppingCart.Summary](ShoppingCart.Get)

      result3.reply === (expectedSummary)
    }

    "reject adjust quantity for non-positive values" in {
      val expectedSummary = ShoppingCart.Summary(Map("foo" -> 42), checkedOut = false)

      val result1 =
        eventSourcedTestKit.runCommand[StatusReply[ShoppingCart.Summary]](ShoppingCart.AddItem("foo", 42, _))
      result1.reply.isSuccess should ===(true)

      val result2 =
        eventSourcedTestKit.runCommand[StatusReply[ShoppingCart.Summary]](
          ShoppingCart.AdjustItemQuantity("anotherItem", 0, _)
        )
      result2.reply.isError should ===(true)

      val result3 =
        eventSourcedTestKit.runCommand[StatusReply[ShoppingCart.Summary]](
          ShoppingCart.AdjustItemQuantity("anotherItem", -10, _)
        )
      result3.reply.isError should ===(true)

      val result4 =
        eventSourcedTestKit.runCommand[ShoppingCart.Summary](ShoppingCart.Get)
      result4.reply === (expectedSummary)
    }

    "reject commands when cart is checked out" in {

      // add a new item
      eventSourcedTestKit
        .runCommand[StatusReply[ShoppingCart.Summary]](ShoppingCart.AddItem("foo", 42, _))
        .reply
        .isSuccess should ===(true)

      // check out the cart
      val result2 = eventSourcedTestKit.runCommand[StatusReply[ShoppingCart.Summary]](ShoppingCart.Checkout)
      result2.reply should ===(StatusReply.Success(ShoppingCart.Summary(Map("foo" -> 42), checkedOut = true)))
      result2.event.asInstanceOf[ShoppingCart.CheckedOut].cartId should ===(cartId)

      // AddItem
      eventSourcedTestKit
        .runCommand[StatusReply[ShoppingCart.Summary]](ShoppingCart.AddItem("bar", 13, _))
        .reply
        .isError should ===(true)

      // RemoveItem
      eventSourcedTestKit
        .runCommand[StatusReply[ShoppingCart.Summary]](ShoppingCart.RemoveItem("bar", _))
        .reply
        .isError should ===(true)

      // AdjustItemQuantity
      eventSourcedTestKit
        .runCommand[StatusReply[ShoppingCart.Summary]](ShoppingCart.AdjustItemQuantity("bar", 50, _))
        .reply
        .isError should ===(true)
    }

  }

}
