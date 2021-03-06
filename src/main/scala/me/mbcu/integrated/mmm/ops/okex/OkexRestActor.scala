package me.mbcu.integrated.mmm.ops.okex

import akka.dispatch.ExecutionContexts.global
import akka.stream.ActorMaterializer
import me.mbcu.integrated.mmm.ops.Definitions.ShutdownCode
import me.mbcu.integrated.mmm.ops.common.AbsRestActor._
import me.mbcu.integrated.mmm.ops.common.Side.Side
import me.mbcu.integrated.mmm.ops.common.{AbsRestActor, Offer, Status}
import me.mbcu.scala.MyLogging
import play.api.libs.json.{JsValue, Json}
import play.api.libs.ws.ahc.StandaloneAhcWSClient

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

object OkexRestActor {
  def parseForId(js: JsValue): String = (js \ "order_id").as[Long].toString

  val toOffer: JsValue => Offer = (data: JsValue) => {
    val status = (data \ "status").as[Int] match {
      case -1 => Status.cancelled
      case 0 => Status.active
      case 1 => Status.partiallyFilled
      case 2 => Status.filled
      case 3 => Status.cancelled
      case _ => Status.cancelled
    }
    new Offer(
      (data \ "order_id").as[Long].toString,
      (data \ "symbol").as[String],
      (data \ "type").as[Side],
      status,
      (data \ "create_date").as[Long],
      None,
      (data \ "amount").as[BigDecimal],
      (data \ "price").as[BigDecimal],
      (data \ "deal_amount").asOpt[BigDecimal]
    )
  }

  def parseFilled(js: JsValue, lastCounterId: Long): (Seq[Offer], Option[String]) = {
    val filleds = (js \ "orders").as[List[JsValue]]
      .filter(a => (a \ "status").as[Int] == OkexStatus.filled.id) // assuming partially filled orders eventually have filled status
      .filter(a => (a \ "order_id").as[Long] > lastCounterId)
      .map(toOffer)
    if (filleds.isEmpty){
      (Seq.empty[Offer], None)
    } else {
      val latestCounterId = filleds.head.id
      val sorted = filleds.sortWith(_.createdAt < _.createdAt)
      (sorted, Some(latestCounterId))
    }
  }
}

class OkexRestActor() extends AbsRestActor() with MyLogging {
  import play.api.libs.ws.DefaultBodyReadables._
  import play.api.libs.ws.DefaultBodyWritables._

  private implicit val materializer = ActorMaterializer()
  private implicit val ec: ExecutionContextExecutor = global
  val OKEX_ERRORS: Map[Int, String] = OkexRest.OKEX_ERRORS
  val url: String = OkexRest.endpoint

  private var ws = StandaloneAhcWSClient()

  override def start(): Unit = setOp(Some(sender()))

  override def sendRequest(r: SendRest): Unit = {

    r match {
      case a: NewOrder => httpPost(a,s"$url/trade.do", OkexRestRequest.restNewOrder(a.bot.credentials, a.bot.pair, a.offer.side, a.offer.price, a.offer.quantity))

      case a: CancelOrder => httpPost(a,s"$url/cancel_order.do", OkexRestRequest.restCancelOrder(a.bot.credentials, a.bot.pair, a.offer.id))

      case a: GetActiveOrders => httpPost(a,s"$url/order_history.do", OkexRestRequest.restOwnTrades(a.bot.credentials, a.bot.pair, OkexStatus.unfilled, a.page) )

      case a: GetFilledOrders => httpPost(a,s"$url/order_history.do", OkexRestRequest.restOwnTrades(a.bot.credentials, a.bot.pair, OkexStatus.filled, 1))

      case a: GetTickerStartPrice => httpGet(a,s"$url/ticker.do", OkexRestRequest.restTicker(a.bot.pair))

    }

    def httpPost(a: SendRest, url: String, params: Map[String, String]): Unit =
      ws.url(url)
        .addHttpHeaders("Content-Type" -> "application/x-www-form-urlencoded")
        .withRequestTimeout(requestTimeoutSec seconds)
        .post(stringifyXWWWForm(params))
        .map(response => parse(a, response.body[String]))
        .recover{
          case e: Exception => errorRetry(a, 0, e.getMessage, shouldEmail = false)
        }

    def httpGet(a: SendRest, url: String, params: Map[String, String]): Unit =
      ws.url(url)
        .addQueryStringParameters(params.toSeq: _*)
        .withRequestTimeout(requestTimeoutSec seconds)
        .get()
        .map(response => parse(a, response.body[String]))
        .recover{
          case e: Exception => errorRetry(a, 0, e.getMessage, shouldEmail = false)
        }

  }

  def parse(a: SendRest, raw: String): Unit = {
    info(logResponse(a, raw))
    val arriveMs = System.currentTimeMillis()

    a match {
      case p: NewOrder => op foreach (_ ! GotNewOrderId("unused", p.as, arriveMs, p))
      case _ =>
    }

    val x = Try(Json parse raw)
    x match {
      case Success(js) =>
        if ((js \ "success").isDefined) {
          val code = (js \ "error_code").as[Int]
          val msg = OKEX_ERRORS.getOrElse(code, " code list : https://github.com/okcoin-okex/API-docs-OKEx.com/blob/master/API-For-Spot-EN/Error%20Code%20For%20Spot.md")
          pipeErrors(code, msg, a)
        }
        else {
          val book = a.book
          a match {
            case t: GetTickerStartPrice =>
                val lastTrade = (js \ "ticker" \ "last").as[BigDecimal]
                book ! GotTickerStartPrice(Some(lastTrade), arriveMs, t)

            case t: GetFilledOrders =>
              val res = OkexRestActor.parseFilled(js, t.lastCounterId.toLong)
              book ! GotUncounteredOrders(res._1, res._2, isSortedFromOldest = true, arriveMs, t)

            case t: GetActiveOrders =>
              val res = (js \ "orders").as[List[JsValue]].map(OkexRestActor.toOffer)
              val currentPage = (js \ "currency_page").as[Int]
              val nextPage = if ((js \ "page_length").as[Int] > 200) true else false
              book ! GotActiveOrders(res, currentPage, nextPage, arriveMs, t)

            case t: CancelOrder => // not handled

            case t: NewOrder => // not handled, serverTime not available

            case _ => error(s"Unknown OkexRestActor#parseRest : $raw")
          }
        }

      case Failure(e) => raw match {
        case u: String if u.contains("<html>") =>
          val code = -1000
          val msg =
            s"""${OKEX_ERRORS(code)}
               |$raw
             """.stripMargin
          pipeErrors(code, msg, a)
        case _ => error(s"Unknown OkexRestActor#parseRest : $raw")
      }
    }
  }

  private def pipeErrors(code: Int, msg: String, sendRequest: SendRest): Unit = {
    code match {
      case 10009 | 10010 | 10011 | 10014 | 10016 | 10024 | 1002 => errorIgnore(code, msg)
      case 20100 | 10007 | 10005 | -1000 => errorRetry(sendRequest, code, msg)
      case 10008 | 10100 => errorShutdown(ShutdownCode.fatal, code, msg)
      case _ => errorRetry(sendRequest, code, msg)
    }
  }

}
