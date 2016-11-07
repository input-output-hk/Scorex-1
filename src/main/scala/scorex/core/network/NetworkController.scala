package scorex.core.network

import java.net.{InetAddress, InetSocketAddress, NetworkInterface, URI}

import akka.actor._
import akka.io.Tcp._
import akka.io.{IO, Tcp}
import akka.pattern.ask
import akka.util.Timeout
import scorex.core.app.ApplicationVersion
import scorex.core.network.message.{Message, MessageHandler, MessageSpec}
import scorex.core.network.peer.PeerManager
import scorex.core.serialization.ScorexKryoPool
import scorex.core.settings.Settings
import scorex.core.utils.ScorexLogging

import scala.collection.JavaConversions._
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.language.existentials
import scala.reflect.runtime.universe.TypeTag
import scala.util.{Failure, Success, Try}

/**
  * Control all network interaction
  * must be singleton
  */
class NetworkController(settings: Settings,
                        messageHandler: MessageHandler,
                        upnp: UPnP,
                        agentName: String,
                        appVersion: ApplicationVersion,
                        serializer: ScorexKryoPool) extends Actor with ScorexLogging {

  import NetworkController._

  val peerManager = context.system.actorOf(Props(classOf[PeerManager], settings, self))

  val peerSynchronizer = context.system.actorOf(Props(classOf[PeerSynchronizer], self, peerManager), "PeerSynchronizer")

  private implicit val system = context.system

  private implicit val timeout = Timeout(5.seconds)

  private val messageHandlers = mutable.Map[Seq[Message.MessageCode], ActorRef]()

  //check own declared address for validity
  if (!settings.localOnly) {
    settings.declaredAddress.map { myAddress =>
      Try {
        val uri = new URI("http://" + myAddress)
        val myHost = uri.getHost
        val myAddrs = InetAddress.getAllByName(myHost)

        NetworkInterface.getNetworkInterfaces.exists { intf =>
          intf.getInterfaceAddresses.exists { intfAddr =>
            val extAddr = intfAddr.getAddress
            myAddrs.contains(extAddr)
          }
        } match {
          case true => true
          case false =>
            if (settings.upnpEnabled) {
              val extAddr = upnp.externalAddress
              myAddrs.contains(extAddr)
            } else false
        }
      }.recover { case t: Throwable =>
        log.error("Declared address validation failed: ", t)
        false
      }.getOrElse(false)
    }.getOrElse(true).ensuring(b => b, "Declared address isn't valid")
  }

  lazy val localAddress = new InetSocketAddress(InetAddress.getByName(settings.bindAddress), settings.port)

  lazy val externalSocketAddress = settings.declaredAddress
    .flatMap(s => Try(InetAddress.getByName(s)).toOption)
    .orElse {
      if (settings.upnpEnabled) upnp.externalAddress else None
    }.map(ia => new InetSocketAddress(ia, settings.port))

  //an address to send to peers
  lazy val ownSocketAddress = externalSocketAddress

  log.info(s"Declared address: $ownSocketAddress")

  private lazy val handshakeTemplate = Handshake(agentName,
    appVersion,
    settings.nodeName,
    settings.nodeNonce,
    ownSocketAddress,
    0
  )


  lazy val connTimeout = Some(new FiniteDuration(settings.connectionTimeout, SECONDS))

  //bind to listen incoming connections
  IO(Tcp) ! Bind(self, localAddress)

  private def bindingLogic: Receive = {
    case b@Bound(localAddr) =>
      log.info("Successfully bound to the port " + settings.port)
      context.system.scheduler.schedule(600.millis, 5.seconds)(peerManager ! PeerManager.CheckPeers)

    case CommandFailed(_: Bind) =>
      log.error("Network port " + settings.port + " already in use!")
      context stop self
    //TODO catch?
  }

  def businessLogic: Receive = {
    //a message coming in from another peer
    case Message(spec, Left(msgBytes), Some(remote)) =>
      val msgId = spec.messageCode

      spec.deserializeData(msgBytes) match {
        case Success(content) =>
          messageHandlers.find(_._1.contains(msgId)).map(_._2) match {
            case Some(handler) =>
              handler ! DataFromPeer(spec, content, remote)

            case None =>
              log.error("No handlers found for message: " + msgId)
            //todo: ban a peer
          }
        case Failure(e) =>
          log.error("Failed to deserialize data: ", e)
        //todo: ban peer
      }

    case SendToNetwork(message, sendingStrategy) =>
      (peerManager ? PeerManager.FilterPeers(sendingStrategy))
        .map(_.asInstanceOf[Seq[ConnectedPeer]])
        .foreach(_.foreach(_.handlerRef ! message))
  }

  def peerLogic: Receive = {
    case ConnectTo(remote) =>
      log.info(s"Connecting to: $remote")
      IO(Tcp) ! Connect(remote, localAddress = None, timeout = connTimeout, pullMode = true)

    case c@Connected(remote, local) =>
      val connection = sender()
      val handler = context.actorOf(Props(classOf[PeerConnectionHandler], self, peerManager, messageHandler, connection,
        remote, serializer))
      connection ! Register(handler, keepOpenOnPeerClosed = false, useResumeWriting = true)
      val newPeer = ConnectedPeer(remote, handler)
      newPeer.handlerRef ! handshakeTemplate.copy(time = System.currentTimeMillis() / 1000)
      peerManager ! PeerManager.Connected(newPeer)

    case CommandFailed(c: Connect) =>
      log.info("Failed to connect to : " + c.remoteAddress)
      peerManager ! PeerManager.Disconnected(c.remoteAddress)
  }

  //calls from API / application
  def interfaceCalls: Receive = {
    case ShutdownNetwork =>
      log.info("Going to shutdown all connections & unbind port")
      (peerManager ? PeerManager.FilterPeers(Broadcast))
        .map(_.asInstanceOf[Seq[ConnectedPeer]])
        .foreach(_.foreach(_.handlerRef ! PeerConnectionHandler.CloseConnection))
      self ! Unbind
      context stop self
  }

  override def receive: Receive = bindingLogic orElse businessLogic orElse peerLogic orElse interfaceCalls orElse {
    case RegisterMessagesHandler(specs, handler) =>
      log.info(s"Registering handlers for ${specs.map(s => s.messageCode -> s.messageName)}")
      messageHandlers += specs.map(_.messageCode) -> handler

    case CommandFailed(cmd: Tcp.Command) =>
      log.info("Failed to execute command : " + cmd)

    case nonsense: Any =>
      log.warn(s"NetworkController: got something strange $nonsense")
  }
}

object NetworkController {

  case class RegisterMessagesHandler(specs: Seq[MessageSpec[_]], handler: ActorRef)

  case class SendToNetwork(message: Message[_], sendingStrategy: SendingStrategy)

  case object ShutdownNetwork

  case class ConnectTo(address: InetSocketAddress)

  case class DataFromPeer[DT: TypeTag](spec: MessageSpec[DT], data: DT, source: ConnectedPeer)

}