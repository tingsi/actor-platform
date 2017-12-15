package im.actor.server.activation

import im.actor.server.activation.common.{ CallCode, Code, EmailCode, SmsCode }
import im.actor.util.ThreadLocalSecureRandom
import im.actor.util.misc.EmailUtils._
import im.actor.util.misc.PhoneNumberUtils._

import scala.util.Try

trait CodeGen {

  //// 生成验证码。 按不同类型生成。
  // 重点应该是方便测试使用，测试判断函数在util包里面。
  // Code 等类型定义在 common.codes.scala里面。
  def generateCode(codeTemplate: Code): Code = codeTemplate match {
    case s: SmsCode   ⇒ s.copy(code = genPhoneCode(s.phone))
    case c: CallCode  ⇒ c.copy(code = genPhoneCode(c.phone))
    case e: EmailCode ⇒ e.copy(code = genEmailCode(e.email))
  }

  // 处理测试用的email 或者 genCode;
  private def genEmailCode(email: String): String =
    if (isTestEmail(email))
      (email replaceAll (""".*acme""", "")) replaceAll (".com", "")
    else genCode()

  // 处理测试用的phone， 或者 genCode；
  protected def genPhoneCode(phone: Long): String =
    if (isTestPhone(phone)) {
      val strPhone = phone.toString
      Try(strPhone(4).toString * 4) getOrElse strPhone
    } else genCode()

  //// 其实最终都是调用这个函数生成随机long,然后去掉前导0和负号，截取5位的。
  private def genCode() = ThreadLocalSecureRandom.current.nextLong().toString.dropWhile(c ⇒ c == '0' || c == '-').take(5)

}