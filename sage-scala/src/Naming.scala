package sage

/** Gives a learning a name a person can read and say.
  *
  * A content-addressed store is honest but mute: `a126650c315e998b…` tells you
  * a blob exists and nothing about what is in it. So every blob gets a name in
  * the dictionary, and the names follow one shape:
  *
  *     verb - noun - adjective
  *     observed-protocol-pppoe
  *     read-firmware-czw008
  *     counted-pages-six
  *
  * The order is doing work, not decoration. The **verb** says how the fact was
  * come by, which is the first thing you want when deciding whether to trust it
  * — `read` came off a page, `checked` came from the firewall, `observed` is
  * weaker than both. The **noun** is the subject, so names about the same
  * subject sort together. The **adjective** is the distinguishing value, so two
  * facts about firmware are told apart by the part that differs.
  *
  * A name is derived from the claim and never from the count of times it has
  * been re-confirmed. Names have to be stable: a dictionary key that changes
  * when a fact is re-checked is not a key, it is a second copy.
  */
object Naming:

  /** Verbs, in the order they are tested. The evidence says how the fact was
    * come by, so the verb is read off the evidence rather than guessed from the
    * claim.
    */
  private val verbsByEvidence: List[(List[String], String)] = List(
    List("--- begin modem page", "url:", "http://", "form fields") -> "read",
    List("netguard", "blocked offline", "allow  ", "private (lan)") -> "checked",
    List("session cookie", "logged in")                            -> "entered",
    List("models:", "context length", "can call tools", "ctx")     -> "probed",
    List("pages on this modem", "-> /")                            -> "listed"
  )

  /** Subjects worth naming directly. Anything outside this list falls back to
    * the longest ordinary word, which is usually right and never wrong enough
    * to matter — the name is a handle, not a schema.
    */
  private val nouns = List(
    "firmware", "serial", "protocol", "password", "channel", "gateway",
    "uptime", "firewall", "session", "wireless", "encapsulation", "subnet",
    "address", "hostname", "username", "bandwidth", "interface", "telnet",
    "traceroute", "model", "device", "page", "port", "dns", "ipv6", "wan",
    "lan", "wifi", "dhcp", "lease", "route", "vlan", "sip", "mtu", "isp"
  )

  private val stopWords = Set(
    "the", "this", "that", "with", "from", "into", "have", "has", "had", "and",
    "for", "its", "it's", "are", "was", "were", "been", "being", "modem",
    "does", "not", "set", "uses", "used", "using", "which", "when", "there",
    "here", "they", "them", "their", "some", "all", "any", "own", "current",
    "currently", "appears", "seems", "shows", "shown", "value", "field",
    "number", "version", "type", "state", "status", "configured", "listed"
  )

  private val numberWords = Vector(
    "zero", "one", "two", "three", "four", "five", "six",
    "seven", "eight", "nine", "ten", "eleven", "twelve"
  )

  private def words(text: String): List[String] =
    text.toLowerCase.split("[^a-z0-9.:_-]+").filter(_.nonEmpty).toList

  private def slug(token: String): String =
    token.toLowerCase.replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+)|(-+$)", "")

  private[sage] def verbFor(evidence: String): String =
    val low = evidence.toLowerCase
    verbsByEvidence
      .collectFirst { case (markers, verb) if markers.exists(low.contains) => verb }
      .getOrElse("observed")

  private[sage] def nounFor(claim: String): String =
    val ws = words(claim)
    // A known subject wins, matched on a prefix so "pages" finds "page" and
    // "addresses" finds "address".
    nouns.find(n => ws.exists(w => w.startsWith(n))) match
      case Some(n) => n
      case None =>
        ws.filter(w => w.length >= 4 && w.forall(_.isLetter) && !stopWords.contains(w))
          .sortBy(w => (-w.length, w))
          .headOption
          .getOrElse("fact")

  /** The distinguishing part.
    *
    * A concrete value wins, because that is what actually tells two facts about
    * the same subject apart. Small counts become words — `counted-pages-six`
    * reads as language, `counted-pages-6` reads as a serial number. A long
    * identifier is cut at its first segment: `czw008` is recognisable, whereas
    * `czw008-4-16-013-4` is the value again rather than a name for it.
    */
  private[sage] def adjectiveFor(claim: String, noun: String): String =
    val ws = words(claim)

    val numeric = ws.find(w => w.exists(_.isDigit) && w.length >= 2)
    val plain   = ws.find(w => w.forall(_.isDigit))

    plain.flatMap(_.toIntOption).filter(n => n >= 0 && n < numberWords.length) match
      case Some(n) => numberWords(n)
      case None =>
        numeric.map(slug).filter(_.nonEmpty) match
          // An identifier that STARTS with letters has a recognisable head:
          // `czw008` names the firmware, whereas `czw008-4-16-013-4` is the
          // value again rather than a name for it. A purely numeric one is
          // kept whole, because an address means nothing truncated - the
          // useful name for a verdict about 8.8.8.8 is 8-8-8-8, not 8.
          case Some(v) =>
            (if v.head.isLetter then v.split('-').headOption.getOrElse(v) else v).take(14)
          case None =>
            // No value in it at all, so fall back to the most substantial word
            // that is not already the noun.
            ws.filter(w =>
                w.length >= 4 && w.forall(_.isLetter) &&
                  !stopWords.contains(w) && !w.startsWith(noun))
              .sortBy(w => (-w.length, w))
              .headOption
              .map(_.take(14))
              .getOrElse("plain")

  /** Builds the name. `taken` is consulted so a second fact about the same
    * subject and value gets a distinct key rather than overwriting the first.
    */
  def nameFor(claim: String, evidence: String, taken: Set[String] = Set.empty): String =
    val noun = nounFor(claim)
    val base = List(verbFor(evidence), noun, adjectiveFor(claim, noun))
      .map(slug)
      .filter(_.nonEmpty)
      .mkString("-")

    if !taken.contains(base) then base
    else
      // Numbered rather than hashed: a person reading the dictionary should be
      // able to see that these two are siblings.
      LazyList.from(2).map(i => s"$base-$i").find(!taken.contains(_)).get
