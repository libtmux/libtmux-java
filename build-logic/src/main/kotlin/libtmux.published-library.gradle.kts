// A module that is both a Java library and a published artifact. Coordinates, metadata and the route
// to Central all live in the publication convention, which the BOM shares.
//
// No publication is declared here: com.vanniktech.maven.publish detects the module's shape and
// creates one. Declaring another would publish the same artifact twice.
plugins {
    id("libtmux.java-library")
    id("libtmux.publication")
}
