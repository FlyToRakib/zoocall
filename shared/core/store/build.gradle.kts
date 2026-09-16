plugins {
    id("zoocall.kmp.library")
    alias(libs.plugins.sqldelight)
}

sqldelight {
    databases {
        create("ZoocallDatabase") {
            packageName.set("app.zoocall.core.store.db")
            schemaOutputDirectory.set(file("src/commonMain/sqldelight/databases"))
            verifyMigrations.set(true)
        }
    }
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.shared.core.model)
            implementation(libs.sqldelight.coroutines)
        }
        androidMain.dependencies {
            implementation(libs.sqldelight.android.driver)
            implementation(libs.sqlcipher.android)
            implementation(libs.androidx.sqlite)
        }
        getByName("desktopMain").dependencies {
            // sqlite-jdbc-crypt replaces xerial sqlite-jdbc (same org.sqlite package) and adds SQLCipher.
            implementation(libs.sqldelight.jvm.driver.get().toString()) {
                exclude(group = "org.xerial", module = "sqlite-jdbc")
            }
            implementation(libs.sqlite.jdbc.crypt)
            implementation(libs.jna.platform)
        }
        getByName("desktopTest").dependencies {
            implementation(libs.turbine)
        }
    }
}
