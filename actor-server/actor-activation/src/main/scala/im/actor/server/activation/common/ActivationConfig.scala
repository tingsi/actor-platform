package im.actor.server.activation.common

import com.github.kxbmap.configs.syntax._
import com.typesafe.config.{ Config, ConfigFactory }

import scala.concurrent.duration._
import scala.util.Try

//// 验证码的配置信息
//// 重复发送的间隔、 验证码失效时间、尝试次数。
object ActivationConfig {
  def load(config: Config): Try[ActivationConfig] =
    for {
      limit ← config.get[Try[Duration]]("repeat-limit")
      exp ← config.get[Try[Duration]]("code-expiration")
      att ← config.get[Try[Int]]("code-attempts")
    } yield ActivationConfig(limit, exp, att)
  def load: Try[ActivationConfig] = load(ConfigFactory.load().getConfig("services.activation"))
}

case class ActivationConfig(repeatLimit: Duration, expiration: Duration, attempts: Int)
