plugins { java }
group = "com.jlucraft"
version = "0.1.0"
repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}
dependencies { compileOnly("io.papermc.paper:paper-api:26.3.build.214-stable") }
java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }
val activityTest = sourceSets.create("activityTest") {
    java.srcDir("src/activityTest/java")
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
val activityTests by tasks.registering(JavaExec::class) {
    dependsOn(tasks.named(activityTest.classesTaskName))
    classpath = activityTest.runtimeClasspath
    mainClass.set("com.jlucraft.scoring.ActivityAccumulatorTest")
}
tasks.check { dependsOn(activityTests) }
