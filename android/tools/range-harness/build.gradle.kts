// Off-device harness: resolves a video with the app's own core code (NewPipe Extractor + StreamSelector)
// and prints the exact streams PostClip would fetch. run_range_tests.sh then cuts them with ffmpeg.
plugins {
    kotlin("jvm") version "2.0.21"
    application
}
repositories { mavenCentral(); maven("https://jitpack.io") }
sourceSets {
    main { kotlin.srcDir("../../app/src/main/java/com/postcora/postclip/core") }
    test { kotlin.srcDir("../../app/src/test/java/com/postcora/postclip/core") }
}
dependencies {
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.27.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
}
kotlin { jvmToolchain(17) }
application { mainClass.set("HarnessKt") }
