package im.actor.server.activation

import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture
import cats.data.Xor
import im.actor.server.activation.common._
import im.actor.server.db.DbExtension
import im.actor.server.model.{ AuthEmailTransaction, AuthPhoneTransaction, AuthTransactionBase, ExpirableCode }
import im.actor.util.cache.CacheHelpers

import scala.concurrent.Future

//// 附加验证码生成功能的激活上下文，基于actor体系。
final class ActivationContext(implicit system: ActorSystem) extends CodeGen {

  import system.dispatcher
  import ActivationProviders._
  import CacheHelpers._

  // 系统数据库。
  private val db = DbExtension(system).db

  // 配置的Providers。
  private val providers = getProviders()

  // 至少有一个 不是Internal的provider。
  require(providers exists { case (k, _) ⇒ k != Internal }, "Should be at least one external activation provider")

  // 获取各个 provider;
  private val optInternalProvider = providers.get(Internal)
  private val optSmsProvider = providers.get(Sms)
  private val optCallProvider = providers.get(Call)
  private val optSmtpProvider = providers.get(Smtp)

  // 验证码缓存。
  private val MaxCacheSize = 1000L
  private implicit val codesCache = createCache[String, Code](MaxCacheSize)

  /**
   * We don't care about result of sending internal code.
   * But we do care about sending code via external provider.
   * We also don't show "Try to send code later" warning to end users.
   */
  def send(txHash: String, codeTemplate: Code): Future[CodeFailure Xor Unit] = {

    //// 根据codeTemplate的具体类型（sms，call，email），先生成验证码，然后放入缓存。 或者直接从缓存读取---已有的话。
    val code = getCachedOrElsePut(txHash, generateCode(codeTemplate))
    (for {
      //// 先尝试使用内部provider发送验证码。
      _ ← trySend(optInternalProvider, txHash, code, logFailure = false)

      //// 再根据具体类型调用具体Provider发送验证码。
      result ← code match {
        case s: SmsCode   ⇒ trySend(optSmsProvider, txHash, s)
        case e: EmailCode ⇒ trySend(optSmtpProvider, txHash, e)
        case c: CallCode  ⇒ trySend(optCallProvider, txHash, c)
      }
    } yield result) map { //// 记录错误到日志。忽略或报错。
      case Xor.Left(BadRequest(message)) ⇒
        system.log.warning("Bad request. Message: {}. Tx hash: {}, code: {}", message, txHash, code)
        Xor.Right(())
      case error @ Xor.Left(SendFailure(message)) ⇒
        system.log.error("Send failure. Message: {}. Tx hash: {}, code: {}", message, txHash, code)
        error
      case result: Xor.Right[_] ⇒ result
    }
  }

  /**
   * If internal code validates - we are fine.
   * Otherwise - validate code sent via external provider.
   */
  def validate(tx: AuthTransactionBase with ExpirableCode, code: String): Future[ValidationResponse] =
    for {
      //// 先用内部Provider校验下验证码。 tx 类似session。
      internalResp ← tryValidate(optInternalProvider, tx.transactionHash, code, logFailure = false)
      result ← if (internalResp == Validated) {
        FastFuture.successful(internalResp)
      } else {
        ////  失败后，在调用相应的Provider 来校验 code验证码。
        for {
          resp ← tx match {
            case _: AuthEmailTransaction ⇒ tryValidate(optSmtpProvider, tx.transactionHash, code)
            case _: AuthPhoneTransaction ⇒ tryValidate(optSmsProvider, tx.transactionHash, code)
          }
        } yield resp
      }
    } yield result

  /**
   * It is required to cleanup both internal and external provider.
   */
  def cleanup(tx: AuthTransactionBase with ExpirableCode): Future[Unit] =
    for {
      _ ← tryCleanup(optInternalProvider, tx.transactionHash)
      _ ← for {
        //// 清除 tx, 也就是认证期的session。
        resp ← tx match {
          case _: AuthEmailTransaction ⇒ tryCleanup(optSmtpProvider, tx.transactionHash)
          case _: AuthPhoneTransaction ⇒ tryCleanup(optSmsProvider, tx.transactionHash)
        }
      } yield ()

      //// 删除缓存的验证码。
      _ = codesCache.invalidate(tx.transactionHash)
    } yield ()

  //// 尝试发送验证码，并翻译错误信息--- 屏蔽内部错误。
  private def trySend(optProvider: Option[ActivationProvider], txHash: String, code: Code, logFailure: Boolean = true): Future[CodeFailure Xor Unit] =
    optProvider map (_.send(txHash, code)) getOrElse {
      if (logFailure) { system.log.error(s"No provider found to handle code of type {}", code.getClass) }
      FastFuture.successful(Xor.left(SendFailure(s"No provider found to handle code of type ${code.getClass}")))
    }

  //// 尝试校验验证码，记录错误信息，翻译错误信息---屏蔽内部错误。
  private def tryValidate(optProvider: Option[ActivationProvider], txHash: String, code: String, logFailure: Boolean = true): Future[ValidationResponse] =
    optProvider map (_.validate(txHash, code)) getOrElse {
      if (logFailure) { system.log.error(s"No provider found to validate code") }
      FastFuture.successful(InternalError)
    }

  //// 尝试清理现场。
  private def tryCleanup(optProvider: Option[ActivationProvider], txHash: String): Future[Unit] =
    optProvider map (_.cleanup(txHash)) getOrElse FastFuture.successful(())
}
