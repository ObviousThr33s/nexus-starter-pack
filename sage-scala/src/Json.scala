package sage

import scala.annotation.tailrec
import scala.collection.mutable

/** A JSON value.
  *
  * The JDK ships an HTTP client, a cookie manager and a regex engine, but no
  * JSON parser - so here is one. It exists to keep the promise the rest of this
  * program is built on: nothing outside the standard library, so it runs on a
  * machine that cannot reach a package manager.
  *
  * An `enum` rather than a class hierarchy because that is what JSON is - a
  * closed set of six shapes. The compiler can then check a match is exhaustive,
  * which is the whole reason for writing this in Scala rather than Go: a new
  * case here becomes a compile error at every site that must handle it, not a
  * runtime surprise.
  *
  * `Obj` keeps a Vector of pairs rather than a Map so that writing a value back
  * out preserves field order. Round-tripping a request through this should not
  * scramble it, and a diff of two payloads should be readable.
  */
enum Json:
  case Null
  case Bool(value: Boolean)
  case Num(value: Double)
  case Str(value: String)
  case Arr(items: Vector[Json])
  case Obj(fields: Vector[(String, Json)])

  /** Field lookup. Returns None for a missing key *and* for a non-object, which
    * is what callers reading a server response actually want: malformed and
    * absent are the same problem at the point of use.
    */
  def apply(key: String): Option[Json] = this match
    case Obj(fields) => fields.collectFirst { case (k, v) if k == key => v }
    case _           => None

  /** Reach through nested objects: `json / "choices" / "0"` is not supported,
    * but `json.get("error", "message")` is - the common shape in an API error.
    */
  def get(path: String*): Option[Json] =
    path.foldLeft(Option(this)) { (cur, key) => cur.flatMap(_.apply(key)) }

  def str: Option[String] = this match
    case Str(v) => Some(v)
    case _      => None

  def num: Option[Double] = this match
    case Num(v) => Some(v)
    case _      => None

  def int: Option[Int] = num.map(_.toInt)

  def bool: Option[Boolean] = this match
    case Bool(v) => Some(v)
    case _       => None

  def arr: Vector[Json] = this match
    case Arr(items) => items
    case _          => Vector.empty

  def isNull: Boolean = this == Json.Null

  /** The string value, or "" - for the many places where a missing field and an
    * empty one mean the same thing (a tool call with no content, say).
    */
  def strOrEmpty: String = str.getOrElse("")

  def render: String = Json.write(this)

  override def toString: String = render

object Json:

  // ------------------------------------------------------------------
  // construction
  // ------------------------------------------------------------------

  def obj(fields: (String, Json)*): Json = Obj(fields.toVector)
  def arr(items: Json*): Json            = Arr(items.toVector)

  given Conversion[String, Json]  = Str(_)
  given Conversion[Int, Json]     = i => Num(i.toDouble)
  given Conversion[Double, Json]  = Num(_)
  given Conversion[Boolean, Json] = Bool(_)

  /** Drops fields whose value is None, so optional parts of a request can be
    * written inline without building the object conditionally. The wire format
    * distinguishes "absent" from "null", and several runtimes care.
    */
  def objOpt(fields: (String, Option[Json])*): Json =
    Obj(fields.collect { case (k, Some(v)) => (k, v) }.toVector)

  // ------------------------------------------------------------------
  // writing
  // ------------------------------------------------------------------

  def write(value: Json): String =
    val sb = StringBuilder()
    writeTo(sb, value)
    sb.toString

  private def writeTo(sb: StringBuilder, value: Json): Unit = value match
    case Null    => sb ++= "null"
    case Bool(b) => sb ++= (if b then "true" else "false")
    case Num(d) =>
      // Whole numbers must not go out as "3.0": a model id or a token count
      // rendered that way is wrong, and some servers reject it outright.
      if d.isWhole && d.abs < 1e15 then sb ++= d.toLong.toString
      else sb ++= d.toString
    case Str(s) => writeString(sb, s)
    case Arr(items) =>
      sb += '['
      var first = true
      items.foreach { item =>
        if !first then sb += ','
        first = false
        writeTo(sb, item)
      }
      sb += ']'
    case Obj(fields) =>
      sb += '{'
      var first = true
      fields.foreach { case (k, v) =>
        if !first then sb += ','
        first = false
        writeString(sb, k)
        sb += ':'
        writeTo(sb, v)
      }
      sb += '}'

  private def writeString(sb: StringBuilder, s: String): Unit =
    sb += '"'
    var i = 0
    while i < s.length do
      val c = s.charAt(i)
      c match
        case '"'  => sb ++= "\\\""
        case '\\' => sb ++= "\\\\"
        case '\n' => sb ++= "\\n"
        case '\r' => sb ++= "\\r"
        case '\t' => sb ++= "\\t"
        case '\b' => sb ++= "\\b"
        case '\f' => sb ++= "\\f"
        case _ =>
          // Control characters must be escaped or the document is invalid.
          // Everything else goes out as-is: the transport is UTF-8, so there is
          // no reason to \u-escape text and every reason not to (it triples the
          // size of a page of prose).
          if c < 0x20 then sb ++= f"\\u${c.toInt}%04x"
          else sb += c
      i += 1
    sb += '"'

  // ------------------------------------------------------------------
  // parsing
  // ------------------------------------------------------------------

  final class ParseError(message: String, val offset: Int)
      extends RuntimeException(s"$message (at offset $offset)")

  /** Parses one JSON document. Throws ParseError on malformed input; use
    * [[tryParse]] where a failure is expected rather than exceptional.
    */
  def parse(input: String): Json =
    val p = Parser(input)
    p.skipWhitespace()
    val value = p.parseValue()
    p.skipWhitespace()
    if !p.atEnd then p.fail("trailing content after the JSON value")
    value

  def tryParse(input: String): Either[String, Json] =
    try Right(parse(input))
    catch case e: ParseError => Left(e.getMessage)

  def isValid(input: String): Boolean = tryParse(input).isRight

  private final class Parser(val s: String):
    private var i = 0

    def atEnd: Boolean = i >= s.length

    def fail(message: String): Nothing = throw ParseError(message, i)

    @tailrec
    def skipWhitespace(): Unit =
      if i < s.length then
        val c = s.charAt(i)
        if c == ' ' || c == '\t' || c == '\n' || c == '\r' then
          i += 1
          skipWhitespace()

    private def expect(c: Char): Unit =
      if atEnd || s.charAt(i) != c then fail(s"expected '$c'")
      i += 1

    def parseValue(): Json =
      if atEnd then fail("unexpected end of input")
      s.charAt(i) match
        case '{'                            => parseObject()
        case '['                            => parseArray()
        case '"'                            => Str(parseString())
        case 't'                            => parseLiteral("true", Bool(true))
        case 'f'                            => parseLiteral("false", Bool(false))
        case 'n'                            => parseLiteral("null", Null)
        case c if c == '-' || c.isDigit     => parseNumber()
        case c                              => fail(s"unexpected character '$c'")

    private def parseLiteral(word: String, value: Json): Json =
      if !s.startsWith(word, i) then fail(s"expected $word")
      i += word.length
      value

    private def parseObject(): Json =
      expect('{')
      val fields = mutable.ArrayBuffer.empty[(String, Json)]
      skipWhitespace()
      if !atEnd && s.charAt(i) == '}' then
        i += 1
        return Obj(fields.toVector)

      var done = false
      while !done do
        skipWhitespace()
        val key = parseString()
        skipWhitespace()
        expect(':')
        skipWhitespace()
        fields += (key -> parseValue())
        skipWhitespace()
        if atEnd then fail("unterminated object")
        s.charAt(i) match
          case ',' => i += 1
          case '}' => i += 1; done = true
          case c   => fail(s"expected ',' or '}' in object, found '$c'")
      Obj(fields.toVector)

    private def parseArray(): Json =
      expect('[')
      val items = mutable.ArrayBuffer.empty[Json]
      skipWhitespace()
      if !atEnd && s.charAt(i) == ']' then
        i += 1
        return Arr(items.toVector)

      var done = false
      while !done do
        skipWhitespace()
        items += parseValue()
        skipWhitespace()
        if atEnd then fail("unterminated array")
        s.charAt(i) match
          case ',' => i += 1
          case ']' => i += 1; done = true
          case c   => fail(s"expected ',' or ']' in array, found '$c'")
      Arr(items.toVector)

    private def parseString(): String =
      expect('"')
      val sb = StringBuilder()
      var done = false
      while !done do
        if atEnd then fail("unterminated string")
        val c = s.charAt(i)
        i += 1
        c match
          case '"'  => done = true
          case '\\' => sb += parseEscape()
          case _    => sb += c
      sb.toString

    private def parseEscape(): Char =
      if atEnd then fail("unterminated escape")
      val c = s.charAt(i)
      i += 1
      c match
        case '"'  => '"'
        case '\\' => '\\'
        case '/'  => '/'
        case 'b'  => '\b'
        case 'f'  => '\f'
        case 'n'  => '\n'
        case 'r'  => '\r'
        case 't'  => '\t'
        case 'u' =>
          if i + 4 > s.length then fail("truncated \\u escape")
          val hex = s.substring(i, i + 4)
          i += 4
          // Surrogate pairs need no special handling: a Java String is UTF-16,
          // so writing both halves in sequence produces the right code point.
          try Integer.parseInt(hex, 16).toChar
          catch case _: NumberFormatException => fail(s"bad \\u escape '$hex'")
        case other => fail(s"unknown escape '\\$other'")

    private def parseNumber(): Json =
      val start = i
      if !atEnd && s.charAt(i) == '-' then i += 1
      while !atEnd && s.charAt(i).isDigit do i += 1
      if !atEnd && s.charAt(i) == '.' then
        i += 1
        while !atEnd && s.charAt(i).isDigit do i += 1
      if !atEnd && (s.charAt(i) == 'e' || s.charAt(i) == 'E') then
        i += 1
        if !atEnd && (s.charAt(i) == '+' || s.charAt(i) == '-') then i += 1
        while !atEnd && s.charAt(i).isDigit do i += 1
      val text = s.substring(start, i)
      text.toDoubleOption match
        case Some(d) => Num(d)
        case None    => fail(s"malformed number '$text'")
