import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.9.22"
    id("org.jetbrains.intellij") version "1.17.3"
}

group = providers.gradleProperty("pluginGroup").get()
version = "0.1.4"

repositories {
    mavenCentral()
}

intellij {
    version.set(providers.gradleProperty("platformVersion").get())
    type.set(providers.gradleProperty("platformType").get())
    plugins.set(listOf("Git4Idea"))
}

tasks.withType<JavaCompile>().configureEach {
    val jv = providers.gradleProperty("javaVersion").get()
    sourceCompatibility = jv
    targetCompatibility = jv
}

tasks.withType<KotlinCompile>().configureEach {
    kotlinOptions.jvmTarget = providers.gradleProperty("javaVersion").get()
}

tasks {
    patchPluginXml {
        sinceBuild.set("232")
        untilBuild.set("")  // no upper bound — supports all future IDE versions
    }
    // Branch-Knife has no configurable settings UI, so searchable options are not needed
    buildSearchableOptions {
        enabled = false
    }
}
