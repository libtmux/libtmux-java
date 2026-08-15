// A module that is both a Java library and a published artifact. Metadata lives in the publication
// convention, which the BOM shares.
plugins {
    id("libtmux.java-library")
    id("libtmux.publication")
}

publishing { publications { create<MavenPublication>("maven") { from(components["java"]) } } }
