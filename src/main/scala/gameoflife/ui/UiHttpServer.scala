package gameoflife.ui

import com.sun.net.httpserver.{HttpExchange, HttpServer}
import gameoflife.{BoardCoordinator, BoardPattern, BoardSize, BoardSpec, Position}
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.{ExecutorService, Executors}
import org.apache.pekko.actor.typed.ActorRef
import org.slf4j.LoggerFactory
import scala.concurrent.duration.*

final class UiHttpServer(
    port: Int,
    coordinator: ActorRef[BoardCoordinator.Command],
    eventHub: UiEventHub,
    backendManager: BackendProcessManager,
    host: String = "0.0.0.0"
):
  private val log = LoggerFactory.getLogger(getClass)

  private val executor: ExecutorService = Executors.newCachedThreadPool { r =>
    val t = new Thread(r, "ui-http-worker")
    t.setDaemon(true)
    t
  }

  private val server: HttpServer = HttpServer.create(new InetSocketAddress(host, port), 0)
  server.setExecutor(executor)

  val _ = server.createContext("/", exchange => handleStatic(exchange))
  val _ = server.createContext("/api/state", exchange => handleGetState(exchange))
  val _ = server.createContext("/api/events", exchange => handleSse(exchange))
  val _ = server.createContext("/api/simulation/", exchange => handleSimulation(exchange))
  val _ = server.createContext("/api/cells/", exchange => handleCells(exchange))
  val _ = server.createContext("/api/backends", exchange => handleBackends(exchange))

  def start(): Int =
    server.start()
    val boundPort = server.getAddress.getPort
    log.info("Web UI server listening on http://localhost:{}", boundPort)
    boundPort

  def boundPort: Int = server.getAddress.getPort

  def stop(): Unit =
    server.stop(1)
    eventHub.close()
    val _ = executor.shutdownNow()

  private def handleStatic(exchange: HttpExchange): Unit =
    val method = exchange.getRequestMethod
    if method != "GET" && method != "HEAD" then
      sendJson(exchange, 405, UiJson.encodeError("Method not allowed"))
    else
      val rawPath                     = exchange.getRequestURI.getPath
      val (resourcePath, contentType) = rawPath match
        case "/" | "/index.html" => ("/ui/index.html", "text/html; charset=utf-8")
        case "/styles.css"       => ("/ui/styles.css", "text/css; charset=utf-8")
        case "/app.js"           => ("/ui/app.js", "application/javascript; charset=utf-8")
        case _                   => ("", "")

      if resourcePath.isEmpty then sendJson(exchange, 404, UiJson.encodeError("Not found"))
      else
        Option(getClass.getResourceAsStream(resourcePath)) match
          case None => sendJson(exchange, 404, UiJson.encodeError(s"Missing asset $resourcePath"))
          case Some(stream) =>
            val bytes =
              try readAllBytes(stream)
              finally stream.close()
            exchange.getResponseHeaders.set("Content-Type", contentType)
            exchange.getResponseHeaders.set("Cache-Control", "no-cache")
            if method == "HEAD" then
              exchange.getResponseHeaders.set("Content-Length", bytes.length.toString)
              exchange.sendResponseHeaders(200, -1L)
              exchange.close()
            else
              exchange.sendResponseHeaders(200, bytes.length.toLong)
              val out = exchange.getResponseBody
              try out.write(bytes)
              finally out.close()

  private def handleGetState(exchange: HttpExchange): Unit =
    if exchange.getRequestMethod != "GET" then
      sendJson(exchange, 405, UiJson.encodeError("Method not allowed"))
    else sendJson(exchange, 200, eventHub.fullStateJson())

  private def handleSse(exchange: HttpExchange): Unit =
    if exchange.getRequestMethod != "GET" then
      sendJson(exchange, 405, UiJson.encodeError("Method not allowed"))
    else
      val headers = exchange.getResponseHeaders
      headers.set("Content-Type", "text/event-stream; charset=utf-8")
      headers.set("Cache-Control", "no-cache")
      headers.set("Connection", "keep-alive")
      headers.set("X-Accel-Buffering", "no")
      exchange.sendResponseHeaders(200, 0L)
      val out    = exchange.getResponseBody
      val client = eventHub.addClient(out)
      // Block this worker thread until the client disconnects or server closes
      try
        val in = exchange.getRequestBody
        while in.read() != -1 do ()
        // Keep thread parked until socket breaks on write
        while client.send(": heartbeat\n\n") do Thread.sleep(15000L)
      catch case _: Exception => ()
      finally eventHub.removeClient(client)

  private def handleSimulation(exchange: HttpExchange): Unit =
    if exchange.getRequestMethod != "POST" then
      sendJson(exchange, 405, UiJson.encodeError("Method not allowed"))
    else
      val action = exchange.getRequestURI.getPath.stripPrefix("/api/simulation/")
      val body   = readBody(exchange)
      try
        action match
          case "start" =>
            coordinator ! BoardCoordinator.StartSimulation
            sendJson(exchange, 200, UiJson.encodeOk("started"))

          case "pause" =>
            coordinator ! BoardCoordinator.PauseSimulation
            sendJson(exchange, 200, UiJson.encodeOk("paused"))

          case "resume" =>
            coordinator ! BoardCoordinator.ResumeSimulation
            sendJson(exchange, 200, UiJson.encodeOk("resumed"))

          case "step" =>
            coordinator ! BoardCoordinator.StepOnce
            sendJson(exchange, 200, UiJson.encodeOk("stepped"))

          case "tick" =>
            val json   = UiJson.parseObject(body)
            val tickMs = json.path("tickMs").asLong(1000L)
            if tickMs < 100L || tickMs > 10000L then
              sendJson(exchange, 400, UiJson.encodeError("tickMs must be between 100 and 10000"))
            else
              coordinator ! BoardCoordinator.UpdateTickInterval(tickMs.millis)
              sendJson(exchange, 200, UiJson.encodeOk("updated"))

          case "auto-crash" =>
            val json     = UiJson.parseObject(body)
            val enabled  = json.path("enabled").asBoolean(false)
            val everyOpt =
              if json.hasNonNull("everyMs") then Some(json.path("everyMs").asLong(5000L).millis)
              else None
            coordinator ! BoardCoordinator.SetAutoCrash(enabled, everyOpt)
            sendJson(exchange, 200, UiJson.encodeOk("updated"))

          case "reset" =>
            val json      = UiJson.parseObject(body)
            val width     = json.path("width").asInt(10)
            val height    = json.path("height").asInt(10)
            val seed      = json.path("seed").asLong(42L)
            val patternId = json.path("pattern").asText("random")
            val autoStart = json.path("autoStart").asBoolean(false)
            if width < 2 || width > 40 || height < 2 || height > 30 then
              sendJson(
                exchange,
                400,
                UiJson.encodeError("Board dimensions must be within 2..40 width and 2..30 height")
              )
            else
              val spec = BoardSpec(
                size = BoardSize(width, height),
                seed = seed,
                pattern = BoardPattern.fromId(patternId)
              )
              coordinator ! BoardCoordinator.ResetSimulation(spec, autoStart)
              sendJson(exchange, 200, UiJson.encodeOk("reset"))

          case _ =>
            sendJson(exchange, 404, UiJson.encodeError(s"Unknown simulation action '$action'"))
      catch
        case ex: Exception =>
          sendJson(exchange, 400, UiJson.encodeError(s"Invalid request: ${ex.getMessage}"))

  private def handleCells(exchange: HttpExchange): Unit =
    if exchange.getRequestMethod != "POST" then
      sendJson(exchange, 405, UiJson.encodeError("Method not allowed"))
    else
      val action = exchange.getRequestURI.getPath.stripPrefix("/api/cells/")
      val body   = readBody(exchange)
      action match
        case "crash" =>
          try
            val json = UiJson.parseObject(body)
            if json.path("random").asBoolean(false) then
              coordinator ! BoardCoordinator.CrashRandomCell
              sendJson(exchange, 200, UiJson.encodeOk("random-crash-sent"))
            else if json.has("x") && json.has("y") then
              val pos = Position(json.path("x").asInt(), json.path("y").asInt())
              coordinator ! BoardCoordinator.CrashCell(pos)
              sendJson(exchange, 200, UiJson.encodeOk("crash-sent"))
            else sendJson(exchange, 400, UiJson.encodeError("Provide {x, y} or {random: true}"))
          catch
            case ex: Exception =>
              sendJson(exchange, 400, UiJson.encodeError(s"Invalid JSON: ${ex.getMessage}"))
        case _ =>
          sendJson(exchange, 404, UiJson.encodeError(s"Unknown cell action '$action'"))

  private def handleBackends(exchange: HttpExchange): Unit =
    val method = exchange.getRequestMethod
    val path   = exchange.getRequestURI.getPath.stripPrefix("/api/backends").stripPrefix("/")

    (method, path) match
      case ("POST", "") =>
        backendManager.startBackend() match
          case Right(info) =>
            sendJson(exchange, 201, UiJson.toJson(UiJson.encodeManagedBackend(info)))
          case Left(err) =>
            sendJson(exchange, 500, UiJson.encodeError(err))

      case ("GET", "") =>
        val arr = UiJson.mapper.createArrayNode()
        backendManager.listBackends().foreach { b =>
          val _ = arr.add(UiJson.encodeManagedBackend(b))
        }
        sendJson(exchange, 200, UiJson.toJson(arr))

      case ("POST", subpath) if subpath.endsWith("/stop") =>
        val id = subpath.stripSuffix("/stop")
        backendManager.stopBackend(id) match
          case Right(_)  => sendJson(exchange, 200, UiJson.encodeOk("stopping"))
          case Left(err) => sendJson(exchange, 404, UiJson.encodeError(err))

      case ("POST", subpath) if subpath.endsWith("/kill") =>
        val id = subpath.stripSuffix("/kill")
        backendManager.killBackend(id) match
          case Right(_)  => sendJson(exchange, 200, UiJson.encodeOk("killing"))
          case Left(err) => sendJson(exchange, 404, UiJson.encodeError(err))

      case ("GET", subpath) if subpath.endsWith("/logs") =>
        val id = subpath.stripSuffix("/logs")
        backendManager.logsFor(id) match
          case Some(lines) => sendJson(exchange, 200, UiJson.encodeLogs(id, lines))
          case None        => sendJson(exchange, 404, UiJson.encodeError(s"Unknown backend '$id'"))

      case _ =>
        sendJson(exchange, 404, UiJson.encodeError(s"Unknown backend endpoint: $method /$path"))

  private def readBody(exchange: HttpExchange): String =
    val in = exchange.getRequestBody
    try new String(readAllBytes(in), StandardCharsets.UTF_8)
    finally in.close()

  private def readAllBytes(in: java.io.InputStream): Array[Byte] =
    val buf  = new ByteArrayOutputStream()
    val data = new Array[Byte](4096)
    var n    = in.read(data)
    while n != -1 do
      buf.write(data, 0, n)
      n = in.read(data)
    buf.toByteArray

  private def sendJson(exchange: HttpExchange, status: Int, json: String): Unit =
    val bytes = json.getBytes(StandardCharsets.UTF_8)
    exchange.getResponseHeaders.set("Content-Type", "application/json; charset=utf-8")
    exchange.getResponseHeaders.set("Cache-Control", "no-store")
    exchange.sendResponseHeaders(status, bytes.length.toLong)
    val out = exchange.getResponseBody
    try out.write(bytes)
    finally out.close()
