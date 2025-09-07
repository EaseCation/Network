plugins {
    `java-library`
    id("ecbuild.java-conventions")
}

dependencies {
    api(project(":Network:common"))
    compileOnly(libs.netty.handler)
    annotationProcessor(libs.lombok)
    compileOnly(libs.lombok)
    compileOnly(libs.javax.annotations)
}

group = "com.nukkitx.network"
description = "rcon"
