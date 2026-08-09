package sage

/** The duplicate, as a test.
  *
  * Every claim below is either verbatim from the dictionary on E: or a fact
  * this modem can actually produce. A dedupe rule exercised only on invented
  * examples is a dedupe rule that has not been exercised.
  *
  * Nothing here touches disk. `Learnings.home` is a private lazy val read from
  * the environment with no seam, and build.cmd runs --self-test on every build,
  * so a check that went through `record` would write junk into the operator's
  * real ledger every time the program was rebuilt. Keeping the decision a pure
  * function is what makes it testable at this depth at all.
  */
object ClaimsSuite:

  private val Held     = "The Quick Setup page contains fields for entering the PPP username and password."
  private val Reworded = "The Quick Setup page includes fields for entering the PPP username and password."
  private val Related  = "The modem requires entering PPP username and password for quick setup."

  def register(): Unit = Check.suite("claims - one fact, however worded") { c =>

    def same(label: String, a: String, b: String): Unit =
      c(label)(Claim.sameFact(a, b).same)

    def differ(label: String, a: String, b: String): Unit =
      c(label)(!Claim.sameFact(a, b).same)

    // ---- the duplicate that is on disk ---------------------------------
    same("one synonym is not a second fact", Held, Reworded)
    same("and it reads the same way round", Reworded, Held)
    c.contains("it says what it matched on",
      Claim.sameFact(Held, Reworded).reason, "the same content words")

    // The same duplicate reached by the other word this modem's pages invite.
    // It leaked once: "has" is a hedge in Naming.stopWords and was dropped
    // rather than compared, so it differed from "contains" by a word only one
    // side had.
    same("a page that has a field is a page that contains one", Held,
      "The Quick Setup page has fields for entering the PPP username and password.")
    // And the same sentence negated is a separate finding, not a re-confirmation
    // of it. This is the pair a similarity score cannot separate at any cutoff.
    differ("the negation of the held claim is a different finding", Held,
      "The Quick Setup page does not contain fields for entering the PPP username and password.")

    // A requirement and a page-structure observation are two findings. A rule
    // loose enough to fold these is a rule loose enough to fold blocks into
    // allows, so this pair stays two entries and the command says why.
    differ("a requirement is not a page listing", Held, Related)
    c.contains("and it says what it matched on instead",
      Claim.sameFact(Held, Related).reason, "overlap")

    // ---- pairs that must never fold ------------------------------------
    differ("two firmware builds",
      "The modem firmware is CZW008-4.16.013.4.",
      "The modem firmware is CZW008-4.16.013.5.")
    differ("two page paths",
      "The connection status page is at /modemstatus_connectionstatus.html.",
      "The connection status page is at /modemstatus_connectionsummary.html.")
    differ("a block is not an allow",
      "The firewall blocks 8.8.8.8 while offline.",
      "The firewall allows 8.8.8.8 while offline.")
    differ("a fact is not its negation",
      "I have been in nature.",
      "I have not been in nature.")
    differ("enabled is not disabled",
      "IPv6 is enabled on the WAN interface.",
      "IPv6 is disabled on the WAN interface.")
    differ("a page that shows a field is not a page that hides it",
      "The Quick Setup page shows the PPP password field.",
      "The Quick Setup page does not show the PPP password field.")
    differ("primary is not secondary",
      "The primary DNS server is 8.8.8.8.",
      "The secondary DNS server is 8.8.8.8.")
    differ("WAN is not LAN",
      "The WAN interface is up.",
      "The LAN interface is up.")
    // A one-character count, which Witness.supports would not see as a value at
    // all: its four-character floor is right for scanning prose and fatal here.
    differ("six pages is not eight pages",
      "This modem advertises 6 pages.",
      "This modem advertises 8 pages.")
    differ("one channel is not another",
      "The wireless channel is 6.",
      "The wireless channel is 11.")
    // Scores exactly what contains/includes scores before the synonym table is
    // applied. Same number, opposite answer - which is why nothing is a score.
    differ("one differing word is not automatically a synonym",
      Held,
      "The Quick Setup page contains fields for entering the PPP username and hostname.")

    // ---- the gate that did the work, named -----------------------------
    c.contains("a version mismatch is reported as a value mismatch",
      Claim.sameFact("The modem firmware is CZW008-4.16.013.4.",
                     "The modem firmware is CZW008-4.16.013.5.").reason, "different values")
    c.contains("a negation is reported as a polarity mismatch",
      Claim.sameFact("I have been in nature.",
                     "I have not been in nature.").reason, "different polarity")

    // ---- what this can do that keyOf cannot ----------------------------
    same("a number and the word for it are one value",
      "This modem advertises 6 pages.", "This modem advertises six pages.")
    same("case and punctuation still fold",
      Held, "the quick setup page contains fields for entering the ppp username and password")

    // ---- values must never survive as content words --------------------
    c.eq("an address stays one token",
      Claim.print("The gateway is 192.168.0.1.").values, Vector("192-168-0-1"))
    c.eq("a firmware string stays one token",
      Claim.print("Firmware CZW008-4.16.013.4.").values, Vector("czw008-4-16-013-4"))
    c.eq("three-letter subjects survive the filter",
      Claim.print("The WAN uses PPP.").content, Vector("wan", "ppp"))

    // ---- invariants on the two hand-written tables ---------------------
    //
    // The synonym table is the only thing in the store that can turn two
    // strings into one claim, so the two ways of extending it wrongly are
    // asserted rather than left to review. A polarity word appearing as a
    // synonym would make up and down one word; a negation dropped as a hedge
    // would make a fact and its opposite one claim.
    c("no polarity word is also a synonym")(
      (Claim.Synonyms.keySet ++ Claim.Synonyms.values).intersect(Claim.Polarity).isEmpty)
    c("no synonym is dropped as a hedge")(
      Claim.Synonyms.values.forall(v => !Claim.Ignored.contains(v)))
    c("negation is never treated as a hedge")(
      !Claim.Ignored.contains("not") && Claim.Polarity.contains("not"))
    c("the hedge list only ever shrinks from Naming's")(
      Claim.Ignored.subsetOf(Naming.stopWords))
  }
