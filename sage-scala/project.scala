// Build configuration for SAGE.
//
// There is no dependency list here, and there must never be one. This runs on a
// machine that may have no internet, and the whole design follows from that:
// the JDK gives us HTTP with TLS (java.net.http), cookies (java.net.CookieManager),
// regex with backreferences (java.util.regex), and a GUI (javax.swing). The one
// thing it does not give us is a JSON parser, so Json.scala is one - about 200
// lines, versus a Maven artifact and the promise that goes with it.
//
// Once scala3-library is in the Coursier cache, a build here needs no network.

//> using scala 3.8.4
//> using jvm 24

// -deprecation and -feature so the compiler says what it is unhappy about
// rather than staying quiet. -Wunused catches the leftover import that is
// occasionally the actual bug.
//> using options -deprecation -feature -unchecked -Wunused:all -Wvalue-discard

//> using mainClass sage.Main
