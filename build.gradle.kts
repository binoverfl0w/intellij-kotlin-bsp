import org.jetbrains.kotlin.gradle.utils.`is`

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.10"
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

group = "com.bino"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()

    intellijPlatform {
        defaultRepositories()
    }
}

tasks {
//    processResources {
//        println(buildServerProject.layout.buildDirectory.dir("libs/plugins").get().asFile.path)
//        println(buildServerProject.layout.buildDirectory.get().asFile.path)
//        from(buildServerProject.layout.buildDirectory.file("libs/server.jar")) {
//            include("server.jar")
//            into(layout.buildDirectory.dir("libs/server").get().asFile)
//            into("server")
//        }
//        from(buildServerProject.layout.buildDirectory.dir("libs/plugins")) {
//            into("plugins")
//        }
//    }

//    buildPlugin {
//        doFirst {
//            val serverJar = buildServerProject.layout.buildDirectory.file("libs/server.jar").get().asFile
//            val pluginJar = buildServerProject.layout.buildDirectory.file("libs/plugins/plugin-${buildServerProject.version}.jar").get().asFile
//            val initGradle = buildServerProject.layout.buildDirectory.file("libs/plugins/init.gradle").get().asFile
//
//            if (!serverJar.exists() || !pluginJar.exists() || !initGradle.exists()) {
//                throw IllegalStateException("Missing artifacts. Please run `./gradlew :build-server-for-gradle-kotlin:build`")
//            }
//        }
//    }

//    prepareSandbox {
//        from(buildServerProject.layout.buildDirectory.dir("libs/server.jar")) {
//            into("${project.name}/server")
//        }
//        from(buildServerProject.layout.buildDirectory.dir("libs/plugins")) {
//            into("${project.name}/plugins")
//        }
//    }

    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = "17"
    }

//    patchPluginXml {
//        sinceBuild.set("241")
//        untilBuild.set("243.*")
//    }

//    signPlugin {
//        certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
//        privateKey.set(System.getenv("PRIVATE_KEY"))
//        password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
//    }
//
//    publishPlugin {
//        token.set(System.getenv("PUBLISH_TOKEN"))
//    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2024.3.3")
        bundledPlugins(
            "com.intellij.java",
//            "com.intellij.gradle",
//            "org.jetbrains.plugins.gradle",
            "org.jetbrains.kotlin"
        )
    }
    implementation("ch.epfl.scala:bsp4j:2.1.0-M4")
    implementation(files("lib/server.jar"))
}