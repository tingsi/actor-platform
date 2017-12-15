package im.actor.server.activation.common

import java.time.temporal.ChronoUnit._
import java.time.{ LocalDateTime, ZoneOffset }

import akka.http.scaladsl.util.FastFuture
import cats.data.Xor
import im.actor.server.db.ActorPostgresDriver.api._
import im.actor.server.model.AuthCode
import im.actor.server.persist.AuthCodeRepo

import scala.concurrent.{ ExecutionContext, Future }

//// 通用验证码机制
trait CommonAuthCodes {
  self: ActivationProvider ⇒

  protected val activationConfig: ActivationConfig
  protected val db: Database
  protected implicit val ec: ExecutionContext

  //// 校验验证码。
  override def validate(txHash: String, code: String): Future[ValidationResponse] = {
    val action = for {

      //// 找到对应的transaction.
      //// 先检查是否过期，然后不匹配的话检查是否超过尝试次数，并增加尝试次数。 最后才是正确的情况，返回成功。
      optCode ← AuthCodeRepo.findByTransactionHash(txHash)
      result ← optCode map {
        case s if isExpired(s, activationConfig.expiration.toMillis) ⇒
          for (_ ← AuthCodeRepo.deleteByTransactionHash(txHash)) yield ExpiredCode
        case s if s.code != code ⇒
          if (s.attempts + 1 >= activationConfig.attempts) {
            for (_ ← AuthCodeRepo.deleteByTransactionHash(txHash)) yield ExpiredCode
          } else {
            for (_ ← AuthCodeRepo.incrementAttempts(txHash, s.attempts)) yield InvalidCode
          }
        case _ ⇒ DBIO.successful(Validated)
      } getOrElse DBIO.successful(InvalidHash)
    } yield result
    db.run(action)
  }

  //// 删除 transaction 回话。
  protected def deleteAuthCode(txHash: String): Future[Unit] = db.run(AuthCodeRepo.deleteByTransactionHash(txHash).map(_ ⇒ ()))

  //// 创建 transaction ,验证码关联关系 。
  protected def createAuthCodeIfNeeded(resp: CodeFailure Xor Unit, txHash: String, code: String): Future[Int] = resp match {
    case Xor.Left(_)  ⇒ FastFuture.successful(0)
    case Xor.Right(_) ⇒ db.run(AuthCodeRepo.createOrUpdate(txHash, code))
  }

  //// 检查是否过期。过期时间从配置文件读，默认一天。
  protected def isExpired(code: AuthCode, expiration: Long): Boolean =
    code.createdAt.plus(expiration, MILLIS).isBefore(LocalDateTime.now(ZoneOffset.UTC))

}
