package xitrum

import akka.actor.ActorRef

import org.jboss.netty.channel.ChannelFutureListener

import xitrum.routing.Routes
import xitrum.sockjs.{CloseFromHandler, MessageFromHandler}
import xitrum.util.Json

abstract class SockJsHandler extends Logger {
  /** Set by SockJsController; true if raw WebSocket transport is used */
  var rawWebSocket = false

  /** Set by SockJsController; null if WebSocket (raw or not) is used (polling is not used) */
  var nonWebSocketSessionActorRef: ActorRef = null

  /** Set by SockJsController; null if WebSocket (raw or not) is not used (polling is used) */
  var webSocketAction: Action = null

  //----------------------------------------------------------------------------
  // Abstract methods that must be implemented by apps

  /**
   * @param controller the controller just before switching to this SockJS handler,
   * you can use extract session data, request headers etc. from it
   */
  def onOpen(action: Action)

  def onMessage(message: String)

  def onClose()

  //----------------------------------------------------------------------------
  // Helper methods for apps to use

  def send(message: Any) {
    if (webSocketAction == null) {
      // FIXME: Ugly code
      // nonWebSocketSessionActorRef is set to null by SockJsNonWebSocketSession on postStop
      if (nonWebSocketSessionActorRef != null) {
        if (nonWebSocketSessionActorRef.isTerminated) {
          onClose()
        } else {
          nonWebSocketSessionActorRef ! MessageFromHandler(message.toString)
        }
      }
    } else {
      // WebSocket is used, but it may be raw or not raw
      if (rawWebSocket) {
        webSocketAction.respondWebSocketText(message)
      } else {
        val json = Json.generate(Seq(message))
        webSocketAction.respondWebSocketText("a" + json)
      }
    }
  }

  def close() {
    if (webSocketAction == null) {
      // Until the timeout occurs, the server must serve the close message
      nonWebSocketSessionActorRef ! CloseFromHandler
    } else {
      // For WebSocket, must explicitly close
      // WebSocket is used, but it may be raw or not raw
      if (rawWebSocket) {
        webSocketAction.channel.close()
      } else {
        webSocketAction.respondWebSocketText("c[3000,\"Go away!\"]")
        .addListener(ChannelFutureListener.CLOSE)
      }
    }
  }
}
