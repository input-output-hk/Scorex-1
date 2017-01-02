package scorex.core.api.http

import java.net.{URI, InetAddress, InetSocketAddress}
import javax.ws.rs.Path

import akka.actor.{ActorRef, ActorRefFactory}
import akka.http.scaladsl.server.Route
import akka.pattern.ask
import io.circe.generic.auto._
import io.circe.parser._
import io.circe.syntax._
import io.swagger.annotations._
import scorex.core.network.Handshake
import scorex.core.network.NetworkController.ConnectTo
import scorex.core.network.peer.{PeerInfo, PeerManager}
import scorex.core.settings.Settings

import scala.concurrent.ExecutionContext.Implicits.global

@Path("/peers")
@Api(value = "/peers", description = "Get info about peers", position = 2)
case class PeersApiRoute(peerManager: ActorRef,
                         networkController: ActorRef,
                         override val settings: Settings)(implicit val context: ActorRefFactory)
  extends ApiRoute {

  override lazy val route =
    pathPrefix("peers") {
      allPeers ~ connectedPeers ~ blacklistedPeers ~ connect
    }

  @Path("/all")
  @ApiOperation(value = "Peer list", notes = "Peer list", httpMethod = "GET")
  @ApiResponses(Array(
    new ApiResponse(code = 200, message = "Json with peer list or error")
  ))
  def allPeers: Route = path("all") {
    getJsonRoute {
      (peerManager ? PeerManager.GetAllPeers)
        .mapTo[Map[InetSocketAddress, PeerInfo]]
        .map { peers =>
          peers.map { case (address, peerInfo) =>
            Map(
              "address" -> address.toString,
              "nodeName" -> (peerInfo.nodeName.getOrElse("N/A"): String),
              "nodeNonce" -> (peerInfo.nonce.map(_.toString).getOrElse("N/A"): String)
            )
          }.asJson
        }
    }
  }

  @Path("/connected")
  @ApiOperation(value = "Connected peers list", notes = "Connected peers list", httpMethod = "GET")
  @ApiResponses(Array(
    new ApiResponse(code = 200, message = "Json with connected peers or error")
  ))
  def connectedPeers: Route = path("connected") {
    getJsonRoute {
      (peerManager ? PeerManager.GetConnectedPeers)
        .mapTo[Seq[Handshake]]
        .map { handshakes =>
          val peerData = handshakes.map { handshake =>
            Map(
              "declaredAddress" -> handshake.declaredAddress.toString,
              "peerName" -> handshake.nodeName,
              "peerNonce" -> handshake.nodeNonce.toString
            ).asJson
          }.asJson
          Map("peers" -> peerData).asJson
        }
    }
  }

  private case class ConnectCommandParams(uri: String)

  @Path("/connect")
  @ApiOperation(value = "Connect to peer", notes = "Connect to peer", httpMethod = "POST")
  @ApiImplicitParams(Array(
    new ApiImplicitParam(
      name = "body",
      value = "Json with data",
      required = true,
      paramType = "body",
      defaultValue = "{\n\t\"host\":\"127.0.0.1\",\n\t\"port\":\"9084\"\n}"
    )
  )) def connect: Route = path("connect") {
    entity(as[String]) { body =>
      withAuth {
        postJsonRoute {
          decode[ConnectCommandParams](body) match {
            case Right(ConnectCommandParams(uriStr)) =>
              val uri = new URI(uriStr)
              val add: InetSocketAddress = new InetSocketAddress(InetAddress.getByName(uri.getHost), uri.getPort)
              networkController ! ConnectTo(add, Some(uri))
              Map("hostname" -> add.getHostName, "status" -> "Trying to connect").asJson
            case _ =>
              ApiError.wrongJson
          }
        }
      }
    }
  }

  @Path("/blacklisted")
  @ApiOperation(value = "Blacklisted peers list", notes = "Connected peers list", httpMethod = "GET")
  @ApiResponses(Array(
    new ApiResponse(code = 200, message = "Json with connected peers or error")
  ))
  def blacklistedPeers: Route = path("blacklisted") {
    getJsonRoute {
      (peerManager ? PeerManager.GetBlacklistedPeers)
        .mapTo[Seq[String]]
        .map(_.asJson)
    }
  }
}
