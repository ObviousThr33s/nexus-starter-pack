package sage

import java.net.{InetAddress, ProxySelector, URI}
import java.net.http.HttpClient
import java.time.Duration
import java.util.concurrent.{Callable, ExecutorService, Executors, TimeUnit, TimeoutException}
import scala.util.control.NonFatal

/** The outbound firewall.
  *
  * The rule this box runs under: nothing here reaches the public internet
  * unless someone deliberately turns that off. Localhost runtimes, the modem at
  * 192.168.0.1, anything else on the LAN - all fine.
  *
  * It is a policy layer, not a packet filter: it decides before a socket opens,
  * by resolving the hostname and classifying every address it resolves to. That
  * catches what a hostname allowlist misses - a name that resolves off-LAN, or
  * one with a private and a public record.
  *
  * ==Why this is an allowlist, not a port of the Python==
  *
  * `netguard.py` asked Python's ipaddress module "is_private?" and believed the
  * answer. CPython's `is_private` is far broader than the name suggests, and
  * because `_local_ip` consulted it *before* `is_reserved`, the Python permits
  * several addresses that are anything but local. Measured against the live
  * module during this rewrite:
  *
  * {{{
  * 2002:0808:0808::1  ->  ALLOW "private (LAN)"   6to4, routes to 8.8.8.8
  * 2001::1            ->  ALLOW "private (LAN)"   Teredo tunnel
  * 169.254.169.254    ->  ALLOW "link-local"      cloud metadata, classic SSRF
  * 240.0.0.1          ->  ALLOW "private (LAN)"   reserved
  * 198.18.0.1         ->  ALLOW "private (LAN)"   benchmark range
  * 203.0.113.9        ->  ALLOW "private (LAN)"   TEST-NET-3
  * }}}
  *
  * So this names the ranges it permits and refuses everything else. A range
  * nobody listed is refused, which is what you want from a thing whose entire
  * job is saying no.
  */
object Netguard:

  /** Raised instead of opening a connection the policy forbids. */
  final class Blocked(val target: String, val reason: String)
      extends RuntimeException(
        s"$target refused by netguard - $reason. Set SAGE_OFFLINE=0 to allow the " +
          "internet, or add the host to SAGE_ALLOW."
      )

  final case class Verdict(
      target: String,
      allowed: Boolean,
      reason: String,
      host: String = "",
      addrs: List[String] = Nil
  ):
    override def toString: String =
      val mark  = if allowed then "allow" else "BLOCK"
      val where = if addrs.isEmpty then "" else addrs.mkString(" [", ", ", "]")
      s"$mark  $target$where  - $reason"

  /** Schemes worth letting through. Everything else - file:, ftp:, gopher: - is
    * refused outright rather than reasoned about.
    */
  val AllowedSchemes: Set[String] = Set("http", "https")

  /** The JVM cannot portably change its own environment, so the policy reads go
    * through here. It is not only a test seam: the UI needs to flip offline
    * mode at runtime too, and the Python could do that because `os.environ` is
    * writable in-process.
    *
    * Deliberately consulted on EVERY read rather than cached. A stale copy of
    * this value is a hole in the policy.
    */
  @volatile private var overrides: Map[String, String] = Map.empty

  private def setting(key: String, default: String): String =
    overrides.getOrElse(key, sys.env.getOrElse(key, default))

  /** Runs `body` with settings overridden, then restores them. */
  private[sage] def withSettings[A](pairs: (String, String)*)(body: => A): A =
    val saved = overrides
    overrides = saved ++ pairs.toMap
    try body
    finally overrides = saved

  /** Permanently overrides a setting, for the UI's offline toggle. */
  def setOffline(on: Boolean): Unit =
    overrides = overrides + ("SAGE_OFFLINE" -> (if on then "1" else "0"))

  def offline: Boolean = setting("SAGE_OFFLINE", "1") != "0"

  def allowlist: List[String] =
    setting("SAGE_ALLOW", "")
      .replace(';', ',')
      .split(',')
      .map(_.trim.toLowerCase)
      .filter(_.nonEmpty)
      .toList

  // ------------------------------------------------------------------
  // address classification
  // ------------------------------------------------------------------

  /** One CIDR block. Written out rather than borrowed, because the JDK has no
    * CIDR type and the alternatives all disagree with each other about the
    * edges - which is the mistake being corrected here.
    */
  final case class Cidr(base: Array[Byte], bits: Int):
    def contains(addr: Array[Byte]): Boolean =
      if addr.length != base.length then false
      else
        var remaining = bits
        var i         = 0
        var ok        = true
        while ok && remaining > 0 do
          val take = math.min(8, remaining)
          val mask = (0xff << (8 - take)) & 0xff
          if (addr(i) & mask) != (base(i) & mask) then ok = false
          remaining -= take
          i += 1
        ok

  object Cidr:
    def apply(spec: String): Cidr =
      spec.split('/') match
        case Array(addr, bits) => Cidr(InetAddress.getByName(addr).getAddress, bits.toInt)
        case _ => throw IllegalArgumentException(s"not a CIDR: $spec")

  /** The whole permitted world. Anything not covered here is refused, so this
    * list is the security boundary - read it as the answer to "what counts as
    * our side of the wire?"
    */
  private val localPrefixes: List[(Cidr, String)] = List(
    Cidr("127.0.0.0/8")    -> "loopback",
    Cidr("::1/128")        -> "loopback",
    Cidr("10.0.0.0/8")     -> "private (LAN)",
    Cidr("172.16.0.0/12")  -> "private (LAN)",
    Cidr("192.168.0.0/16") -> "private (LAN)",
    Cidr("fc00::/7")       -> "private (LAN, ULA)",
    Cidr("169.254.0.0/16") -> "link-local",
    Cidr("fe80::/10")      -> "link-local"
  )

  /** Refused with a specific reason before the allowlist runs.
    *
    * Most of these would already fall off the end of `localPrefixes`. They are
    * named so the message says *why*: "6to4 tunnel to a public address" is a
    * better thing to read at 1am than "public internet". The metadata addresses
    * are the exception that genuinely needs to be here - they sit inside
    * link-local, which is permitted.
    */
  private val deniedPrefixes: List[(Cidr, String)] = List(
    Cidr("169.254.169.254/32") -> "cloud metadata service, never a LAN device",
    Cidr("fd00:ec2::254/128")  -> "cloud metadata service, never a LAN device",
    Cidr("2002::/16")          -> "6to4 tunnel - carries a public IPv4 address",
    Cidr("2001::/32")          -> "Teredo tunnel - carries a public IPv4 address",
    Cidr("64:ff9b::/96")       -> "NAT64 - translates to a public IPv4 address",
    Cidr("64:ff9b:1::/48")     -> "NAT64 - translates to a public IPv4 address"
  )

  private val v4MappedPrefix = Cidr("::ffff:0:0/96")

  /** ::ffff:8.8.8.8 is 8.8.8.8 wearing a hat. Java usually normalises these to
    * an Inet4Address, but not from every construction path, so unmap explicitly
    * rather than depending on which one the caller used.
    */
  private def unmap(bytes: Array[Byte]): Array[Byte] =
    if bytes.length == 16 && v4MappedPrefix.contains(bytes) then bytes.slice(12, 16)
    else bytes

  /** Classifies one address. Returns (allowed, why). */
  def classify(addr: InetAddress): (Boolean, String) =
    val bytes = unmap(addr.getAddress)

    if bytes.forall(_ == 0) then (false, "unspecified address (0.0.0.0/::), blocked")
    else if addr.isMulticastAddress then (false, "multicast address, blocked")
    else
      deniedPrefixes.collectFirst { case (cidr, why) if cidr.contains(bytes) => why } match
        case Some(why) => (false, s"$why, blocked")
        case None =>
          localPrefixes.collectFirst { case (cidr, why) if cidr.contains(bytes) => why } match
            case Some(why) => (true, why)
            case None      => (false, "public internet address, blocked offline")

  // ------------------------------------------------------------------
  // target checking
  // ------------------------------------------------------------------

  /** Pulls (scheme, host) out of a URL, a host:port, or a bare host. */
  private[sage] def splitTarget(target: String): (String, String) =
    val (scheme, afterScheme) = target.indexOf("://") match
      case -1 => ("http", target)
      case i  => (target.substring(0, i).toLowerCase, target.substring(i + 3))
    val hostPort = afterScheme.takeWhile(c => c != '/' && c != '?')
    val host =
      if hostPort.startsWith("[") then
        val end = hostPort.indexOf(']')
        if end >= 0 then hostPort.substring(1, end) else hostPort.substring(1)
      else if hostPort.count(_ == ':') == 1 then hostPort.takeWhile(_ != ':')
      else hostPort
    (scheme, host.trim.toLowerCase)

  private val ipv4Literal = "^\\d{1,3}(\\.\\d{1,3}){3}$".r

  private def isLiteral(host: String): Boolean =
    host.contains(':') || ipv4Literal.matches(host)

  /** DNS with a deadline. `InetAddress.getAllByName` has no timeout parameter,
    * and a black-holed resolver would otherwise stall --doctor for the system
    * default - which on Windows can be many seconds.
    */
  private lazy val resolverPool: ExecutorService =
    Executors.newCachedThreadPool { r =>
      val t = Thread(r, "netguard-dns")
      t.setDaemon(true) // must never hold the JVM open
      t
    }

  private def resolve(host: String): Either[String, List[InetAddress]] =
    val task: Callable[Array[InetAddress]] = () => InetAddress.getAllByName(host)
    try
      val addrs = resolverPool.submit(task).get(5, TimeUnit.SECONDS)
      if addrs.isEmpty then Left("does not resolve (no addresses)")
      else Right(addrs.toList)
    catch
      case _: TimeoutException => Left("does not resolve (timed out after 5s)")
      case NonFatal(e) =>
        val cause = Option(e.getCause).getOrElse(e)
        Left(s"does not resolve (${cause.getMessage})")

  /** Decides whether `target` may be contacted. Never opens a connection. */
  def check(target: String): Verdict =
    val (scheme, host) = splitTarget(target)

    if !AllowedSchemes.contains(scheme) then
      Verdict(target, false, s"scheme '$scheme' is not permitted")
    else if host.isEmpty then Verdict(target, false, "no host in that target")
    else if !offline then
      Verdict(target, true, "offline mode is off - all targets allowed", host)
    else
      allowlist.find(p => host == p || globMatches(p, host)) match
        case Some(pattern) =>
          Verdict(target, true, s"host matches SAGE_ALLOW entry '$pattern'", host)
        case None if isLiteral(host) =>
          // A literal needs no DNS, and must not get any - resolving it would
          // hand back the same number.
          try
            val addr        = InetAddress.getByName(host)
            val (ok, why)   = classify(addr)
            Verdict(target, ok, why, host, List(host))
          catch case NonFatal(e) => Verdict(target, false, s"not a usable address (${e.getMessage})", host)
        case None =>
          resolve(host) match
            case Left(why) => Verdict(target, false, why, host)
            case Right(addrs) =>
              val strs = addrs.map(_.getHostAddress).distinct
              // Every address must be local. One public record is enough to
              // refuse: which one the socket would pick is not ours to predict.
              addrs.map(a => (a, classify(a))).collectFirst { case (a, (false, why)) => (a, why) } match
                case Some((a, why)) =>
                  Verdict(target, false, s"resolves to ${a.getHostAddress} ($why)", host, strs)
                case None =>
                  Verdict(target, true, classify(addrs.head)._2, host, strs)

  /** `check`, but throws instead of returning a refusal. */
  def guard(target: String): Verdict =
    val v = check(target)
    if !v.allowed then throw Blocked(target, v.reason)
    v

  /** fnmatch-style host patterns: "*.example.com". Only `*` and `?` are
    * meaningful, and `.` is a literal - the opposite of a regex, which is why
    * this is not one.
    */
  private[sage] def globMatches(pattern: String, host: String): Boolean =
    val regex = pattern.flatMap {
      case '*' => ".*"
      case '?' => "."
      case c   => java.util.regex.Pattern.quote(c.toString)
    }
    host.matches(regex)

  // ------------------------------------------------------------------
  // the guarded HTTP client
  // ------------------------------------------------------------------

  /** Builds the only HttpClient this program may use.
    *
    * Two things here are deliberate and load-bearing:
    *
    *   - `NO_PROXY`. The JDK's default builder consults the system proxy
    *     selector. With a proxy configured, the target host is never contacted
    *     at all - the proxy is - so the policy would vet a host nobody dials
    *     while the traffic egresses somewhere else entirely. This must never be
    *     left at the default.
    *   - `Redirect.NEVER`. A redirect is a new target, possibly a new host,
    *     possibly off-LAN. The JDK gives no per-hop callback, so redirects are
    *     followed by hand in [[Http.send]] with the policy re-run on each hop.
    *
    * A note on what this cannot do. Go's `net.Dialer.ControlContext` runs after
    * DNS with the address about to be connected, which closes the window
    * between checking a name and dialling it. `java.net.http.HttpClient`
    * exposes no socket-level hook, so that backstop is not available here. The
    * mitigation is [[Http.pinned]]: for a name (rather than a literal), resolve
    * once, validate every address, then connect to the validated address
    * directly - which removes the second lookup instead of racing it.
    */
  def httpClient(cookies: java.net.CookieHandler | Null = null): HttpClient =
    val b = HttpClient
      .newBuilder()
      .proxy(HttpClient.Builder.NO_PROXY)
      .followRedirects(HttpClient.Redirect.NEVER)
      .connectTimeout(Duration.ofSeconds(10))
    if cookies != null then b.cookieHandler(cookies): Unit
    b.build()

  /** A ProxySelector that refuses everything, for the rare API that insists on
    * one rather than accepting NO_PROXY.
    */
  private[sage] val noProxy: ProxySelector = HttpClient.Builder.NO_PROXY

  // ------------------------------------------------------------------
  // reporting
  // ------------------------------------------------------------------

  def policyLines: List[String] =
    val state  = if offline then "ON  - LAN and localhost only" else "OFF - internet permitted"
    val switch = setting("SAGE_OFFLINE", "1 (default)")
    val except = if allowlist.isEmpty then "(none)  - set SAGE_ALLOW to add some" else allowlist.mkString(", ")
    List(
      s"offline mode   : $state",
      s"  switch       : SAGE_OFFLINE=$switch",
      s"  exceptions   : $except",
      s"  schemes      : ${AllowedSchemes.toList.sorted.mkString(", ")}",
      "  allowed      : loopback, link-local, RFC1918/ULA private ranges",
      "  refused      : public, multicast, reserved, 6to4/Teredo/NAT64 tunnels,",
      "                 cloud metadata; names resolving to any of those"
    )

  /** Normalises a URI for reporting and comparison. */
  private[sage] def uriOf(target: String): URI = URI.create(target)
