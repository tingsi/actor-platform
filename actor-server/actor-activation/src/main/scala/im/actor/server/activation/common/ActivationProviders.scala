package im.actor.server.activation.common

import akka.actor.ActorSystem
import im.actor.config.ActorConfig

import scala.collection.JavaConversions._
import scala.util.{ Failure, Success, Try }

//// provider管理器。
//// 默认维护四个类型的provider。
//// 根据配置文件类配置和加载具体的provider.

object ActivationProviders {
  val Sms = "sms"
  val Smtp = "smtp"
  val Call = "call"
  val Internal = "internal"

  /**
   * Instantiates activation providers based on configuration.
   * Makes sure to instantiate only one instance of provider,
   * if it is present for several activation types
   * @param system actor system
   * @return map from activation type to activation provider instance
   */
  def getProviders()(implicit system: ActorSystem): Map[String, ActivationProvider] = {

    //// 读取配置信息。
    val providersConfig = ActorConfig.load().getConfig("services.activation.providers")
    val configMap = providersConfig.root.unwrapped.toMap

    //// 略复杂，去除重复类型的配置，以后在分析。 
    //// TODO:
    val reverseAcc = Map.empty[String, List[String]].withDefaultValue(List.empty[String])
    // this is made to avoid duplicate instantiation of same providers
    val reverseMap = (configMap foldLeft reverseAcc) {
      case (acc, (activationType, value)) ⇒
        val className = value.toString
        acc.updated(className, activationType :: acc(className))
    }

    reverseMap flatMap {
      case (className, activationTypes) ⇒
        providerOf(className, system) match {
          case Success(instance) ⇒
            system.log.debug("Successfully instantiated code provider: {}, for activation types: [{}]", className, activationTypes mkString ", ")
            (activationTypes map { _ → instance }).toMap
          case Failure(e) ⇒
            system.log.warning("Failed to instantiate code provider: {}, exception: {}", className, e)
            Map.empty[String, ActivationProvider]
        }
    }
  }

  //// 根据配置创建Provider实例。
  private def providerOf(fqcn: String, system: ActorSystem): Try[ActivationProvider] = {
    for {
      constructor ← Try(Class.forName(fqcn).asSubclass(classOf[ActivationProvider]).getConstructor(classOf[ActorSystem]))
    } yield constructor.newInstance(system)
  }
}
