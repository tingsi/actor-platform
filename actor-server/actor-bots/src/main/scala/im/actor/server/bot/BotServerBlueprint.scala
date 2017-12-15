package im.actor.server.bot

import akka.actor.ActorSystem
import akka.event.Logging
import akka.http.scaladsl.util.FastFuture
import akka.stream.FlowShape
import akka.stream.scaladsl._
import im.actor.api.rpc.Update
import im.actor.bots.BotMessages
import im.actor.server.bot.services._
import upickle.Js

import scala.concurrent.Future

final class BotServerBlueprint(system: ActorSystem) {

  import BotMessages._

  import system.dispatcher

  //// 好多的bot服务。
  private val msgService = new MessagingBotService(system)
  private val kvService = new KeyValueBotService(system)
  private val botsService = new BotsBotService(system)
  private val webhooksService = new WebHooksBotService(system)
  private val usersService = new UsersBotService(system)
  private val groupsService = new GroupsBotService(system)
  private val stickersService = new StickersBotService(system)
  private val filesService = new FilesBotService(system)

  private val log = Logging(system, getClass)

  //// 建立一个消息流处理图，并调用对应的服务来处理不同类型的消息。
  //// BotRequest 里就需要指定 服务:body。 
  def flow(botUserId: Int, botAuthId: Long, botAuthSid: Int): Flow[BotRequest, BotMessageOut, akka.NotUsed] = {
    val updBuilder = new BotUpdateBuilder(botUserId, botAuthId, system)

    val updSource =
      Source.actorPublisher[(Int, Update)](UpdatesSource.props(botUserId, botAuthId, botAuthSid))
        .mapAsync(1) {
          case (seq, update) ⇒ updBuilder(seq, update)
        }.collect {
          case Some(upd) ⇒ upd
        }

    val rqrspFlow = Flow[BotRequest]
      .mapAsync(1) {
        case BotRequest(id, service, body) ⇒ handleRequest(botUserId, botAuthId, botAuthSid)(id, service, body)
      }
      .map(_.asInstanceOf[BotMessageOut])

    //// 此处刘flow用到了图graph的概念？
    //// 看来还需要了解下他的消息flow的机制。
    Flow.fromGraph(
      GraphDSL.create() { implicit b ⇒
        import akka.stream.scaladsl.GraphDSL.Implicits._

        val upd = b.add(updSource)
        val rqrsp = b.add(rqrspFlow)
        val merge = b.add(Merge[BotMessageOut](2))

        upd ~> merge
        rqrsp ~> merge

        FlowShape(rqrsp.in, merge.out)
      }
    ) recover {
        case e ⇒
          log.error(e, "Failure in bot flow, userId: {}", botUserId)
          throw e
      }
  }
  //// 按服务->能处理body的handler。最后handle body消息，返回 response
  private def handleRequest(botUserId: Int, botAuthId: Long, botAuthSid: Int)(id: Long, service: String, body: RequestBody): Future[BotResponse] = {
    val resultFuture =
      if (services.isDefinedAt(service)) {
        val handlers = services(service).handlers

        if (handlers.isDefinedAt(body)) {
          for {
            response ← handlers(body).handle(botUserId, botAuthId, botAuthSid)
          } yield response
        } else FastFuture.successful(BotError(400, "REQUEST_NOT_SUPPORTED", Js.Obj(), None))
      } else FastFuture.successful(BotError(400, "SERVICE_NOT_REGISTERED", Js.Obj(), None))

    resultFuture map (BotResponse(id, _))
  }

  private val services: PartialFunction[String, BotServiceBase] = {
    case Services.KeyValue  ⇒ kvService
    case Services.Messaging ⇒ msgService
    case Services.Bots      ⇒ botsService
    case Services.WebHooks  ⇒ webhooksService
    case Services.Users     ⇒ usersService
    case Services.Groups    ⇒ groupsService
    case Services.Stickers  ⇒ stickersService
    case Services.Files     ⇒ filesService
  }
}
