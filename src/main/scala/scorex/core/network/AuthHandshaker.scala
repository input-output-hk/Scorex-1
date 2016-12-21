package scorex.core.network

import java.net.URI

import akka.util.ByteString

trait Secrets

sealed trait AuthHandshakeResult
case object AuthHandshakeError extends AuthHandshakeResult
case class AuthHandshakeSuccess(secrets: Secrets) extends AuthHandshakeResult

trait AuthHandshaker {
  def initiate(uri: URI): (ByteString, AuthHandshaker)
  def handleInitialMessage(data: ByteString): (ByteString, AuthHandshakeResult)
  def handleResponseMessage(data: ByteString): AuthHandshakeResult
}
