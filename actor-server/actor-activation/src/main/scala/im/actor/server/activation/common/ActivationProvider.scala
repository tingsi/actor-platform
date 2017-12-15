package im.actor.server.activation.common

import cats.data.Xor

import scala.concurrent.Future

//// Provider 三要素： 发送 验证码，校验 验证码， 清理 验证码。
trait ActivationProvider {
  def send(txHash: String, code: Code): Future[CodeFailure Xor Unit]
  def validate(txHash: String, code: String): Future[ValidationResponse]
  def cleanup(txHash: String): Future[Unit]
}