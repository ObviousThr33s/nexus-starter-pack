package sage

import java.nio.file.{Files, Path, Paths, StandardOpenOption}
import java.security.MessageDigest
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.ZoneId
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** One observation, exactly as it was made. Immutable, and the unit of storage.
  *
  * This is the blob. It says what was claimed, what was seen, where, and when -
  * and once written it is never edited, because editing it would be editing the
  * past. Re-confirming a fact writes a NEW observation rather than touching the
  * old one, so the record of a claim is the list of times it was actually
  * looked at.
  */
final case class Observation(claim: String, evidence: String, source: String, at: String):
  def toJson: Json = Json.obj(
    "claim"    -> Json.Str(claim),
    "evidence" -> Json.Str(evidence),
    "source"   -> Json.Str(source),
    "at"       -> Json.Str(at)
  )

object Observation:
  def fromJson(j: Json): Option[Observation] =
    for claim <- j("claim").flatMap(_.str) if claim.nonEmpty
    yield Observation(
      claim,
      j("evidence").flatMap(_.str).getOrElse(""),
      j("source").flatMap(_.str).getOrElse(""),
      j("at").flatMap(_.str).getOrElse("")
    )

/** A dictionary entry: a name, and the observations standing behind it.
  *
  * The split matters. The blob is what happened and cannot change; this is the
  * bookkeeping about it and changes every time the claim is re-examined. Fusing
  * the two would mean a re-check rewrote the evidence, which is exactly the
  * thing that makes a record untrustworthy.
  */
final case class Learning(
    name: String,
    claim: String,
    evidence: String, // from the most recent observation, for display
    source: String,
    firstSeen: String,
    lastChecked: String,
    checks: Int,
    blobs: Vector[String]
):
  /** Dedupe key. Two claims differing only in case or punctuation are the same
    * claim, and a store that disagrees fills with near-duplicates.
    */
  def key: String = Learning.keyOf(claim)

  def entryJson: Json = Json.obj(
    "claim"       -> Json.Str(claim),
    "firstSeen"   -> Json.Str(firstSeen),
    "lastChecked" -> Json.Str(lastChecked),
    "checks"      -> Json.Num(checks),
    "blobs"       -> Json.Arr(blobs.map(Json.Str.apply))
  )

object Learning:
  val MaxClaim    = 200
  val MaxEvidence = 600

  def keyOf(claim: String): String =
    claim.toLowerCase.replaceAll("[^a-z0-9 ]", "").replaceAll("\\s+", " ").trim

/** The learning store: content-addressed blobs plus a named dictionary.
  *
  *     sage-feed\learnings\blobs\a1\a126650c…    one observation
  *     sage-feed\learnings\dictionary.json       name -> claim, checks, blobs
  *
  * The blob layout is cistern's, deliberately: sha256, sharded on the first two
  * hex characters. That is the pattern this drive already uses, and matching it
  * means anything that can read one store can read the other. It is a separate
  * store rather than an addition to cistern's, because cistern.db owns that one
  * and rule 1 is not to make a mess in a folder that belongs to something else.
  *
  * Content addressing earns its place here rather than being decoration: the
  * same observation made twice is the same blob, so a store of thirty re-checks
  * of one fact costs one blob, and two claims that quote the same page share
  * it. It also means a blob cannot be quietly altered - the name IS the hash of
  * what it says.
  */
object Learnings:

  private val stamp =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

  private def now: String = stamp.format(Instant.now())

  /** Where the store lives.
    *
    * Learnings are HELIOS's record, not this folder's, so they sit on E: beside
    * the run feed the Python already writes there. Resolved exactly as
    * `helios_feed._pick_home()` does it, including the part that matters most:
    * E: is never a hard dependency. If the drive is missing, locked, or
    * read-only, the run continues on %LOCALAPPDATA% rather than failing.
    *
    * Nested under `wall\ledger` per E:\Helios\README.md rule 1 - nothing loose
    * at the top of Helios.
    */
  private val PreferredHome = Paths.get("E:\\Helios\\wall\\ledger\\sage-feed")

  private def fallbackHome: Path =
    Paths.get(sys.env.getOrElse("LOCALAPPDATA", sys.props("user.home"))).resolve("HELIOS")

  private def usable(home: Path): Boolean =
    try
      Files.createDirectories(home)
      val probe = home.resolve(".write-probe")
      Files.writeString(probe, "")
      Files.deleteIfExists(probe)
      true
    catch case NonFatal(_) => false

  private lazy val home: Path =
    sys.env.get("HELIOS_HOME").map(Paths.get(_)) match
      case Some(p) if usable(p)       => p
      case _ if usable(PreferredHome) => PreferredHome
      case _ =>
        val fb = fallbackHome
        usable(fb): Unit
        fb

  private def root: Path       = home.resolve("learnings")
  private def blobDir: Path    = root.resolve("blobs")
  def dictionaryPath: Path     = root.resolve("dictionary.json")
  private def legacyPath: Path = home.resolve("learnings.jsonl")

  def where: String =
    if home == PreferredHome then "E: (HELIOS drive)"
    else if home == fallbackHome then "LOCALAPPDATA fallback - E: unavailable"
    else "HELIOS_HOME override"

  // ------------------------------------------------------------------
  // blobs
  // ------------------------------------------------------------------

  private def sha256(text: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(text.getBytes("UTF-8"))
      .map(b => f"${b & 0xff}%02x")
      .mkString

  private def blobPath(hash: String): Path =
    blobDir.resolve(hash.take(2)).resolve(hash)

  /** Writes an observation and returns its hash. Writing the same observation
    * twice is a no-op, which is the whole point of addressing by content.
    */
  private def putBlob(obs: Observation): String =
    val body = obs.toJson.render
    val hash = sha256(body)
    val path = blobPath(hash)
    try
      if !Files.exists(path) then
        Files.createDirectories(path.getParent)
        Files.writeString(path, body, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING): Unit
    catch case NonFatal(_) => () // the store must never kill a run
    hash

  def getBlob(hash: String): Option[Observation] =
    try
      if Files.exists(blobPath(hash)) then
        Json.tryParse(Files.readString(blobPath(hash))).toOption.flatMap(Observation.fromJson)
      else None
    catch case NonFatal(_) => None

  def blobCount: Int =
    try
      if Files.exists(blobDir) then
        Files.walk(blobDir).iterator.asScala.count(Files.isRegularFile(_))
      else 0
    catch case NonFatal(_) => 0

  // ------------------------------------------------------------------
  // the dictionary
  // ------------------------------------------------------------------

  private val byName = mutable.LinkedHashMap.empty[String, Learning]
  private var loaded = false

  def all: Vector[Learning] =
    if !loaded then load()
    byName.values.toVector

  def byKey(claim: String): Option[Learning] =
    val k = Learning.keyOf(claim)
    all.find(_.key == k)

  /** Looks a learning up by dictionary name, which is the point of having
    * names: `forget observed-protocol-pppoe` rather than by hash or by
    * retyping the whole claim.
    */
  def byNameExact(name: String): Option[Learning] =
    if !loaded then load()
    byName.get(name.trim.toLowerCase)

  def load(): Unit =
    byName.clear()
    loaded = true
    migrateLegacy()
    try
      if Files.exists(dictionaryPath) then
        Json.tryParse(Files.readString(dictionaryPath)).toOption.foreach {
          case Json.Obj(fields) =>
            for (name, entry) <- fields do
              val blobs = entry("blobs").map(_.arr).getOrElse(Vector.empty).flatMap(_.str)
              // Evidence is not stored in the dictionary - it lives in the
              // blob, so display reads the newest one back.
              val newest = blobs.lastOption.flatMap(getBlob)
              for claim <- entry("claim").flatMap(_.str) do
                byName(name) = Learning(
                  name = name,
                  claim = claim,
                  evidence = newest.map(_.evidence).getOrElse(""),
                  source = newest.map(_.source).getOrElse(""),
                  firstSeen = entry("firstSeen").flatMap(_.str).getOrElse(""),
                  lastChecked = entry("lastChecked").flatMap(_.str).getOrElse(""),
                  checks = entry("checks").flatMap(_.int).getOrElse(1),
                  blobs = blobs
                )
          case _ => ()
        }
    catch case NonFatal(_) => ()

  /** Converts a flat learnings.jsonl from the earlier design into blobs and a
    * dictionary, once. The old file is kept, renamed - believe the disk, and
    * never delete a record while converting it.
    */
  private def migrateLegacy(): Unit =
    try
      if Files.exists(legacyPath) && !Files.exists(dictionaryPath) then
        val taken = mutable.Set.empty[String]
        val out   = mutable.LinkedHashMap.empty[String, Learning]
        for
          line <- Files.readAllLines(legacyPath).asScala.map(_.trim).filter(_.nonEmpty)
          j    <- Json.tryParse(line).toOption
          claim <- j("claim").flatMap(_.str)
        do
          val evidence = j("evidence").flatMap(_.str).getOrElse("")
          val source   = j("source").flatMap(_.str).getOrElse("")
          val at       = j("firstSeen").flatMap(_.str).getOrElse(now)
          val hash     = putBlob(Observation(claim, evidence, source, at))
          val name     = Naming.nameFor(claim, evidence, taken.toSet)
          taken += name
          out(name) = Learning(name, claim, evidence, source, at,
            j("lastChecked").flatMap(_.str).getOrElse(at),
            j("checks").flatMap(_.int).getOrElse(1), Vector(hash))
        byName ++= out
        writeDictionary()
        Files.move(legacyPath, legacyPath.resolveSibling("learnings.jsonl.migrated")): Unit
    catch case NonFatal(_) => ()

  private def writeDictionary(): Unit =
    try
      Files.createDirectories(root)
      val fields = byName.values.toVector.map(l => l.name -> l.entryJson)
      Files.writeString(dictionaryPath, Json.Obj(fields).render,
        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING): Unit
    catch case NonFatal(_) => ()

  // ------------------------------------------------------------------
  // recording
  // ------------------------------------------------------------------

  /** Records a claim, or re-confirms one already held.
    *
    * Re-confirming appends an observation rather than replacing one, so
    * `checks` counts real lookups and `blobs` is the trail of them. A learning
    * seen five times across five sessions is a different kind of fact from one
    * seen once, and the store should be able to say which.
    */
  def record(claimRaw: String, evidenceRaw: String, source: String): Either[String, Learning] =
    val claim = claimRaw.trim.replaceAll("\\s+", " ")
    if claim.isEmpty then Left("a learning needs a claim")
    else if claim.length > Learning.MaxClaim then
      Left(s"that claim is ${claim.length} characters. Learnings are small - one fact, " +
        s"under ${Learning.MaxClaim} characters. Split it or cut it down.")
    else
      if !loaded then load()
      val evidence = evidenceRaw.trim.take(Learning.MaxEvidence)
      val hash     = putBlob(Observation(claim, evidence, source, now))

      val entry = byName.values.find(_.key == Learning.keyOf(claim)) match
        case Some(existing) =>
          existing.copy(
            evidence = if evidence.nonEmpty then evidence else existing.evidence,
            lastChecked = now,
            checks = existing.checks + 1,
            // distinct: re-observing something identical is the same blob, and
            // listing it twice would overstate the trail.
            blobs = (existing.blobs :+ hash).distinct
          )
        case None =>
          val name = Naming.nameFor(claim, evidence, byName.keySet.toSet)
          Learning(name, claim, evidence, source, now, now, 1, Vector(hash))

      byName(entry.name) = entry
      writeDictionary()
      Right(entry)

  /** Forgets by dictionary name or by claim.
    *
    * The blob is left on disk. It records something that genuinely was observed
    * at a moment, and deleting it would be pretending the observation never
    * happened - what is being withdrawn is the belief, not the sighting.
    */
  def forget(nameOrClaim: String): Boolean =
    if !loaded then load()
    val needle = nameOrClaim.trim
    val target = byName.get(needle.toLowerCase)
      .orElse(byName.values.find(_.key == Learning.keyOf(needle)))
    target match
      case Some(l) =>
        byName.remove(l.name)
        writeDictionary()
        true
      case None => false

  def clear(): Unit =
    if !loaded then load()
    byName.clear()
    writeDictionary()

  // ------------------------------------------------------------------
  // context
  // ------------------------------------------------------------------

  /** What the model is told it already knows.
    *
    * The name leads, because a name is how the model refers back to one - to
    * re-confirm it or to retract it - and a claim retyped from memory is a
    * claim retyped wrong. The evidence stays out: it is for a human deciding
    * whether to keep a learning, and sending it would spend context
    * re-justifying facts instead of using them.
    */
  def contextBlock: String =
    val held = all
    if held.isEmpty then ""
    else
      val lines = held.toList.sortBy(-_.checks).map { l =>
        val seen = if l.checks > 1 then s" [confirmed ${l.checks}x]" else ""
        s"- ${l.name}: ${l.claim}$seen"
      }
      s"""
         |What you have previously found out about this network, by name:
         |${lines.mkString("\n")}
         |
         |These are prior findings, not guarantees - the device can change. If one is
         |relevant, check it against a tool before relying on it, then save_learning to
         |re-confirm it, or forget_learning by name and save the corrected fact.""".stripMargin

  /** The tool that lets the model record what it worked out.
    *
    * Evidence is required AND verified against what the tools actually returned
    * this session. Asking alone does not work: a model that is confabulating
    * confabulates the evidence too, and a store of unevidenced claims becomes a
    * hallucination cache that is re-injected every session.
    */
  def registerTool(registry: Registry, witness: Witness, source: => String): Unit =
    registry.register(
      "save_learning",
      "Record one small, durable fact you have just established about this network, " +
        "so it is known in future sessions. One fact per call, under 200 characters. " +
        "Only for something you verified with a tool in THIS conversation, quoting the " +
        "observation that shows it.",
      Json.obj(
        "type" -> Json.Str("object"),
        "properties" -> Json.obj(
          "claim" -> Json.obj(
            "type" -> Json.Str("string"),
            "description" -> Json.Str(
              "The fact, in one sentence. e.g. 'The modem firmware is CZW008-4.16.013.4'")
          ),
          "evidence" -> Json.obj(
            "type" -> Json.Str("string"),
            "description" -> Json.Str(
              "The tool output that shows it - quote the relevant line, do not paraphrase")
          )
        ),
        "required" -> Json.arr(Json.Str("claim"), Json.Str("evidence"))
      )
    ) { args =>
      val claim    = args("claim").flatMap(_.str).getOrElse("")
      val evidence = args("evidence").flatMap(_.str).getOrElse("")
      if evidence.trim.isEmpty then
        "error: a learning needs the evidence for it. Quote the tool output that " +
          "shows this is true, or do not save it."
      else if witness.isEmpty then
        "error: nothing has been looked up in this conversation yet, so there is " +
          "nothing this could be evidence of. Use a tool first."
      else if !witness.supports(evidence) then
        "error: that evidence does not match anything the tools actually returned " +
          "in this conversation. Do not save what you inferred, worked out from the " +
          "model name, or already believed - only what you SAW. Quote the exact line " +
          "from the tool output, or look it up first and then save it."
      else
        record(claim, evidence, source) match
          case Left(why) => s"error: $why"
          case Right(l) =>
            if l.checks > 1 then s"re-confirmed '${l.name}' (seen ${l.checks} times): ${l.claim}"
            else s"saved as '${l.name}': ${l.claim}"
    }
