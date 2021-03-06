package com.wavesplatform.matcher.market

import akka.actor.Props
import akka.http.scaladsl.model._
import akka.persistence._
import com.wavesplatform.matcher.MatcherSettings
import com.wavesplatform.matcher.api._
import com.wavesplatform.matcher.market.OrderBookActor._
import com.wavesplatform.matcher.model.Events.{Event, ExchangeTransactionCreated, OrderAdded, OrderExecuted}
import com.wavesplatform.matcher.model.MatcherModel.{Level, Price}
import com.wavesplatform.matcher.model._
import com.wavesplatform.metrics.TimerExt
import com.wavesplatform.network._
import com.wavesplatform.state.ByteStr
import com.wavesplatform.transaction.ValidationError
import com.wavesplatform.transaction.ValidationError.{AccountBalanceError, NegativeAmount, OrderValidationError}
import com.wavesplatform.transaction.assets.exchange._
import com.wavesplatform.utils.{NTP, ScorexLogging}
import com.wavesplatform.utx.UtxPool
import io.netty.channel.group.ChannelGroup
import kamon.Kamon
import play.api.libs.json._

import scala.annotation.tailrec
import scala.concurrent.ExecutionContext.Implicits.global

class OrderBookActor(assetPair: AssetPair,
                     updateSnapshot: OrderBook => Unit,
                     utx: UtxPool,
                     allChannels: ChannelGroup,
                     settings: MatcherSettings,
                     createTransaction: OrderExecuted => Either[ValidationError, ExchangeTransaction])
    extends PersistentActor
    with ScorexLogging {

  override def persistenceId: String = OrderBookActor.name(assetPair)

  private val timer       = Kamon.timer("matcher.orderbook.match").refine("pair"    -> assetPair.toString)
  private val cancelTimer = Kamon.timer("matcher.orderbook.persist").refine("event" -> "OrderCancelled")

  private val cleanupCancellable = context.system.scheduler.schedule(settings.orderCleanupInterval, settings.orderCleanupInterval, self, OrderCleanup)
  private var orderBook          = OrderBook.empty
  private var lastTrade          = Option.empty[Order]

  private def fullCommands: Receive = readOnlyCommands orElse snapshotsCommands orElse executeCommands

  private def executeCommands: Receive = {
    case order: Order        => onAddOrder(order)
    case cancel: CancelOrder => onCancelOrder(cancel.orderId)
    case OrderCleanup        => onOrderCleanup(orderBook, NTP.correctedTime())
  }

  private def snapshotsCommands: Receive = {
    case SaveSnapshot =>
      saveSnapshot(Snapshot(orderBook))

    case SaveSnapshotSuccess(metadata) =>
      log.debug(s"Snapshot has been saved: $metadata")
      deleteMessages(metadata.sequenceNr)
      deleteSnapshots(SnapshotSelectionCriteria.Latest.copy(maxSequenceNr = metadata.sequenceNr - 1))

    case SaveSnapshotFailure(metadata, reason) =>
      log.error(s"Failed to save snapshot: $metadata", reason)

    case DeleteOrderBookRequest(pair) =>
      updateSnapshot(OrderBook.empty)
      orderBook.asks.values
        .++(orderBook.bids.values)
        .flatten
        .foreach(x => context.system.eventStream.publish(Events.OrderCanceled(x, unmatchable = false)))
      deleteMessages(lastSequenceNr)
      deleteSnapshots(SnapshotSelectionCriteria.Latest)
      sender() ! GetOrderBookResponse(NTP.correctedTime(), pair, Seq(), Seq())
      context.stop(self)

    case DeleteSnapshotsSuccess(criteria) =>
      log.info(s"$persistenceId DeleteSnapshotsSuccess with $criteria")

    case DeleteSnapshotsFailure(criteria, cause) =>
      log.error(s"$persistenceId DeleteSnapshotsFailure with $criteria, reason: $cause")

    case DeleteMessagesSuccess(toSequenceNr) =>
      log.info(s"$persistenceId DeleteMessagesSuccess up to $toSequenceNr")

    case DeleteMessagesFailure(cause: Throwable, toSequenceNr: Long) =>
      log.error(s"$persistenceId DeleteMessagesFailure up to $toSequenceNr, reason: $cause")
  }

  private def readOnlyCommands: Receive = {
    case GetOrdersRequest =>
      sender() ! GetOrdersResponse(orderBook.asks.values.flatten.toSeq ++ orderBook.bids.values.flatten.toSeq)
    case GetAskOrdersRequest =>
      sender() ! GetOrdersResponse(orderBook.asks.values.flatten.toSeq)
    case GetBidOrdersRequest =>
      sender() ! GetOrdersResponse(orderBook.bids.values.flatten.toSeq)
    case GetMarketStatusRequest(pair) =>
      handleGetMarketStatus(pair)
  }

  private def onOrderCleanup(orderBook: OrderBook, ts: Long): Unit = {
    orderBook.asks.values
      .++(orderBook.bids.values)
      .flatten
      .filterNot(x => {
        val validation = x.order.isValid(ts)
        validation
      })
      .map(_.order.id())
      .foreach(onCancelOrder)
  }

  private def onCancelOrder(orderIdToCancel: ByteStr): Unit =
    OrderBook.cancelOrder(orderBook, orderIdToCancel) match {
      case Some(oc) =>
        val st = cancelTimer.start()
        persist(oc) { _ =>
          handleCancelEvent(oc)
          sender() ! OrderCanceled(orderIdToCancel)
          st.stop()
        }
      case _ =>
        log.debug(s"Error cancelling $orderIdToCancel: order not found")
        sender() ! OrderCancelRejected("Order not found")
    }

  private def handleGetMarketStatus(pair: AssetPair): Unit = {
    if (pair == assetPair)
      sender() ! GetMarketStatusResponse(pair, orderBook.bids.headOption, orderBook.asks.headOption, lastTrade)
    else
      sender() ! GetMarketStatusResponse(pair, None, None, None)
  }

  private def onAddOrder(order: Order): Unit = {
    log.trace(s"Order accepted: '${order.id()}' in '${order.assetPair.key}', trying to match ...")
    timer.measure(matchOrder(LimitOrder(order)))
    sender() ! OrderAccepted(order)
  }

  private def applyEvent(e: Event): Unit = {
    log.trace(s"Apply event $e")
    orderBook = OrderBook.updateState(orderBook, e)
    updateSnapshot(orderBook)
  }

  @tailrec
  private def matchOrder(limitOrder: LimitOrder): Unit = {
    val (submittedRemains, counterRemains) = handleMatchEvent(OrderBook.matchOrder(orderBook, limitOrder))
    if (counterRemains.isDefined) {
      if (!counterRemains.get.isValid) {
        val canceled = Events.OrderCanceled(counterRemains.get, unmatchable = true)
        processEvent(canceled)
      }
    }
    if (submittedRemains.isDefined) {
      if (submittedRemains.get.isValid) {
        matchOrder(submittedRemains.get)
      } else {
        val canceled = Events.OrderCanceled(submittedRemains.get, unmatchable = true)
        processEvent(canceled)
      }
    }
  }

  private def processEvent(e: Event): Unit = {
    val st = Kamon.timer("matcher.orderbook.persist").refine("event" -> e.getClass.getSimpleName).start()
    if (lastSequenceNr % settings.snapshotsInterval == 0) self ! SaveSnapshot
    persist(e)(_ => st.stop())
    applyEvent(e)
    context.system.eventStream.publish(e)
  }

  private def processInvalidTransaction(event: Events.OrderExecuted, err: ValidationError): Option[LimitOrder] = {
    def cancelCounterOrder(): Option[LimitOrder] = {
      processEvent(Events.OrderCanceled(event.counter, unmatchable = false))
      Some(event.submitted)
    }

    log.debug(s"Failed to execute order: $err")
    err match {
      case OrderValidationError(order, _) if order == event.submitted.order => None
      case OrderValidationError(order, _) if order == event.counter.order   => cancelCounterOrder()
      case AccountBalanceError(errs) =>
        errs.foreach(e => log.error(s"Balance error: ${e._2}"))
        if (errs.contains(event.counter.order.senderPublicKey)) {
          cancelCounterOrder()
        }
        if (errs.contains(event.submitted.order.senderPublicKey)) {
          None
        } else {
          Some(event.submitted)
        }
      case _: NegativeAmount =>
        processEvent(Events.OrderCanceled(event.submitted, unmatchable = true))
        None
      case _ =>
        cancelCounterOrder()
    }
  }

  private def handleMatchEvent(e: Event): (Option[LimitOrder], Option[LimitOrder]) = {
    e match {
      case e: Events.OrderAdded =>
        processEvent(e)
        (None, None)

      case event @ Events.OrderExecuted(o, c) =>
        (for {
          tx <- createTransaction(event)
          _  <- utx.putIfNew(tx)
        } yield tx) match {
          case Right(tx) =>
            lastTrade = Some(o.order)
            allChannels.broadcastTx(tx)
            processEvent(event)
            context.system.eventStream.publish(ExchangeTransactionCreated(tx))
            (
              if (event.submittedRemainingAmount <= 0) None
              else
                Some(
                  o.partial(
                    event.submittedRemainingAmount,
                    event.submittedRemainingFee
                  )
                ),
              if (event.counterRemainingAmount <= 0) None
              else
                Some(
                  c.partial(
                    event.counterRemainingAmount,
                    event.counterRemainingFee
                  )
                )
            )
          case Left(ex) =>
            log.info(s"""Can't create tx: $ex
                 |o1: (amount=${o.amount}, fee=${o.fee}): ${Json.prettyPrint(o.order.json())}
                 |o2: (amount=${c.amount}, fee=${c.fee}): ${Json.prettyPrint(c.order.json())}""".stripMargin)
            (processInvalidTransaction(event, ex), None)
        }

      case _ => (None, None)
    }
  }

  private def handleCancelEvent(e: Event): Unit = {
    applyEvent(e)
    context.system.eventStream.publish(e)
  }

  override def receiveCommand: Receive = fullCommands

  override def receiveRecover: Receive = {
    case evt: Event =>
      applyEvent(evt)
      if (settings.recoverOrderHistory) context.system.eventStream.publish(evt)

    case RecoveryCompleted =>
      updateSnapshot(orderBook)
      log.debug(s"Recovery completed: $orderBook")

      if (settings.recoverOrderHistory) {
        val orders = (orderBook.asks.valuesIterator ++ orderBook.bids.valuesIterator).flatten
        if (orders.nonEmpty) {
          val ids = orders.map { limitOrder =>
            context.system.eventStream.publish(OrderAdded(limitOrder))
            limitOrder.order.id()
          }.toSeq

          log.info(s"Recovering an order history for orders: ${ids.mkString(", ")}")
        }
      }

    case SnapshotOffer(_, snapshot: Snapshot) =>
      orderBook = snapshot.orderBook
      updateSnapshot(orderBook)
      log.debug(s"Recovering $persistenceId from $snapshot")
  }

  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    log.warn(s"Restarting actor because of $message", reason)
    super.preRestart(reason, message)
  }

  override def postStop(): Unit = {
    cleanupCancellable.cancel()
  }
}

object OrderBookActor {
  def props(assetPair: AssetPair,
            updateSnapshot: OrderBook => Unit,
            utx: UtxPool,
            allChannels: ChannelGroup,
            settings: MatcherSettings,
            createTransaction: OrderExecuted => Either[ValidationError, ExchangeTransaction]): Props =
    Props(new OrderBookActor(assetPair, updateSnapshot, utx, allChannels, settings, createTransaction))

  def name(assetPair: AssetPair): String = assetPair.toString

  case class DeleteOrderBookRequest(assetPair: AssetPair)

  case class CancelOrder(orderId: ByteStr)

  case object OrderCleanup

  case class GetMarketStatusRequest(assetPair: AssetPair)

  case class GetOrderBookResponse(ts: Long, pair: AssetPair, bids: Seq[LevelAgg], asks: Seq[LevelAgg]) {
    def toHttpResponse: HttpResponse = HttpResponse(
      entity = HttpEntity(
        ContentTypes.`application/json`,
        JsonSerializer.serialize(OrderBookResult(ts, pair, bids, asks))
      )
    )
  }

  object GetOrderBookResponse {
    def empty(pair: AssetPair): GetOrderBookResponse = GetOrderBookResponse(NTP.correctedTime(), pair, Seq(), Seq())
  }

  case class GetMarketStatusResponse(pair: AssetPair,
                                     bid: Option[(Price, Level[LimitOrder])],
                                     ask: Option[(Price, Level[LimitOrder])],
                                     last: Option[Order])
      extends MatcherResponse(
        StatusCodes.OK,
        Json.obj(
          "lastPrice" -> last.map(_.price),
          "lastSide"  -> last.map(_.orderType.toString),
          "bid"       -> bid.map(_._1),
          "bidAmount" -> bid.map(_._2.map(_.amount).sum),
          "ask"       -> ask.map(_._1),
          "askAmount" -> ask.map(_._2.map(_.amount).sum)
        )
      )

  // Direct requests
  case object GetOrdersRequest

  case object GetBidOrdersRequest

  case object GetAskOrdersRequest

  case class GetOrdersResponse(orders: Seq[LimitOrder])

  case object SaveSnapshot

  case class Snapshot(orderBook: OrderBook)
}
