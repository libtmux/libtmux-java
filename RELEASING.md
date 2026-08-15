# Releasing

Nothing here is published to Maven Central yet. Two things gate the first
release, neither of them code, and both are worth doing before they are urgent.

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

Cut a release by overriding the property; nothing is committed with the release
version in it:

```console
$ ./gradlew publish -PlibtmuxVersion=0.0.1-alpha.1
```

## Gate 1 — own the namespace

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

Central rejects unsigned artifacts. Generate a key that belongs to the project
rather than to a laptop.

```console
$ gpg --quick-generate-key "libtmux <tony@git-pull.com>" rsa4096 sign 2y
```

Publish the public half so Central can find it, and export the private half in
the ASCII form a CI secret can hold:

```console
$ gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
```

```console
$ gpg --armor --export-secret-keys <KEY_ID> > /tmp/libtmux-signing.asc
```

Then add four repository secrets, and delete the exported file:

| secret                     | value                                     |
| -------------------------- | ----------------------------------------- |
| `SIGNING_KEY`              | the contents of the exported `.asc`       |
| `SIGNING_PASSWORD`         | the key's passphrase                      |
| `CENTRAL_PORTAL_USERNAME`  | from central.sonatype.com/usertoken       |
| `CENTRAL_PORTAL_PASSWORD`  | the token's password half                 |

```console
$ gh secret set SIGNING_KEY < /tmp/libtmux-signing.asc && rm /tmp/libtmux-signing.asc
```

A Portal token is not an OSSRH token. OSSRH reached end of life on 30 June 2025
and any instructions naming `oss.sonatype.org` are describing a service that no
longer exists.

## Which plugin

Sonatype ships no first-party Gradle plugin. Two real options:

| plugin                            | why you would pick it                                                    |
| --------------------------------- | ------------------------------------------------------------------------ |
| `com.vanniktech.maven.publish`     | knows the Portal API directly, configures signing and the POM, one block  |
| JReleaser                          | one release tool across artifacts, changelog and GitHub release           |

**Recommended: `com.vanniktech.maven.publish`**, because this repository already
writes its own POM in `libtmux.publication` and wants a publisher, not a release
manager. It replaces the `maven-publish` block in that convention plugin and
nothing else changes.

Do not reach for `io.github.gradle-nexus.publish-plugin`. It targets the OSSRH
staging API, which is the retired path.

## The order to do this in

1. Verify the namespace — everything else is blocked on it, and it costs one
   public repository and a minute.
2. Create and publish the signing key.
3. Add the secrets.
4. Swap the publishing plugin and dry-run with
   `./gradlew publishToMavenLocal -PlibtmuxVersion=0.0.1-alpha.1`.
5. Publish, then check the deployment in the Portal before releasing it — a
   deployment can be dropped, a release cannot be unpublished.
