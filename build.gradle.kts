import org.gradle.api.tasks.compile.JavaCompile

plugins {
    java
}

group = "com.dragonbuildtools"
version = "2.0.0"

val baselinePaperApi = "1.21.8-R0.1-SNAPSHOT"

val apiCompatibilityTargets = mapOf(
    "1_20_4" to "1.20.4-R0.1-SNAPSHOT",
    "1_21_11" to "1.21.11-R0.1-SNAPSHOT",
    "26_1" to "26.1.2.build.74-stable",
    "26_2" to "26.2.build.134-stable",
    "26_3" to "26.3.build.211-stable",
)

val faweVersion = "2.16.1"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") { name = "papermc" }
    maven("https://maven.enginehub.org/repo/") { name = "enginehub" }
}

val annotationLibraries = listOf(
    "org.jetbrains:annotations:26.0.2",
    "com.google.code.findbugs:jsr305:3.0.2",
    "org.checkerframework:checker-qual:3.49.0",
)

dependencies {
    compileOnly("io.papermc.paper:paper-api:$baselinePaperApi")
    compileOnly("com.fastasyncworldedit:FastAsyncWorldEdit-Core:$faweVersion")
    compileOnly("com.fastasyncworldedit:FastAsyncWorldEdit-Bukkit:$faweVersion") {
        isTransitive = false
    }
    annotationLibraries.forEach { compileOnly(it) }

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("io.papermc.paper:paper-api:$baselinePaperApi")
    testImplementation("com.fastasyncworldedit:FastAsyncWorldEdit-Core:$faweVersion")
    testImplementation("com.fastasyncworldedit:FastAsyncWorldEdit-Bukkit:$faweVersion") {
        isTransitive = false
    }
    annotationLibraries.forEach { testImplementation(it) }
}

tasks.test {
    useJUnitPlatform()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
    options.compilerArgs.addAll(listOf("-Xlint:-removal", "-Xlint:-deprecation", "-Xmaxerrs", "1000"))
}

tasks.processResources {
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filesMatching("plugin.yml") { expand(props) }
}

tasks.jar {
    archiveBaseName.set("DragonBuildTools")
}

val apiCompatibilityChecks = apiCompatibilityTargets.map { (id, paperVersion) ->
    val classpathConfig = configurations.create("paperApi$id") {
        isCanBeConsumed = false
        isCanBeResolved = true
    }
    dependencies {
        classpathConfig("io.papermc.paper:paper-api:$paperVersion")
        classpathConfig("com.fastasyncworldedit:FastAsyncWorldEdit-Core:$faweVersion")
        classpathConfig("com.fastasyncworldedit:FastAsyncWorldEdit-Bukkit:$faweVersion") {
            isTransitive = false
        }
        annotationLibraries.forEach { classpathConfig(it) }
    }

    tasks.register<JavaCompile>("compileAgainstPaper$id") {
        group = "verification"
        description = "Verifies DragonBuildTools compiles against Paper API $paperVersion"
        source = sourceSets.main.get().java
        classpath = classpathConfig
        destinationDirectory.set(layout.buildDirectory.dir("api-compat/$id"))
        javaCompiler.set(javaToolchains.compilerFor { languageVersion.set(JavaLanguageVersion.of(25)) })
    }
}

tasks.register("checkApiCompatibility") {
    group = "verification"
    description = "Compiles DragonBuildTools against every supported Paper API version"
    dependsOn(apiCompatibilityChecks)
}

tasks.check {
    dependsOn("checkApiCompatibility")
}
