package im.actor.server.bot

import akka.actor._
import akka.cluster.singleton.{ ClusterSingletonManager, ClusterSingletonManagerSettings }
import akka.http.scaladsl.util.FastFuture
import akka.pattern.pipe
import akka.stream.scaladsl.{ Sink, Source }
import akka.stream.{ ActorMaterializer, OverflowStrategy }
import akka.util.Timeout
import im.actor.botkit.BotBase
import im.actor.config.ActorConfig
import im.actor.server.dialog.DialogExtension

import scala.concurrent.Future
import scala.util.Failure

private object InternalBot {

  final case class Initialized(authId: Long, authSid: Int)

  ////NOTE: 此处支持集群？
  def start(props: Props)(implicit system: ActorSystem) =
    system.actorOf(ClusterSingletonManager.props(
      props,
      PoisonPill,
      ClusterSingletonManagerSettings(system)
    ))
}

//// 创建bot的用户信息,session,token什么的。
abstract class InternalBot(userId: Int, nickname: String, name: String, isAdmin: Boolean) extends BotBase {

  import InternalBot._
  import context.dispatcher

  private implicit val mat = ActorMaterializer()(context.system)

  override protected implicit val timeout = Timeout(ActorConfig.defaultTimeout)

  protected val botExt = BotExtension(context.system)
  protected val dialogExt = DialogExtension(context.system)

  init()

  def receive = {
    case Initialized(authId, authSid) ⇒
      log.warning("Initialized bot {} {} {}", userId, nickname, name)

      val rqSource =
        Source.actorRef(100, OverflowStrategy.fail)
          .via(botExt.botServerBlueprint.flow(userId, authId, authSid))
          .to(Sink.actorRef(self, Kill))
          .run()

      setRqSource(rqSource)

      context become workingBehavior
    case Status.Failure(e) ⇒
      log.error(e, "Failed to initialize bot")
  }

  //// 检查bot 的 userID， 没有就创建，有就直接返回。
  private def init() = {
    log.warning("Initiating bot {} {} {}", userId, nickname, name)

    val existence = botExt.exists(userId) flatMap { exists ⇒
      if (exists) {
        log.warning("Bot already exists")
        FastFuture.successful(())
      } else {
        log.warning("Creating user {}", userId)
        botExt.create(userId, nickname, name, isAdmin) map (_ ⇒ ()) andThen {
          case Failure(e) ⇒ log.error(e, "Failed to create bot user")
        }
      }
    }

    //// 获得bot的session信息， authId, sid，用来初始化。
    (for {
      _ ← existence
      session ← botExt.getAuthSession(userId)
    } yield Initialized(session.authId, session.id)) pipeTo self
  }

  override protected def onStreamFailure(cause: Throwable): Unit = {
    log.error(cause, "Bot stream failure")
    throw cause
  }
}
