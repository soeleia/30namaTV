plugins {
    // AGP 9 brings its own Kotlin support, so kotlin-android is never applied; declaring it here
    // only pins which Kotlin compiler that built-in support uses (the libraries need 2.4).
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.android") version "2.4.20" apply false
}
