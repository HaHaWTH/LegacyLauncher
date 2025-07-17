plugins {
  // Apply the Java Gradle plugin development plugin to add support for developing Gradle plugins
  id("java-library")
  id("com.palantir.git-version") version "0.15.0"
  id("maven-publish")
}

java {
  toolchain {
    languageVersion.set(JavaLanguageVersion.of(8))
    vendor.set(JvmVendorSpec.ADOPTIUM)
  }
  withSourcesJar()
  withJavadocJar()
}

repositories {
  maven { url = uri("https://libraries.minecraft.net/") }
  maven {
    url = uri("https://files.prismlauncher.org/maven")
    metadataSources { artifact() }
  }
  mavenCentral()
}

dependencies {
  api("net.sf.jopt-simple:jopt-simple:4.5")
  api("org.ow2.asm:asm-commons:9.4")
  api("org.ow2.asm:asm-tree:9.4")
  api("org.ow2.asm:asm-util:9.4")
  api("org.ow2.asm:asm-analysis:9.4")
  api("org.lwjgl.lwjgl:lwjgl:2.9.4-nightly-20150209")
  api("org.apache.logging.log4j:log4j-core:2.0-beta9-fixed")
  api("org.apache.logging.log4j:log4j-api:2.0-beta9-fixed")
}

val gitVersion: groovy.lang.Closure<String> by extra

group = "net.minecraft"

version = gitVersion()

publishing {
  publications { create<MavenPublication>("launchwrapper") { from(components["java"]) } }

  repositories {
    maven {
      url = uri("https://nexus.gtnewhorizons.com/repository/releases/")
      isAllowInsecureProtocol = true
      credentials {
        username = System.getenv("MAVEN_USER") ?: "NONE"
        password = System.getenv("MAVEN_PASSWORD") ?: "NONE"
      }
    }
  }
}
