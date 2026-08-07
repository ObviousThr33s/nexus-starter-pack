package sage

import scala.util.control.NonFatal

object Main:

  private val Usage =
    """SAGE - local assistant behind your firewall.
      |
      |  sage --doctor                     diagnose connectivity and model capabilities
      |  sage --firewall [target...]       show the outbound policy, check targets
      |  sage --self-test                  run the built-in checks
      |  sage --ask "question"             ask one question and exit
      |  sage --cli                        terminal conversation
      |  sage --login                      log in to the modem and list its pages
      |  sage --learnings [forget <name>]  what has been found out, and its evidence
      |
      |Endpoint shorthands: --ollama --localai --lmstudio --vllm
      |Options:             --base-url URL   --model NAME   --temperature N   --no-stream
      |
      |Offline by default. Every outbound request goes through netguard, which
      |permits localhost and the LAN and refuses the public internet unless
      |SAGE_OFFLINE=0.""".stripMargin

  /** Rewrites the convenience tokens into flags, as the Python did. A bare
    * `--ollama` is a base URL, and `--http://host/page` is a page to fetch.
    */
  private def preprocess(args: List[String]): List[String] = args.flatMap { tok =>
    val low = tok.toLowerCase
    if low.startsWith("--http://") || low.startsWith("--https://") then
      List("--url", tok.drop(2))
    else Sage.Shorthands.get(low) match
      case Some(url) => List("--base-url", url)
      case None      => List(tok)
  }

  private final case class Opts(
      baseUrl: Option[String] = None,
      model: Option[String] = None,
      temperature: Double = 0.7,
      stream: Boolean = true,
      url: Option[String] = None,
      command: String = "",
      question: String = "",
      rest: List[String] = Nil
  )

  private def parse(args: List[String]): Opts =
    def loop(remaining: List[String], acc: Opts): Opts = remaining match
      case Nil                            => acc
      case "--base-url" :: v :: t         => loop(t, acc.copy(baseUrl = Some(v)))
      case "--model" :: v :: t            => loop(t, acc.copy(model = Some(v)))
      case "--temperature" :: v :: t      => loop(t, acc.copy(temperature = v.toDoubleOption.getOrElse(0.7)))
      case "--url" :: v :: t              => loop(t, acc.copy(url = Some(v)))
      case "--no-stream" :: t             => loop(t, acc.copy(stream = false))
      case "--cloud" :: t                 => loop(t, acc.copy(baseUrl = Some(Sage.CloudBaseUrl)))
      case "--ask" :: v :: t              => loop(t, acc.copy(command = "ask", question = v))
      case cmd :: t if cmd.startsWith("--") && acc.command.isEmpty =>
        loop(t, acc.copy(command = cmd.drop(2)))
      case other :: t                     => loop(t, acc.copy(rest = acc.rest :+ other))
    loop(args, Opts())

  def main(args: Array[String]): Unit =
    val opts = parse(preprocess(args.toList))
    val code =
      try
        opts.command match
          case "self-test" => SelfTest.run()
          case "doctor"    => Sage.doctor()
          case "firewall"  => firewall(opts.rest)
          case "login"     => login()
          case "learnings" => learnings(opts.rest)
          case "ask"       => ask(opts)
          case "cli"       => repl(opts)
          case "gui"       => Gui.launch(); -1 // the window owns the process now
          case "help"      => println(Usage); 0
          // No arguments means the window, as the Desktop shortcut expects.
          case ""          => Gui.launch(); -1
          case other       => println(s"unknown option --$other\n"); println(Usage); 2
      catch
        case b: Netguard.Blocked =>
          Console.err.println(s"! ${b.getMessage}")
          2
        case e: Llm.ApiError =>
          Console.err.println(s"! ${e.getMessage}")
          1
        case NonFatal(e) =>
          Console.err.println(s"! ${Option(e.getMessage).getOrElse(e.getClass.getSimpleName)}")
          1
    // -1 means a window is up and owns the process from here. Calling exit
    // would close it the instant it opened.
    if code >= 0 then sys.exit(code)

  private def firewall(targets: List[String]): Int =
    println("netguard policy")
    println("-" * 62)
    Netguard.policyLines.foreach(l => println("  " + l))
    if targets.isEmpty then
      println("\nPass one or more URLs/hosts to test them.")
      0
    else
      println("\ntargets")
      println("-" * 62)
      targets.map { t =>
        val v = Netguard.check(t)
        println("  " + v)
        if v.allowed then 0 else 1
      }.max

  /** Logs in to the modem and lists what this firmware actually has.
    *
    * Worth having as its own command: it needs no model at all, and it is the
    * fastest way to find out whether the password is right.
    */
  private def login(): Int =
    Sage.modemPassword match
      case None =>
        println("No modem password found. Put it in secret.txt next to the jar, " +
          "or set MODEM_PASSWORD.")
        2
      case Some(password) =>
        val modem = ModemSession(Sage.modemHost)
        modem.login("admin", password) match
          case Left(why) =>
            println(s"! $why")
            1
          case Right(msg) =>
            println(s"modem: $msg")
            print("reading the modem's menu... ")
            modem.discover() match
              case Left(why) => println(s"\n! $why"); 1
              case Right(pages) if pages.isEmpty =>
                println("\nno pages discovered - the menu may be built differently on " +
                  "this firmware.")
                1
              case Right(pages) =>
                println(s"found ${pages.size}\n")
                pages.toList.sortBy(_._2).foreach((label, path) => println(f"  $label%-28s $path"))
                0

  /** Shows the store, or forgets by name. Needs no model and no modem, which is
    * the point - looking at what you know should not require starting anything.
    */
  private def learnings(args: List[String]): Int =
    args match
      case "forget" :: name :: _ =>
        if Learnings.forget(name) then
          println(s"forgotten: $name")
          0
        else
          println(s"no learning named '$name'.")
          1
      case _ =>
        val held = Learnings.all
        println(s"learnings: ${Learnings.dictionaryPath}")
        println(s"           [${Learnings.where}]  ${held.size} named, " +
          s"${Learnings.blobCount} observation blob(s)")
        if held.isEmpty then println("\n(nothing recorded yet)")
        else
          println()
          for l <- held.sortBy(l => (-l.checks, l.name)) do
            val seen = if l.checks > 1 then s"  [${l.checks}x]" else ""
            println(s"  ${l.name}$seen")
            println(s"      ${l.claim}")
            // The evidence is the examination. Showing it here is the whole
            // difference between a list of beliefs and a list of findings.
            if l.evidence.nonEmpty then
              println(s"      evidence: ${Llm.abbreviate(l.evidence.linesIterator.mkString(" "), 150)}")
            println(s"      ${l.blobs.length} observation(s), last ${l.lastChecked}")
            println()
          println("forget one with:  sage-scala.cmd --learnings forget <name>")
        0

  private def connect(opts: Opts): Sage.Session =
    Sage.connect(opts.baseUrl, opts.model, opts.temperature, log = println) match
      case Left(why) =>
        println(s"\n! $why")
        println("  Run:  sage --doctor")
        sys.exit(1)
      case Right(s) => s

  private def wire(session: Sage.Session, streaming: Boolean): Unit =
    // Narrate tool use. Watching the model decide to go and look is most of
    // what makes the agent loop feel different from the old paste-and-ask.
    session.agent.onToolCall = (name, args) =>
      Console.err.println(s"  [tool] $name ${Llm.abbreviate(args, 120)}")
    session.agent.onToolResult = (name, result) =>
      Console.err.println(s"  [ -> ] $name: ${Llm.abbreviate(result.linesIterator.mkString(" "), 120)}")
    if streaming then session.agent.onDelta = s => print(s)

  private def ask(opts: Opts): Int =
    val session = connect(opts)
    println(s"[connected to ${session.client.baseUrl} using ${session.model}" +
      s" - ${session.caps.summary}]")
    if !session.caps.tools && session.caps.probed then
      println("[note: this model does not support tools, so SAGE cannot look things up " +
        "for itself. Pick a tools-capable model with --model.]")
    wire(session, opts.stream)

    // Log the modem in first when we have a password, so the tools have a
    // session and page discovery has run. Without it every fetch returns the
    // stub and the model is left describing a redirect.
    Sage.modemPassword.foreach { pw =>
      session.modem.login("admin", pw) match
        case Right(_)  => session.modem.discover(): Unit
        case Left(why) => Console.err.println(s"  [modem] not logged in: $why")
    }

    println()
    val (_, answer) = session.agent.ask(session.history, opts.question, opts.stream)
    if opts.stream then println() else println(answer)
    0

  private def repl(opts: Opts): Int =
    val session = connect(opts)
    println(s"[connected to ${session.client.baseUrl} using ${session.model}" +
      s" - ${session.caps.summary}]")
    wire(session, opts.stream)

    Sage.modemPassword.foreach { pw =>
      session.modem.login("admin", pw) match
        case Right(msg) =>
          println(s"[modem: $msg]")
          session.modem.discover() match
            case Right(pages) if pages.nonEmpty => println(s"[modem: ${pages.size} pages discovered]")
            case _                              => ()
        case Left(why) => println(s"[modem: not logged in - $why]")
    }

    println("\nSAGE terminal mode. /quit to exit, /reset to clear history.\n")
    var running = true
    while running do
      print("you > ")
      Console.flush()
      Option(scala.io.StdIn.readLine()) match
        case None => running = false // EOF
        case Some(line) =>
          line.trim match
            case ""                            => ()
            case "/quit" | "/exit" | "/q"      => running = false
            case "/reset" =>
              session.history = session.history.take(1)
              println("  history cleared")
            case "/pages" =>
              session.modem.knownPages.toList.sortBy(_._2)
                .foreach((label, path) => println(f"  $label%-28s $path"))
            case question =>
              try
                print("\nsage > ")
                val (history, answer) = session.agent.ask(session.history, question, opts.stream)
                session.history = history
                if !opts.stream then print(answer)
                println("\n")
              catch
                case NonFatal(e) =>
                  println(s"\n! ${Option(e.getMessage).getOrElse(e.getClass.getSimpleName)}\n")
    println("bye.")
    0
