plugins {
    `java-library`
    id("ecbuild.java-conventions")
}

dependencies {
    api(libs.netty.buffer)
    api(libs.netty.epoll)
    api(libs.netty.kqueue)
    annotationProcessor(libs.lombok)
    compileOnly(libs.lombok)
    compileOnly(libs.javax.annotations)
}

group = "com.nukkitx.network"
description = "common"
