plugins {
    id("zoocall.kmp.library")
    alias(libs.plugins.wire)
}

wire {
    sourcePath {
        srcDir(rootProject.file("protocol/proto"))
    }
    kotlin {
        // Immutable data classes; Wire ignores unknown fields on decode (forward compatible).
        javaInterop = false
    }
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.shared.core.model)
            api(libs.wire.runtime)
        }
    }
}
