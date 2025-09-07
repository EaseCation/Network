plugins {
    `java-library`
    id("ecbuild.java-conventions")
}

dependencies {
    api(project(":Network:common"))
    api(libs.netty.handler)
    api(libs.expiringmap)
    annotationProcessor(libs.lombok)
    compileOnly(libs.lombok)
    compileOnly(libs.javax.annotations)
}

group = "com.nukkitx.network"
description = "raknet"
