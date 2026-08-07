package sage

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration
import scala.jdk.CollectionConverters.*

/** The one place an outbound request is made.
  *
  * Everything goes through here so the policy has a single point of
  * enforcement. A guarantee is only as good as the number of ways around it,
  * and the JDK offers several: `URL.openStream`, `HttpClient.newHttpClient`
  * (which picks up the system proxy), and a builder left at `Redirect.NORMAL`
  * (which follows a hop nobody vetted). None of those appear anywhere else in
  * this program.
  */
object Http:

  final case class Response(
      status: Int,
      body: String,
      headers: Map[String, String],
      finalUri: URI
  ):
    def header(name: String): String =
      headers.getOrElse(name.toLowerCase, "?")

    def contentType: String = header("content-type")
    def server: String      = header("server")
    def isOk: Boolean       = status >= 200 && status < 300

  /** Sends a request, following redirects by hand so each hop is vetted.
    *
    * The JDK's automatic redirect handling has no per-hop callback, which is
    * why it is switched off in [[Netguard.httpClient]]. The Python re-ran its
    * whole policy on every hop through a urllib handler; doing it manually here
    * keeps that guarantee rather than quietly losing it.
    *
    * @param followRedirects
    *   when false, a 3xx is returned as-is. Model-driven fetches use this: a
    *   redirect is a destination the model did not ask for and the user never
    *   saw, so the right answer is to stop and report.
    */
  def send(
      client: HttpClient,
      uri: URI,
      method: String = "GET",
      body: Option[String] = None,
      headers: Map[String, String] = Map.empty,
      timeout: Duration = Duration.ofSeconds(30),
      followRedirects: Boolean = true,
      maxRedirects: Int = 10
  ): Response =
    var current = uri
    var hops    = 0

    while true do
      Netguard.guard(current.toString)

      val builder = HttpRequest
        .newBuilder(current)
        .timeout(timeout)
        .header("User-Agent", "SAGE/2.0")
      headers.foreach { case (k, v) => builder.header(k, v) }

      val publisher = body match
        case Some(b) => HttpRequest.BodyPublishers.ofString(b)
        case None    => HttpRequest.BodyPublishers.noBody()
      builder.method(method.toUpperCase, publisher)

      val resp = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
      val hdrs = resp
        .headers()
        .map()
        .asScala
        .map { case (k, v) => k.toLowerCase -> v.asScala.mkString(", ") }
        .toMap

      val isRedirect = resp.statusCode() >= 300 && resp.statusCode() < 400
      val location   = hdrs.get("location")

      if !isRedirect || !followRedirects || location.isEmpty then
        return Response(resp.statusCode(), resp.body(), hdrs, current)

      hops += 1
      if hops > maxRedirects then
        throw RuntimeException(s"stopped after $maxRedirects redirects from $uri")

      // resolve() handles a relative Location, which most devices send.
      current = current.resolve(location.get)

    throw IllegalStateException("unreachable")

  /** Streams a response body line by line, for server-sent events.
    *
    * Deliberately a BufferedReader over the raw stream rather than anything
    * that buffers the whole body: the entire point of streaming is that the
    * first token arrives before the last one is written. `ofLines` would also
    * work, but reading by hand keeps the cancellation path obvious.
    */
  def stream(
      client: HttpClient,
      uri: URI,
      body: String,
      headers: Map[String, String] = Map.empty,
      timeout: Duration = Duration.ofMinutes(10)
  )(onLine: String => Unit): Int =
    Netguard.guard(uri.toString)

    val builder = HttpRequest
      .newBuilder(uri)
      .timeout(timeout)
      .header("User-Agent", "SAGE/2.0")
      .header("Accept", "text/event-stream")
    headers.foreach { case (k, v) => builder.header(k, v) }
    builder.POST(HttpRequest.BodyPublishers.ofString(body))

    val resp = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
    val status = resp.statusCode()

    val reader = java.io.BufferedReader(
      java.io.InputStreamReader(resp.body(), java.nio.charset.StandardCharsets.UTF_8)
    )
    try
      var line = reader.readLine()
      while line != null do
        onLine(line)
        line = reader.readLine()
    finally reader.close()
    status
