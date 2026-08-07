package sage

import java.awt.{BorderLayout, Color, Dimension, Font, GridLayout}
import java.awt.event.{InputEvent, KeyEvent, WindowAdapter, WindowEvent}
import javax.swing.*
import javax.swing.text.{SimpleAttributeSet, StyleConstants}
import scala.util.control.NonFatal

/** The window.
  *
  * Swing because it is in the JDK. That is not a grudging reason: it means no
  * download, no browser tab, no local HTTP server to harden, and the modem
  * password stays in a JPasswordField instead of somewhere a browser offers to
  * remember it.
  *
  * Three things are on screen at all times, and they are the three that decide
  * whether an answer can be trusted:
  *
  *   1. the context bar - which model, can it call tools, how much window is
  *      left, is the firewall on, is the modem logged in. Every one of those
  *      silently changes what an answer is worth.
  *   2. the transcript, including tool calls as they happen. Watching the model
  *      go and look is what distinguishes an answer from a guess.
  *   3. the learnings, beside the conversation rather than buried behind a
  *      menu, because they are the part that outlives the session.
  */
object Gui:

  private val Bg      = Color(0x12, 0x15, 0x1c)
  private val Panel   = Color(0x1b, 0x1f, 0x29)
  private val Fg      = Color(0xd6, 0xda, 0xe2)
  private val Accent  = Color(0x7f, 0xd1, 0xb9)
  private val Muted   = Color(0x8b, 0x93, 0xa3)
  private val Warn    = Color(0xe0, 0xa4, 0x58)
  private val Err     = Color(0xff, 0x7b, 0x72)
  private val UserCol = Color(0x8a, 0xb4, 0xf8)

  private val Mono = Font("Consolas", Font.PLAIN, 13)

  def launch(): Unit =
    // Swing must be built and touched on the event dispatch thread. Everything
    // slow - connecting, fetching, inference - runs off it, and comes back
    // through invokeLater. That discipline is the whole reason the Python GUI
    // needed a depth counter and a Tk mark; here it is just where the code sits.
    SwingUtilities.invokeLater(() => Window().show())

  private final class Window:
    private val frame = JFrame("SAGE")

    private val transcript = JTextPane()
    private val input      = JTextArea(3, 40)
    private val contextBar = JLabel(" starting...")
    private val statusBar  = JLabel(" ")
    private val progress   = JProgressBar()

    private val learningList  = DefaultListModel[Learning]()
    private val learningsView = JList[Learning](learningList)

    private val modemUser = JTextField("admin", 10)
    private val modemPass = JPasswordField(12)

    /** Removing the buttons only works if the keys are discoverable. This is
      * the one line of chrome that replaces six of them.
      */
    private val hint = JLabel("enter sends · ctrl+L clears · del forgets · dbl-click re-examines")

    private var session: Option[Sage.Session] = None
    @volatile private var busy = false

    // ----------------------------------------------------------------
    // construction
    // ----------------------------------------------------------------

    def show(): Unit =
      frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE)
      frame.addWindowListener(new WindowAdapter:
        override def windowClosing(e: WindowEvent): Unit =
          frame.dispose()
          sys.exit(0)
      )
      frame.setSize(Dimension(1040, 720).width, 720)
      frame.setLocationRelativeTo(null)
      frame.getContentPane.setBackground(Bg)
      frame.setLayout(BorderLayout(6, 6))

      frame.add(topArea(), BorderLayout.NORTH)
      frame.add(centre(), BorderLayout.CENTER)
      frame.add(bottom(), BorderLayout.SOUTH)

      hint.setForeground(Muted)
      hint.setFont(Font("Consolas", Font.PLAIN, 11))

      refreshLearnings()
      emit("SAGE ready. Ask a question - the model looks things up on the modem itself.", Muted)
      emit(s"learnings: ${Learnings.dictionaryPath}  [${Learnings.where}]", Muted)
      emit(s"           ${Learnings.all.size} named, ${Learnings.blobCount} observation blob(s)", Muted)
      emit("", Muted)

      frame.setVisible(true)
      connect()

    private def topArea(): JComponent =
      val box = JPanel(GridLayout(2, 1))
      box.setBackground(Bg)

      contextBar.setForeground(Muted)
      contextBar.setFont(Mono)
      contextBar.setBackground(Bg)
      contextBar.setOpaque(true)
      box.add(contextBar)

      val modemRow = JPanel()
      modemRow.setBackground(Bg)
      modemRow.setLayout(BoxLayout(modemRow, BoxLayout.X_AXIS))
      modemRow.add(label("modem "))
      style(modemUser)
      style(modemPass)
      modemRow.add(modemUser)
      modemRow.add(Box.createHorizontalStrut(4))
      modemRow.add(modemPass)
      // No Log in button. Enter in the password field is the same gesture with
      // one less thing on screen, and the common case never reaches here at
      // all - secret.txt means login already happened on connect.
      modemPass.addActionListener(_ => login())
      modemRow.add(Box.createHorizontalStrut(8))
      modemRow.add(hint)
      modemRow.add(Box.createHorizontalGlue())
      box.add(modemRow)

      // Prefill from secret.txt so the common case is one click, not typing a
      // password off a sticker again.
      Sage.modemPassword.foreach(modemPass.setText)
      box

    private def centre(): JComponent =
      transcript.setEditable(false)
      transcript.setBackground(Color(0x0d, 0x10, 0x16))
      transcript.setForeground(Fg)
      transcript.setFont(Mono)

      val split = JSplitPane(
        JSplitPane.HORIZONTAL_SPLIT,
        JScrollPane(transcript),
        learningsPanel()
      )
      split.setResizeWeight(0.72)
      split.setBackground(Bg)
      split

    private def learningsPanel(): JComponent =
      val panel = JPanel(BorderLayout(0, 4))
      panel.setBackground(Bg)

      val head = label("  learnings")
      head.setForeground(Accent)
      panel.add(head, BorderLayout.NORTH)

      learningsView.setBackground(Panel)
      learningsView.setForeground(Fg)
      learningsView.setFont(Font("Consolas", Font.PLAIN, 12))
      learningsView.setCellRenderer((_, value, _, selected, _) =>
        val l = value.asInstanceOf[Learning]
        val seen = if l.checks > 1 then s"  (${l.checks}x)" else ""
        val cell = JLabel(s"<html><body style='width:200px'>&bull; ${escape(l.claim)}" +
          s"<font color='#8b93a3'>$seen</font></body></html>")
        cell.setOpaque(true)
        cell.setBackground(if selected then Color(0x2a, 0x2f, 0x3b) else Panel)
        cell.setForeground(Fg)
        cell.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6))
        // The evidence is the examination. It is one hover away rather than on
        // screen, because a wall of quoted output would bury the claims.
        cell.setToolTipText(
          s"<html><body style='width:420px'><b>${escape(l.claim)}</b><br><br>" +
            s"<i>evidence</i><br>${escape(l.evidence)}<br><br>" +
            s"<font color='#666'>${escape(l.source)} &middot; first seen ${l.firstSeen}" +
            s" &middot; last checked ${l.lastChecked}</font></body></html>")
        cell
      )
      // Re-examination is the point of keeping evidence beside a claim: put the
      // claim back in front of the model and make it check. That is a gesture
      // on the thing itself, not a button somewhere else - double-click the
      // learning, Delete to forget it.
      learningsView.addMouseListener(new java.awt.event.MouseAdapter:
        override def mouseClicked(e: java.awt.event.MouseEvent): Unit =
          if e.getClickCount == 2 then reexamineSelected()
      )
      learningsView.getInputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "forget")
      learningsView.getActionMap.put("forget", action(() => forgetSelected()))

      panel.add(JScrollPane(learningsView), BorderLayout.CENTER)
      panel

    private def bottom(): JComponent =
      val box = JPanel(BorderLayout(4, 2))
      box.setBackground(Bg)

      progress.setIndeterminate(true)
      progress.setVisible(false)
      progress.setBackground(Panel)
      progress.setForeground(Accent)
      box.add(progress, BorderLayout.NORTH)

      val row = JPanel(BorderLayout(6, 0))
      row.setBackground(Bg)
      input.setBackground(Panel)
      input.setForeground(Fg)
      input.setCaretColor(Fg)
      input.setFont(Mono)
      input.setLineWrap(true)
      input.setWrapStyleWord(true)

      // Enter sends, Shift+Enter is a newline. Same as the Python, because that
      // is the muscle memory already in place.
      val im = input.getInputMap(JComponent.WHEN_FOCUSED)
      im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "send")
      im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), "newline")
      im.put(KeyStroke.getKeyStroke(KeyEvent.VK_L, InputEvent.CTRL_DOWN_MASK), "clear")
      input.getActionMap.put("send", action(() => send()))
      input.getActionMap.put("newline", action(() => input.append("\n")))
      input.getActionMap.put("clear", action(() => clearChat()))

      // No Send button: Enter already sends, and a button that duplicates a key
      // is a door that only exists to be noticed. No Doctor button either - the
      // model has run_diagnostics, so "why can't you reach the modem?" answers
      // itself instead of telling the user to go and press something.
      row.add(JScrollPane(input), BorderLayout.CENTER)
      box.add(row, BorderLayout.CENTER)

      statusBar.setForeground(Warn)
      statusBar.setFont(Font("Consolas", Font.PLAIN, 11))
      box.add(statusBar, BorderLayout.SOUTH)
      box

    // ----------------------------------------------------------------
    // context awareness
    // ----------------------------------------------------------------

    /** The line that says what an answer is currently worth.
      *
      * Every field here changes the meaning of what the model says. A model
      * with no tool support cannot look anything up; a modem that is not logged
      * in returns the redirect stub for every page; a context window near full
      * means older turns have already been dropped. Leaving any of that
      * off-screen is how you end up trusting an answer you should not.
      */
    private def updateContext(): Unit =
      val parts = scala.collection.mutable.ArrayBuffer.empty[String]
      session match
        case None => parts += "not connected"
        case Some(s) =>
          parts += s.model
          parts += (if s.caps.tools then "tools" else "NO TOOLS")
          if s.caps.contextLength > 0 then
            val used = s.agent.budget.estimate(s.history)
            val pct  = (used * 100.0 / s.caps.contextLength).toInt
            parts += f"context $pct%d%% of ${s.caps.contextLength}"
          parts += (if s.modem.loggedIn then s"modem in (${s.modem.knownPages.size} pages)"
                    else "modem OUT")
      parts += (if Netguard.offline then "offline" else "INTERNET ALLOWED")
      parts += s"${Learnings.all.size} learnings"
      contextBar.setText("  " + parts.mkString("  ·  "))
      contextBar.setForeground(
        if session.exists(s => !s.caps.tools) then Warn
        else if session.isEmpty then Err
        else Muted
      )

    private def refreshLearnings(): Unit =
      learningList.clear()
      Learnings.all.sortBy(-_.checks).foreach(learningList.addElement)
      updateContext()

    // ----------------------------------------------------------------
    // actions
    // ----------------------------------------------------------------

    private def connect(): Unit = offThread("connecting...") {
      Sage.connect(None, None, log = m => onUi(emit(m, Muted))) match
        case Left(why) => onUi {
          emit(why, Err)
          emit("Run: sage-scala.cmd --doctor   (it explains exactly what is missing)", Muted)
          statusBar.setForeground(Err)
          statusBar.setText("  not connected")
        }
        case Right(s) => onUi {
          session = Some(s)
          wire(s)
          emit(s"connected to ${s.client.baseUrl} using ${s.model} - ${s.caps.summary}", Accent)
          if !s.caps.tools && s.caps.probed then
            emit("This model cannot call tools, so SAGE cannot look anything up for " +
              "itself. Answers will be from the conversation only.", Warn)
          statusBar.setForeground(Accent)
          statusBar.setText("  ready")
          updateContext()
          // A password in secret.txt means we can be useful immediately rather
          // than waiting to be told to log in.
          if modemPass.getPassword.nonEmpty then login()
        }
    }

    private def wire(s: Sage.Session): Unit =
      s.agent.onDelta = text => onUi(append(text, Accent))
      s.agent.onToolCall = (name, args) =>
        onUi(emit(s"  [${name}] ${Llm.abbreviate(args, 90)}", Muted))
      s.agent.onToolResult = (name, result) =>
        onUi {
          val flat = result.linesIterator.mkString(" ")
          val isErr = result.startsWith("error:")
          emit(s"  [ -> ] ${Llm.abbreviate(flat, 110)}", if isErr then Warn else Muted)
          // A save_learning call changes the panel, so reflect it at once -
          // seeing it land is what makes the feature feel real.
          if name == "save_learning" && !isErr then refreshLearnings()
        }

    private def login(): Unit =
      val user = modemUser.getText.trim
      val pass = String(modemPass.getPassword)
      if pass.isEmpty then
        emit("Enter the modem password (it is on the sticker under the modem).", Err)
      else offThread(s"logging in to ${Sage.modemHost}...") {
        session.foreach { s =>
          s.modem.login(user, pass) match
            case Left(why) => onUi(emit(s"modem: $why", Err))
            case Right(msg) =>
              onUi(emit(s"modem: $msg", Muted))
              setStatus("reading the modem's menu...")
              // No page buttons. There used to be one per discovered page, up
              // to six, and each did nothing but type a sentence into the input
              // box. The model has list_modem_pages and fetch_modem_page, so it
              // can reach every one of those pages itself - and unlike a fixed
              // strip of six, it can reach the seventh. The buttons were a
              // worse copy of a capability that already existed.
              s.modem.discover() match
                case Right(pages) if pages.nonEmpty => onUi {
                  emit(s"modem: ${pages.size} pages discovered - " +
                    "ask about any of them, or ask what there is.", Muted)
                  updateContext()
                }
                case Right(_)  => onUi(emit("modem: menu not readable on this firmware.", Warn))
                case Left(why) => onUi(emit(s"modem: $why", Warn))
        }
      }

    private def send(): Unit =
      val text = input.getText.trim
      if text.isEmpty then ()
      else if busy then emit("still working - one at a time.", Warn)
      else session match
        case None => emit("Not connected - press Doctor to see why.", Err)
        case Some(s) =>
          input.setText("")
          emit(s"\nyou > $text", UserCol)
          append("\nsage > ", Accent)
          offThread("thinking...") {
            try
              val (history, _) = s.agent.ask(s.history, text, stream = true)
              s.history = history
              // Nothing is appended here. onDelta already wrote every token as
              // it arrived, so printing the returned answer as well showed the
              // whole reply twice. Guarding with endsWith did not save it - the
              // stream ends with whitespace the answer does not have.
              onUi {
                append("\n\n", Fg)
                refreshLearnings()
              }
            catch case NonFatal(e) => onUi(emit(s"\nrequest failed: ${msg(e)}\n", Err))
          }

    private def reexamineSelected(): Unit =
      Option(learningsView.getSelectedValue).foreach { l =>
        input.setText(
          s"""Re-examine this earlier finding against the modem as it is now: "${l.claim}"
             |Check it with a tool. If it still holds, save_learning it again to re-confirm.
             |If it is now wrong, say so plainly and save the corrected fact.""".stripMargin)
        send()
      }

    private def forgetSelected(): Unit =
      Option(learningsView.getSelectedValue).foreach { l =>
        if Learnings.forget(l.claim) then
          emit(s"forgot: ${l.claim}", Muted)
          refreshLearnings()
      }

    private def clearChat(): Unit =
      transcript.setText("")
      // Keep the system prompt: it carries the tool contract and the learnings.
      session.foreach(s => s.history = s.history.take(1))
      updateContext()

    // ----------------------------------------------------------------
    // threading and painting
    // ----------------------------------------------------------------

    /** Runs `body` off the event dispatch thread, with the UI marked busy.
      *
      * The Python needed a depth counter here because its Enter binding could
      * start a second job mid-flight. This refuses the second job instead,
      * which is simpler and, for a local 7B, more honest - two concurrent
      * inferences on one GPU is not two answers faster.
      */
    private def offThread(what: String)(body: => Unit): Unit =
      busy = true
      setStatus(what)
      progress.setVisible(true)
      Thread
        .ofVirtual()
        .start(() =>
          try body
          catch case NonFatal(e) => onUi(emit(s"! ${msg(e)}", Err))
          finally onUi {
            busy = false
            progress.setVisible(false)
            statusBar.setForeground(Accent)
            statusBar.setText("  ready")
            updateContext()
          }
        ): Unit

    private def onUi(body: => Unit): Unit = SwingUtilities.invokeLater(() => body)

    private def setStatus(text: String): Unit = onUi {
      statusBar.setForeground(Warn)
      statusBar.setText("  " + text)
    }

    private def emit(text: String, colour: Color): Unit = append(text + "\n", colour)

    private def append(text: String, colour: Color): Unit =
      val attrs = SimpleAttributeSet()
      StyleConstants.setForeground(attrs, colour)
      val doc = transcript.getDocument
      doc.insertString(doc.getLength, text, attrs)
      transcript.setCaretPosition(doc.getLength)

    // ----------------------------------------------------------------
    // small helpers
    // ----------------------------------------------------------------

    private def label(text: String): JLabel =
      val l = JLabel(text)
      l.setForeground(Fg)
      l.setFont(Mono)
      l

    private def style(field: JTextField): Unit =
      field.setBackground(Panel)
      field.setForeground(Fg)
      field.setCaretColor(Fg)
      field.setFont(Mono)
      field.setMaximumSize(Dimension(160, 26))

    // There is no button() helper any more, and that is the point: after
    // folding Doctor and Forget into tools, and Send/Clear/Log in/the page
    // strip into keys, nothing was left for it to build.

    private def action(run: () => Unit): Action =
      new AbstractAction:
        def actionPerformed(e: java.awt.event.ActionEvent): Unit = run()

    private def escape(s: String): String =
      s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private def msg(e: Throwable): String =
      Option(e.getMessage).filter(_.nonEmpty).getOrElse(e.getClass.getSimpleName)
