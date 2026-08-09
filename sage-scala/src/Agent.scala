package sage

import scala.collection.mutable
import scala.util.control.NonFatal

import Llm.{Message, Reply, Request, Role, ToolCall, ToolDef, Usage}

/** The set of things a model is allowed to do.
  *
  * An allowlist by construction: a model can only name a tool that was
  * registered, and every argument it sends is decoded before a handler sees it.
  * There is no path from model output to an arbitrary URL, command, or file.
  */
final class Registry:
  private final case class Entry(tool: ToolDef, run: Json => String)
  private val entries = mutable.LinkedHashMap.empty[String, Entry]

  def register(name: String, description: String, parameters: Json)(run: Json => String): Unit =
    entries(name) = Entry(ToolDef(name, description, parameters), run)

  def tools: Vector[ToolDef] = entries.values.map(_.tool).toVector
  def names: List[String] = entries.keys.toList
  def isEmpty: Boolean    = entries.isEmpty

  /** Runs one call and returns the text for its `role:"tool"` message.
    *
    * It never throws. Every failure - unknown tool, bad arguments, a handler
    * that blew up - becomes text the model reads and can react to. Throwing
    * here would end the conversation at exactly the moment the model was one
    * correction away from getting it right, and a 7B model corrects itself
    * surprisingly often when told plainly what went wrong.
    */
  def dispatch(call: ToolCall): String =
    entries.get(call.name) match
      case None =>
        s"error: no such tool '${call.name}'. Available tools: ${names.mkString(", ")}"
      case Some(entry) =>
        call.function.args match
          case Left(why) => s"error: $why"
          case Right(args) =>
            try entry.run(args)
            catch
              case b: Netguard.Blocked => s"error: ${b.getMessage}"
              case NonFatal(e) =>
                val msg = Option(e.getMessage).filter(_.nonEmpty).getOrElse(e.getClass.getSimpleName)
                s"error: $msg"

/** What was actually observed this session.
  *
  * This exists because of a real failure, seen live. Asked for the firmware
  * version, the model fetched the wrong page, did not find it, and saved the
  * learning anyway with the evidence "Inferred from the model name ZyXEL C1000Z
  * and known firmware versions." The claim happened to be correct. The evidence
  * was an inference wearing the costume of an observation - and it would have
  * been re-injected as established fact in every future session.
  *
  * Asking the model for evidence is not enough, because a model that is
  * confabulating will confabulate the evidence too. So evidence is checked
  * against what the tools genuinely returned. A claim that cannot be traced to
  * something that actually happened is not a learning; it is a guess with a
  * citation.
  */
final class Witness:
  private val seen = StringBuilder()

  def record(text: String): Unit = (seen ++= "\n" ++= text.toLowerCase): Unit

  private def tokens(text: String): Set[String] =
    text.toLowerCase.split("[^a-z0-9.:_-]+").filter(_.nonEmpty).toSet

  /** Did the tools actually show this?
    *
    * Scoring by overall token overlap does not work, and the self-test proves
    * it: "Firmware Version: CZW008-9.99.999.9" shares "firmware" and "version"
    * with a real page and gets two thirds of the way there on words that carry
    * no information. The invented part was the only part that mattered.
    *
    * So the specifics decide it. Anything with a digit in it - a version, a
    * serial, an IP, a MAC - is a value the model is claiming to have READ, and
    * every one of those must appear in what the tools returned. Quoting a
    * number nobody saw is the whole failure mode, and there is no fraction of
    * it that is acceptable.
    *
    * Only when evidence contains no specifics at all does it fall back to
    * requiring several substantial words to line up - and it demands more of
    * them, because a claim with nothing concrete in it is weak evidence even
    * when honest.
    */
  def supports(evidence: String): Boolean =
    val observed  = seen.toString
    val all       = tokens(evidence)
    val specifics = all.filter(t => t.length >= 4 && t.exists(_.isDigit))

    if specifics.nonEmpty then specifics.forall(observed.contains)
    else
      val words = all.filter(_.length >= 8)
      words.size >= 3 && words.count(observed.contains) * 10 >= words.size * 7

  def isEmpty: Boolean = seen.isEmpty

/** Keeps a conversation inside the model's context window.
  *
  * The obvious way needs a tokenizer, which would be a dependency and a
  * per-model one at that. Instead this counts characters and calibrates the
  * ratio against the token counts the server already reports after every
  * request. The first estimate is a guess; by the second turn it is measured,
  * and measured against the tokenizer that actually matters.
  *
  * @param limit
  *   the model's context length. Zero disables trimming entirely, which is
  *   right when the probe failed: better to let the server reject an over-long
  *   request with a clear error than to silently discard history on a guess.
  */
final class Budget(val limit: Int):
  val reserve: Int = math.min(4096, math.max(512, limit / 4))

  private var charsPerToken = 4.0 // English prose through a BPE tokenizer
  private var calibrated    = false

  def available: Int =
    if limit <= 0 then 0
    else if limit - reserve > 0 then limit - reserve
    else limit / 2

  def observe(sent: Vector[Message], usage: Usage): Unit =
    if usage.promptTokens > 0 then
      val ratio = chars(sent).toDouble / usage.promptTokens
      // An implausible ratio means a truncated or cached prompt, not a
      // measurement. Letting it in would poison every estimate after it.
      if ratio >= 1.0 && ratio <= 20.0 then
        if !calibrated then
          charsPerToken = ratio
          calibrated = true
        else charsPerToken = 0.7 * charsPerToken + 0.3 * ratio

  def estimate(msgs: Vector[Message]): Int =
    // Per-message framing (role tokens, delimiters) costs a few tokens that
    // character counting cannot see.
    (chars(msgs) / charsPerToken).toInt + 4 * msgs.length

  private def chars(msgs: Vector[Message]): Int =
    msgs.map { m =>
      m.content.length + m.role.wire.length +
        m.toolCalls.map(tc => tc.name.length + tc.arguments.length).sum
    }.sum

  /** Drops the oldest exchanges until the history fits.
    *
    * Two rules make this safe rather than merely short:
    *
    *   - Leading system messages are never dropped. They carry the grounding
    *     instructions and the tool contract, and a model that loses them starts
    *     inventing modem fields again.
    *   - An assistant turn that requested tools is dropped together with the
    *     `role:"tool"` replies answering it. Splitting that pair leaves orphan
    *     tool messages, which the API rejects outright.
    */
  def trim(msgs: Vector[Message]): Vector[Message] =
    if limit <= 0 || estimate(msgs) <= available then msgs
    else
      val (system, body) = msgs.span(_.role == Role.System)

      // Group each turn with the tool replies belonging to it.
      val groups = mutable.ArrayBuffer.empty[Vector[Message]]
      var i      = 0
      while i < body.length do
        var j = i + 1
        while j < body.length && body(j).role == Role.Tool do j += 1
        groups += body.slice(i, j)
        i = j

      // Drop from the oldest end, always keeping the most recent group - that
      // is the question being asked right now.
      while groups.length > 1 && estimate(system ++ groups.toVector.flatten) > available do
        groups.remove(0): Unit

      val kept = system ++ groups.toVector.flatten
      if estimate(kept) <= available then kept else shrinkLargest(kept)

  /** Still too big with only the system prompt and the current turn. The
    * realistic cause is one enormous tool result - a fetched modem page - so
    * shrink that rather than dropping the question.
    */
  private def shrinkLargest(msgs: Vector[Message]): Vector[Message] =
    var current = msgs
    var going   = true
    while going && estimate(current) > available do
      val idx = current.zipWithIndex
        .filter((m, _) => m.role != Role.System)
        .maxByOption((m, _) => m.content.length)
        .map((_, i) => i)
      idx match
        case Some(i) if current(i).content.length >= 200 =>
          val size = current(i).content.length
          val cut  = size / 2
          current = current.updated(
            i,
            current(i).copy(content =
              current(i).content.take(cut) +
                s"\n\n[trimmed ${size - cut} characters to fit the context window]"
            )
          )
        case _ => going = false // nothing left worth cutting; let the server object
    current

/** Runs a conversation in which the model may call tools before answering.
  *
  * This is the difference between "paste a page in and ask about it" and
  * actually asking a question: the model decides what it needs to look at,
  * fetches it, reads the result, and answers - inside one question.
  */
final class Agent(
    val client: Llm.Client,
    val model: String,
    val tools: Registry,
    val budget: Budget,
    val witness: Witness,
    val temperature: Double = 0.7,
    /** Bounds the tool loop. A model that has not answered after this many
      * rounds is looping, and looping against a local 7B is a wait with no end.
      */
    val maxSteps: Int = 6
):
  var onDelta: String => Unit                = _ => ()
  var onToolCall: (String, String) => Unit   = (_, _) => ()
  var onToolResult: (String, String) => Unit = (_, _) => ()

  /** Spots an answer that is really an uncalled tool invocation.
    *
    * Looks for a registered tool name followed by something JSON-shaped. Kept
    * deliberately narrow: prose that merely mentions a tool by name is fine and
    * common ("I used modem_page to check"), so the brace is what distinguishes
    * describing a call from attempting one.
    */
  private def narratedCall(text: String): Option[String] =
    val head = text.trim.take(400)
    tools.names.find { name =>
      val i = head.toLowerCase.indexOf(name.toLowerCase)
      i >= 0 && head.indexOf('{', i) >= 0 && head.indexOf('{', i) - i < name.length + 8
    }

  /** Appends the question and runs the loop until the model answers plainly.
    *
    * `history` is returned updated, tool turns included - that full record is
    * what the next question sees, and dropping the tool turns would make the
    * model re-fetch what it already looked at.
    */
  def ask(
      history: Vector[Message],
      question: String,
      stream: Boolean = true
  ): (Vector[Message], String) =
    run(history :+ Message.user(question), stream)

  def run(start: Vector[Message], stream: Boolean = true): (Vector[Message], String) =
    var history = start
    var step    = 0
    var answer  = Option.empty[String]
    // Once only. If the correction does not take, the model is not going to get
    // there and looping on it just spends the user's time.
    var nudged = false
    // Every (tool, arguments) pair already dispatched in this turn. One turn is
    // the unit where a repeat cannot mean anything: the first result is still in
    // `history` word for word, so an identical call has nothing new to return.
    // A later question, and a later session, each start empty - re-checking a
    // fact tomorrow is a real check and must still count as one.
    val dispatched = mutable.Set.empty[String]

    while answer.isEmpty && step < maxSteps do
      step += 1
      history = budget.trim(history)

      val req = Request(model, history, temperature, tools.tools)
      val reply: Reply =
        if stream then client.stream(req)(onDelta) else client.complete(req)

      budget.observe(history, reply.usage)
      history = history :+ reply.message

      if !reply.wantsTools then
        // A 7B several tool-rounds deep sometimes drops out of tool-calling and
        // *writes* the call instead - "save_learning {"claim": ...}" as prose.
        // Observed live. The intent is right and the channel is wrong, so say
        // so once and let it try again;;silently returning that text would show
        // the user a tool call and save nothing.
        narratedCall(reply.message.content) match
          case Some(name) if !nudged =>
            nudged = true
            history = history :+ Message.user(
              s"You wrote a $name call as text instead of calling it. Nothing ran. " +
                "Call the tool properly now, then answer.")
          case _ => answer = Some(reply.message.content)
      else
        // Every call the model made must come back with a matching tool
        // message. Skipping one leaves the conversation malformed and the next
        // request is rejected outright.
        for call <- reply.message.toolCalls do
          onToolCall(call.name, call.arguments)
          val signature = call.name + "|" + call.arguments
          val result =
            // Seen live: the model saved a fact it already held four times
            // running, was told each time that the save had worked, and spent
            // the whole budget without ever answering. Re-running the call is
            // what makes that possible, so it is not re-run. save_learning
            // above all must not - a second identical save adds a check to the
            // dictionary against the same observation, and that inflated count
            // then sorts the fact to the top of every future context block with
            // a confidence it did not earn.
            if !dispatched.add(signature) then
              s"you already called ${call.name} with these exact arguments this turn - it " +
                "was not run again, because its result is already above in this " +
                "conversation. Stop calling tools and answer the user's question now."
            else
              val fresh = tools.dispatch(call)
              // Everything a tool returned is on the record, and a learning has
              // to be traceable back to it. save_learning is excluded - it
              // returns our own confirmation, and letting a claim vouch for
              // itself would defeat the whole check.
              if call.name != "save_learning" then witness.record(fresh)
              fresh
          onToolResult(call.name, result)
          history = history :+ Message.tool(call.id, call.name, result)

        // Nothing else tells the model a budget exists, so it cannot know it is
        // one round from being cut off. Said on the last round that still has a
        // reply after it, and only there.
        if step == maxSteps - 1 then
          history = history :+ Message.user(
            "You have no tool rounds left. Do not call another tool. Answer the user's " +
              "question now from what the tools have already returned, and say plainly " +
              "if anything you needed is missing.")

    answer match
      case Some(text) => (history, text)
      case None =>
        // Out of rounds with the model still reaching for tools. One more
        // request with none attached: Request.toJson omits the field entirely
        // when `tools` is empty, so there is nothing to call and the only thing
        // left is prose. Asking has already failed by this point, and throwing
        // here would discard the whole turn - every page fetched in those
        // rounds goes with it, and the next question re-fetches them.
        val forced = history :+ Message.user(
          "You are out of tool rounds. Answer the user's question now, using only what " +
            "the tools have already returned in this conversation. If you never got what " +
            "you needed, say what is missing.")
        val finalReq = Request(model, budget.trim(forced), temperature)
        val last =
          if stream then client.stream(finalReq)(onDelta) else client.complete(finalReq)
        val text = last.message.content.trim
        if text.nonEmpty then (forced :+ last.message, text)
        else
          // Nothing came back even with nothing to call. Say so plainly rather
          // than returning nothing: the user needs to know the answer is
          // missing because the model kept looking, not because nothing
          // happened.
          throw Llm.ApiError(
            s"gave up after $maxSteps rounds without a final answer - the model may be " +
              "looping. Try a more specific question"
          )
