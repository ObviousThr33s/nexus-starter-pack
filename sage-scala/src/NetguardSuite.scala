package sage

object NetguardSuite:

  def register(): Unit =
    Check.suite("netguard - the original SELF_TEST table") { c =>
      Netguard.withSettings("SAGE_OFFLINE" -> "1", "SAGE_ALLOW" -> "") {
        // Carried over verbatim from netguard.py. The classifier is the whole
        // product here; these are the cases it must never get wrong, and they
        // are the contract with the Python this replaces.
        val cases = List(
          "http://127.0.0.1:11434/v1"   -> true,
          "http://localhost:8080"       -> true,
          "http://192.168.0.1/login.html" -> true,
          "http://10.0.0.5"             -> true,
          "http://172.16.4.4"           -> true,
          "http://[::1]:1234/v1"        -> true,
          "https://8.8.8.8"             -> false,
          "https://api.openai.com/v1"   -> false,
          "http://172.32.0.1"           -> false, // just outside RFC1918
          "ftp://192.168.0.1"           -> false,
          "http://224.0.0.1"            -> false
        )
        for (target, want) <- cases do
          val v = Netguard.check(target)
          c.eq(s"check($target)", v.allowed, want)
      }
    }

    Check.suite("netguard - bypasses the Python allowed") { c =>
      Netguard.withSettings("SAGE_OFFLINE" -> "1", "SAGE_ALLOW" -> "") {
        // Every one of these was confirmed ALLOW against the live netguard.py
        // during planning. Each is a route off the LAN that the offline promise
        // says cannot exist.
        val bypasses = List(
          "http://[2002:0808:0808::1]" -> "6to4 encoding 8.8.8.8",
          "http://[2001::1]"           -> "Teredo tunnel",
          "http://169.254.169.254"     -> "cloud metadata service",
          "http://[fd00:ec2::254]"     -> "cloud metadata service (IPv6)",
          "http://240.0.0.1"           -> "reserved 240/4",
          "http://198.18.0.1"          -> "benchmark range",
          "http://203.0.113.9"         -> "TEST-NET-3",
          "http://[64:ff9b::808:808]"  -> "NAT64 to 8.8.8.8",
          "http://0.0.0.0"             -> "unspecified",
          "http://[::ffff:8.8.8.8]"    -> "IPv4-mapped public address"
        )
        for (target, why) <- bypasses do
          val v = Netguard.check(target)
          c(s"$target must be blocked - $why (got: ${v.reason})")(!v.allowed)
      }
    }

    Check.suite("netguard - link-local is still permitted") { c =>
      Netguard.withSettings("SAGE_OFFLINE" -> "1", "SAGE_ALLOW" -> "") {
        // Guards against over-correcting: the metadata deny is one address, not
        // the whole link-local range.
        c("169.254.1.1 is a normal link-local address")(Netguard.check("http://169.254.1.1").allowed)
        c("fe80::1 is a normal link-local address")(Netguard.check("http://[fe80::1]").allowed)
      }
    }

    Check.suite("netguard - the offline switch") { c =>
      Netguard.withSettings("SAGE_OFFLINE" -> "0", "SAGE_ALLOW" -> "") {
        c("with the switch off, a public address is allowed")(Netguard.check("https://8.8.8.8").allowed)
        // The scheme rule is not an offline rule; it holds either way.
        c("ftp is refused even with the switch off")(!Netguard.check("ftp://192.168.0.1").allowed)
      }
      Netguard.withSettings("SAGE_OFFLINE" -> "1", "SAGE_ALLOW" -> "") {
        c("with the switch on, a public address is refused")(!Netguard.check("https://8.8.8.8").allowed)
      }
    }

    Check.suite("netguard - SAGE_ALLOW") { c =>
      Netguard.withSettings("SAGE_OFFLINE" -> "1", "SAGE_ALLOW" -> "8.8.8.8,*.example.com") {
        c("an exact entry is honoured")(Netguard.check("https://8.8.8.8").allowed)
        c("a wildcard entry is honoured")(Netguard.check("https://api.example.com").allowed)
        c("a deeper subdomain still matches the wildcard")(
          Netguard.check("https://a.b.example.com").allowed
        )
        c("the allowlist does not leak to an unlisted host")(!Netguard.check("https://1.1.1.1").allowed)
        // A dot in the pattern is a literal, not a regex metacharacter. Without
        // quoting, "*.example.com" would also match "xxexample.com".
        c("dots in a pattern are literal")(!Netguard.check("https://wwwXexample.com").allowed)
      }
    }

    Check.suite("netguard - target parsing") { c =>
      val cases = List(
        "http://192.168.0.1/login.html" -> ("http", "192.168.0.1"),
        "https://api.openai.com/v1"     -> ("https", "api.openai.com"),
        "localhost:11434"               -> ("http", "localhost"),
        "http://[::1]:1234/v1"          -> ("http", "::1"),
        "http://[fe80::1]"              -> ("http", "fe80::1"),
        "192.168.0.1"                   -> ("http", "192.168.0.1"),
        "ftp://192.168.0.1"             -> ("ftp", "192.168.0.1"),
        "http://host/path?q=1"          -> ("http", "host")
      )
      for (in, want) <- cases do c.eq(s"splitTarget($in)", Netguard.splitTarget(in), want)
    }

    Check.suite("netguard - CIDR containment") { c =>
      val ten = Netguard.Cidr("10.0.0.0/8")
      c("10.0.0.5 is inside 10/8")(ten.contains(java.net.InetAddress.getByName("10.0.0.5").getAddress))
      c("11.0.0.5 is outside 10/8")(!ten.contains(java.net.InetAddress.getByName("11.0.0.5").getAddress))

      // The /12 is the one people get wrong: 172.16-172.31 are private,
      // 172.32 is not. netguard.py's SELF_TEST called this out specifically.
      val oneSeventyTwo = Netguard.Cidr("172.16.0.0/12")
      c("172.16.4.4 is inside 172.16/12")(
        oneSeventyTwo.contains(java.net.InetAddress.getByName("172.16.4.4").getAddress)
      )
      c("172.31.255.255 is inside 172.16/12")(
        oneSeventyTwo.contains(java.net.InetAddress.getByName("172.31.255.255").getAddress)
      )
      c("172.32.0.1 is outside 172.16/12")(
        !oneSeventyTwo.contains(java.net.InetAddress.getByName("172.32.0.1").getAddress)
      )

      // An IPv4 address must never match an IPv6 prefix on a byte prefix
      // accident - the lengths differ and the comparison must say so.
      val ula = Netguard.Cidr("fc00::/7")
      c("an IPv4 address does not match an IPv6 prefix")(
        !ula.contains(java.net.InetAddress.getByName("10.0.0.1").getAddress)
      )
      c("fd00::1 is inside fc00::/7")(ula.contains(java.net.InetAddress.getByName("fd00::1").getAddress))
    }

    Check.suite("netguard - the guarded client") { c =>
      // The JDK's default builder consults the system proxy selector. With a
      // proxy set, the target host is never contacted - so the policy would vet
      // a host nobody dials. This must be NO_PROXY, always.
      val client = Netguard.httpClient()
      c("the client uses no proxy")(client.proxy().isEmpty || client.proxy().get() == Netguard.noProxy)
      // Redirects must not be followed automatically: the JDK gives no per-hop
      // callback, so Http.send follows them by hand and re-runs the policy.
      c.eq(
        "redirects are not followed automatically",
        client.followRedirects(),
        java.net.http.HttpClient.Redirect.NEVER
      )
    }

    Check.suite("netguard - guard throws with a usable message") { c =>
      Netguard.withSettings("SAGE_OFFLINE" -> "1", "SAGE_ALLOW" -> "") {
        c.throws("a public address throws")(Netguard.guard("https://8.8.8.8"))
        try Netguard.guard("https://8.8.8.8")
        catch
          case b: Netguard.Blocked =>
            c.contains("the message names netguard", b.getMessage, "netguard")
            c.contains("the message says how to override it", b.getMessage, "SAGE_OFFLINE=0")
        c("a LAN address does not throw") {
          Netguard.guard("http://192.168.0.1/login.html").allowed
        }
      }
    }
