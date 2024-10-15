import org.gradle.api.tasks.Copy

plugins {
    kotlin("jvm") version "2.1.0-Beta1"
    id("com.gradleup.shadow") version "8.3.3"
    id("xyz.jpenilla.run-paper") version "2.3.1"
}

group = "net.hynse"
version = "1.0-SNAPSHOT"

logger.lifecycle("Project: ${project.name}, Group: $group, Version: $version")

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") {
        name = "papermc-repo"
    }
    maven("https://oss.sonatype.org/content/groups/public/") {
        name = "sonatype"
    }
    maven("https://jitpack.io")
}

logger.lifecycle("Repositories configured: ${repositories.names}")

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")
    implementation("com.github.NahuLD:folia-scheduler-wrapper:v0.0.3")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
}

logger.lifecycle("Dependencies added")

val targetJavaVersion = 21
kotlin {
    jvmToolchain(targetJavaVersion)
}

logger.lifecycle("Kotlin JVM toolchain set to Java $targetJavaVersion")
tasks {
    jar {
        manifest {
            attributes["paperweight-mappings-namespace"] = "mojang"
        }
    }
    shadowJar {
        archiveClassifier.set("")
        relocate("com.github.NahuLD", "net.hynse.link.lib")
        minimize()

        doFirst {
            logger.lifecycle("Starting ShadowJar task")
            logger.lifecycle("Relocating 'com.github.NahuLD' to 'net.hynse.link.lib'")
        }

        doLast {
            logger.lifecycle("ShadowJar task completed. Output: ${archiveFile.get().asFile.absolutePath}")
        }
        manifest {
            attributes["paperweight-mappings-namespace"] = "mojang"
        }
    }

    build {
        dependsOn(shadowJar)
        doFirst {
            logger.lifecycle("Starting build task")
        }
        doLast {
            logger.lifecycle("Build task completed")
        }
    }

    processResources {
        val props = mapOf("version" to version)
        inputs.properties(props)
        filteringCharset = "UTF-8"
        filesMatching("paper-plugin.yml") {
            expand(props)
        }

        doFirst {
            logger.lifecycle("Processing resources")
            logger.lifecycle("Expanding properties in paper-plugin.yml: $props")
        }

        doLast {
            logger.lifecycle("Resource processing completed")
        }
    }

    register<Copy>("sendToServer") {
        val destinationDir = file("/mnt/hynse_mc/6_VanillaNGT_Lumional/plugins/")
        val sourceFile = layout.buildDirectory.file("libs/${project.name}-${project.version}.jar").get().asFile
        val oldPluginFile = file("${destinationDir}/${project.name}-${project.version}.jar")

        doFirst {
            logger.lifecycle("Starting sendToServer task")
            logger.lifecycle("Source file: ${sourceFile.absolutePath}")
            logger.lifecycle("Destination directory: ${destinationDir.absolutePath}")

            if (oldPluginFile.exists()) {
                logger.lifecycle("Deleting old plugin file: ${oldPluginFile.absolutePath}")
                oldPluginFile.delete()
            }
        }

        from(sourceFile)
        into(destinationDir)

        doLast {
            logger.lifecycle("File copied to server: ${destinationDir.resolve(sourceFile.name).absolutePath}")
        }
    }

    named("sendToServer") {
        dependsOn("shadowJar")
        doFirst {
            logger.lifecycle("sendToServer task depends on shadowJar")
        }
    }

    named("sendToServer") {
        dependsOn("build")
    }

    val version = "1.21.1"
    val javaVersion = JavaLanguageVersion.of(21)

    val jvmArgsExternal = listOf(
        "-Dcom.mojang.eula.agree=true",
        "-XX:+AllowEnhancedClassRedefinition",
        "-XX:HotswapAgent=core"
    )
    withType(xyz.jpenilla.runtask.task.AbstractRun::class) {
        runServer {
            minecraftVersion(version)
            runDirectory = rootDir.resolve("run/paper/$version")

            javaLauncher = project.javaToolchains.launcherFor {
                vendor = JvmVendorSpec.JETBRAINS
                languageVersion = javaVersion
            }

            jvmArgs = jvmArgsExternal
        }
        runPaper.folia.registerTask {
            minecraftVersion(version)
            runDirectory = rootDir.resolve("run/folia/$version")
            serverJar(rootDir.resolve("run/folia/$version/server.jar"))

            javaLauncher = project.javaToolchains.launcherFor {
                vendor = JvmVendorSpec.JETBRAINS
                languageVersion = javaVersion
            }

            jvmArgs = jvmArgsExternal
        }
    }


    gradle.taskGraph.whenReady {
        logger.lifecycle("Task execution order: ${allTasks.joinToString(", ") { it.name }}")
    }
}