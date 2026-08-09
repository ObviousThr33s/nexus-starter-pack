package sage

import com.sun.net.httpserver.{HttpExchange, HttpServer}
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets.UTF_8
import scala.collection.mutable
import scala.language.implicitConversions

import Llm.{Message, Role}

/** Tests for the tool loop.
  *
  * The loop cannot be exercised without something answering
  * `/chat/completions`, so this serves one. `com.sun.net.httpserver` ships with
  * the JDK, which keeps the no-dependency rule, and it binds on loopback, which
  * netguard permits.
  */
object AgentSuite:

  /** Serves the given replies in order, repeating the last forever, and keeps
    * every request body so a test can see what was actually sent.
    */
  private final class Scripted(replies: List[String]):
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val bodies         = mutable.ArrayBuffer.empty[String]
    private var n      = 0

    server.createContext("/v1/chat/completions", (ex: HttpExchange) => {
      val body = String(ex.getRequestBody.readAllBytes, UTF_8)
      val out = synchronized {
        bodies.append(body)
        val r = replies(math.min(n, replies.length - 1))
        n += 1
        r
      }
      val bytes = out.getBytes(UTF_8)
      ex.getResponseHeaders.add("Content-Type", "application/json")
      ex.sendResponseHeaders(200, bytes.length.toLong)
      ex.getResponseBody.write(bytes)
      ex.close()
    }): Unit
    server.start()

    val baseUrl: String = s"http://127.0.0.1:${server.getAddress.getPort}/v1"
    def stop(): Unit    = server.stop(0)

  /** One choice, rendered. Built in named steps rather than one nested
    * expression: the wire shape is seven objects deep and counting brackets is
    * not a thing to be doing in a test.
    */
  private def choice(finish: String, message: Json): String =
    val one = Json.obj(
      "finish_reason" -> Json.Str(finish),
      "message"       -> message
    )
    Json.obj("choices" -> Json.arr(one)).render

  private def toolReply(name: String, args: String): String =
    val fn = Json.obj(
      "name"      -> Json.Str(name),
      "arguments" -> Json.Str(args)
    )
    val call = Json.obj(
      "id"       -> Json.Str("c1"),
      "function" -> fn
    )
    val message = Json.obj(
      "role"       -> Json.Str("assistant"),
      "content"    -> Json.Str(""),
      "tool_calls" -> Json.arr(call)
    )
    choice("tool_calls", message)

  private def textReply(text: String): String =
    val message = Json.obj(
      "role"    -> Json.Str("assistant"),
      "content" -> Json.Str(text)
    )
    choice("stop", message)

  def register(): Unit = Check.suite("agent - the tool loop terminates") { c =>
    // The failure this exists for, reproduced. The model called one tool with
    // one set of arguments over and over, was told every time that it had
    // worked, and the turn died without an answer.
    var ran   = 0
    val tools = Registry()
    tools.register("probe", "counts its calls", Json.obj("type" -> Json.Str("object"))) { _ =>
      ran += 1
      "probe result: the page lists a serial number"
    }
    val repeated = toolReply("probe", """{"x":1}""")
    val srv      = Scripted(List(repeated, repeated, textReply("here is the answer")))
    try
      val agent = Agent(Llm.Client(srv.baseUrl), "m", tools, Budget(0), Witness(), 0.0, maxSteps = 4)
      val (history, answer) = agent.run(Vector(Message.user("go")), stream = false)

      c.eq("an identical call is not run a second time in one turn", ran, 1)
      val refusal = history.filter(_.role == Role.Tool).last.content
      c.contains("the model is told it was not run", refusal, "already called probe")
      c.contains("and told what to do instead", refusal, "answer the user's question now")
      // 'error:' is what the system prompt tells the model to retry, and what
      // the GUI colours on - a dead end must not wear it.
      c("the refusal is not shaped like an error")(!refusal.startsWith("error:"))
      c.eq("the turn still ends with an answer", answer, "here is the answer")
    finally srv.stop()

    // Budget spent with tools still being called. Nothing is thrown: one more
    // request goes out with no tools attached, so there is nothing to call.
    val tools2 = Registry()
    tools2.register("probe", "counts its calls", Json.obj("type" -> Json.Str("object")))(_ => "ok")
    val srv2 = Scripted(List(
      toolReply("probe", """{"x":1}"""),
      toolReply("probe", """{"x":2}"""),
      textReply("forced answer")))
    try
      val agent = Agent(Llm.Client(srv2.baseUrl), "m", tools2, Budget(0), Witness(), 0.0, maxSteps = 2)
      val (_, answer) = agent.run(Vector(Message.user("go")), stream = false)
      c.eq("a spent budget still produces an answer", answer, "forced answer")
      c.eq("it costs exactly one extra request", srv2.bodies.length, 3)
      c.excludes("the forced request offers no tools", srv2.bodies.last, "\"tools\":")
      c.contains("and says why", srv2.bodies.last, "out of tool rounds")
      c.contains("the last round was warned first", srv2.bodies(1), "no tool rounds left")
    finally srv2.stop()
  }
