package im.actor.server.bot

import akka.actor.{ Props, ActorSystem }
import im.actor.api.rpc.peers.{ ApiPeerType, ApiPeer }
import im.actor.bots.BotMessages
import im.actor.server.user.UserErrors
import im.actor.util.misc.IdUtils

import scala.util.{ Failure, Success }

//// 机器人属性： id，name，UserName
object ActorBot {
  val UserId = 10 ////Fixed: 固定的还是只是默认值？   只是默认值，具体配置从Props里读取。
  val Username = "actor"
  val Name = "Actor Bot"

  val NewCmd = "/bot new" //// 可以定制启动命令？

  val ApiPeer = new ApiPeer(ApiPeerType.Private, UserId)

  def start()(implicit system: ActorSystem) = InternalBot.start(props)

  private def props = Props(classOf[ActorBot])
}

final class ActorBot extends InternalBot(ActorBot.UserId, ActorBot.Username, ActorBot.Name, isAdmin = true) {
  import BotMessages._
  import ActorBot._

  import context._

  //// 默认的机器人消息处理，只处理文本类型，其他类型忽略了。
  //// 此Bot只是用来创建具体的bot，没处理bot的事物。
  override def onMessage(m: Message): Unit = {
    m.message match {
      case TextMessage(text, ext) ⇒
        //// 只处理新建机器人命令。
        if (m.peer.isPrivate && text.startsWith(NewCmd)) {
          text.drop(NewCmd.length + 1).trim.split(" ").map(_.trim).toList match {
            case nickname :: name :: Nil ⇒
              log.warning("Creating new bot")

              //// 只取前两个参数作为昵称和名字，名字不能重复。 创建成功会返回机器人的token和userid。
              requestCreateBot(nickname, name) onComplete {
                case Success(token) ⇒ requestSendMessage(m.peer, nextRandomId(), TextMessage(s"Yay! Bot created, bot token: ${token.token}, bot id: ${token.userId}", None))
                case Failure(BotError(_, "USERNAME_TAKEN", _, _)) ⇒
                  requestSendMessage(m.peer, nextRandomId(), TextMessage("Username already taken", None))
                case Failure(e) ⇒
                  log.error(e, "Failed to create bot")
                  requestSendMessage(m.peer, nextRandomId(), TextMessage("There was a problem on our side. Please, try again a bit later.", None))
              }
            case _ ⇒ requestSendMessage(m.peer, nextRandomId(), TextMessage("Command format is: /bot new <nickname> <name>", None))
          }
        }
      case _ ⇒

    }
  }

  //// TODO: 此回调含义待定
  override def onRawUpdate(u: RawUpdate): Unit = {}
}