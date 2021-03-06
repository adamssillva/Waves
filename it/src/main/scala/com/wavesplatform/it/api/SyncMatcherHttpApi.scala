package com.wavesplatform.it.api

import akka.http.scaladsl.model.StatusCodes
import com.wavesplatform.account.{PrivateKeyAccount, PublicKeyAccount}
import com.wavesplatform.it.Node
import com.wavesplatform.it.api.SyncHttpApi.RequestAwaitTime
import com.wavesplatform.transaction.Proofs
import com.wavesplatform.transaction.assets.exchange.{AssetPair, Order, OrderType}
import org.asynchttpclient.util.HttpConstants
import org.asynchttpclient.{RequestBuilder, Response}
import org.scalatest.{Assertion, Assertions, Matchers}
import play.api.libs.json.Json.parse
import play.api.libs.json.{Format, Json, Writes}

import scala.concurrent.{Await, Awaitable}
import scala.concurrent.duration._
import scala.util.control.NonFatal
import scala.util.{Failure, Try}

object SyncMatcherHttpApi extends Assertions {
  case class ErrorMessage(error: Int, message: String)

  implicit val errorMessageFormat: Format[ErrorMessage] = Json.format

  case class NotFoundErrorMessage(message: String)

  object NotFoundErrorMessage {
    implicit val format: Format[NotFoundErrorMessage] = Json.format
  }

  def assertNotFoundAndMessage[R](f: => R, errorMessage: String): Assertion = Try(f) match {
    case Failure(UnexpectedStatusCodeException(_, statusCode, responseBody)) =>
      Assertions.assert(statusCode == StatusCodes.NotFound.intValue && parse(responseBody).as[NotFoundErrorMessage].message.contains(errorMessage))
    case Failure(e) => Assertions.fail(e)
    case _          => Assertions.fail(s"Expecting not found error")
  }

  def sync[A](awaitable: Awaitable[A], atMost: Duration = RequestAwaitTime) =
    try Await.result(awaitable, atMost)
    catch {
      case usce: UnexpectedStatusCodeException => throw usce
      case NonFatal(cause) =>
        throw new Exception(cause)
    }

  implicit class MatcherNodeExtSync(m: Node) extends Matchers {

    import com.wavesplatform.it.api.AsyncMatcherHttpApi.{MatcherAsyncHttpApi => async}

    private val RequestAwaitTime      = 15.seconds
    private val OrderRequestAwaitTime = 1.minutes

    def orderBook(assetPair: AssetPair): OrderBookResponse =
      sync(async(m).orderBook(assetPair))

    def marketStatus(assetPair: AssetPair): MarketStatusResponse =
      sync(async(m).marketStatus(assetPair))

    def fullOrderHistory(sender: PrivateKeyAccount): Seq[OrderbookHistory] =
      sync(async(m).fullOrdersHistory(sender))

    def orderHistoryByPair(sender: PrivateKeyAccount, assetPair: AssetPair): Seq[OrderbookHistory] =
      sync(async(m).orderHistoryByPair(sender, assetPair))

    def activeOrderHistory(sender: PrivateKeyAccount): Seq[OrderbookHistory] =
      sync(async(m).activeOrderHistory(sender))

    def placeOrder(order: Order): MatcherResponse =
      sync(async(m).placeOrder(order))

    def placeOrder(sender: PrivateKeyAccount,
                   pair: AssetPair,
                   orderType: OrderType,
                   amount: Long,
                   price: Long,
                   version: Byte = 1: Byte,
                   timeToLive: Duration = 30.days - 1.seconds): MatcherResponse =
      sync(async(m).placeOrder(sender, pair, orderType, amount, price, version, timeToLive))

    def orderStatus(orderId: String, assetPair: AssetPair, waitForStatus: Boolean = true): MatcherStatusResponse =
      sync(async(m).orderStatus(orderId, assetPair, waitForStatus))

    def transactionsByOrder(orderId: String): Seq[ExchangeTransaction] =
      sync(async(m).transactionsByOrder(orderId))

    def waitOrderStatus(assetPair: AssetPair,
                        orderId: String,
                        expectedStatus: String,
                        waitTime: Duration = OrderRequestAwaitTime): MatcherStatusResponse =
      sync(async(m).waitOrderStatus(assetPair, orderId, expectedStatus), waitTime)

    def waitOrderStatusAndAmount(assetPair: AssetPair,
                                 orderId: String,
                                 expectedStatus: String,
                                 expectedFilledAmount: Option[Long],
                                 waitTime: Duration = OrderRequestAwaitTime): MatcherStatusResponse =
      sync(async(m).waitOrderStatusAndAmount(assetPair, orderId, expectedStatus, expectedFilledAmount), waitTime)

    def reservedBalance(sender: PrivateKeyAccount, waitTime: Duration = OrderRequestAwaitTime): Map[String, Long] =
      sync(async(m).reservedBalance(sender), waitTime)

    def tradableBalance(sender: PrivateKeyAccount, assetPair: AssetPair, waitTime: Duration = OrderRequestAwaitTime): Map[String, Long] =
      sync(async(m).tradableBalance(sender, assetPair), waitTime)

    def tradingMarkets(waitTime: Duration = OrderRequestAwaitTime): MarketDataInfo =
      sync(async(m).tradingMarkets(), waitTime)

    def expectIncorrectOrderPlacement(order: Order,
                                      expectedStatusCode: Int,
                                      expectedStatus: String,
                                      waitTime: Duration = OrderRequestAwaitTime): Boolean =
      sync(async(m).expectIncorrectOrderPlacement(order, expectedStatusCode, expectedStatus), waitTime)

    def cancelOrder(sender: PrivateKeyAccount,
                    assetPair: AssetPair,
                    orderId: Option[String],
                    timestamp: Option[Long] = None,
                    waitTime: Duration = OrderRequestAwaitTime): MatcherStatusResponse =
      sync(async(m).cancelOrder(sender, assetPair, orderId, timestamp), waitTime)

    def cancelAllOrders(sender: PrivateKeyAccount,
                        timestamp: Option[Long] = None,
                        waitTime: Duration = OrderRequestAwaitTime): MatcherStatusResponse =
      sync(async(m).cancelAllOrders(sender, timestamp), waitTime)

    def cancelOrderWithApiKey(orderId: String, waitTime: Duration = OrderRequestAwaitTime): MatcherStatusResponse =
      sync(async(m).cancelOrderWithApiKey(orderId), waitTime)

    def matcherGet(path: String,
                   f: RequestBuilder => RequestBuilder = identity,
                   statusCode: Int = HttpConstants.ResponseStatusCodes.OK_200,
                   waitForStatus: Boolean = false,
                   waitTime: Duration = RequestAwaitTime): Response =
      sync(async(m).matcherGet(path, f, statusCode, waitForStatus), waitTime)

    def matcherGetStatusCode(path: String, statusCode: Int, waitTime: Duration = RequestAwaitTime): MessageMatcherResponse =
      sync(async(m).matcherGetStatusCode(path, statusCode), waitTime)

    def matcherPost[A: Writes](path: String, body: A, waitTime: Duration = RequestAwaitTime): Response =
      sync(async(m).matcherPost(path, body), waitTime)

    def prepareOrder(sender: PrivateKeyAccount,
                     pair: AssetPair,
                     orderType: OrderType,
                     amount: Long,
                     price: Long,
                     version: Byte = 1: Byte,
                     timeToLive: Duration = 30.days - 1.seconds): Order = {
      val creationTime        = System.currentTimeMillis()
      val timeToLiveTimestamp = creationTime + timeToLive.toMillis
      val matcherPublicKey    = m.publicKey
      val unsigned =
        Order(PublicKeyAccount(sender.publicKey),
              matcherPublicKey,
              pair,
              orderType,
              amount,
              price,
              creationTime,
              timeToLiveTimestamp,
              300000,
              Proofs.empty,
              version)
      Order.sign(unsigned, sender)
    }

    def ordersByAddress(sender: PrivateKeyAccount, activeOnly: Boolean, waitTime: Duration = RequestAwaitTime): Seq[OrderbookHistory] =
      sync(async(m).ordersByAddress(sender, activeOnly), waitTime)
  }

}
