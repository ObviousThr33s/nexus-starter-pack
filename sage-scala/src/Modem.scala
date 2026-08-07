package sage

import java.net.{CookieManager, CookiePolicy, HttpCookie, URI}
import java.net.http.HttpClient
import java.time.Duration
import java.util.regex.Pattern
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** HTML extraction for the modem's pages.
  *
  * Note what is NOT a problem here. Go's regexp is RE2, which has no
  * backreferences, so `<(script|style)\b.*?</\1>` cannot compile there at all -
  * it has to become two patterns. `java.util.regex` is a backtracking engine
  * with full backreference support, so the Python's pattern ports across
  * character for character. That is a small thing, but it is the kind of small
  * thing that decides how faithful a port can be.
  */
object Html:

  // DOTALL so `.` crosses newlines (Python's re.S); CASE_INSENSITIVE for re.I.
  private val scriptOrStyle =
    Pattern.compile("<(script|style)\\b.*?</\\1>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE)
  private val lineBreaks =
    Pattern.compile("<br\\s*/?>|</p>|</div>|</tr>", Pattern.CASE_INSENSITIVE)
  private val anyTag     = Pattern.compile("<[^>]+>")
  private val spaces     = Pattern.compile("[ \\t\\r\\f\\u000B]+")
  private val blankLines = Pattern.compile("\\n\\s*\\n+")

  /** The 99-byte answer this modem gives to EVERY path when logged out - real
    * pages and invented ones alike. It is a 200 that means "no", and treating
    * it as content is how the model ends up describing fields that do not
    * exist.
    */
  private val loginStub =
    Pattern.compile("top\\.location\\s*=\\s*'login\\.html'", Pattern.CASE_INSENSITIVE)

  /** The signpost pages: tiny documents whose only content is a redirect. */
  private val redirectTo =
    Pattern.compile("top\\.location\\s*=\\s*['\"]([^'\"]+)['\"]", Pattern.CASE_INSENSITIVE)

  private val linkTag = Pattern.compile(
    "<a\\b[^>]*href=[\"']([^\"'#?]+\\.html)[^\"']*[\"'][^>]*>(.*?)</a>",
    Pattern.DOTALL | Pattern.CASE_INSENSITIVE
  )

  /** This firmware builds its nav menu in JavaScript, so most page names never
    * appear in an href - they are bare quoted strings. Anything scraping the
    * menu must look for both forms or it finds almost nothing.
    */
  private val htmlToken = Pattern.compile("[\"']([A-Za-z0-9_/]+\\.html)[\"']")

  /** Two patterns rather than one, and not for regex-engine reasons.
    *
    * The Python used a single `<(?:input|select|button)\b[^>]*?(?:name|id)=...`
    * which, being non-greedy, stops at whichever of name or id comes FIRST and
    * never looks at the other. On this modem's login page that reports
    * id="fake_password" and misses name="admin_password" - the field actually
    * submitted, and the one that revealed the decoy. Matching the whole tag and
    * then reading every name/id out of it reports all of them.
    */
  private val inputTag = Pattern.compile(
    "<(?:input|select|button)\\b[^>]*?>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE)
  private val nameOrId = Pattern.compile(
    "\\b(?:name|id)\\s*=\\s*[\"']([^\"']+)", Pattern.CASE_INSENSITIVE)
  private val formAction = Pattern.compile(
    "<form\\b[^>]*?action\\s*=\\s*[\"']([^\"']*)", Pattern.CASE_INSENSITIVE)
  private val titleTag = Pattern.compile(
    "<title[^>]*>(.*?)</title>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE)

  def isLoginStub(raw: String): Boolean = loginStub.matcher(raw).find()

  def redirectTarget(raw: String): Option[String] =
    val m = redirectTo.matcher(raw)
    if m.find() then Some(m.group(1)) else None

  def toText(raw: String): String =
    var t = scriptOrStyle.matcher(raw).replaceAll(" ")
    t = lineBreaks.matcher(t).replaceAll("\n")
    t = anyTag.matcher(t).replaceAll(" ")
    t = unescape(t)
    t = spaces.matcher(t).replaceAll(" ")
    blankLines.matcher(t).replaceAll("\n\n").trim

  /** HTML entity decoding. The JDK has no public unescaper outside the Swing
    * HTML parser, so the handful this firmware actually emits are done here.
    */
  private def unescape(s: String): String =
    if !s.contains('&') then s
    else
      val named = Map(
        "&nbsp;" -> " ", "&amp;" -> "&", "&lt;" -> "<", "&gt;" -> ">",
        "&quot;" -> "\"", "&apos;" -> "'", "&#39;" -> "'", "&copy;" -> "\u00a9",
        "&reg;" -> "\u00ae", "&mdash;" -> "\u2014", "&ndash;" -> "\u2013"
      )
      val withNamed = named.foldLeft(s) { case (acc, (k, v)) => acc.replace(k, v) }
      // Numeric entities: &#160; and &#xa0;
      val m  = Pattern.compile("&#(x?)([0-9A-Fa-f]+);").matcher(withNamed)
      val sb = StringBuilder()
      var last = 0
      while m.find() do
        sb ++= withNamed.substring(last, m.start())
        try
          val radix = if m.group(1).isEmpty then 10 else 16
          sb += Integer.parseInt(m.group(2), radix).toChar
        catch case NonFatal(_) => sb ++= m.group(0)
        last = m.end()
      sb ++= withNamed.substring(last)
      sb.toString

  def title(raw: String): Option[String] =
    val m = titleTag.matcher(raw)
    if m.find() then Some(unescape(m.group(1)).trim).filter(_.nonEmpty) else None

  /** Every form input name and id on a page.
    *
    * Tag stripping eats these, and on a login page they are the most
    * interesting thing there. Both attributes are reported because this
    * firmware uses them differently: the decoy and the real password input
    * share a name and are told apart only by id.
    */
  def fields(raw: String): List[String] =
    val out  = mutable.LinkedHashSet.empty[String]
    val tags = inputTag.matcher(raw)
    while tags.find() do
      val attrs = nameOrId.matcher(tags.group())
      while attrs.find() do out += attrs.group(1)
    out.toList

  def actions(raw: String): List[String] =
    val out = mutable.LinkedHashSet.empty[String]
    val m   = formAction.matcher(raw)
    while m.find() do if m.group(1).nonEmpty then out += m.group(1)
    out.toList

  /** Pulls page names out of one document, by href and by bare JS string. */
  def harvest(raw: String, candidates: mutable.ArrayBuffer[String],
              labelled: mutable.Map[String, String]): Unit =
    val links = linkTag.matcher(raw)
    while links.find() do
      val path  = "/" + links.group(1).stripPrefix("/")
      val label = toText(links.group(2)).trim.take(28)
      if label.nonEmpty then labelled.getOrElseUpdate(path, label): Unit
      candidates += path
    val tokens = htmlToken.matcher(raw)
    while tokens.find() do candidates += "/" + tokens.group(1).stripPrefix("/")

  /** modemstatus_devicetable.html -> "Devicetable". A serviceable button label
    * when the menu gave us no link text.
    */
  def prettify(path: String): String =
    val file = path.substring(path.lastIndexOf('/') + 1)
    val stem = file.lastIndexOf('.') match
      case -1 => file
      case i  => file.substring(0, i)
    val tail = stem.lastIndexOf('_') match
      case -1 => stem
      case i  => stem.substring(i + 1)
    val clean = tail.replace('-', ' ')
    if clean.isEmpty then path
    else (clean.head.toUpper +: clean.tail.toLowerCase).take(24)

  /** Renders a fetched page for the model: the metadata that matters, the form
    * structure tag-stripping would destroy, then the visible text.
    */
  def describe(url: String, status: Int, contentType: String, server: String,
               raw: String, maxChars: Int): String =
    val sb = StringBuilder()
    sb ++= s"URL: $url\n"
    sb ++= s"HTTP $status | Content-Type: $contentType | Server: $server\n"
    sb ++= s"Title: ${title(raw).getOrElse("(none)")}\n"
    val acts = actions(raw)
    if acts.nonEmpty then sb ++= s"Form actions: ${acts.mkString(", ")}\n"
    val flds = fields(raw)
    if flds.nonEmpty then sb ++= s"Form fields: ${flds.mkString(", ")}\n"

    val text = toText(raw)
    sb ++= "\nVisible text:\n"
    if text.isEmpty then sb ++= "(no visible text - likely rendered by JavaScript)"
    else if maxChars > 0 && text.length > maxChars then
      sb ++= text.take(maxChars)
      sb ++= s"\n\n[truncated at $maxChars chars of ${text.length}]"
    else sb ++= text
    sb.toString

/** Drives the CenturyLink C1000Z web UI.
  *
  * Almost everything awkward here is awkward because the device is. The
  * findings encoded below were measured against the live modem:
  *
  *   - Logged OUT, every path answers with the same 99-byte redirect stub.
  *     /modemstatus_devicetable.html and /definitely_not_real.html return
  *     byte-identical 200s, so a 200 proves nothing at all.
  *   - Logged IN, a missing file returns a real 404 from micro_httpd. That is
  *     the only authoritative existence test available.
  *   - Page paths differ between firmware builds, which is why the menu is
  *     discovered rather than hardcoded.
  *   - Login success is signalled by a session cookie, not by the body.
  */
object Modem:

  /** Caps what one page contributes to a prompt.
    *
    * The Python used 12000, chosen before anyone knew the window size. These
    * models report 32768 tokens - roughly 130k characters - so 12000 left most
    * of the window unused while truncating tables halfway. The budget shrinks
    * this further when a conversation is already long, so it is a ceiling
    * rather than a promise.
    */
  val DefaultMaxPageChars = 24000

  /** Seeds for discovery. "/" is a tiny redirect to index.html, and index.html
    * is the only page on this build that names the other sections - a signpost,
    * never a button, which is why it also appears in SkipPages.
    */
  private[sage] val LandingPages =
    List("/index.html", "/inde.html", "/", "/home.html", "/utilities_webactivitylog.html")

  private[sage] val SkipPages = List("login.html", "help.html", "inde.html", "index.html")

  /** The two paths confirmed on this box. Used only when discovery finds
    * nothing - a different firmware build has different paths, and guessing is
    * what put us here in the first place.
    */
  val FallbackPages: Map[String, String] = Map(
    "Activity log" -> "/utilities_webactivitylog.html",
    "Login page"   -> "/login.html"
  )

final class ModemSession(hostRaw: String):
  import Modem.*

  val host: String = hostRaw.replaceAll("/+$", "")

  /** ACCEPT_ALL rather than the default. The JDK's default policy refuses
    * cookies it considers third-party, and a bare IP host confuses that
    * judgement - which would silently produce a session that holds nothing.
    */
  private val cookies = CookieManager(null, CookiePolicy.ACCEPT_ALL)
  private val client: HttpClient = Netguard.httpClient(cookies)

  private var pages: Map[String, String] = Map.empty

  def url(path: String): String = host + "/" + path.stripPrefix("/")

  private def get(path: String, timeout: Duration = Duration.ofSeconds(15)): Http.Response =
    Http.send(client, URI.create(url(path)), "GET",
      headers = Map("Accept" -> "text/html,*/*"), timeout = timeout,
      followRedirects = false)

  /** Cookies currently held for the modem.
    *
    * The Python asked `len(_JAR)`. The JDK's CookieStore does expose its
    * contents, so unlike Go this needs no wrapper - but it still must be asked
    * about the right URI, because the store filters by domain and path.
    */
  def sessionCookies: List[HttpCookie] =
    try cookies.getCookieStore.get(URI.create(host + "/")).asScala.toList
    catch case NonFatal(_) => Nil

  def loggedIn: Boolean = sessionCookies.nonEmpty

  /** Posts to the modem's login.cgi.
    *
    * The form has TWO inputs both named admin_password: a decoy that appears
    * first and the real one second. The page's own JavaScript blanks the decoy
    * before submitting, so a browser sends the name twice with one empty value.
    * Sending the real password once is what the CGI accepts.
    */
  def login(username: String, password: String): Either[String, String] =
    val form = s"admin_username=${enc(username)}&admin_password=${enc(password)}"
    try
      val resp = Http.send(client, URI.create(url("/login.cgi")), "POST",
        body = Some(form),
        headers = Map(
          "Content-Type" -> "application/x-www-form-urlencoded",
          "Referer"      -> url("/login.html")
        ),
        timeout = Duration.ofSeconds(20), followRedirects = false)

      val low = resp.body.toLowerCase
      if List("invalid", "incorrect", "try again").exists(low.contains) then
        Left("modem rejected those credentials")
      else
        val held = sessionCookies
        if held.isEmpty then
          // Success is signalled by a cookie, not by the body. No cookie means
          // the login failed however cheerful the page looks.
          Left(s"no session cookie was issued (HTTP ${resp.status}) - login probably " +
            "failed. Check the password on the sticker under the modem")
        else
          // Names only. A session cookie's value is a credential, and this
          // string reaches diagnostics and the feed.
          Right(s"logged in (${held.length} session cookie(s): ${held.map(_.getName).mkString(", ")})")
    catch
      case b: Netguard.Blocked => Left(b.getMessage)
      case NonFatal(e)         => Left(s"could not reach the modem - ${msgOf(e)}")

  private def enc(s: String): String =
    java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8)

  /** Returns one page rendered for the model.
    *
    * A 200 carrying only the redirect stub is a logged-out answer, not a page.
    * Saying so beats handing the model an empty document to hallucinate over -
    * which is exactly what it did before this check existed.
    */
  def fetch(path: String, maxChars: Int = DefaultMaxPageChars): Either[String, String] =
    try
      val resp = get(path)
      if Html.isLoginStub(resp.body) then
        Left(s"${url(path)} answered with the modem's login redirect - no session. " +
          s"Log in first (HTTP ${resp.status}, ${resp.body.length} bytes)")
      else if resp.status == 404 then
        Left(s"${url(path)} does not exist on this firmware (HTTP 404)")
      else
        Right(Html.describe(url(path), resp.status, resp.contentType, resp.server,
          resp.body, maxChars))
    catch
      case b: Netguard.Blocked => Left(b.getMessage)
      case NonFatal(e)         => Left(s"could not reach ${url(path)} - ${msgOf(e)}")

  /** Asks the logged-in modem which pages it actually has.
    *
    * Needs a live session: logged out, every path is the stub and every
    * candidate would appear to exist. Returns label -> path, each verified.
    */
  def discover(limit: Int = 24): Either[String, Map[String, String]] =
    if !loggedIn then
      Left("not logged in - discovery needs a session, because logged out every path " +
        "returns the same redirect stub")
    else
      val labelled   = mutable.Map.empty[String, String]
      val candidates = mutable.ArrayBuffer.empty[String]
      val seeds      = mutable.Queue.from(LandingPages)
      val seen       = mutable.Set.empty[String]

      while seeds.nonEmpty do
        val start = seeds.dequeue()
        if !seen.contains(start) then
          seen += start
          try
            val resp = get(start)
            if !Html.isLoginStub(resp.body) && !resp.body.contains("admin_username") then
              // A tiny page that only redirects is a signpost, not content.
              Html.redirectTarget(resp.body) match
                case Some(hop) if resp.body.length < 400 =>
                  seeds.enqueue("/" + hop.stripPrefix("/"))
                case _ => Html.harvest(resp.body, candidates, labelled)
          catch case NonFatal(_) => () // a dead seed is not a failure

      // Two rounds. The menu names only the top-level sections; each section
      // page names its siblings, which is where most of the interest lives.
      val found   = mutable.LinkedHashMap.empty[String, String]
      val settled = mutable.Set.empty[String]
      for round <- 0 to 1 do
        for path <- candidates.distinct.toList if found.size < limit do
          val low = path.toLowerCase
          // /_html/ is the bundled help tree, not part of the modem's menu.
          if !low.startsWith("/_html/") && !SkipPages.exists(low.endsWith) &&
            !settled.contains(path)
          then
            settled += path
            try
              val resp = get(path)
              if resp.status == 200 && resp.body.length > 400 && !Html.isLoginStub(resp.body) then
                found(labelled.getOrElse(path, Html.prettify(path))) = path
                if round == 0 then Html.harvest(resp.body, candidates, labelled)
            catch case NonFatal(_) => ()

      pages = found.toMap
      Right(pages)

  /** The discovered menu, falling back to the two confirmed paths. */
  def knownPages: Map[String, String] = if pages.isEmpty then FallbackPages else pages

  /** Whether `path` is one the model may fetch.
    *
    * This is the tool layer's allowlist, and it is deliberately strict: only
    * paths the modem itself advertised, never a .cgi, never a traversal.
    * netguard cannot help here - the modem is a private address, so every one
    * of its endpoints is "allowed" as far as the firewall is concerned,
    * including the ones that change settings.
    */
  def allows(path: String): Either[String, String] =
    val clean = "/" + path.stripPrefix("/")
    if clean.contains("..") || clean.contains("//") then
      Left(s"'$path' is not a plain modem page path")
    else if clean.toLowerCase.contains(".cgi") then
      Left(s"'$path' is a .cgi endpoint - those change modem settings and are not " +
        "available to the assistant")
    else if knownPages.values.exists(_.equalsIgnoreCase(clean)) then Right(clean)
    else if clean.equalsIgnoreCase("/login.html") then Right(clean)
    else
      // A refusal the model cannot act on is a dead end. Naming the
      // alternatives is not enough on its own - a 7B reads "here is a list" as
      // information and stops. Telling it explicitly to try again turns the
      // same refusal into a retry, which is the difference between an answer
      // and "I was unable to fetch that".
      Left(s"'$path' is not a page on this modem. Retry this tool with one of these " +
        s"exact paths:\n${knownPages.values.toList.sorted.map("  " + _).mkString("\n")}")

  private def msgOf(e: Throwable): String =
    Option(e.getMessage).filter(_.nonEmpty).getOrElse(e.getClass.getSimpleName)
