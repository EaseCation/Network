plugins {
    `java-library`
}

dependencies {
    testImplementation(libs.jupiter.engine)
    testRuntimeOnly(libs.junit.launcher)
    api(libs.netty.buffer)
    api(libs.netty.epoll)
    api(libs.netty.kqueue)
    api(libs.netty.uring)
    annotationProcessor(libs.lombok)
    compileOnly(libs.lombok)
    compileOnly(libs.javax.annotations)
}

group = "com.nukkitx.network"
description = "common"

tasks.test {
    useJUnitPlatform()
}
