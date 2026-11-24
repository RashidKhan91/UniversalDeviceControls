plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.parcelize")
    `maven-publish`
}

group = providers.gradleProperty("GROUP").orElse("dev.universal").get()
version = providers.gradleProperty("VERSION_NAME").orElse("0.1.0").get()

android {
    namespace = "dev.universal.devicecontrols"
    compileSdk = 36
    defaultConfig { minSdk = 26; consumerProguardFiles("consumer-rules.pro"); testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) } }
    publishing { singleVariant("release") { withSourcesJar() } }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.activity:activity:1.10.1")
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = project.group.toString()
                artifactId = "devicecontrols"
                version = project.version.toString()
                pom {
                    name.set("Universal Device Controls")
                    description.set("Android device and app control APIs with explicit platform capability results.")
                    url.set("https://github.com/RashidKhan91/UniversalDeviceControls")
                    licenses { license { name.set("The MIT License"); url.set("https://opensource.org/license/mit") } }
                    scm {
                        url.set("https://github.com/RashidKhan91/UniversalDeviceControls")
                        connection.set("scm:git:git://github.com/RashidKhan91/UniversalDeviceControls.git")
                        developerConnection.set("scm:git:ssh://github.com/RashidKhan91/UniversalDeviceControls.git")
                    }
                }
            }
        }
        repositories {
            maven {
                name = "configured"
                url = uri(providers.gradleProperty("publishingUrl").orElse(layout.buildDirectory.dir("maven-repo").get().asFile.toURI().toString()).get())
                credentials { username = providers.gradleProperty("publishingUser").orNull; password = providers.gradleProperty("publishingPassword").orNull }
            }
        }
    }
}
