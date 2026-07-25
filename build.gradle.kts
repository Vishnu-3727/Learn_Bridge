// Plugins declared `apply false` at the root so the Android Gradle classpath resolves once and
// :app (application) and :engine (library) apply their aliases without re-resolving versions.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
}
