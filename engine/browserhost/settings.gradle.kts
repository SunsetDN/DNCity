plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "browserhost"

// Needs engine:window's NativeWindow.nFindWindowByTitle (see BrowserHostMain's doc) -- same
// composite-build pattern the root settings.gradle.kts already uses for that module.
includeBuild("../window") {
    dependencySubstitution {
        substitute(module("engine:window")).using(project(":"))
    }
}
