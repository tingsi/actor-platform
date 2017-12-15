package im.actor.server.activation.telesign

import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture
import akka.pattern.ask
import akka.util.Timeout
import cats.data.Xor
import im.actor.config.ActorConfig
import im.actor.server.activation.common.ActivationStateActor.{ ForgetSentCode, Send, SendAck }
import im.actor.server.activation.common._
import im.actor.server.db.DbExtension
import im.actor.server.model.AuthPhoneTransaction
import im.actor.server.persist.auth.AuthTransactionRepo
import im.actor.server.sms.{ TelesignCallEngine, TelesignClient, TelesignSmsEngine }
import im.actor.util.misc.PhoneNumberUtils.isTestPhone

import scala.concurrent.Future
import scala.concurrent.duration._

//// 短信服务器 telesign
private[activation] final class TelesignProvider(implicit system: ActorSystem) extends ActivationProvider with CommonAuthCodes {

  protected val activationConfig = ActivationConfig.load.getOrElse(throw new RuntimeException("Failed to load activation config"))
  protected val db = DbExtension(system).db
  protected implicit val ec = system.dispatcher

  //// 具体的短信，电话的telesign接口，封装在 actor-sms 模块中。
  private val telesignClient = new TelesignClient(ActorConfig.load().getConfig("services.telesign"))
  private val smsEngine = new TelesignSmsEngine(telesignClient)
  private val callEngine = new TelesignCallEngine(telesignClient)

  private implicit val timeout = Timeout(20.seconds)

  //// 创建actor
  private val smsStateActor = system.actorOf(ActivationStateActor.props[Long, SmsCode](
    repeatLimit = activationConfig.repeatLimit,
    sendAction = (code: SmsCode) ⇒ smsEngine.sendCode(code.phone, code.code),
    id = (code: SmsCode) ⇒ code.phone
  ), "telesign-sms-state")

  //// 创建actor
  private val callStateActor = system.actorOf(ActivationStateActor.props[Long, CallCode](
    repeatLimit = activationConfig.repeatLimit,
    sendAction = (code: CallCode) ⇒ callEngine.sendCode(code.phone, code.code, code.language),
    id = (code: CallCode) ⇒ code.phone
  ), "telesign-calls-state")

  //// 根据类型发送短信通知或者电话通知。
  override def send(txHash: String, code: Code): Future[CodeFailure Xor Unit] = code match {
    case s: SmsCode ⇒
      for {
        resp ← if (isTestPhone(s.phone))
          FastFuture.successful(Xor.right(()))
        else
          (smsStateActor ? Send(code)).mapTo[SendAck].map(_.result)
        _ ← createAuthCodeIfNeeded(resp, txHash, code.code)
      } yield resp
    case c: CallCode ⇒
      for {
        resp ← if (isTestPhone(c.phone))
          FastFuture.successful(Xor.right(()))
        else
          (callStateActor ? Send(code)).mapTo[SendAck].map(_.result)
        _ ← createAuthCodeIfNeeded(resp, txHash, code.code)
      } yield resp
    case other ⇒ throw new RuntimeException(s"This provider can't handle code of type: ${other.getClass}")
  }

  //// 清理 transaction 
  override def cleanup(txHash: String): Future[Unit] = {
    for {
      ac ← db.run(AuthTransactionRepo.findChildren(txHash))
      _ = ac match {
        case Some(x: AuthPhoneTransaction) ⇒
          smsStateActor ! ForgetSentCode.phone(x.phoneNumber)
          callStateActor ! ForgetSentCode.phone(x.phoneNumber)
        case _ ⇒
      }
      _ ← deleteAuthCode(txHash)
    } yield ()
  }

}
