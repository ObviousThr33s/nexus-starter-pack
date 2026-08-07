package sage

import java.net.URI
import java.net.http.HttpClient
import java.time.Duration
import scala.collection.mutable

/** Speaks the OpenAI chat-completions wire format directly.
  *
  * There is no SDK here on purpose: the protocol is small enough to write out,
  * and writing it out is what lets sage run where no package manager can reach.
  * Everything below was checked against the Ollama 0.32.6 instance on this box
  * rather than against the spec, because the spec is not what answers on 11434.
  */
object Llm:

  /** Roles, as an enum rather than bare strings.
    *
    * The wire uses strings, but nothing inside this program should: a typo in
    * `"assistant"` is a runtime mystery, whereas a missing case here is a
    * compile error. This is most of the reason to be in Scala at all.
    */
  enum Role:
    case System, User, Assistant, Tool

    def wire: String = this match
      case System    => "system"
      case User      => "user"
      case Assistant => "assistant"
      case Tool      => "tool"

  object Role:
    def fromWire(s: String): Role = s match
      case "system"    => System
      case "user"      => User
      case "tool"      => Tool
      case _           => Assistant

  /** One function the model asked to call. */
  final case class FunctionCall(name: String, arguments: String):
    /** The arguments field is a JSON document encoded *as a string* -
      * `"{\"path\":\"/x\"}"`, not an object. Confirmed live against Ollama.
      * Decoding needs a second parse, so it happens here and no caller can
      * forget.
      */
    def args: Either[String, Json] =
      val text = arguments.trim
      if text.isEmpty then Right(Json.Obj(Vector.empty))
      else
        Json.tryParse(text).left.map(e => s"tool '$name' sent arguments that are not JSON: $e")

  final case class ToolCall(id: String, name: String, arguments: String, index: Int = 0):
    def function: FunctionCall = FunctionCall(name, arguments)

  final case class Message(
      role: Role,
      content: String = "",
      toolCalls: Vector[ToolCall] = Vector.empty,
      toolCallId: Option[String] = None,
      name: Option[String] = None
  ):
    def toJson: Json =
      val base = mutable.ArrayBuffer[(String, Json)](
        "role"    -> Json.Str(role.wire),
        "content" -> Json.Str(content)
      )
      if toolCalls.nonEmpty then
        base += "tool_calls" -> Json.Arr(toolCalls.map { tc =>
          Json.obj(
            "id"   -> Json.Str(tc.id),
            "type" -> Json.Str("function"),
            "function" -> Json.obj(
              "name"      -> Json.Str(tc.name),
              "arguments" -> Json.Str(tc.arguments)
            )
          )
        })
      toolCallId.foreach(id => base += "tool_call_id" -> Json.Str(id))
      name.foreach(n => base += "name" -> Json.Str(n))
      Json.Obj(base.toVector)

  object Message:
    def system(text: String): Message    = Message(Role.System, text)
    def user(text: String): Message      = Message(Role.User, text)
    def assistant(text: String): Message = Message(Role.Assistant, text)

    def tool(callId: String, toolName: String, result: String): Message =
      Message(Role.Tool, result, toolCallId = Some(callId), name = Some(toolName))

    def fromJson(j: Json): Message =
      val calls = j("tool_calls").map(_.arr).getOrElse(Vector.empty).zipWithIndex.map { (tc, i) =>
        ToolCall(
          id = tc("id").flatMap(_.str).getOrElse(""),
          name = tc.get("function", "name").flatMap(_.str).getOrElse(""),
          arguments = tc.get("function", "arguments").flatMap(_.str).getOrElse(""),
          index = tc("index").flatMap(_.int).getOrElse(i)
        )
      }
      Message(
        role = j("role").flatMap(_.str).map(Role.fromWire).getOrElse(Role.Assistant),
        content = j("content").flatMap(_.str).getOrElse(""),
        toolCalls = calls,
        toolCallId = j("tool_call_id").flatMap(_.str),
        name = j("name").flatMap(_.str)
      )

  /** Token counts, straight from the server.
    *
    * This is what makes context budgeting possible without a tokenizer: the
    * server counts for us, against the tokenizer that actually matters.
    */
  final case class Usage(promptTokens: Int, completionTokens: Int, totalTokens: Int)

  object Usage:
    val zero: Usage = Usage(0, 0, 0)

    def fromJson(j: Json): Usage = Usage(
      j("prompt_tokens").flatMap(_.int).getOrElse(0),
      j("completion_tokens").flatMap(_.int).getOrElse(0),
      j("total_tokens").flatMap(_.int).getOrElse(0)
    )

  /** A function offered to the model.
    *
    * Named ToolDef rather than Tool so it cannot be confused with `Role.Tool` -
    * they mean genuinely different things (a capability versus a message
    * author), and the compiler refuses the ambiguity anyway.
    */
  final case class ToolDef(name: String, description: String, parameters: Json):
    def toJson: Json = Json.obj(
      "type" -> Json.Str("function"),
      "function" -> Json.obj(
        "name"        -> Json.Str(name),
        "description" -> Json.Str(description),
        "parameters"  -> parameters
      )
    )

  final case class Reply(
      message: Message,
      finishReason: String,
      usage: Usage,
      model: String
  ):
    /** Did the model stop in order to call something? */
    def wantsTools: Boolean = finishReason == "tool_calls" || message.toolCalls.nonEmpty

  final case class Request(
      model: String,
      messages: Vector[Message],
      temperature: Double = 0.7,
      tools: Vector[ToolDef] = Vector.empty,
      jsonMode: Boolean = false
  ):
    def toJson(stream: Boolean): Json =
      val fields = mutable.ArrayBuffer[(String, Json)](
        "model"       -> Json.Str(model),
        "messages"    -> Json.Arr(messages.map(_.toJson)),
        "temperature" -> Json.Num(temperature),
        "stream"      -> Json.Bool(stream)
      )
      if tools.nonEmpty then fields += "tools" -> Json.Arr(tools.map(_.toJson))
      if jsonMode then
        fields += "response_format" -> Json.obj("type" -> Json.Str("json_object"))
      Json.Obj(fields.toVector)

  /** What a model can actually do.
    *
    * `/v1/models` does not report this - only Ollama's native `/api/show` does -
    * and it decides whether the agent loop is available at all, so it is worth
    * the extra request.
    */
  final case class Caps(
      model: String,
      tools: Boolean = false,
      contextLength: Int = 0,
      family: String = "",
      parameterSize: String = "",
      probed: Boolean = false,
      probeFailure: String = ""
  ):
    def summary: String =
      if !probed then s"capabilities unknown ($probeFailure)"
      else
        val bits = List(
          if tools then Some("tools") else None,
          if contextLength > 0 then Some(s"${contextLength} ctx") else None,
          Option(parameterSize).filter(_.nonEmpty),
          Option(family).filter(_.nonEmpty)
        ).flatten
        bits.mkString(", ")

  final class ApiError(message: String) extends RuntimeException(message)

  // ------------------------------------------------------------------
  // client
  // ------------------------------------------------------------------

  final class Client(baseUrlRaw: String, val apiKey: String = ""):
    val baseUrl: String = baseUrlRaw.replaceAll("/+$", "")

    // No cookie handler. API calls have no business carrying the modem's
    // session cookie, which is why this is a separate client from the modem's.
    private val http: HttpClient = Netguard.httpClient()

    private def headers: Map[String, String] =
      val base = Map("Accept" -> "application/json", "Content-Type" -> "application/json")
      if apiKey.nonEmpty then base + ("Authorization" -> s"Bearer $apiKey") else base

    /** Lists what the endpoint serves. Any OpenAI-compatible API answers
      * /models, which is what makes this work against Ollama, LocalAI and the
      * cloud without special-casing any of them.
      */
    def models(): Either[String, List[String]] =
      try
        val resp = Http.send(http, URI.create(s"$baseUrl/models"), "GET",
          headers = headers, timeout = Duration.ofSeconds(8))
        if resp.status == 401 || resp.status == 403 then
          Left(s"reachable but rejected the key (HTTP ${resp.status})")
        else if !resp.isOk then
          Left(s"HTTP ${resp.status} - port is serving something, but not an LLM API")
        else
          Json.tryParse(resp.body) match
            case Left(_) => Left("responded, but not with an OpenAI-style model list")
            case Right(j) =>
              val names = j("data").map(_.arr).getOrElse(Vector.empty)
                .flatMap(_("id").flatMap(_.str)).toList
              Right(names)
      catch
        case b: Netguard.Blocked => Left(s"refused by the firewall - ${b.reason}")
        case e: Exception        => Left(s"no response (${friendly(e)})")

    /** Asks the native API what a model supports.
      *
      * A non-Ollama endpoint has no /api/show and will 404. That is not worth
      * surfacing as an error - it just means `probed` stays false and the
      * caller assumes nothing.
      */
    def capabilities(model: String): Caps =
      val native = baseUrl.stripSuffix("/v1")
      try
        val resp = Http.send(http, URI.create(s"$native/api/show"), "POST",
          body = Some(Json.obj("model" -> Json.Str(model)).render),
          headers = headers, timeout = Duration.ofSeconds(10))
        if !resp.isOk then
          Caps(model, probeFailure = s"HTTP ${resp.status} (endpoint has no /api/show)")
        else
          Json.tryParse(resp.body) match
            case Left(e) => Caps(model, probeFailure = e)
            case Right(j) =>
              val caps = j("capabilities").map(_.arr).getOrElse(Vector.empty).flatMap(_.str)
              // The context key is namespaced by architecture -
              // "qwen2.context_length", "llama.context_length" - so find it by
              // suffix rather than guessing the architecture.
              val ctx = j("model_info") match
                case Some(Json.Obj(fields)) =>
                  fields.collectFirst {
                    case (k, v) if k.endsWith(".context_length") => v.int.getOrElse(0)
                  }.getOrElse(0)
                case _ => 0
              Caps(
                model = model,
                tools = caps.contains("tools"),
                contextLength = ctx,
                family = j.get("details", "family").flatMap(_.str).getOrElse(""),
                parameterSize = j.get("details", "parameter_size").flatMap(_.str).getOrElse(""),
                probed = true
              )
      catch case e: Exception => Caps(model, probeFailure = friendly(e))

    /** One non-streaming turn. */
    def complete(req: Request): Reply =
      val resp = Http.send(http, URI.create(s"$baseUrl/chat/completions"), "POST",
        body = Some(req.toJson(stream = false).render),
        headers = headers, timeout = Duration.ofMinutes(5))
      if !resp.isOk then throw ApiError(errorDetail(resp.status, resp.body))
      Json.tryParse(resp.body) match
        case Left(e) =>
          throw ApiError(s"$baseUrl replied with something that isn't a chat completion: $e")
        case Right(j) => replyFrom(j)

    private def replyFrom(j: Json): Reply =
      val choice = j("choices").map(_.arr).getOrElse(Vector.empty).headOption
      Reply(
        message = choice.flatMap(_("message")).map(Message.fromJson)
          .getOrElse(Message(Role.Assistant)),
        finishReason = choice.flatMap(_("finish_reason")).flatMap(_.str).getOrElse(""),
        usage = j("usage").map(Usage.fromJson).getOrElse(Usage.zero),
        model = j("model").flatMap(_.str).getOrElse("")
      )

    /** One streaming turn, calling `onDelta` for each token as it lands.
      *
      * Returns the assembled Reply so a caller that also wants the whole
      * message does not have to accumulate it twice. Tool calls arrive spread
      * across frames and are reassembled here by index.
      */
    def stream(req: Request)(onDelta: String => Unit): Reply =
      val content = StringBuilder()
      val calls   = mutable.ArrayBuffer.empty[ToolCall]
      var finish  = ""
      var usage   = Usage.zero
      var model   = ""
      var done    = false
      val errorBody = StringBuilder()

      val status = Http.stream(http, URI.create(s"$baseUrl/chat/completions"),
        req.toJson(stream = true).render, headers) { line =>
        val trimmed = line.stripTrailing()
        if done then ()
        // A blank separator, or a comment/keep-alive frame. Both are legal SSE
        // and both would be a JSON error to a parser that did not skip them.
        else if trimmed.isEmpty || trimmed.startsWith(":") then ()
        else if trimmed.startsWith("data:") then
          val data = trimmed.stripPrefix("data:").trim
          if data == "[DONE]" then done = true
          else
            Json.tryParse(data) match
              case Left(e) => throw ApiError(s"malformed SSE frame from $baseUrl: $e")
              case Right(j) =>
                j("model").flatMap(_.str).filter(_.nonEmpty).foreach(model = _)
                j("usage").map(Usage.fromJson).filter(_.totalTokens > 0).foreach(usage = _)
                for choice <- j("choices").map(_.arr).getOrElse(Vector.empty).headOption do
                  choice("finish_reason").flatMap(_.str).filter(_.nonEmpty).foreach(finish = _)
                  for delta <- choice("delta") do
                    val text = delta("content").flatMap(_.str).getOrElse("")
                    if text.nonEmpty then
                      content ++= text
                      onDelta(text)
                    mergeToolCalls(calls, delta("tool_calls").map(_.arr).getOrElse(Vector.empty))
        else
          // Not SSE at all. An error response arrives as a plain JSON body, and
          // discarding it here is how you end up with "the stream was empty".
          errorBody ++= trimmed
      }

      if status < 200 || status >= 300 then
        throw ApiError(errorDetail(status, errorBody.toString))

      Reply(
        message = Message(Role.Assistant, content.toString, calls.toVector),
        finishReason = finish,
        usage = usage,
        model = model
      )

    private def errorDetail(status: Int, body: String): String =
      val detail = Json.tryParse(body).toOption
        .flatMap(_.get("error", "message"))
        .flatMap(_.str)
        .getOrElse(body.trim)
      s"HTTP $status from $baseUrl - ${abbreviate(detail, 400)}"

  /** Folds streamed tool-call fragments into the accumulating set.
    *
    * A streamed tool call arrives in pieces: the name in one frame, the
    * arguments a few characters at a time across the next several. `index` is
    * what ties the fragments together when the model asks for two things at
    * once, and it is why this cannot simply append.
    */
  private[sage] def mergeToolCalls(
      acc: mutable.ArrayBuffer[ToolCall],
      deltas: Vector[Json]
  ): Unit =
    for d <- deltas do
      val i = d("index").flatMap(_.int).getOrElse(0)
      while acc.length <= i do acc += ToolCall(id = "", name = "", arguments = "", index = acc.length)
      val cur = acc(i)
      acc(i) = cur.copy(
        id = d("id").flatMap(_.str).filter(_.nonEmpty).getOrElse(cur.id),
        name = d.get("function", "name").flatMap(_.str).filter(_.nonEmpty).getOrElse(cur.name),
        arguments = cur.arguments + d.get("function", "arguments").flatMap(_.str).getOrElse("")
      )

  private def friendly(e: Throwable): String =
    Option(e.getMessage).filter(_.nonEmpty).getOrElse(e.getClass.getSimpleName)

  private[sage] def abbreviate(s: String, n: Int): String =
    if s.length <= n then s else s.take(n) + "..."
