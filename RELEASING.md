# Releasing

`0.0.1-alpha.1` is on Maven Central, published 16 August 2026. Both gates below
are done, so cutting the next release is a tag and someone pressing publish.

## Versions

`alpha` is the lowest qualifier Maven's comparator knows. Its full order is

```
alpha < beta < milestone < rc < snapshot < (release) < sp
```

so `0.0.1-alpha.1` sorts below everything this project could publish later, and
`0.0.1-alpha.1-SNAPSHOT` — the value in `gradle.properties` — sorts below the
release it precedes.

Write the qualifier after a hyphen and the increment after a dot:
`0.0.1-alpha.1`, never `0.0.1.alpha.1`. Maven treats a version that does not
match `major[.minor[.increment]][-qualifier]` as one long qualifier string, and
then nothing orders the way it looks like it should.

Cut a release by tagging. The workflow reads the version from the tag, refuses a
snapshot, and refuses anything that is not a shape this project publishes:

```console
$ git tag v0.0.1-alpha.1 && git push origin v0.0.1-alpha.1
```

Nothing is ever committed with a release version in it — `gradle.properties`
stays on the `-SNAPSHOT` value and the release overrides it.

## Gate 1 — own the namespace — **done**

`io.github.libtmux` is verified on the Central Portal, against the `libtmux`
organisation. Kept here because it is the step nobody remembers a year later.

The group is `io.github.libtmux`, which Central verifies through GitHub rather
than through DNS. There is nothing to change in a zone file: you prove control of
the account by creating a repository named after the verification key.

1. Sign in at [central.sonatype.com](https://central.sonatype.com/) and add the
   namespace `io.github.libtmux`.
2. Copy the verification key it shows.
3. Create a **public** repository in the `libtmux` organisation whose name is
   exactly that key:

   ```console
   $ gh repo create libtmux/<verification-key> --public
   ```

4. Press **Verify Namespace**. It resolves in about a minute.
5. Delete the repository once the namespace shows **Verified**.

Two things worth knowing:

- **Signing in with GitHub grants `io.github.<your-username>` automatically, and
  only that one.** An organisation namespace like `io.github.libtmux` is never
  automatic — it always needs the key repository, in the organisation, public.
- **A domain namespace is the other option and is not better here.** Owning
  `git-pull.com` would allow `com.git-pull` via a DNS TXT record, but the group
  is the name every consumer writes down, and it should name the project rather
  than the person who happened to register a domain.

## Gate 2 — a signing key CI can use — **done**

What was done, recorded because a key outlives the memory of setting one up:

- **Key** `D6B3443B2E8F467A7CEC14BF3FACCB0FE2F4C97B`, uid `libtmux`. The fingerprint
  is the whole identity that matters here, and it is public by construction —
  keyservers serve it and every signature carries it.
- **It carries a passphrase**, so this repository is on the four-secret path
  described below, `SIGNING_PASSWORD` included.
- **Published to both keyservers.** `keyserver.ubuntu.com` serves it complete.
  `keys.openpgp.org` serves the key but strips the user id, which is its policy
  until the verification email is answered — a fetch from there returns a key
  `gpg --import` skips for having no user id. That does not affect validation,
  which checks the signature rather than the name attached to it.

Central rejects unsigned artifacts, and that has not changed. Sonatype is rolling
out Sigstore alongside PGP, but a missing Sigstore signature does not block a
publish and PGP is not going away — an invalid Sigstore signature will eventually
block one, which is a reason to leave it alone rather than to rely on it.

**There is no OIDC.** The Central Portal API documents one authentication method:
a user token, base64-encoded into an `Authorization` header. There is no trusted
publishing, no workload identity federation, and no way to exchange a GitHub
Actions identity for a Portal credential. Every comparable project checked here
does the same thing — the MCP Java SDK, Conductor and OpenMetadata all carry a
token and a key as repository secrets. Where `id-token: write` appears in a Java
release workflow it is for build attestations, not for Central.

So the credentials are long-lived secrets, and the question worth asking is how
few of them there can be.

### Three secrets, or four

A passphrase on the signing key is optional, and the plugin only wants
`signingInMemoryKeyPassword` if the key has one.

| | secrets | when it makes sense |
| --- | --- | --- |
| **key with no passphrase** | 3 | a key made for this repository and nothing else, revocable on its own |
| key with a passphrase | 4 | a key that also lives somewhere else, where the passphrase is a second factor |

A passphrase stored in the same secret store as the key it protects adds very
little, so a dedicated release key without one is the reasonable default. Use a
passphrase if the key has a life outside CI.

```console
$ gpg --quick-generate-key "libtmux <you@example.com>" rsa4096 sign 2y
```

Publish the public half where Central looks. It checks `keyserver.ubuntu.com`,
`keys.openpgp.org` and `pgp.mit.edu` — and since the SKS network was deprecated
those no longer sync with each other, so send it to more than one rather than
trusting propagation. `pgp.mit.edu` is the least reliable of the three.

```console
$ gpg --keyserver keyserver.ubuntu.com --send-keys <FINGERPRINT>
```

```console
$ gpg --keyserver keys.openpgp.org --send-keys <FINGERPRINT>
```

Use the **full fingerprint**, not a short key id. Then prove it arrived, because a
key Central cannot fetch fails the deployment and a key nobody can fetch later
makes every signature unverifiable:

```console
$ gpg --keyserver keyserver.ubuntu.com --recv-keys <FINGERPRINT>
```

`keys.openpgp.org` stores the key immediately but strips the user id until you
answer its verification email, so the key is servable and unattributed until you
do.

Export the private half in the ASCII form a secret can hold:

```console
$ gpg --armor --export-secret-keys <FINGERPRINT> > /tmp/libtmux-signing.asc
```

| secret | value |
| --- | --- |
| `SIGNING_KEY` | the contents of the exported `.asc` |
| `SIGNING_PASSWORD` | the passphrase — **omit entirely if the key has none** |
| `CENTRAL_PORTAL_USERNAME` | from central.sonatype.com/usertoken |
| `CENTRAL_PORTAL_PASSWORD` | the token's password half |

```console
$ gh secret set SIGNING_KEY < /tmp/libtmux-signing.asc && rm /tmp/libtmux-signing.asc
```

An unset secret reaches a workflow as an **empty string**, not as nothing, and
the publisher does not read blank as absent — it would offer the empty string as
the passphrase and fail to decrypt. The release workflow therefore sets that
variable only when there is a passphrase to set, and the build treats a blank key
as no key.

Two more things that fail a release rather than a build:

- **Sign with the primary key.** Some tools sign with a subkey by default, and
  Central verifies against the primary.
- **Watch the expiry.** An expired key fails validation, and `2y` above is a
  choice, not a default. Extending it with `gpg --edit-key` is not enough on its
  own: keyservers do not pick the extension up, so send the key again.

### Can an existing key be used — a Keybase one, say?

Yes, with two conditions, and it is still usually the wrong choice.

A Keybase PGP key is an ordinary OpenPGP key and exports like any other
(`keybase pgp export -s`). But **Keybase's own keyserver is not one Central
checks**, so the public half has to go to `keyserver.ubuntu.com` or
`keys.openpgp.org` regardless — being on Keybase buys nothing here. Keybase also
generates with a passphrase, which puts you on the four-secret path.

The stronger objection is what the key is *for*. A release key ends up in a
repository secret, usable by anything that can run a workflow. An identity key —
one that signs commits, or proves accounts — should not be in that position. A
key minted for this repository can be revoked without touching anything else,
which is the whole argument for a dedicated one.

A Portal token is not an OSSRH token. OSSRH reached end of life on 30 June 2025,
and anything naming `oss.sonatype.org` describes a service that no longer exists.

## The plugin, already wired

`com.vanniktech.maven.publish` is applied by `libtmux.publication`. Sonatype
ships no first-party Gradle plugin, and this one talks to the Portal API
directly.

Its own API records how thoroughly OSSRH is gone: version 0.37 dropped the
`SonatypeHost` parameter entirely, because there is no longer another host to
choose. Anything written against `oss.sonatype.org` — `gradle-nexus/publish-plugin`
included — targets a service that no longer exists.

Two behaviours worth knowing:

- **Signing is conditional.** It is enabled only when `signingInMemoryKey` is
  present, so `publishToMavenLocal` works on a machine with no key. That is how
  a publication gets checked before anything reaches Central.
- **`automaticRelease` is false.** The upload lands as a *pending deployment*.
  Someone has to open the Portal and publish it. A pending deployment can be
  dropped; a released one can never be unpublished.

## The order to do this in

1. ~~Verify the namespace~~ — everything else is blocked on it, and it costs one
   public repository and a minute. **Done.**
2. ~~Create and publish the signing key.~~ **Done.**
3. ~~Add the secrets.~~ **Done** — all four.
4. Dry-run locally, which needs no key and no token:
   `./gradlew publishToMavenLocal -PlibtmuxVersion=0.0.1-alpha.1`.
5. Tag. The Release workflow runs `check`, uploads, and stops.
6. Open [the Portal](https://central.sonatype.com/publishing/deployments) and
   publish the deployment, or drop it.
