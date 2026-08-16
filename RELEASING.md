# Releasing

Nothing here is published to Maven Central yet. The namespace is verified and
the build can publish; what remains is a signing key and a Portal token, neither
of which is code.

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

## Gate 2 — a signing key CI can use

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
$ gpg --quick-generate-key "libtmux <tony@git-pull.com>" rsa4096 sign 2y
```

Publish the public half so Central can find it, and export the private half in
the ASCII form a secret can hold:

```console
$ gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
```

```console
$ gpg --armor --export-secret-keys <KEY_ID> > /tmp/libtmux-signing.asc
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
  choice, not a default.

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

1. Verify the namespace — everything else is blocked on it, and it costs one
   public repository and a minute.
2. Create and publish the signing key.
3. Add the secrets.
4. Dry-run locally, which needs no key and no token:
   `./gradlew publishToMavenLocal -PlibtmuxVersion=0.0.1-alpha.1`.
5. Tag. The Release workflow runs `check`, uploads, and stops.
6. Open [the Portal](https://central.sonatype.com/publishing/deployments) and
   publish the deployment, or drop it.
