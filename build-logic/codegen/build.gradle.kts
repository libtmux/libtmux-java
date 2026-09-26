// Reads the two catalogs :libtmux ships (operation-catalog.json, field-catalog.tsv) and generates
// what each facade compiles against: the Java field metamodel, the Kotlin suspend mirror and field
// properties, and the Scala forwards and field companions. One reader per catalog, whichever
// language is generated from it. Run inside the build by the conventions' tasks, never published.
plugins { `embedded-kotlin` }

dependencies {
    implementation(libs.jackson.databind)
    // The generators return JavaPoet and KotlinPoet files, which the tasks write out.
    api(libs.javapoet)
    api(libs.kotlinpoet)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("test"))
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
