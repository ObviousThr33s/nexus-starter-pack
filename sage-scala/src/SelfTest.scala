package sage

import scala.collection.mutable
import scala.util.control.NonFatal

/** A test harness, in about eighty lines.
  *
  * There is a good argument for munit here. The argument against is the one
  * this whole folder is built on: `lint.py` is a hand-written linter "with no
  * packages to install", and `netguard.py` carries its own SELF_TEST table. A
  * test framework would be the only thing we ever fetched beyond the compiler,
  * and it would ship nowhere near enough value to justify being the exception.
  *
  * Tests live in the program rather than beside it, so `sage --self-test` works
  * on a deployed build - the same reason netguard.py did it that way.
  */
object Check:

  final class Suite(val name: String):
    private[Check] val failures = mutable.ArrayBuffer.empty[String]
    private[Check] var count = 0

    /** The workhorse. `label` should say what is being asserted, not what went
      * wrong - the failure message prints the values.
      */
    def apply(label: String)(cond: => Boolean): Unit =
      count += 1
      try if !cond then failures += label
      catch case NonFatal(e) => failures += s"$label - threw ${e.getClass.getSimpleName}: ${e.getMessage}"

    def eq[A](label: String, got: A, want: A): Unit =
      count += 1
      if got != want then failures += s"$label\n        got:  $got\n        want: $want"

    def contains(label: String, haystack: String, needle: String): Unit =
      count += 1
      if !haystack.contains(needle) then
        failures += s"$label\n        expected to contain: $needle\n        in: ${abbreviate(haystack, 200)}"

    def excludes(label: String, haystack: String, needle: String): Unit =
      count += 1
      if haystack.contains(needle) then
        failures += s"$label\n        expected NOT to contain: $needle\n        in: ${abbreviate(haystack, 200)}"

    /** Asserts the body throws. A test that silently passes because nothing
      * happened is worse than no test.
      */
    def throws(label: String)(body: => Any): Unit =
      count += 1
      try
        body
        failures += s"$label - expected an exception, none was thrown"
      catch case NonFatal(_) => ()

  private val suites = mutable.ArrayBuffer.empty[Suite]

  def suite(name: String)(body: Suite => Unit): Unit =
    val s = Suite(name)
    suites += s
    try body(s)
    catch case NonFatal(e) => s.failures += s"suite aborted - ${e.getClass.getSimpleName}: ${e.getMessage}"

  /** Runs every registered suite and prints a report. Returns a process exit
    * code, 0 for clean.
    */
  def report(out: String => Unit = println): Int =
    var checks = 0
    var failed = 0
    out("SAGE self-test")
    out("-" * 66)
    for s <- suites do
      checks += s.count
      failed += s.failures.size
      if s.failures.isEmpty then out(f"  ok    ${s.name}%-42s ${s.count}%3d checks")
      else
        out(f"  FAIL  ${s.name}%-42s ${s.failures.size} of ${s.count}")
        for f <- s.failures do out(s"        - $f")
    out("-" * 66)
    out(s"$checks checks, $failed failure(s)")
    if failed > 0 then 1 else 0

  private def abbreviate(s: String, n: Int): String =
    val flat = s.replace("\n", "\\n")
    if flat.length <= n then flat else flat.take(n) + "..."

/** Every suite in the program. Registering them here rather than by reflection
  * keeps the order stable and the list readable.
  */
object SelfTest:
  def run(out: String => Unit = println): Int =
    JsonSuite.register()
    NetguardSuite.register()
    LlmSuite.register()
    Check.report(out)

object JsonSuite:
  def register(): Unit = Check.suite("json - parse and write") { c =>
    import Json.*

    c.eq("parse an integer", parse("42").num, Some(42.0))
    c.eq("parse a negative float", parse("-1.5").num, Some(-1.5))
    c.eq("parse an exponent", parse("1e3").num, Some(1000.0))
    c.eq("parse a string", parse("\"hi\"").str, Some("hi"))
    c.eq("parse true", parse("true").bool, Some(true))
    c.eq("parse null", parse("null"), Json.Null)
    c.eq("parse an empty object", parse("{}"), Obj(Vector.empty))
    c.eq("parse an empty array", parse("[]"), Arr(Vector.empty))

    val nested = parse("""{"a":{"b":[1,2,{"c":"deep"}]}}""")
    c.eq("reach a nested field", nested.get("a", "b").map(_.arr.length), Some(3))
    c.eq("reach through an array element", nested.get("a", "b").map(_.arr(2)).flatMap(_("c")).flatMap(_.str), Some("deep"))

    // The wire quirk this program lives with: tool arguments arrive as a JSON
    // document encoded *inside* a string, so parsing has to happen twice.
    val doubled = parse("""{"arguments":"{\"path\":\"/x.html\"}"}""")
    val inner = doubled("arguments").flatMap(_.str).map(parse)
    c.eq("parse a JSON string containing JSON", inner.flatMap(_("path")).flatMap(_.str), Some("/x.html"))

    c.eq("unescape the standard escapes", parse(""""a\nb\tc\"d\\e\/f"""").str, Some("a\nb\tc\"d\\e/f"))
    c.eq("unescape \\u", parse(""""Aé"""").str, Some("Aé"))
    // A surrogate pair must survive: a UTF-16 string holds both halves, so
    // writing them in sequence is all that is needed.
    c.eq("unescape a surrogate pair", parse(""""😀"""").str, Some("😀"))

    c.eq("whitespace is ignored", parse(" { \"a\" : [ 1 , 2 ] } ").get("a").map(_.arr.length), Some(2))

    // Round trips
    val payload = obj(
      "model" -> Str("qwen2.5:7b-instruct"),
      "messages" -> arr(obj("role" -> Str("user"), "content" -> Str("hi \"there\"\n"))),
      "temperature" -> Num(0.7),
      "stream" -> Bool(false)
    )
    c.eq("round trip a request", parse(payload.render), payload)
    c.contains("whole numbers render without a decimal point", obj("n" -> Num(3)).render, "\"n\":3")
    c.excludes("whole numbers do not render as 3.0", obj("n" -> Num(3)).render, "3.0")
    c.contains("control characters are escaped", Str("ab").render, "\\u0001")
    c.contains("newlines are escaped", Str("a\nb").render, "\\n")
    // Field order must survive a round trip, or diffing two payloads is misery.
    c.eq("field order is preserved", parse("""{"z":1,"a":2}""").render, """{"z":1,"a":2}""")

    // Non-ASCII goes out as itself. Escaping it would triple the size of a page
    // of prose for no benefit on a UTF-8 transport.
    c.contains("non-ASCII is written literally", Str("café").render, "café")

    // Malformed input must fail loudly rather than returning something wrong.
    c.throws("unterminated string")(parse("\"abc"))
    c.throws("unterminated object")(parse("""{"a":1"""))
    c.throws("unterminated array")(parse("[1,2"))
    c.throws("trailing content")(parse("{} extra"))
    c.throws("bare word")(parse("nope"))
    c.throws("missing colon")(parse("""{"a" 1}"""))
    c.eq("tryParse reports rather than throws", tryParse("{oops").isLeft, true)
    c.eq("isValid on good input", isValid("""{"a":[1,2,3]}"""), true)

    // Accessors on the wrong shape return None instead of throwing - a server
    // that sends the wrong type should not crash the program.
    c.eq("str on a number is None", parse("1").str, None)
    c.eq("missing key is None", parse("{}")("nope"), None)
    c.eq("field lookup on a non-object is None", parse("[1]")("nope"), None)

    // objOpt drops absent fields, because the wire distinguishes absent from
    // null and several runtimes care which one they get.
    val sparse = objOpt("a" -> Some(Str("x")), "b" -> None)
    c.eq("objOpt omits None fields", sparse.render, """{"a":"x"}""")
  }
