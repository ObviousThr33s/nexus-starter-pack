package sage

/** Is a claim the same claim as one already held?
  *
  * `Learning.keyOf` answers by exact string match after case and punctuation
  * folding, and one synonym defeats it. The dictionary on E: holds "The Quick
  * Setup page contains fields for entering the PPP username and password." and
  * "...includes fields..." as two separate facts: the model was told the first
  * was already known, changed one word, and the store grew a third entry for
  * one observation of one page.
  *
  * The obvious repair - score the overlap and merge above a threshold - does
  * not work, and the store's own contents prove it rather than a thought
  * experiment. Measured on content words alone:
  *
  *     "The modem firmware is CZW008-4.16.013.4."   {firmware, is}
  *     "The modem firmware is CZW008-4.16.013.5."   {firmware, is}     1.00
  *
  *     "I have been in nature."                     {in, nature}
  *     "I have not been in nature."                 {in, nature}       1.00
  *
  * Two firmware builds, and a statement beside its own negation, both score a
  * perfect 1.00 - and both wordings come from entries this store already holds
  * (observed-firmware-czw008, observed-fact-plain). Nor is there a cutoff below
  * 1.00: one word differing out of nine scores 0.80 for contains/includes,
  * which is one fact, and 0.80 for password/hostname, which is two. The same
  * number, opposite answers.
  *
  * So nothing is scored. A claim is taken apart into three lists and each must
  * match EXACTLY, in claim order:
  *
  *     values    every token carrying a digit - a version, an address, a count.
  *               `Witness.supports` draws the same line for the same reason: a
  *               token with a digit in it is something the model claims to have
  *               READ, and there is no fraction of it that is acceptable.
  *     polarity  a closed list of words that flip a fact, taken out BEFORE
  *               stopwords, because `Naming.stopWords` contains "not".
  *     content   what is left, hedges dropped, a closed synonym table applied.
  *
  * Widening the first two lists is always safe. A word moved into `Polarity`
  * leaves both content lists if both claims have it - verdict unchanged - and
  * blocks on the new list if only one claim has it, where the content lists
  * already differed. The verdict can move from same to different and never the
  * other way, so those lists can be extended without review.
  *
  * `Synonyms` is the only thing here that can turn two different strings into
  * one claim, so it is closed, tiny, and argued for one word at a time.
  *
  * The answer carries its reason. A store that declines to file a second record
  * has to be able to say what it thought the two had in common.
  */
object Claim:

  /** Words that flip a fact rather than describe it.
    *
    * Compared as an ordered list, so "the WAN is up and the LAN is down" does
    * not match the same sentence with the two swapped. A word missing from here
    * costs a duplicate; a word wrongly here also costs a duplicate. Both errors
    * land on the safe side, which is why this can stay blunt.
    */
  private[sage] val Polarity: Set[String] = Set(
    "not", "no", "never", "none", "cannot", "cant", "without", "nothing",
    "isnt", "arent", "doesnt", "dont", "wont",
    "enabled", "disabled", "enable", "disable", "on", "off",
    "blocked", "allowed", "blocks", "allows", "block", "allow",
    "denied", "denies", "deny", "permitted", "rejected",
    "up", "down", "present", "absent", "missing", "empty", "unset",
    "required", "optional", "failed", "failure", "succeeded", "success",
    "open", "closed", "active", "inactive", "connected", "disconnected"
  )

  /** Words this store treats as one word.
    *
    * The only mechanism here that can make two different strings the same
    * claim, so every member has to be a word this device's facts genuinely do
    * not turn on. A page that CONTAINS a field and a page that INCLUDES it are
    * not two findings; that is the class the live duplicate needed.
    *
    * To add a class: name the pair of real claims it merges, check that no fact
    * this modem can produce distinguishes the two words, and add a check to
    * ClaimsSuite with it. Anything that could be a real distinction on this
    * device - primary/secondary, static/dynamic, wan/lan, allow/block - is not
    * a synonym, it is the fact. ClaimsSuite asserts that no word here is also a
    * polarity word, so a table that ever swallowed one fails the build.
    */
  private[sage] val Synonyms: Map[String, String] = Map(
    "contains"  -> "contains",
    "contain"   -> "contains",
    "containing"-> "contains",
    "includes"  -> "contains",
    "include"   -> "contains",
    "including" -> "contains",
    // "has" is a hedge in Naming.stopWords, dropped rather than compared, so
    // "the page has fields" and "the page contains fields" differed by a word
    // that was only present on one side. Mapping it before the hedge filter is
    // what puts them back on the same footing; a page that HAS a field and a
    // page that CONTAINS one are not two findings.
    "has"       -> "contains",
    "requires"  -> "requires",
    "require"   -> "requires",
    "needs"     -> "requires",
    "need"      -> "requires"
  )

  /** Hedges and grammar, dropped from the comparison.
    *
    * `Naming.stopWords` minus a carve-out, and the carve-out is the point. That
    * set was tuned for picking ONE distinguishing word out of a claim, where a
    * negation and a category noun are both noise. Here every word dropped is a
    * word that can no longer keep two claims apart, so removal is the unsafe
    * direction and this list is shortened rather than extended.
    *
    *     "not", "does"      negation has to survive twice - once as polarity,
    *                        once as an ordinary differing word.
    *     the category nouns "the firmware version is X" and "the firmware type
    *                        is X" are not obviously one sentence, and this gate
    *                        is not the place to decide that they are.
    */
  private[sage] val Ignored: Set[String] =
    Naming.stopWords -- Set(
      "not", "does", "set", "configured", "listed",
      "value", "field", "number", "version", "type", "state", "status")

  /** A claim taken apart. Every list is in claim order, because word order
    * carries which value belongs to which subject.
    */
  final case class Print(
      values: Vector[String],
      polarity: Vector[String],
      content: Vector[String])

  /** The answer and the reason for it. A bare boolean cannot explain itself,
    * and an unexplained refusal is indistinguishable from a bug.
    */
  final case class Verdict(same: Boolean, reason: String)

  /** A token's value, if it is one. A number word becomes the number it names,
    * so "advertises six pages" and "advertises 6 pages" state the same value -
    * which `keyOf` cannot see today.
    *
    * No length floor. `Witness.supports` uses one because it is scanning prose
    * for accidental digits; here a bare "6" is the entire difference between
    * six pages and eight, and a floor of four characters would let every port,
    * channel and page count through as though it were not a value at all.
    */
  private def valueOf(token: String): Option[String] =
    val n = Naming.numberWords.indexOf(token)
    if n >= 0 then Some(n.toString)
    else if token.exists(_.isDigit) then Some(token)
    else None

  def print(claim: String): Print =
    // Naming.words keeps . : _ - INSIDE a token, so 192.168.0.1 and
    // czw008-4.16.013.4 each survive as one value. keyOf destroys exactly those
    // boundaries - 19216801 - and the boundary is what this gate needs most.
    val toks     = Naming.words(claim).map(Naming.slug).filter(_.nonEmpty)
    val values   = toks.flatMap(valueOf).toVector
    val rest     = toks.filter(t => valueOf(t).isEmpty)
    val polarity = rest.filter(Polarity.contains).toVector
    val content  = rest
      .filterNot(Polarity.contains)
      .map(t => Synonyms.getOrElse(t, t))
      // Two characters, not Naming's four: "wan" and "lan" are the whole
      // difference between two facts, and a filter that eats them merges them.
      .filter(t => t.length >= 2 && !Ignored.contains(t))
      .toVector
    Print(values, polarity, content)

  /** Content-word overlap. Reported so a person can see how close a rejected
    * pair was; never consulted for the verdict. See the header for why.
    */
  def overlap(a: Print, b: Print): Double =
    val x = a.content.toSet
    val y = b.content.toSet
    if x.isEmpty && y.isEmpty then 0.0
    else (x intersect y).size.toDouble / (x union y).size

  private def list(v: Vector[String]): String =
    if v.isEmpty then "none" else v.mkString(" ")

  /** Are these two claims the same claim?
    *
    * Only ever asked about strings `keyOf` has already found to differ, so a
    * `same` here is always a rewording and never a repeat.
    */
  def sameFact(held: String, fresh: String): Verdict =
    val a = print(held)
    val b = print(fresh)
    if a.content.isEmpty || b.content.isEmpty then
      Verdict(false, "one of the claims has no content words left to compare")
    else if a.values != b.values then
      Verdict(false, s"different values: ${list(a.values)} against ${list(b.values)}")
    else if a.polarity != b.polarity then
      Verdict(false, s"different polarity: ${list(a.polarity)} against ${list(b.polarity)}")
    else if a.content != b.content then
      Verdict(false, f"different content words (${overlap(a, b)}%.2f overlap): " +
        s"${list(a.content)} against ${list(b.content)}")
    else
      Verdict(true, s"the same content words in the same order [${list(a.content)}], " +
        s"the same values [${list(a.values)}], the same polarity [${list(a.polarity)}]")
