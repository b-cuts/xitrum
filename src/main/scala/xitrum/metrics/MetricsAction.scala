package xitrum.metrics

import akka.actor.{Actor, ActorRef, Props, Terminated}
import akka.cluster.{Cluster, NodeMetrics}
import akka.cluster.ClusterEvent.{ClusterMetricsChanged, CurrentClusterState}
import akka.cluster.StandardMetrics.{Cpu, HeapMemory}

import io.netty.handler.codec.http.HttpResponseStatus
import glokka.Registry

import xitrum.{Action, Config, Log, SockJsAction, SockJsText}
import xitrum.annotation.{GET, Last, SOCKJS}
import xitrum.util.SeriDeseri
import xitrum.view.{DocType, Js}

/** JMX metrics collector for single node application. */
class MetricsCollector(localPublisher: ActorRef) extends Actor {
  def receive = {
    case Collect => localPublisher ! MetricsManager.jmx.sample()
    case _ =>
  }
}

/** JMX metrics collector for clustered node application. */
class ClusterMetricsCollector(localPublisher: ActorRef) extends Actor {
  override def preStart =
    Cluster(context.system).subscribe(self, classOf[ClusterMetricsChanged])

  override def postStop =
    Cluster(context.system).unsubscribe(self)

  def receive = {
    case m @ ClusterMetricsChanged(clusterMetrics) =>
      if (localPublisher != null) localPublisher ! m

    case UnSubscribe =>
      Cluster(context.system).unsubscribe(self)

    case _ =>
  }
}

/**
 * Example: XitrumMetricsChannel
 */
class MetricsPublisher extends Actor with Log {
  private var clients                                = Seq[ActorRef]()
  private var lastPublished                          = System.currentTimeMillis()
  private var cachedNodeMetrics:    NodeMetrics      = _
  private var cachedClusterMetrics: Set[NodeMetrics] = _
  private var cachedRegistryAsJson: String           = _

  def receive = {
    case ClusterMetricsChanged(clusterMetrics) =>
      cachedClusterMetrics = clusterMetrics

    case nodeMetrics: NodeMetrics =>
      cachedNodeMetrics = nodeMetrics

    case Publish(registryAsJson) =>
      cachedRegistryAsJson = registryAsJson
      clients.foreach { client =>
        client ! Publish(registryAsJson)
        if (cachedNodeMetrics != null) client ! cachedNodeMetrics
        // Ignore if clusterd metricsManager sent duplicated requests
        if (System.currentTimeMillis - lastPublished > Config.xitrum.metrics.collectActorInterval * 1000) {
          if (cachedClusterMetrics != null) client ! cachedClusterMetrics.toList
          lastPublished = System.currentTimeMillis
        }
      }

    case Pull =>
      sender ! Publish(MetricsManager.registryAsJson)
      if (cachedNodeMetrics != null)    sender ! cachedNodeMetrics
      if (cachedClusterMetrics != null) sender ! cachedClusterMetrics.toList

    case Subscribe =>
      clients = clients :+ sender
      context.watch(sender)

    case UnSubscribe =>
      clients =  clients.filterNot(_ == sender)
      context.unwatch(sender)

    case Terminated(client) =>
      clients = clients.filterNot(_ == client)

    case _ =>
  }
}

/** Javascript fragment for establish metrics JSON sockJS channel. */
trait MetricsViewer extends Action {
  def jsAddMetricsNameSpace(namespace: String = null) {
    jsAddToView(s"""
(function (namespace) {
  var ns = namespace || window;
  var pullTimer;
  var initMetricsChannel = function(onMessageFunc, onCloseFunc) {
    var url = '${sockJsUrl[XitrumMetricsChannel]}';
    var socket;
    socket = new SockJS(url);
    socket.onopen = function(event) {
      socket.send('${MetricsManager.APIKEY}');
      pullTimer = setInterval(function(){ socket.send('pull')}, 5000);
    };
    socket.onclose   = function(e){clearInterval(pullTimer); onCloseFunc(e);};
    socket.onmessage = function(e){onMessageFunc(e.data);}
  };
  ns.initMetricsChannel = initMetricsChannel;
})($namespace);
"""
    )
  }
}

/**
 * Default metrics viewer page.
 * This page could be overwritten in user application with any style.
 */
@Last
@GET("xitrum/metrics/viewer")
class XitrumMetricsViewer extends Action with MetricsViewer {
  beforeFilter {
    val apiKey  = param("api_key")
    val correct = apiKey == MetricsManager.APIKEY
    if (!correct) {
      response.setStatus(HttpResponseStatus.UNAUTHORIZED)
      respondHtml(<p>Unauthorized</p>)
    }
    correct
  }

  lazy val html = DocType.html5(
    <html>
      <head>
        {xitrumCss}
        {jsDefaults}
        <script type="text/javascript" src={resourceUrl("xitrum/d3-3.4.3/d3.min.js")}></script>
        <script type="text/javascript" src={resourceUrl("xitrum/metrics.js")}></script>
        <title>Xitrum Default Metrics Viewer</title>
      </head>
      <body class="metricsViewer">
        <h2>Xitrum Default Metrics Viewer</h2>
        <div>
          <h3><a href="http://doc.akka.io/api/akka/snapshot/index.html#akka.cluster.NodeMetrics" target="_blank" >NodeMetrics</a> Status</h3>
          <div class="metricsContent">
            <div id='heapMemory' class="metricsTableWrapper">
              <table id='heapMemoryTableHeader' class='metricsTableHeader'>
              <caption>NodeMetrics(HeapMemory)</caption>
              <tr><th>Time</th><th>Node</th><th>Committed(MB)</th><th>Used(MB)</th><th>Max(MB)</th></tr>
              </table>
              <table id='heapMemoryTable' class='metricsTable'>
              </table>
            </div>
            <div id="heapMemoryGraph" class="metricsGraph nodeMetricsGraph"></div>
            <div class="clearFroat"></div>
          </div>
          <hr/>
          <div class="metricsContent">
            <div id='cpu' class="metricsTableWrapper">
              <table id='cpuTableHeader' class='metricsTableHeader'>
                <caption>NodeMetrics(CPU)</caption>
                <tr><th>Time</th><th>Node</th><th>Processors</th><th>Load Average</th></tr>
              </table>
              <table id='cpuTable' class='metricsTable'>
              </table>
            </div>
            <div id="cpuGraph" class="metricsGraph"></div>
            <div class="clearFroat"></div>
          </div>
        </div>
        <hr/>
        <div>
          <h3>Application <a href="http://metrics.codahale.com/" target="_blank" >Metrics</a> Status</h3>
          <div class="metricsContent">
            <div id='metrics_registry'>
              <div id='histograms' class="metricsTableWrapper">
                <table id='histogramsTableHeader' class='metricsTableHeader'>
                  <caption>Histograms</caption>
                  <tr><th>Node</th><th>Key</th><th>Count</th><th>Min(ms)</th><th>Max(ms)</th><th>Mean(ms)</th></tr>
                </table>
                <table id='histogramsTable' class='metricsTable'>
                </table>
              </div>
              <div id="histogramsGraph" class="metricsGraph"></div>
              <div class="clearFroat"></div>
            </div>
          </div>
        </div>
        {jsForView}
      </body>
    </html>
  )

  lazy val focusHtml = DocType.html5(
    <html>
      <head>
        {xitrumCss}
        {jsDefaults}
        <script type="text/javascript" src={resourceUrl("xitrum/d3-3.4.3/d3.min.js")}></script>
        <script type="text/javascript" src={resourceUrl("xitrum/metrics.js")}></script>
        <title>Xitrum Default Metrics Viewer</title>
      </head>
      <body class="metricsViewer">
        <div>
          <h3 id="title"></h3>
          <div id="focusGraph"></div>
        </div>
        {jsForView}
      </body>
    </html>
  )

  def execute() {
    jsAddMetricsNameSpace("window")
    paramo("focusAction") match {
      case Some(key) =>
        jsAddToView("initMetricsChannel(channelOnMessageWithKey('"+ key +"'),channelOnClose);")
        respondHtml(focusHtml)

      case None =>
        jsAddToView("initMetricsChannel(channelOnMessage,channelOnClose);")
        respondHtml(html)
    }
  }
}

/** SockJS channel for metrics JSON. */
@SOCKJS("xitrum/metrics/channel")
class XitrumMetricsChannel extends SockJsAction with PublisherLookUp {
  def execute() {
    checkAPIKey()
  }

  private def checkAPIKey() {
    context.become {
      case SockJsText(text) if (text == MetricsManager.APIKEY) =>
        lookUpPublisher()

      case SockJsText(key) =>
        respondSockJsText("Wrong apikey")
        respondSockJsClose()

      case ignore =>
        log.warn("Unexpected message: " + ignore)
    }
  }

  override def doWithPublisher(publisher: ActorRef) {
    publisher ! Subscribe
    context.watch(publisher)
    context.become {
      case msg @ (first::rest) =>
        msg.foreach { nodeMetrics =>
          sendHeapMemory(nodeMetrics.asInstanceOf[NodeMetrics])
          sendCpu(nodeMetrics.asInstanceOf[NodeMetrics])
        }

      case nodeMetrics: NodeMetrics =>
        sendHeapMemory(nodeMetrics)
        sendCpu(nodeMetrics)

      case Publish(registryAsJson) =>
        respondSockJsText(registryAsJson)

      case SockJsText(text) =>
        if (text == "pull" ) publisher ! Pull

      case Terminated(publisher) =>
        lookUpPublisher()

      case _ =>
    }
  }

  private def sendHeapMemory(nodeMetrics:NodeMetrics) {
    nodeMetrics match {
      case HeapMemory(address, timestamp, used, committed, max) =>
        respondSockJsText(SeriDeseri.toJson(Map(
          "TYPE"      -> "heapMemory",
          "SYSTEM"    -> address.system,
          "HOST"      -> address.host,
          "PORT"      -> address.port,
          "HASH"      -> address.hashCode,
          "TIMESTAMP" -> timestamp,
          "USED"      -> used,
          "COMMITTED" -> committed,
          "MAX"       -> max
      )))
    }
  }

  private def sendCpu(nodeMetrics:NodeMetrics):Unit = {
    nodeMetrics match {
      case Cpu(address, timestamp, Some(systemLoadAverage), cpuCombined, processors) =>
        respondSockJsText(SeriDeseri.toJson(Map(
          "TYPE"              -> "cpu",
          "SYSTEM"            -> address.system,
          "HOST"              -> address.host,
          "PORT"              -> address.port,
          "HASH"              -> address.hashCode,
          "TIMESTAMP"         -> timestamp,
          "SYSTEMLOADAVERAGE" -> systemLoadAverage,
          "CPUCOMBINED"       -> cpuCombined,
          "PROCESSORS"        -> processors
      )))
    }
  }
}