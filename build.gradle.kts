// Aggregate entry points, so the gate is one command whatever the module layout becomes.
tasks.register("check") {
    group = "verification"
    description = "Every gate that must hold before publication."
    dependsOn(subprojects.map { "${it.path}:check" })
}
