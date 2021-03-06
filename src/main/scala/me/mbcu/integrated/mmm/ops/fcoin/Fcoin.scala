package me.mbcu.integrated.mmm.ops.fcoin

import akka.actor.Props
import me.mbcu.integrated.mmm.ops.Definitions.Exchange.Exchange
import me.mbcu.integrated.mmm.ops.Definitions.{Exchange, Op}
import me.mbcu.integrated.mmm.ops.Definitions.Op.Protocol
import me.mbcu.integrated.mmm.ops.common.{AbsExchange, Bot}

object Fcoin extends AbsExchange {

  override val name: Exchange = Exchange.fcoin

  override val protocol: Protocol = Op.rest

  override val endpoint: String = "https://api.fcoin.com/v2/%s"

  override def intervalMillis: Int = 800

  override def getActorRefProps: Props = Props(new FcoinActor())

}
