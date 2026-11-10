// Same plugin versions as the shop's POS, deliberately: the toolchain on this Mac is already
// warmed for them (Gradle 8.9 + JDK 21), and an event app is not the place to find out that a
// newer AGP needs something downloading.
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    id("com.google.devtools.ksp") version "1.9.24-1.0.20" apply false
    id("com.google.dagger.hilt.android") version "2.51.1" apply false
}
