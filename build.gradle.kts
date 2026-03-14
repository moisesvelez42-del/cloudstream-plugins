import com.lagradost.cloudstream3.gradle.CloudstreamExtension 
import com.android.build.gradle.BaseExtension

buildscript {
    repositories {
        google()
        mavenCentral()
        // Shitpack repo which contains our tools and dependencies
        maven("https://jitpack.io")
    }

    dependencies {
        // AGP 8.3.0+ is required for Kotlin 2.3.0 compatibility
        classpath("com.android.tools.build:gradle:8.3.0")
        // Cloudstream gradle plugin which makes everything work and builds plugins
        classpath("com.github.recloudstream:gradle:master-SNAPSHOT")
        // Kotlin 2.3.0 matches the metadata version compiled into CloudStream3 pre-release
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.0")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

fun Project.cloudstream(configuration: CloudstreamExtension.() -> Unit) = extensions.getByName<CloudstreamExtension>("cloudstream").configuration()

fun Project.android(configuration: BaseExtension.() -> Unit) = extensions.getByName<BaseExtension>("android").configuration()

subprojects {
    apply(plugin = "com.android.library")
    apply(plugin = "kotlin-android")
    apply(plugin = "com.lagradost.cloudstream3.gradle")

    cloudstream {
        // when running through github workflow, GITHUB_REPOSITORY should contain current repository name
        // you can modify it to use other git hosting services, like gitlab
        setRepo(System.getenv("GITHUB_REPOSITORY") ?: "https://github.com/user/repo")
    }

    android {
        // Upgrade SDK versions to modern standards (34)
        compileSdkVersion(34)

        defaultConfig {
            minSdk = 21
            targetSdk = 34
        }

        compileOptions {
            // Java 17 compatibility requires AGP 8.0+
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }

        tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
            compilerOptions {
                // JVM target 17 for modern Kotlin
                jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
                // Disables some unnecessary features
                freeCompilerArgs.addAll(
                    "-Xno-call-assertions",
                    "-Xno-param-assertions",
                    "-Xno-receiver-assertions"
                )
            }
        }
    }

    dependencies {
        val apk by configurations
        val implementation by configurations

        // Stubs for all Cloudstream classes
        apk("com.lagradost:cloudstream3:pre-release")

        // these dependencies can include any of those which are added by the app,
        // but you dont need to include any of them if you dont need them
        // https://github.com/recloudstream/cloudstream/blob/master/app/build.gradle
        implementation(kotlin("stdlib")) // adds standard kotlin features, like listOf, mapOf etc
        implementation("com.github.Blatzar:NiceHttp:0.4.16") // http library
        implementation("org.jsoup:jsoup:1.13.1") // html parser
    }
}

task<Delete>("clean") {
    delete(rootProject.buildDir)
}
