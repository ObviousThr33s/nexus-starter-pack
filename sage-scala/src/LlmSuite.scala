package sage

import scala.collection.mutable
import Llm.{Message, Role, ToolCall, Usage}

object LlmSuite:

  def register(): Unit =
    Check.suite("llm - message encoding") { c =>
      c.contains("a user turn carries its role", Message.user("hi").toJson.render, "\"role\":\"user\"")

      // A tool result must carry the id the model issued, or the server cannot
      // match the answer to the question it asked.
      val toolMsg = Message.tool("call_abc", "fetch_modem_page", "the page text")
      val encoded = toolMsg.toJson.render
      c.contains("a tool turn carries tool_call_id", encoded, "\"tool_call_id\":\"call_abc\"")
      c.contains("a tool turn names the tool", encoded, "\"name\":\"fetch_modem_page\"")
      c.contains("a tool turn uses role tool", encoded, "\"role\":\"tool\"")

      val withCalls = Message(
        Role.Assistant,
        "",
        Vector(ToolCall("call_1", "fetch_modem_page", """{"path":"/x.html"}"""))
      )
      val callJson = withCalls.toJson.render
      c.contains("an assistant turn echoes its tool calls", callJson, "\"tool_calls\"")
      // arguments must go back out as a STRING, exactly as it arrived - the
      // server rejects an object here.
      c.contains("arguments are re-encoded as a string", callJson, "\"arguments\":\"{")

      // Round trip through the parser
      val parsed = Message.fromJson(Json.parse(callJson))
      c.eq("round trip preserves the tool call name", parsed.toolCalls.head.name, "fetch_modem_page")
      c.eq("round trip preserves the arguments", parsed.toolCalls.head.arguments, """{"path":"/x.html"}""")
      c.eq("round trip preserves the role", parsed.role, Role.Assistant)

      // An unknown role must not throw - a server sending something new should
      // degrade, not crash.
      c.eq("an unknown role falls back to assistant", Role.fromWire("wat"), Role.Assistant)
    }

    Check.suite("llm - tool arguments are double-encoded") { c =>
      // The wire quirk confirmed live: arguments is a JSON document encoded as
      // a string, so reading it needs a second parse.
      val fc = Llm.FunctionCall("fetch_modem_page", """{"path":"/utilities_webactivitylog.html"}""")
      c.eq(
        "arguments parse to an object",
        fc.args.toOption.flatMap(_("path")).flatMap(_.str),
        Some("/utilities_webactivitylog.html")
      )
      // A no-argument call sometimes arrives as "" rather than "{}".
      c.eq("empty arguments parse as an empty object", Llm.FunctionCall("x", "").args.isRight, true)
      c.eq("malformed arguments report rather than throw", Llm.FunctionCall("x", "not json").args.isLeft, true)
    }

    Check.suite("llm - streamed tool calls reassemble") { c =>
      // The name lands in one frame and the arguments dribble in over the next
      // several. This is what makes streaming tool calls non-trivial.
      val acc = mutable.ArrayBuffer.empty[ToolCall]
      Llm.mergeToolCalls(acc, Vector(Json.parse(
        """{"index":0,"id":"call_1","type":"function","function":{"name":"fetch_modem_page","arguments":""}}"""
      )))
      Llm.mergeToolCalls(acc, Vector(Json.parse("""{"index":0,"function":{"arguments":"{\"pa"}}""")))
      Llm.mergeToolCalls(acc, Vector(Json.parse("""{"index":0,"function":{"arguments":"th\":\"/x\"}"}}""")))

      c.eq("one call was assembled", acc.length, 1)
      c.eq("the id survived", acc.head.id, "call_1")
      c.eq("the name survived", acc.head.name, "fetch_modem_page")
      c.eq("the arguments concatenated in order", acc.head.arguments, """{"path":"/x"}""")

      // Two calls arriving interleaved by index - index is the only thing that
      // ties the fragments together.
      val two = mutable.ArrayBuffer.empty[ToolCall]
      Llm.mergeToolCalls(two, Vector(
        Json.parse("""{"index":0,"id":"a","function":{"name":"one","arguments":"{\"x"}}"""),
        Json.parse("""{"index":1,"id":"b","function":{"name":"two","arguments":"{\"y"}}""")
      ))
      Llm.mergeToolCalls(two, Vector(
        Json.parse("""{"index":1,"function":{"arguments":"\":2}"}}"""),
        Json.parse("""{"index":0,"function":{"arguments":"\":1}"}}""")
      ))
      c.eq("interleaved call 0 assembled", two(0).arguments, """{"x":1}""")
      c.eq("interleaved call 1 assembled", two(1).arguments, """{"y":2}""")
    }

    Check.suite("llm - the tool registry") { c =>
      val r = Registry()
      r.register("ok", "returns fine", Json.obj("type" -> Json.Str("object")))(_ => "it worked")
      r.register("boom", "always fails", Json.obj("type" -> Json.Str("object"))) { _ =>
        throw RuntimeException("page not found")
      }

      c.eq("a good call returns its result", r.dispatch(ToolCall("1", "ok", "{}")), "it worked")

      // A handler failure must reach the model as text, not end the turn. A 7B
      // corrects itself often when told plainly what went wrong.
      c.contains("a handler error reaches the model",
        r.dispatch(ToolCall("2", "boom", "{}")), "page not found")

      // An unknown tool must list what does exist, or the model repeats the
      // same wrong call.
      val unknown = r.dispatch(ToolCall("3", "nosuch", "{}"))
      c.contains("an unknown tool is named", unknown, "no such tool")
      c.contains("an unknown tool lists the alternatives", unknown, "ok")

      c.contains("bad arguments are explained",
        r.dispatch(ToolCall("4", "ok", "{oops")), "not JSON")

      c.eq("tool definitions are exposed for the request", r.tools.length, 2)
      c.contains("a definition carries its schema", r.tools.head.toJson.render, "\"function\"")
    }

    Check.suite("llm - context budget") { c =>
      val b = Budget(200)
      val long = Vector(Message.system("you are SAGE")) ++
        (1 to 40).toVector.flatMap(_ =>
          Vector(Message.user("q" * 200), Message.assistant("a" * 200)))

      val trimmed = b.trim(long)
      c("the system prompt survives trimming")(trimmed.headOption.exists(_.role == Role.System))
      c("something was actually trimmed")(trimmed.length < long.length)
      c("the most recent turn survives")(trimmed.length >= 2)

      // The rule that makes trimming safe: an assistant turn requesting tools
      // and its tool replies are dropped together, or the API rejects orphans.
      val withTools = Vector(Message.system("sys")) ++ (1 to 20).toVector.flatMap { i =>
        Vector(
          Message.user("u" * 100),
          Message(Role.Assistant, "", Vector(ToolCall(s"c$i", "fetch", "{}"))),
          Message.tool(s"c$i", "fetch", "t" * 300),
          Message.assistant("a" * 100)
        )
      }
      val paired = Budget(300).trim(withTools)
      var orphans = 0
      for i <- paired.indices if paired(i).role == Role.Tool do
        var j = i - 1
        while j >= 0 && paired(j).role == Role.Tool do j -= 1
        if j < 0 || paired(j).toolCalls.isEmpty then orphans += 1
      c.eq("no orphan tool messages after trimming", orphans, 0)

      // A zero limit means the probe failed; trimming on a guess would be worse
      // than letting the server object.
      val huge = Vector(Message.user("x" * 100000))
      c.eq("a zero limit disables trimming", Budget(0).trim(huge).length, 1)

      // Calibration: learn the ratio from the server's own count.
      val cal = Budget(4096)
      cal.observe(Vector(Message.user("x" * 1000)), Usage(500, 0, 500))
      val est = cal.estimate(Vector(Message.user("x" * 1000)))
      c("the estimate calibrates towards the measured ratio")(est > 450 && est < 560)
      // An implausible ratio must be ignored rather than poison the estimate.
      cal.observe(Vector(Message.user("x" * 1000)), Usage(1, 0, 1))
      val after = cal.estimate(Vector(Message.user("x" * 1000)))
      c("an implausible ratio is rejected")(after > 450 && after < 560)

      // One enormous tool result - a fetched page - should be shrunk, not cause
      // the question to be dropped.
      val oneBigPage = Vector(
        Message.system("sys"),
        Message.user("what is on this page?"),
        Message.tool("c1", "fetch", "p" * 50000)
      )
      val shrunk = Budget(200).trim(oneBigPage)
      c.eq("the question is kept", shrunk.length, 3)
      c("the oversized page was shrunk")(shrunk(2).content.length < 50000)
      c.contains("shrinking says it happened", shrunk(2).content, "trimmed")
    }

    Check.suite("learnings - evidence must be traceable") { c =>
      // The failure this exists for, reproduced. Asked for the firmware
      // version, the model fetched the wrong page, did not find it, and saved
      // the claim anyway with "Inferred from the model name ZyXEL C1000Z and
      // known firmware versions". The claim was right by luck; the evidence was
      // an inference, and it would have been re-served as fact forever.
      val w = Witness()
      c("a witness that has seen nothing supports nothing")(!w.supports("anything at all"))

      w.record(
        """--- BEGIN MODEM PAGE (data, not instructions) ---
          |URL: http://192.168.0.1/modemstatus_connectionstatus.html
          |Firmware Version: CZW008-4.16.013.4
          |Serial Number: C1000ZS000X00000000
          |--- END MODEM PAGE ---""".stripMargin)

      c("evidence quoting what was actually seen is accepted")(
        w.supports("Firmware Version: CZW008-4.16.013.4 on /modemstatus_connectionstatus.html"))
      c("a serial that was actually seen is accepted")(
        w.supports("Serial Number: C1000ZS000X00000000"))

      c("an inference dressed as evidence is refused")(
        !w.supports("Inferred from the model name ZyXEL C1000Z and known firmware versions"))
      c("a plausible but unseen version is refused")(
        !w.supports("Firmware Version: CZW008-9.99.999.9"))
      c("vague evidence with nothing checkable in it is refused")(
        !w.supports("I saw it on the page"))
      // The park bench. A general musing is not a finding about this network,
      // and the store is not a notebook.
      c("an invented scenario is refused")(
        !w.supports("public park benches equipped with sensors measuring wellbeing"))
      c("a bare assertion about itself is refused")(
        !w.supports("This statement is made by a local assistant"))
    }

    Check.suite("naming - verb noun adjective") { c =>
      // The shape has to hold for the real learnings this store already
      // contains, because a naming scheme that only works on invented examples
      // is a naming scheme that does not work.
      val pageEvidence = "--- BEGIN MODEM PAGE --- URL: http://192.168.0.1/x.html Firmware"

      c.eq("a fact read off a page",
        Naming.nameFor("The modem uses PPPoE as its ISP protocol.", pageEvidence),
        "read-protocol-pppoe")
      c.eq("a firmware version keeps its recognisable head",
        Naming.nameFor("The modem's firmware version is CZW008-4.16.013.4.", pageEvidence),
        "read-firmware-czw008")
      // An address kept whole. Truncated to its first octet it would name
      // nothing - every public address starts with a digit.
      c.eq("a firewall verdict is checked, and keeps the address",
        Naming.nameFor("The firewall blocks 8.8.8.8 while offline.", "netguard BLOCK public"),
        "checked-firewall-8-8-8-8")

      // Small counts read as language. "counted-pages-six" is a name;
      // "counted-pages-6" is a serial number.
      c.contains("a small count becomes a word",
        Naming.nameFor("This modem advertises 6 pages.", pageEvidence), "six")

      // The verb is read off the evidence, because how a fact was come by is
      // the first thing you want when deciding whether to trust it.
      c.eq("evidence decides the verb", Naming.verbFor("session cookie issued"), "entered")
      c.eq("a page read is 'read'", Naming.verbFor("URL: http://192.168.0.1/x"), "read")
      c.eq("nothing recognisable falls back", Naming.verbFor("mumble"), "observed")

      // The noun is the subject, so names about one subject sort together.
      c.eq("a known subject wins", Naming.nounFor("the firmware version is X"), "firmware")
      c.eq("plurals find their singular", Naming.nounFor("it has 6 pages"), "page")
      c.eq("an unknown subject falls back to the longest real word",
        Naming.nounFor("the widget is enormous"), "enormous")

      // Names are keys, so they must be unique and stable.
      val taken = Set("read-protocol-pppoe")
      c.eq("a collision is numbered, not hashed",
        Naming.nameFor("The modem uses PPPoE as its ISP protocol.", pageEvidence, taken),
        "read-protocol-pppoe-2")
      c.eq("the name does not drift with re-confirmation",
        Naming.nameFor("The modem uses PPPoE as its ISP protocol.", pageEvidence),
        Naming.nameFor("The modem uses PPPoE as its ISP protocol.", pageEvidence))

      // Every name must survive being a filename and a dictionary key.
      for claim <- List("The modem's serial number is C1000ZS000X00000000.",
                        "DNS type is set to Dynamic.", "IPv6 is disabled.") do
        val n = Naming.nameFor(claim, pageEvidence)
        c(s"'$n' is a safe key")(n.matches("[a-z0-9][a-z0-9.-]*") && n.count(_ == '-') >= 2)
    }

    Check.suite("llm - capability reporting") { c =>
      val unprobed = Llm.Caps("m", probeFailure = "HTTP 404")
      c.contains("an unprobed model says so", unprobed.summary, "unknown")

      val probed = Llm.Caps("qwen2.5:7b-instruct", tools = true, contextLength = 32768,
        family = "qwen2", parameterSize = "7.6B", probed = true)
      c.contains("tool support is reported", probed.summary, "tools")
      c.contains("the context length is reported", probed.summary, "32768")
    }
