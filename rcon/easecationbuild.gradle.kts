plugins {
    `java-library`
}

dependencies {
    api(project(":Network:common"))
    compileOnly(libs.netty.handler)
    annotationProcessor(libs.lombok)
    compileOnly(libs.lombok)
    compileOnly(libs.spotbugs.annotations)
    compileOnly(libs.javax.annotations)
}

group = "com.nukkitx.network"
description = "rcon"
